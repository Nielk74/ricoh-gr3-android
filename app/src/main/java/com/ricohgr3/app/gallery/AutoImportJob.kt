package com.ricohgr3.app.gallery

import com.ricohgr3.app.data.EditedExportQuality
import com.ricohgr3.app.data.PhotoId
import com.ricohgr3.app.looks.emulation.RenderingIntent
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
internal enum class AutoImportJobPhase {
    PREPARING,
    DOWNLOADING,
    PROCESSING,
    PAUSED,
    COMPLETED,
    FAILED,
}

@Serializable
internal enum class AutoImportItemStatus {
    NOT_REQUIRED,
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
}

@Serializable
internal data class StoredTransferPreset(
    val look: String?,
    val intensity: Float,
    val renderingIntent: String,
    val grainEnabled: Boolean = true,
    val quality: String,
    val outputMode: String,
) {
    fun restore(): TransferPreset = TransferPreset(
        look = look,
        intensity = intensity,
        renderingIntent = enumValueOrDefault(renderingIntent, RenderingIntent.SMART),
        grainEnabled = grainEnabled,
        quality = enumValueOrDefault(quality, EditedExportQuality.MAXIMUM),
        outputMode = enumValueOrDefault(outputMode, TransferOutputMode.ORIGINAL_AND_EDITED),
    ).normalized()
}

@Serializable
internal data class StoredImportFile(
    val folder: String,
    val file: String,
    val shotKey: String,
    val spoolName: String,
    val expectedBytes: Long? = null,
    val downloadedBytes: Long = 0L,
    val iso: Int? = null,
    val downloadStatus: AutoImportItemStatus = AutoImportItemStatus.PENDING,
    val originalStatus: AutoImportItemStatus = AutoImportItemStatus.NOT_REQUIRED,
    val editStatus: AutoImportItemStatus = AutoImportItemStatus.NOT_REQUIRED,
    val downloadError: String? = null,
    val originalError: String? = null,
    val editError: String? = null,
) {
    val id: PhotoId get() = PhotoId(folder, file)
}

@Serializable
internal data class AutoImportJob(
    val id: String,
    val createdAtMillis: Long,
    val preset: StoredTransferPreset,
    val phase: AutoImportJobPhase,
    val files: List<StoredImportFile>,
    val metadataComplete: Boolean = false,
    val message: String? = null,
) {
    val downloadTotal: Int get() = files.size
    val downloadCompleted: Int
        get() = files.count { it.downloadStatus.isTerminal }
    val outputTotal: Int
        get() = files.sumOf { file ->
            (if (file.originalStatus != AutoImportItemStatus.NOT_REQUIRED) 1 else 0) +
                (if (file.editStatus != AutoImportItemStatus.NOT_REQUIRED) 1 else 0)
        }
    val outputCompleted: Int
        get() = files.sumOf { file ->
            (if (file.originalStatus.isTerminal) 1 else 0) +
                (if (file.editStatus.isTerminal) 1 else 0)
        }
    val saved: Int
        get() = files.sumOf { file ->
            (if (file.originalStatus == AutoImportItemStatus.SUCCESS) 1 else 0) +
                (if (file.editStatus == AutoImportItemStatus.SUCCESS) 1 else 0)
        }

    fun recoverAfterProcessDeath(): AutoImportJob =
        if (phase == AutoImportJobPhase.PREPARING || phase == AutoImportJobPhase.DOWNLOADING ||
            phase == AutoImportJobPhase.PROCESSING
        ) {
            copy(
                phase = AutoImportJobPhase.PAUSED,
                files = files.map { file ->
                    file.copy(
                        downloadStatus = file.downloadStatus.recoverRunning(),
                        originalStatus = file.originalStatus.recoverRunning(),
                        editStatus = file.editStatus.recoverRunning(),
                    )
                },
                message = "Import was interrupted. Reconnect the camera if downloads remain, then continue.",
            )
        } else {
            this
        }

    fun resetFailuresForRetry(): AutoImportJob = copy(
        phase = if (files.any { it.downloadStatus != AutoImportItemStatus.SUCCESS }) {
            AutoImportJobPhase.DOWNLOADING
        } else {
            AutoImportJobPhase.PROCESSING
        },
        files = files.map { file ->
            val retryDownload = file.downloadStatus == AutoImportItemStatus.FAILED
            file.copy(
                downloadStatus = if (retryDownload) AutoImportItemStatus.PENDING else file.downloadStatus,
                originalStatus = when {
                    file.originalStatus == AutoImportItemStatus.FAILED -> AutoImportItemStatus.PENDING
                    else -> file.originalStatus
                },
                editStatus = when {
                    file.editStatus == AutoImportItemStatus.FAILED -> AutoImportItemStatus.PENDING
                    else -> file.editStatus
                },
                downloadError = if (retryDownload) null else file.downloadError,
                originalError = if (file.originalStatus == AutoImportItemStatus.FAILED) null else file.originalError,
                editError = if (file.editStatus == AutoImportItemStatus.FAILED) null else file.editError,
            )
        },
        message = null,
    )

    companion object {
        fun create(
            plan: AutoImportBatchPlan,
            preset: TransferPreset,
            id: String = UUID.randomUUID().toString(),
        ): AutoImportJob =
            AutoImportJob(
                id = id,
                createdAtMillis = System.currentTimeMillis(),
                preset = preset.normalized().stored(),
                phase = AutoImportJobPhase.PREPARING,
                files = plan.files.mapIndexed { index, planned ->
                    StoredImportFile(
                        folder = planned.id.folder,
                        file = planned.id.file,
                        shotKey = planned.shotKey,
                        spoolName = "%05d_%s".format(index, planned.id.file.safeSpoolName()),
                        originalStatus = if (planned.saveOriginal) {
                            AutoImportItemStatus.PENDING
                        } else {
                            AutoImportItemStatus.NOT_REQUIRED
                        },
                        editStatus = if (planned.developEdited) {
                            AutoImportItemStatus.PENDING
                        } else {
                            AutoImportItemStatus.NOT_REQUIRED
                        },
                    )
                },
            )
    }
}

internal data class AutoImportLiveProgress(
    val downloading: PhotoId? = null,
    val downloadingNumber: Int = 0,
    val downloadBytes: Long = 0L,
    val downloadTotalBytes: Long? = null,
    val processing: PhotoId? = null,
    val processingNumber: Int = 0,
    val processingPart: Int = 0,
    val processingParts: Int = 0,
    val workStage: TransferWorkStage? = null,
    val stopRequested: Boolean = false,
    val heapHeadroomMb: Int? = null,
)

internal fun AutoImportJob.toUiState(
    live: AutoImportLiveProgress = AutoImportLiveProgress(),
): TransferUiState {
    val failures = buildList {
        for (file in files) {
            when {
                file.downloadStatus == AutoImportItemStatus.FAILED ->
                    add(TransferFailure(file.id, file.downloadError ?: "Download failed"))
                else -> {
                    if (file.originalStatus == AutoImportItemStatus.FAILED) {
                        add(TransferFailure(file.id, file.originalError ?: "Original export failed"))
                    }
                    if (file.editStatus == AutoImportItemStatus.FAILED) {
                        add(TransferFailure(file.id, file.editError ?: "Edited export failed"))
                    }
                }
            }
        }
    }
    val uiPhase = when (phase) {
        AutoImportJobPhase.PREPARING -> TransferPhase.SCANNING
        AutoImportJobPhase.DOWNLOADING -> TransferPhase.DOWNLOADING
        AutoImportJobPhase.PROCESSING -> TransferPhase.PROCESSING
        AutoImportJobPhase.PAUSED -> TransferPhase.CANCELLED
        AutoImportJobPhase.COMPLETED -> TransferPhase.COMPLETED
        AutoImportJobPhase.FAILED -> TransferPhase.FAILED
    }
    return TransferUiState(
        phase = uiPhase,
        source = TransferSource.AUTO_IMPORT,
        preset = preset.restore(),
        total = outputTotal,
        completed = outputCompleted,
        saved = saved,
        downloadTotal = downloadTotal,
        downloadCompleted = downloadCompleted,
        downloading = live.downloading,
        downloadingNumber = live.downloadingNumber,
        downloadBytes = live.downloadBytes,
        downloadTotalBytes = live.downloadTotalBytes,
        processing = live.processing,
        processingNumber = live.processingNumber,
        processingPart = live.processingPart,
        processingParts = live.processingParts,
        workStage = live.workStage,
        pipelineDepth = 0,
        heapHeadroomMb = live.heapHeadroomMb,
        diskBacked = true,
        failures = failures,
        message = message,
        stopRequested = live.stopRequested,
    )
}

private val AutoImportItemStatus.isTerminal: Boolean
    get() = this == AutoImportItemStatus.SUCCESS || this == AutoImportItemStatus.FAILED

private fun AutoImportItemStatus.recoverRunning(): AutoImportItemStatus =
    if (this == AutoImportItemStatus.RUNNING) AutoImportItemStatus.PENDING else this

private fun TransferPreset.stored(): StoredTransferPreset = StoredTransferPreset(
    look = look,
    intensity = intensity,
    renderingIntent = renderingIntent.name,
    grainEnabled = grainEnabled,
    quality = quality.name,
    outputMode = outputMode.name,
)

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: fallback

private fun String.safeSpoolName(): String =
    map { character ->
        if (character.isLetterOrDigit() || character == '.' || character == '-' || character == '_') {
            character
        } else {
            '_'
        }
    }.joinToString("").ifBlank { "photo.bin" }
