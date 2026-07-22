package com.ricohgr3.app.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ricohgr3.app.data.EditedExportQuality
import com.ricohgr3.app.data.PhotoExporter
import com.ricohgr3.app.data.PhotoId
import com.ricohgr3.app.data.PhotoItem
import com.ricohgr3.app.data.PhotoRepository
import com.ricohgr3.app.data.PhotoResult
import com.ricohgr3.app.looks.emulation.FilmLookLoader
import com.ricohgr3.app.looks.emulation.RenderingIntent
import com.ricohgr3.app.wifi.ImageSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/** The settings frozen at the start of an auto-import or selection save. */
data class TransferPreset(
    val look: String?,
    val intensity: Float = 1f,
    val renderingIntent: RenderingIntent = RenderingIntent.SMART,
    val grainEnabled: Boolean = true,
    val quality: EditedExportQuality = EditedExportQuality.MAXIMUM,
    val outputMode: TransferOutputMode = TransferOutputMode.EDITED_ONLY,
) {
    /** Clamp values arriving from UI state before a long-running transfer captures them. */
    fun normalized(): TransferPreset = copy(
        intensity = intensity.coerceIn(0.5f, 1.5f),
        outputMode = if (look == null) TransferOutputMode.ORIGINAL_ONLY else outputMode,
    )
}

/** Which files an auto-import should publish after the complete camera batch is staged. */
enum class TransferOutputMode {
    ORIGINAL_ONLY,
    EDITED_ONLY,
    ORIGINAL_AND_EDITED,
}

enum class TransferSource {
    AUTO_IMPORT,
    SELECTION,
}

enum class TransferPhase {
    IDLE,
    SCANNING,
    DOWNLOADING,
    PROCESSING,
    TRANSFERRING,
    COMPLETED,
    CANCELLED,
    FAILED,
}

enum class TransferWorkStage {
    PREPARING,
    DEVELOPING,
    SAVING,
}

data class TransferFailure(
    val id: PhotoId,
    val reason: String,
)

/**
 * One observable transfer snapshot shared by the auto-import page and the gallery batch panel.
 * It exposes transport byte progress for the active camera response plus durable item progress for
 * completed saves, so a long full-resolution download never looks stalled.
 */
data class TransferUiState(
    val phase: TransferPhase = TransferPhase.IDLE,
    val source: TransferSource? = null,
    val preset: TransferPreset? = null,
    val total: Int = 0,
    val completed: Int = 0,
    val saved: Int = 0,
    val downloadTotal: Int = 0,
    val downloadCompleted: Int = 0,
    val downloading: PhotoId? = null,
    val downloadingNumber: Int = 0,
    val downloadBytes: Long = 0L,
    val downloadTotalBytes: Long? = null,
    val processing: PhotoId? = null,
    val processingNumber: Int = 0,
    val processingPart: Int = 0,
    val processingParts: Int = 0,
    val workStage: TransferWorkStage? = null,
    val pipelineDepth: Int = 1,
    val heapHeadroomMb: Int? = null,
    val diskBacked: Boolean = false,
    val failures: List<TransferFailure> = emptyList(),
    val message: String? = null,
    val stopRequested: Boolean = false,
) {
    val isActive: Boolean
        get() = phase == TransferPhase.SCANNING || phase == TransferPhase.DOWNLOADING ||
            phase == TransferPhase.PROCESSING || phase == TransferPhase.TRANSFERRING

    val isTerminal: Boolean
        get() = phase == TransferPhase.COMPLETED || phase == TransferPhase.CANCELLED ||
            phase == TransferPhase.FAILED

    val progress: Float
        get() = when {
            phase == TransferPhase.COMPLETED -> 1f
            total <= 0 -> 0f
            else -> ((downloadProgress + saveProgress) / 2f).coerceIn(0f, 1f)
        }

    val downloadProgress: Float
        get() {
            val count = downloadTotal.takeIf { it > 0 } ?: total
            if (count <= 0) return 0f
            val currentFraction = if (
                downloading != null && downloadTotalBytes != null && downloadTotalBytes > 0L
            ) {
                (downloadBytes.toDouble() / downloadTotalBytes.toDouble()).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            return ((downloadCompleted.toDouble() + currentFraction) / count.toDouble())
                .coerceIn(0.0, 1.0)
                .toFloat()
        }

    val saveProgress: Float
        get() {
            if (total <= 0) return 0f
            val activeRegionFraction = if (
                phase == TransferPhase.PROCESSING &&
                processing != null &&
                workStage == TransferWorkStage.DEVELOPING &&
                processingParts > 0
            ) {
                (processingPart.toFloat() / processingParts.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            return ((completed + activeRegionFraction) / total.toFloat()).coerceIn(0f, 1f)
        }

    val current: PhotoId?
        get() = processing ?: downloading
}

internal data class DownloadedTransfer(
    val id: PhotoId,
    val iso: Int?,
    val payload: FullPhotoPayload,
)

private sealed interface DownloadAttempt {
    val number: Int
    val id: PhotoId

    data class Success(
        override val number: Int,
        val transfer: DownloadedTransfer,
    ) : DownloadAttempt {
        override val id: PhotoId get() = transfer.id
    }

    data class Failure(
        override val number: Int,
        override val id: PhotoId,
        val reason: String,
    ) : DownloadAttempt
}

private typealias TransferDownloader = suspend (
    id: PhotoId,
    preset: TransferPreset,
    onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
) -> DownloadedTransfer

private typealias DownloadedSaver = suspend (
    transfer: DownloadedTransfer,
    preset: TransferPreset,
    onPayloadConsumed: () -> Unit,
    onStage: (TransferWorkStage) -> Unit,
) -> SaveOutcome

/**
 * Runs one full-resolution develop/save at a time, with an adaptive camera-download double buffer
 * when current heap and system RAM make that safe. This overlaps the camera link with CPU work
 * without ever running multiple memory-heavy film develops concurrently.
 */
class TransferViewModel internal constructor(
    private val photoLister: suspend () -> PhotoResult<List<PhotoItem>>,
    private val photoDownloader: TransferDownloader,
    private val downloadedSaver: DownloadedSaver,
    private val pipelinePlanner: (TransferPreset) -> TransferPipelinePlan,
    private val autoImportController: AutoImportController? = null,
) : ViewModel() {

    /** Compact constructor retained for coordination tests and non-Android callers. */
    internal constructor(
        photoLister: suspend () -> PhotoResult<List<PhotoItem>>,
        photoSaver: suspend (PhotoId, TransferPreset) -> SaveOutcome,
    ) : this(
        photoLister = photoLister,
        photoDownloader = { id, _, onProgress ->
            onProgress(0L, 0L)
            DownloadedTransfer(id, iso = null, payload = FullPhotoPayload(ByteArray(0)))
        },
        downloadedSaver = { transfer, preset, onPayloadConsumed, onStage ->
            onStage(if (preset.look == null) TransferWorkStage.SAVING else TransferWorkStage.DEVELOPING)
            try {
                photoSaver(transfer.id, preset)
            } finally {
                transfer.payload.discard()
                onPayloadConsumed()
            }
        },
        pipelinePlanner = { TransferPipelinePlan(maxResidentDownloads = 1, heapHeadroomBytes = 0L) },
    )

    internal constructor(
        repository: PhotoRepository,
        exporter: PhotoExporter,
        filmLookLoader: FilmLookLoader,
        memoryMonitor: TransferMemoryMonitor,
        autoImportController: AutoImportController,
    ) : this(
        photoLister = { repository.loadPhotos() },
        photoDownloader = { id, preset, onProgress ->
            // ISO is best-effort metadata. A missing info response must not prevent the image from
            // importing; it only means the grain model uses its neutral fallback.
            val iso = if (preset.look == null) {
                null
            } else {
                when (val info = repository.photoInfo(id)) {
                    is PhotoResult.Success -> parseTransferIso(info.value.sv)
                    is PhotoResult.Error -> null
                }
            }
            val bytes = when (
                val result = repository.downloadPhotoWithProgress(
                    id = id,
                    size = ImageSize.FULL,
                    onProgress = onProgress,
                )
            ) {
                is PhotoResult.Success -> result.value
                is PhotoResult.Error -> throw (
                    result.cause ?: java.io.IOException(result.message)
                )
            }
            DownloadedTransfer(id = id, iso = iso, payload = FullPhotoPayload(bytes))
        },
        downloadedSaver = { transfer, preset, onPayloadConsumed, onStage ->
            saveDownloadedPhoto(
                id = transfer.id,
                payload = transfer.payload,
                preset = preset,
                exporter = exporter,
                loader = filmLookLoader,
                iso = transfer.iso,
                onPayloadConsumed = onPayloadConsumed,
                onStage = onStage,
            )
        },
        pipelinePlanner = { preset ->
            chooseTransferPipeline(memoryMonitor.snapshot(), preset)
        },
        autoImportController = autoImportController,
    )

    private val _state = MutableStateFlow(TransferUiState())
    val state: StateFlow<TransferUiState> = _state.asStateFlow()

    init {
        autoImportController?.let { controller ->
            viewModelScope.launch {
                controller.state.collect { serviceState ->
                    if (
                        serviceState.phase != TransferPhase.IDLE ||
                        _state.value.source == TransferSource.AUTO_IMPORT
                    ) {
                        _state.value = serviceState
                    }
                }
            }
        }
    }

    private var resumableIds: List<PhotoId> = emptyList()

    /** Scan the connected camera, then save every listed frame with one frozen preset. */
    fun startAutoImport(preset: TransferPreset) {
        if (_state.value.isActive) return
        val safePreset = preset.normalized()
        autoImportController?.let { controller ->
            controller.start(safePreset)
            return
        }
        resumableIds = emptyList()
        _state.value = TransferUiState(
            phase = TransferPhase.SCANNING,
            source = TransferSource.AUTO_IMPORT,
            preset = safePreset,
        )
        viewModelScope.launch {
            runTransferGuarded {
                when (val result = photoLister()) {
                    is PhotoResult.Success -> process(
                        ids = result.value.map { it.id }.distinct(),
                        preset = safePreset,
                        source = TransferSource.AUTO_IMPORT,
                    )

                    is PhotoResult.Error -> _state.update {
                        it.copy(
                            phase = TransferPhase.FAILED,
                            downloading = null,
                            processing = null,
                            workStage = null,
                            message = result.message,
                        )
                    }
                }
            }
        }
    }

    /** Save the supplied contact-sheet selection in its visible order. */
    fun startSelection(ids: List<PhotoId>, preset: TransferPreset) {
        startKnownIds(ids, preset, TransferSource.SELECTION)
    }

    /** Finish the in-flight frame, then pause before the next one to avoid duplicate saves. */
    fun cancel() {
        if (!_state.value.isActive) return
        if (_state.value.source == TransferSource.AUTO_IMPORT && autoImportController != null) {
            autoImportController.pause()
            return
        }
        _state.update { it.copy(stopRequested = true) }
    }

    /** Retry failed frames, or repeat a camera scan when the scan itself failed. */
    fun retry() {
        val previous = _state.value
        if (previous.isActive) return
        if (previous.source == TransferSource.AUTO_IMPORT && autoImportController != null) {
            autoImportController.retry()
            return
        }
        val preset = previous.preset ?: return
        val failedIds = previous.failures.map { it.id }
        when {
            previous.phase == TransferPhase.CANCELLED &&
                (failedIds.isNotEmpty() || resumableIds.isNotEmpty()) ->
                startKnownIds(
                    ids = (failedIds + resumableIds).distinct(),
                    preset = preset,
                    source = previous.source ?: TransferSource.SELECTION,
                )

            failedIds.isNotEmpty() -> startKnownIds(
                ids = failedIds,
                preset = preset,
                source = previous.source ?: TransferSource.SELECTION,
            )

            previous.source == TransferSource.AUTO_IMPORT -> startAutoImport(preset)
        }
    }

    /** Return the panels to their preset state after the user has reviewed the result. */
    fun dismiss() {
        if (!_state.value.isActive) {
            if (_state.value.source == TransferSource.AUTO_IMPORT && autoImportController != null) {
                autoImportController.dismiss()
                return
            }
            resumableIds = emptyList()
            _state.value = TransferUiState()
        }
    }

    private fun startKnownIds(
        ids: List<PhotoId>,
        preset: TransferPreset,
        source: TransferSource,
    ) {
        if (_state.value.isActive) return
        val safeIds = ids.distinct()
        if (safeIds.isEmpty()) return
        val safePreset = preset.normalized()
        resumableIds = safeIds
        _state.value = TransferUiState(
            phase = TransferPhase.TRANSFERRING,
            source = source,
            preset = safePreset,
            total = safeIds.size,
        )
        viewModelScope.launch {
            runTransferGuarded {
                process(safeIds, safePreset, source)
            }
        }
    }

    private suspend fun runTransferGuarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            _state.update {
                it.copy(
                    phase = TransferPhase.CANCELLED,
                    downloading = null,
                    processing = null,
                    workStage = null,
                    stopRequested = false,
                    message = "Stopped after ${it.completed} of ${it.total} frames",
                )
            }
            throw cancelled
        } catch (failure: Throwable) {
            _state.update {
                it.copy(
                    phase = TransferPhase.FAILED,
                    downloading = null,
                    processing = null,
                    workStage = null,
                    stopRequested = false,
                    message = transferFailureReason(failure),
                )
            }
        }
    }

    private suspend fun process(
        ids: List<PhotoId>,
        preset: TransferPreset,
        source: TransferSource,
    ) {
        if (ids.isEmpty()) {
            resumableIds = emptyList()
            _state.value = TransferUiState(
                phase = TransferPhase.COMPLETED,
                source = source,
                preset = preset,
                message = "No photos found on the camera",
            )
            return
        }

        // A pause may have been requested while the camera roll itself was being scanned.
        if (_state.value.stopRequested) {
            resumableIds = ids
            _state.update {
                it.copy(
                    phase = TransferPhase.CANCELLED,
                    total = ids.size,
                    downloading = null,
                    processing = null,
                    workStage = null,
                    stopRequested = false,
                    message = "Paused before the first frame",
                )
            }
            return
        }

        val plan = pipelinePlanner(preset)
        resumableIds = ids
        _state.value = TransferUiState(
            phase = TransferPhase.TRANSFERRING,
            source = source,
            preset = preset,
            total = ids.size,
            pipelineDepth = plan.maxResidentDownloads,
            heapHeadroomMb = (plan.heapHeadroomBytes / BYTES_PER_MIB)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt(),
        )

        var saved = 0
        val failures = mutableListOf<TransferFailure>()
        var paused = false

        coroutineScope {
            val residentSlots = Semaphore(plan.maxResidentDownloads)
            // Rendezvous keeps the optimized mode to a true double buffer: the consumer's current
            // payload plus at most one producer-held next payload, never an unbounded ready queue.
            val attempts = Channel<DownloadAttempt>(capacity = Channel.RENDEZVOUS)
            val producer = launch {
                try {
                    for (index in ids.indices) {
                        if (_state.value.stopRequested) break
                        residentSlots.acquire()
                        if (_state.value.stopRequested) {
                            residentSlots.release()
                            break
                        }

                        val id = ids[index]
                        val number = index + 1
                        var downloadedTransfer: DownloadedTransfer? = null
                        var handedOff = false
                        try {
                            _state.update {
                                it.copy(
                                    downloading = id,
                                    downloadingNumber = number,
                                    downloadBytes = 0L,
                                    downloadTotalBytes = null,
                                )
                            }
                            val transfer = photoDownloader(id, preset) { bytesRead, totalBytes ->
                                _state.update { current ->
                                    if (current.downloading != id) {
                                        current
                                    } else {
                                        current.copy(
                                            downloadBytes = bytesRead.coerceAtLeast(0L),
                                            downloadTotalBytes = totalBytes?.takeIf { it > 0L },
                                        )
                                    }
                                }
                            }
                            downloadedTransfer = transfer
                            _state.update {
                                it.copy(
                                    downloadCompleted = it.downloadCompleted + 1,
                                    downloading = null,
                                    downloadingNumber = 0,
                                    downloadBytes = 0L,
                                    downloadTotalBytes = null,
                                )
                            }
                            attempts.send(DownloadAttempt.Success(number, transfer))
                            handedOff = true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            _state.update {
                                it.copy(
                                    downloadCompleted = it.downloadCompleted + 1,
                                    downloading = null,
                                    downloadingNumber = 0,
                                    downloadBytes = 0L,
                                    downloadTotalBytes = null,
                                )
                            }
                            attempts.send(
                                DownloadAttempt.Failure(
                                    number = number,
                                    id = id,
                                    reason = transferFailureReason(failure),
                                ),
                            )
                        } finally {
                            _state.update { current ->
                                if (current.downloading == id) {
                                    current.copy(
                                        downloading = null,
                                        downloadingNumber = 0,
                                        downloadBytes = 0L,
                                        downloadTotalBytes = null,
                                    )
                                } else {
                                    current
                                }
                            }
                            if (!handedOff) {
                                downloadedTransfer?.payload?.discard()
                                residentSlots.release()
                            }
                        }
                    }
                } finally {
                    attempts.close()
                }
            }

            try {
                for (attempt in attempts) {
                    resumableIds = ids.drop(attempt.number - 1)
                    when (attempt) {
                        is DownloadAttempt.Failure -> {
                            failures += TransferFailure(attempt.id, attempt.reason)
                        }

                        is DownloadAttempt.Success -> {
                            var payloadReleased = false
                            val releasePayload = {
                                if (!payloadReleased) {
                                    payloadReleased = true
                                    attempt.transfer.payload.discard()
                                    residentSlots.release()
                                }
                            }
                            _state.update {
                                it.copy(
                                    processing = attempt.id,
                                    processingNumber = attempt.number,
                                    workStage = if (preset.look == null) {
                                        TransferWorkStage.SAVING
                                    } else {
                                        TransferWorkStage.DEVELOPING
                                    },
                                )
                            }
                            try {
                                val earlyPayloadRelease: () -> Unit =
                                    if (plan.isPipelined) releasePayload else NO_OP
                                downloadedSaver(
                                    attempt.transfer,
                                    preset,
                                    earlyPayloadRelease,
                                ) { stage ->
                                    _state.update { current ->
                                        if (current.processing == attempt.id) {
                                            current.copy(workStage = stage)
                                        } else {
                                            current
                                        }
                                    }
                                }
                                saved++
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Throwable) {
                                failures += TransferFailure(
                                    attempt.id,
                                    transferFailureReason(failure),
                                )
                            } finally {
                                releasePayload()
                            }
                        }
                    }

                    resumableIds = ids.drop(attempt.number)
                    _state.update {
                        it.copy(
                            completed = attempt.number,
                            saved = saved,
                            processing = null,
                            processingNumber = 0,
                            workStage = null,
                            failures = failures.toList(),
                        )
                    }

                    if (_state.value.stopRequested && resumableIds.isNotEmpty()) {
                        paused = true
                        break
                    }
                }
            } finally {
                if (!producer.isCompleted) producer.cancelAndJoin()
                while (true) {
                    val queued = attempts.tryReceive().getOrNull() ?: break
                    if (queued is DownloadAttempt.Success) {
                        queued.transfer.payload.discard()
                        residentSlots.release()
                    }
                }
            }
        }

        if (_state.value.stopRequested && resumableIds.isNotEmpty()) paused = true

        if (paused) {
            _state.update {
                it.copy(
                    phase = TransferPhase.CANCELLED,
                    downloading = null,
                    processing = null,
                    workStage = null,
                    stopRequested = false,
                    message = "Paused after ${it.completed} of ${it.total} frames",
                )
            }
            return
        }

        resumableIds = emptyList()
        _state.update {
            it.copy(
                phase = TransferPhase.COMPLETED,
                completed = ids.size,
                saved = saved,
                downloading = null,
                processing = null,
                workStage = null,
                failures = failures.toList(),
                stopRequested = false,
            )
        }
    }

    internal class Factory(
        private val repository: PhotoRepository,
        private val exporter: PhotoExporter,
        private val filmLookLoader: FilmLookLoader,
        private val memoryMonitor: TransferMemoryMonitor,
        private val autoImportController: AutoImportController,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TransferViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return TransferViewModel(
                repository,
                exporter,
                filmLookLoader,
                memoryMonitor,
                autoImportController,
            ) as T
        }
    }
}

/** Ricoh reports ISO as strings such as "500"; unknown/auto values intentionally stay null. */
internal fun parseTransferIso(value: String?): Int? =
    value?.trim()?.toIntOrNull()?.takeIf { it > 0 }

internal fun transferFailureReason(failure: Throwable): String =
    if (failure is OutOfMemoryError) {
        "Not enough memory to develop this frame"
    } else {
        failure.message?.takeIf { it.isNotBlank() } ?: failure::class.simpleName ?: "Unknown error"
    }

private const val BYTES_PER_MIB = 1024L * 1024L
private val NO_OP: () -> Unit = {}
