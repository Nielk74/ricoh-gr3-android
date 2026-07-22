package com.ricohgr3.app.gallery

import com.ricohgr3.app.data.EditedExportQuality
import com.ricohgr3.app.data.PhotoExporter
import com.ricohgr3.app.data.PhotoRepository
import com.ricohgr3.app.data.PhotoResult
import com.ricohgr3.app.looks.emulation.FilmLookLoader
import com.ricohgr3.app.wifi.ImageSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.IOException

/** Executes one durable drain-first auto-import; the foreground service owns its lifetime. */
internal class AutoImportRunner(
    private val repository: PhotoRepository,
    private val exporter: PhotoExporter,
    private val loader: FilmLookLoader,
    private val store: AutoImportJobStore,
    private val publishTransient: (TransferUiState) -> Unit,
    private val publish: (AutoImportJob, AutoImportLiveProgress) -> Unit,
    private val onCameraPhase: (Boolean) -> Unit,
    private val disconnectCamera: () -> Unit,
    private val memorySnapshot: () -> TransferMemorySnapshot,
) {
    @Volatile
    private var pauseRequested = false

    @Volatile
    private var currentJob: AutoImportJob? = null

    fun requestPause() {
        pauseRequested = true
        currentJob?.let { job ->
            publish(job, AutoImportLiveProgress(stopRequested = true))
        }
    }

    suspend fun startNew(requestedPreset: TransferPreset, jobId: String) {
        pauseRequested = false
        val preset = requestedPreset.normalized()
        publishTransient(
            TransferUiState(
                phase = TransferPhase.SCANNING,
                source = TransferSource.AUTO_IMPORT,
                preset = preset,
                diskBacked = true,
                message = "Reading the camera roll…",
            ),
        )
        val job = try {
            // Scanning and metadata reads also need the high-performance Wi-Fi lock: a phone may
            // be locked before the first large response starts.
            onCameraPhase(true)
            val photos = when (val result = repository.loadPhotos()) {
                is PhotoResult.Success -> result.value
                is PhotoResult.Error -> throw (result.cause ?: IOException(result.message))
            }
            val plan = planAutoImport(photos, preset)
            var prepared = AutoImportJob.create(plan, preset, id = jobId)
            store.create(prepared)
            currentJob = prepared
            publish(prepared, AutoImportLiveProgress())

            if (prepared.files.isEmpty()) {
                prepared = prepared.copy(
                    phase = AutoImportJobPhase.COMPLETED,
                    message = "No supported JPEG or DNG photos were found on the camera.",
                )
                saveAndPublish(prepared)
                return
            }

            prepared = readMetadata(prepared)
            if (prepared.phase != AutoImportJobPhase.PAUSED) {
                prepared = prepared.copy(metadataComplete = true)
            }
            store.save(prepared)
            prepared
        } finally {
            onCameraPhase(false)
        }
        if (job.phase == AutoImportJobPhase.PAUSED) return
        execute(job)
    }

    suspend fun resume() {
        pauseRequested = false
        val loaded = store.load() ?: throw IOException("There is no auto-import to continue")
        var job = loaded.resetFailuresForRetry()
        store.save(job)
        if (!job.metadataComplete && job.files.any { it.downloadStatus != AutoImportItemStatus.SUCCESS }) {
            job = job.copy(
                phase = AutoImportJobPhase.PREPARING,
                message = "Refreshing camera metadata before download…",
            )
            job = try {
                onCameraPhase(true)
                readMetadata(job)
            } finally {
                onCameraPhase(false)
            }
            if (job.phase == AutoImportJobPhase.PAUSED) return
            job = job.copy(metadataComplete = true, message = null)
            store.save(job)
        }
        execute(job)
    }

    private suspend fun readMetadata(initial: AutoImportJob): AutoImportJob {
        var job = initial
        for (index in job.files.indices) {
            if (pauseRequested) return pause(job)
            val entry = job.files[index]
            publish(
                job.copy(message = "Reading metadata ${index + 1} of ${job.files.size}"),
                AutoImportLiveProgress(
                    processing = entry.id,
                    processingNumber = index + 1,
                    workStage = TransferWorkStage.PREPARING,
                ),
            )
            val info = (repository.photoInfo(entry.id) as? PhotoResult.Success)?.value
            val updated = entry.copy(
                expectedBytes = info?.size?.takeIf { it > 0L },
                iso = if (entry.editStatus != AutoImportItemStatus.NOT_REQUIRED) {
                    parseTransferIso(info?.sv)
                } else {
                    null
                },
            )
            job = job.copy(files = job.files.replaced(index, updated), message = null)
        }
        return job
    }

    private suspend fun execute(initial: AutoImportJob) {
        var job = initial.copy(
            files = initial.files.map { entry ->
                val stillNeedsSource =
                    !entry.originalStatus.isSuccessfulOrNotRequired ||
                        !entry.editStatus.isSuccessfulOrNotRequired
                if (
                    entry.downloadStatus == AutoImportItemStatus.SUCCESS &&
                    stillNeedsSource &&
                    !store.stagedFile(entry).isFile
                ) {
                    entry.copy(
                        downloadStatus = AutoImportItemStatus.PENDING,
                        downloadedBytes = 0L,
                        downloadError = null,
                    )
                } else {
                    entry
                }
            },
        )
        currentJob = job
        if (job.files.any { it.downloadStatus != AutoImportItemStatus.SUCCESS }) {
            ensureDiskSpace(job)
            job = downloadAll(job)
            if (job.phase == AutoImportJobPhase.PAUSED) return
        }

        // The camera phase is now over. Processing is entirely local and can safely continue if
        // the camera sleeps, leaves range, or the user turns its Wi-Fi off.
        disconnectCamera()
        job = processAll(job)
        if (job.phase == AutoImportJobPhase.PAUSED) return

        job = job.copy(
            phase = AutoImportJobPhase.COMPLETED,
            message = if (job.saved == job.outputTotal) {
                "All required camera files were downloaded before ${job.saved} outputs were saved."
            } else {
                "${job.saved} of ${job.outputTotal} outputs were saved. Retry the failed items."
            },
        )
        saveAndPublish(job)
    }

    private fun ensureDiskSpace(job: AutoImportJob) {
        val remainingSpoolEstimate = job.files
            .filter { it.downloadStatus != AutoImportItemStatus.SUCCESS }
            .sumOf { it.expectedBytes ?: UNKNOWN_FILE_RESERVE_BYTES }
        // Original-only output replaces its staged file as processing advances. When the same
        // source also produces an edited JPEG, however, that edited copy is net additional storage
        // and accumulates in MediaStore across the batch.
        val editedOutputExpansion = job.files
            .filter {
                it.originalStatus != AutoImportItemStatus.NOT_REQUIRED &&
                    it.editStatus != AutoImportItemStatus.NOT_REQUIRED &&
                    it.editStatus != AutoImportItemStatus.SUCCESS
            }
            .sumOf { it.expectedBytes ?: UNKNOWN_FILE_RESERVE_BYTES }
        val required = remainingSpoolEstimate + editedOutputExpansion
        val available = store.availableBytes()
        if (available - required < MINIMUM_FREE_AFTER_SPOOL_BYTES) {
            throw IOException(
                "Not enough storage to stage the complete camera batch. " +
                    "Need about ${required.toMib()} MiB plus ${MINIMUM_FREE_AFTER_SPOOL_BYTES.toMib()} MiB free.",
            )
        }
    }

    private suspend fun downloadAll(initial: AutoImportJob): AutoImportJob {
        var job = initial.copy(phase = AutoImportJobPhase.DOWNLOADING, message = null)
        store.save(job)
        onCameraPhase(true)
        try {
            for (index in job.files.indices) {
                if (pauseRequested) return pause(job)
                var entry = job.files[index]
                val staged = store.stagedFile(entry)
                if (entry.downloadStatus == AutoImportItemStatus.SUCCESS && staged.isFile) continue
                if (entry.downloadStatus == AutoImportItemStatus.SUCCESS) {
                    entry = entry.copy(downloadStatus = AutoImportItemStatus.PENDING)
                }

                store.partialFile(entry).delete()
                entry = entry.copy(
                    downloadStatus = AutoImportItemStatus.RUNNING,
                    downloadError = null,
                    downloadedBytes = 0L,
                )
                job = job.copy(files = job.files.replaced(index, entry))
                store.save(job)
                publish(
                    job,
                    AutoImportLiveProgress(
                        downloading = entry.id,
                        downloadingNumber = index + 1,
                        downloadTotalBytes = entry.expectedBytes,
                    ),
                )

                val result = withContext(Dispatchers.IO) {
                    val raw = FileOutputStream(store.partialFile(entry))
                    try {
                        val streamed = BufferedOutputStream(raw, DOWNLOAD_BUFFER_BYTES).use { output ->
                            val result = repository.downloadPhotoTo(
                                id = entry.id,
                                destination = output,
                                size = ImageSize.FULL,
                            ) { bytesRead, totalBytes ->
                                publish(
                                    job,
                                    AutoImportLiveProgress(
                                        downloading = entry.id,
                                        downloadingNumber = index + 1,
                                        downloadBytes = bytesRead,
                                        downloadTotalBytes = totalBytes ?: entry.expectedBytes,
                                    ),
                                )
                            }
                            if (result is PhotoResult.Success) {
                                output.flush()
                                raw.fd.sync()
                            }
                            result
                        }
                        when (streamed) {
                            is PhotoResult.Success ->
                                PhotoResult.Success(store.partialFile(entry).length())
                            is PhotoResult.Error -> streamed
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        PhotoResult.Error(failure.message ?: "Download failed", failure)
                    } finally {
                        runCatching { raw.close() }
                    }
                }

                entry = when (result) {
                    is PhotoResult.Success -> {
                        store.commitPartial(entry)
                        entry.copy(
                            downloadStatus = AutoImportItemStatus.SUCCESS,
                            downloadedBytes = result.value,
                            downloadError = null,
                        )
                    }
                    is PhotoResult.Error -> {
                        store.partialFile(entry).delete()
                        entry.copy(
                            downloadStatus = AutoImportItemStatus.FAILED,
                            downloadError = result.message,
                        )
                    }
                }
                job = job.copy(files = job.files.replaced(index, entry))
                saveAndPublish(job)
            }
            return job
        } finally {
            onCameraPhase(false)
        }
    }

    private suspend fun processAll(initial: AutoImportJob): AutoImportJob {
        var job = initial.copy(phase = AutoImportJobPhase.PROCESSING, message = null)
        job = job.copy(
            files = job.files.map { file ->
                if (file.downloadStatus == AutoImportItemStatus.SUCCESS) {
                    file
                } else {
                    val reason = file.downloadError ?: "Camera download failed"
                    file.copy(
                        originalStatus = file.originalStatus.failPending(reason),
                        editStatus = file.editStatus.failPending(reason),
                    )
                }
            },
        )
        store.save(job)

        var taskNumber = 0
        for (index in job.files.indices) {
            var entry = job.files[index]
            val staged = store.stagedFile(entry)

            if (entry.originalStatus != AutoImportItemStatus.NOT_REQUIRED) {
                taskNumber++
                if (entry.originalStatus == AutoImportItemStatus.PENDING) {
                    if (pauseRequested) return pause(job)
                    entry = entry.copy(originalStatus = AutoImportItemStatus.RUNNING, originalError = null)
                    job = job.copy(files = job.files.replaced(index, entry))
                    saveAndPublish(
                        job,
                        AutoImportLiveProgress(
                            processing = entry.id,
                            processingNumber = taskNumber,
                            workStage = TransferWorkStage.SAVING,
                        ),
                    )
                    entry = try {
                        exporter.saveFile(staged, entry.file, mimeTypeFor(entry.file))
                        entry.copy(originalStatus = AutoImportItemStatus.SUCCESS)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        entry.copy(
                            originalStatus = AutoImportItemStatus.FAILED,
                            originalError = transferFailureReason(failure),
                        )
                    }
                    job = job.copy(files = job.files.replaced(index, entry))
                    saveAndPublish(job)
                }
            }

            if (entry.editStatus != AutoImportItemStatus.NOT_REQUIRED) {
                taskNumber++
                if (entry.editStatus == AutoImportItemStatus.PENDING) {
                    if (pauseRequested) return pause(job)
                    entry = entry.copy(editStatus = AutoImportItemStatus.RUNNING, editError = null)
                    job = job.copy(files = job.files.replaced(index, entry))
                    val renderPreset = job.preset.restore()
                    if (renderPreset.quality == EditedExportQuality.MAXIMUM) {
                        // Region arrays from the preceding frame are already unreachable. Ask ART
                        // to reclaim them before measuring headroom so the next plan is based on
                        // live memory, not garbage awaiting a future allocation-triggered cycle.
                        Runtime.getRuntime().gc()
                    }
                    val renderBudget = fullResolutionRenderBudget(memorySnapshot())
                    saveAndPublish(
                        job,
                        AutoImportLiveProgress(
                            processing = entry.id,
                            processingNumber = taskNumber,
                            workStage = TransferWorkStage.DEVELOPING,
                            heapHeadroomMb = (renderBudget / MIB).toInt(),
                        ),
                    )
                    entry = try {
                        saveStagedEdited(
                            source = staged,
                            id = entry.id,
                            preset = renderPreset,
                            exporter = exporter,
                            loader = loader,
                            iso = entry.iso,
                            heapHeadroomBytes = renderBudget,
                            onRegionProgress = { completed, total ->
                                publish(
                                    job,
                                    AutoImportLiveProgress(
                                        processing = entry.id,
                                        processingNumber = taskNumber,
                                        processingPart = completed,
                                        processingParts = total,
                                        workStage = TransferWorkStage.DEVELOPING,
                                        stopRequested = pauseRequested,
                                        heapHeadroomMb = (renderBudget / MIB).toInt(),
                                    ),
                                )
                            },
                            shouldPause = { pauseRequested },
                        )
                        entry.copy(editStatus = AutoImportItemStatus.SUCCESS)
                    } catch (_: ImportPauseRequestedException) {
                        entry = entry.copy(editStatus = AutoImportItemStatus.PENDING)
                        job = job.copy(files = job.files.replaced(index, entry))
                        store.save(job)
                        return pause(job)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        entry.copy(
                            editStatus = AutoImportItemStatus.FAILED,
                            editError = transferFailureReason(failure),
                        )
                    }
                    job = job.copy(files = job.files.replaced(index, entry))
                    saveAndPublish(job)
                }
            }

            if (
                entry.downloadStatus == AutoImportItemStatus.SUCCESS &&
                entry.originalStatus.isSuccessfulOrNotRequired &&
                entry.editStatus.isSuccessfulOrNotRequired
            ) {
                store.deleteStaged(entry)
            }
        }
        return job
    }

    private fun pause(job: AutoImportJob): AutoImportJob {
        val paused = job.copy(
            phase = AutoImportJobPhase.PAUSED,
            files = job.files.map { file ->
                file.copy(
                    downloadStatus = file.downloadStatus.resetRunning(),
                    originalStatus = file.originalStatus.resetRunning(),
                    editStatus = file.editStatus.resetRunning(),
                )
            },
            message = "Paused safely. Downloaded files remain on this phone.",
        )
        saveAndPublish(paused)
        return paused
    }

    private fun saveAndPublish(
        job: AutoImportJob,
        live: AutoImportLiveProgress = AutoImportLiveProgress(),
    ) {
        currentJob = job
        store.save(job)
        publish(job, live)
    }

}

private fun <T> List<T>.replaced(index: Int, value: T): List<T> =
    toMutableList().also { it[index] = value }

private fun AutoImportItemStatus.failPending(@Suppress("UNUSED_PARAMETER") reason: String): AutoImportItemStatus =
    if (this == AutoImportItemStatus.PENDING || this == AutoImportItemStatus.RUNNING) {
        AutoImportItemStatus.FAILED
    } else {
        this
    }

private fun AutoImportItemStatus.resetRunning(): AutoImportItemStatus =
    if (this == AutoImportItemStatus.RUNNING) AutoImportItemStatus.PENDING else this

private val AutoImportItemStatus.isSuccessfulOrNotRequired: Boolean
    get() = this == AutoImportItemStatus.SUCCESS || this == AutoImportItemStatus.NOT_REQUIRED

private fun Long.toMib(): Long = this / MIB

private const val DOWNLOAD_BUFFER_BYTES = 1024 * 1024
private const val UNKNOWN_FILE_RESERVE_BYTES = 64L * 1024L * 1024L
private const val MINIMUM_FREE_AFTER_SPOOL_BYTES = 256L * 1024L * 1024L
private const val MIB = 1024L * 1024L
