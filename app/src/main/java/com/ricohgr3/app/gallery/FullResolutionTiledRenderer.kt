package com.ricohgr3.app.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.os.Build
import com.ricohgr3.app.data.PhotoExporter
import com.ricohgr3.app.data.PhotoId
import com.ricohgr3.app.data.EditedExportQuality
import com.ricohgr3.app.looks.emulation.DevelopEngine
import com.ricohgr3.app.looks.emulation.DevelopOptions
import com.ricohgr3.app.looks.emulation.FilmFormat
import com.ricohgr3.app.looks.emulation.FilmLookLoader
import com.ricohgr3.app.looks.emulation.PhysicalFilmGrain
import com.ricohgr3.app.looks.emulation.RenderingIntent
import com.ricohgr3.app.looks.emulation.SkinMaskMapping
import com.ricohgr3.app.looks.emulation.stableRenderSeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.ceil

internal data class ImageRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

internal data class FullResolutionTile(
    val decode: ImageRegion,
    val core: ImageRegion,
)

/** Raised between completed regions so Pause never leaves a partially published gallery image. */
internal class ImportPauseRequestedException : Exception()

/**
 * Plan full-height, overlap-padded regions from actual process headroom. Reaching the real top
 * edge in every region preserves the connected-sky semantic gate; [DevelopOptions]'s full-frame
 * scale keeps diffusion, halation, and grain physically consistent across the vertical strips.
 */
internal fun planFullResolutionTiles(
    width: Int,
    height: Int,
    heapHeadroomBytes: Long,
    haloPixels: Int = FULL_RESOLUTION_HALO_PIXELS,
): List<FullResolutionTile> {
    require(width > 0 && height > 0)
    require(haloPixels >= 0)
    val pixels = width.toLong() * height.toLong()
    require(pixels <= Int.MAX_VALUE)

    val outputBytes = pixels * ARGB_BYTES_PER_PIXEL
    val tileBudget = heapHeadroomBytes - outputBytes - FULL_RESOLUTION_PROCESS_RESERVE_BYTES
    val fullSpan = height
    val shortSpan = width
    val minimumDecodeThickness = (haloPixels * 2 + MINIMUM_CORE_THICKNESS).coerceAtMost(shortSpan)
    val minimumTileBytes = fullSpan.toLong() * minimumDecodeThickness * TILE_WORKING_BYTES_PER_PIXEL
    if (tileBudget < minimumTileBytes) {
        throw IOException(
            "Not enough safe memory for a full-size edit (${heapHeadroomBytes / MIB} MiB available budget)",
        )
    }

    val decodeThickness = (tileBudget / TILE_WORKING_BYTES_PER_PIXEL / fullSpan)
        .coerceIn(minimumDecodeThickness.toLong(), shortSpan.toLong())
        .toInt()
    val coreThickness = if (decodeThickness == shortSpan) {
        shortSpan
    } else {
        (decodeThickness - haloPixels * 2)
            .coerceAtLeast(MINIMUM_CORE_THICKNESS)
            .coerceAtMost(shortSpan)
    }
    val count = ceil(shortSpan.toDouble() / coreThickness).toInt()

    return List(count) { index ->
        val coreStart = index * coreThickness
        val coreEnd = minOf(shortSpan, coreStart + coreThickness)
        val decodeStart = maxOf(0, coreStart - haloPixels)
        val decodeEnd = minOf(shortSpan, coreEnd + haloPixels)
        FullResolutionTile(
            decode = ImageRegion(decodeStart, 0, decodeEnd, height),
            core = ImageRegion(coreStart, 0, coreEnd, height),
        )
    }
}

/** Use the tiled full-dimension path for Maximum; retain the explicit lower-resolution presets. */
internal suspend fun saveStagedEdited(
    source: File,
    id: PhotoId,
    preset: TransferPreset,
    exporter: PhotoExporter,
    loader: FilmLookLoader,
    iso: Int?,
    heapHeadroomBytes: Long,
    onRegionProgress: (completed: Int, total: Int) -> Unit,
    shouldPause: () -> Boolean,
): SaveOutcome {
    if (preset.quality == EditedExportQuality.MAXIMUM) {
        return saveStagedEditedFullResolution(
            source = source,
            id = id,
            preset = preset,
            exporter = exporter,
            loader = loader,
            iso = iso,
            heapHeadroomBytes = heapHeadroomBytes,
            onRegionProgress = onRegionProgress,
            shouldPause = shouldPause,
        )
    }

    val payload = withContext(Dispatchers.IO) { FullPhotoPayload(source.readBytes()) }
    return saveDownloadedPhoto(
        id = id,
        payload = payload,
        preset = preset,
        exporter = exporter,
        loader = loader,
        iso = iso,
        onPayloadConsumed = {},
        onStage = {},
    )
}

/** Develop a staged JPEG/DNG at its decoded source dimensions using sequential padded regions. */
internal suspend fun saveStagedEditedFullResolution(
    source: File,
    id: PhotoId,
    preset: TransferPreset,
    exporter: PhotoExporter,
    loader: FilmLookLoader,
    iso: Int?,
    heapHeadroomBytes: Long,
    onRegionProgress: (completed: Int, total: Int) -> Unit,
    shouldPause: () -> Boolean,
): SaveOutcome {
    val lookId = preset.look ?: throw IOException("A film look is required for an edited export")
    val resolved = loader.resolve(lookId) ?: throw IOException("Unknown film look: $lookId")
    val isRaw = id.file.substringAfterLast('.', "").equals("dng", ignoreCase = true)

    val rendered = withContext(Dispatchers.Default) {
        val prepared = prepareRegionSource(source, isRaw)
        try {
            val decoder = BitmapRegionDecoder.newInstance(prepared.file.absolutePath, false)
                ?: throw IOException("Could not open ${id.file} for region decoding")
            try {
                val width = decoder.width
                val height = decoder.height
                val tiles = planFullResolutionTiles(width, height, heapHeadroomBytes)
                onRegionProgress(0, tiles.size)
                val preview = decodePreview(decoder, width, height)
                val preGrade = if (isRaw) RawPreGrade else null
                val sceneProfile = if (preset.renderingIntent == RenderingIntent.SMART) {
                    DevelopEngine.analyzeScene(preview, preGrade)
                } else {
                    null
                }
                val globalFaces = if (
                    preset.renderingIntent == RenderingIntent.SMART && resolved.first.skinTone.enabled
                ) {
                    DevelopEngine.detectFaces(preview)
                } else {
                    emptyList()
                }
                val renderSeed = stableRenderSeed(id.toString())
                val globalSkinMask = if (globalFaces.isNotEmpty()) {
                    DevelopEngine.captureSkinMask(
                        src = preview,
                        look = resolved.first,
                        lut = resolved.second,
                        preGrade = preGrade,
                        iso = iso,
                        effectStrength = preset.intensity,
                        options = DevelopOptions(
                            intent = preset.renderingIntent,
                            renderSeed = renderSeed,
                            sceneProfile = sceneProfile,
                            grainEnabled = preset.grainEnabled,
                        ),
                        faceRegions = globalFaces,
                    )
                } else {
                    null
                }
                preview.recycle()

                val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    for ((index, tile) in tiles.withIndex()) {
                        if (shouldPause()) throw ImportPauseRequestedException()
                        val decoded = decoder.decodeRegion(
                            tile.decode.toAndroidRect(),
                            BitmapFactory.Options().apply {
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                            },
                        ) ?: throw IOException("Could not decode region ${index + 1} of ${tiles.size}")
                        try {
                            val longEdgePixels = maxOf(width, height).toFloat()
                            val regionLongEdgePixels = maxOf(tile.decode.width, tile.decode.height)
                            val longEdgeMillimetres = maxOf(
                                FilmFormat.FULL_FRAME_35MM.widthMillimetres,
                                FilmFormat.FULL_FRAME_35MM.heightMillimetres,
                            )
                            val options = DevelopOptions(
                                intent = preset.renderingIntent,
                                renderSeed = renderSeed,
                                sceneProfile = sceneProfile,
                                filmPlane = PhysicalFilmGrain.FilmPlane(
                                    longEdgeMillimeters = longEdgeMillimetres,
                                    originXMillimeters =
                                        tile.decode.left / longEdgePixels * longEdgeMillimetres,
                                    originYMillimeters =
                                        tile.decode.top / longEdgePixels * longEdgeMillimetres,
                                    framingScale = regionLongEdgePixels / longEdgePixels,
                                ),
                                spatialLongEdgePixels = longEdgePixels.toInt(),
                                grainEnabled = preset.grainEnabled,
                                skinMaskMapping = globalSkinMask?.let { mask ->
                                    SkinMaskMapping(
                                        mask = mask,
                                        originX = tile.decode.left,
                                        originY = tile.decode.top,
                                        fullWidth = width,
                                        fullHeight = height,
                                    )
                                },
                            )
                            DevelopEngine.renderRegionInto(
                                src = decoded,
                                destination = output,
                                sourceX = tile.core.left - tile.decode.left,
                                sourceY = tile.core.top - tile.decode.top,
                                coreWidth = tile.core.width,
                                coreHeight = tile.core.height,
                                destinationX = tile.core.left,
                                destinationY = tile.core.top,
                                look = resolved.first,
                                lut = resolved.second,
                                preGrade = preGrade,
                                iso = iso,
                                effectStrength = preset.intensity,
                                options = options,
                                faceRegions = emptyList(),
                            )
                        } finally {
                            decoded.recycle()
                        }
                        onRegionProgress(index + 1, tiles.size)
                    }
                    output
                } catch (failure: Throwable) {
                    output.recycle()
                    throw failure
                }
            } finally {
                decoder.recycle()
            }
        } finally {
            if (prepared.temporary) prepared.file.delete()
        }
    }

    val displayName = editedName(id.file)
    val renderedWidth = rendered.width
    val renderedHeight = rendered.height
    try {
        exporter.saveBitmap(rendered, displayName, preset.quality.jpegQuality)
    } finally {
        rendered.recycle()
    }
    return SaveOutcome(
        displayName = displayName,
        edited = true,
        width = renderedWidth,
        height = renderedHeight,
        jpegQuality = preset.quality.jpegQuality,
    )
}

private data class PreparedRegionSource(val file: File, val temporary: Boolean)

private fun prepareRegionSource(source: File, isRaw: Boolean): PreparedRegionSource {
    if (!source.isFile) throw IOException("Staged source is missing: ${source.name}")
    if (!isRaw) return PreparedRegionSource(source, temporary = false)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        throw IOException("Full-size DNG editing requires Android 9 or newer")
    }

    val temporary = File(source.parentFile, source.name + ".region-source.png")
    val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, _, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
    }
    try {
        FileOutputStream(temporary).use { output ->
            if (!decoded.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw IOException("Could not prepare ${source.name} for tiled DNG development")
            }
            output.fd.sync()
        }
    } finally {
        decoded.recycle()
    }
    return PreparedRegionSource(temporary, temporary = true)
}

private fun decodePreview(decoder: BitmapRegionDecoder, width: Int, height: Int): Bitmap {
    var sample = 1
    while (maxOf(width, height) / sample > PREVIEW_LONG_EDGE) sample *= 2
    return decoder.decodeRegion(
        Rect(0, 0, width, height),
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    ) ?: throw IOException("Could not decode a whole-frame analysis preview")
}

private fun ImageRegion.toAndroidRect(): Rect = Rect(left, top, right, bottom)

private const val PREVIEW_LONG_EDGE = 1024
internal const val FULL_RESOLUTION_HALO_PIXELS = 192
private const val MINIMUM_CORE_THICKNESS = 64
// Decoded ARGB + three float planes + diffusion/halation scratch. Region rendering creates only a
// packed core array after those spatial stages, avoiding a second full decoded-region int copy;
// 32 B/pixel still leaves margin above the 30 B/pixel worst overlapping allocation set.
private const val TILE_WORKING_BYTES_PER_PIXEL = 32L
private const val ARGB_BYTES_PER_PIXEL = 4L
private const val FULL_RESOLUTION_PROCESS_RESERVE_BYTES = 24L * 1024L * 1024L
private const val MIB = 1024L * 1024L
