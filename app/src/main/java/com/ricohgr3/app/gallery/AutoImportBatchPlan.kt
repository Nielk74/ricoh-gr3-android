package com.ricohgr3.app.gallery

import com.ricohgr3.app.data.PhotoId
import com.ricohgr3.app.data.PhotoItem

/** One camera file and the work it contributes to a drain-first auto-import. */
internal data class PlannedImportFile(
    val id: PhotoId,
    val shotKey: String,
    val saveOriginal: Boolean,
    val developEdited: Boolean,
)

/**
 * A camera batch is grouped by folder + basename so `R0000123.JPG` and `R0000123.DNG` are one
 * exposure. All requested files are downloaded before any export starts. When an edited copy is
 * requested, the DNG is the sole develop source when present; its camera JPEG is never developed
 * a second time.
 */
internal data class AutoImportBatchPlan(
    val files: List<PlannedImportFile>,
) {
    val downloadCount: Int get() = files.size
    val outputCount: Int
        get() = files.count { it.saveOriginal } + files.count { it.developEdited }
}

internal fun planAutoImport(
    photos: List<PhotoItem>,
    requestedPreset: TransferPreset,
): AutoImportBatchPlan {
    val preset = requestedPreset.normalized()
    val supported = photos
        .map { it.id }
        .distinct()
        .filter { it.importFileKind() != ImportFileKind.UNSUPPORTED }

    val byShot = linkedMapOf<String, MutableList<PhotoId>>()
    for (id in supported) byShot.getOrPut(id.shotKey()) { mutableListOf() } += id

    val includeOriginals = preset.outputMode != TransferOutputMode.EDITED_ONLY
    val includeEdits = preset.look != null && preset.outputMode != TransferOutputMode.ORIGINAL_ONLY
    val editSources = if (includeEdits) {
        byShot.mapValues { (_, ids) ->
            ids.firstOrNull { it.importFileKind() == ImportFileKind.DNG }
                ?: ids.firstOrNull { it.importFileKind() == ImportFileKind.JPEG }
        }.values.filterNotNull().toSet()
    } else {
        emptySet()
    }

    return AutoImportBatchPlan(
        files = supported.mapNotNull { id ->
            val saveOriginal = includeOriginals
            val developEdited = id in editSources
            if (!saveOriginal && !developEdited) {
                null
            } else {
                PlannedImportFile(
                    id = id,
                    shotKey = id.shotKey(),
                    saveOriginal = saveOriginal,
                    developEdited = developEdited,
                )
            }
        },
    )
}

private enum class ImportFileKind { JPEG, DNG, UNSUPPORTED }

private fun PhotoId.importFileKind(): ImportFileKind =
    when (file.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> ImportFileKind.JPEG
        "dng" -> ImportFileKind.DNG
        else -> ImportFileKind.UNSUPPORTED
    }

private fun PhotoId.shotKey(): String =
    "${folder.lowercase()}/${file.substringBeforeLast('.', file).lowercase()}"
