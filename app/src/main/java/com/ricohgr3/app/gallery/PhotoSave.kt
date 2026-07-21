package com.ricohgr3.app.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.graphics.toArgb
import com.ricohgr3.app.data.EditedExportQuality
import com.ricohgr3.app.data.PhotoExporter
import com.ricohgr3.app.data.PhotoId
import com.ricohgr3.app.data.PhotoRepository
import com.ricohgr3.app.data.PhotoResult
import com.ricohgr3.app.looks.emulation.DevelopEngine
import com.ricohgr3.app.looks.emulation.DevelopOptions
import com.ricohgr3.app.looks.emulation.FilmLookLoader
import com.ricohgr3.app.looks.emulation.RenderingIntent
import com.ricohgr3.app.looks.emulation.stableRenderSeed
import com.ricohgr3.app.ui.LookSwatch
import com.ricohgr3.app.wifi.ImageSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Save-to-device flows for the viewer. Both go through the network-bound [PhotoRepository]
 * (which delegates to the session's Wi-Fi-bound HTTP client), so the full-resolution fetch is
 * routed onto the camera AP's [android.net.Network] rather than failing on the phone's default
 * (internet-less-AP-blind) network.
 *
 * Returns a human-readable result string on success, or throws so the caller can surface the
 * error — nothing is swallowed.
 */

/** Result of a save, including the exact edited-JPEG decisions when an edit was rendered. */
data class SaveOutcome(
    val displayName: String,
    val edited: Boolean,
    val width: Int? = null,
    val height: Int? = null,
    val jpegQuality: Int? = null,
)

/**
 * Download the full-resolution original from the camera and write it, untouched, to the gallery.
 */
suspend fun saveOriginal(
    id: PhotoId,
    repository: PhotoRepository,
    exporter: PhotoExporter,
): SaveOutcome {
    val bytes = fetchFull(id, repository)
    exporter.saveBytes(bytes, id.file, mimeTypeFor(id.file))
    return SaveOutcome(displayName = id.file, edited = false)
}

/**
 * Download the original, decode a memory-bounded working rendition, apply [look], and save the
 * result as a new JPEG so the original is kept.
 *
 * [filmLookId] is a [com.ricohgr3.app.looks.emulation.FilmLookCatalog] stock id (or `null` for
 * Standard). When a [loader] is provided the frame is genuinely **developed** through the film
 * engine ([DevelopEngine]: LUT + split-tone + selective skin/foliage/sky + halation + grain).
 * Without a loader (the JVM path)
 * it falls back to the honest indicative gradient tint that mirrors [PhotoStage]'s overlay.
 *
 * **DNG originals** are rendered to display sRGB by Android's platform decoder
 * ([decodeBounded]) and developed with a nearly neutral DNG base grade (some platform
 * renditions are flatter than the camera JPEG — see [developForSave]).
 * The result is **always saved as JPEG**, because a developed rendition is a finished image, not
 * raw sensor data — keeping a `.dng` extension on baked pixels would be misleading and most
 * viewers wouldn't show it. The untouched original DNG is left on the camera.
 *
 * If a DNG cannot be decoded on this device (some sensors/firmware produce DNGs the platform
 * decoder rejects), the edited save fails explicitly and leaves the user free to choose
 * "Save original". An edited-save request must never silently produce an untouched file.
 */
suspend fun saveEdited(
    id: PhotoId,
    filmLookId: String?,
    repository: PhotoRepository,
    exporter: PhotoExporter,
    loader: FilmLookLoader? = null,
    iso: Int? = null,
    effectStrength: Float = 1f,
    renderingIntent: RenderingIntent = RenderingIntent.SMART,
    exportQuality: EditedExportQuality = EditedExportQuality.HIGH,
): SaveOutcome {
    // Standard (null) is the as-shot baseline — nothing to bake; keep the pristine original.
    if (filmLookId == null) {
        return saveOriginal(id, repository, exporter)
    }
    val isRaw = id.file.substringAfterLast('.', "").equals("DNG", true)
    // The develop runs on the CPU and allocates several full-frame buffers. Scale the decode
    // against this process's actual heap ceiling and the user's explicit quality preset.
    // Fetch/decode lives in a helper scope so the compressed camera payload becomes collectible
    // before the spatial pipeline reaches its peak.
    val maxPixels = developmentPixelLimit(Runtime.getRuntime().maxMemory(), exportQuality)
    val decoded = fetchDecodedForEdit(id, repository, maxPixels)
        ?: if (isRaw) {
            throw java.io.IOException(
                "This device could not render ${id.file} for editing; use Save original instead.",
            )
        } else {
            throw java.io.IOException("Could not decode ${id.file} for editing")
        }

    // Do all pixel work off the main thread.
    val edited = withContext(Dispatchers.Default) {
        // Develop the selected film stock directly. `developForSave` leaves `decoded` untouched,
        // so recycle it after; `applyLookTint` consumes and recycles its source, so we must not
        // recycle again in that path.
        val developed = loader?.resolve(filmLookId)
            ?.let { (film, lut) ->
                developForSave(
                    decoded, film, lut,
                    raw = isRaw,
                    iso = iso,
                    effectStrength = effectStrength,
                    options = DevelopOptions(
                        intent = renderingIntent,
                        renderSeed = stableRenderSeed(id.toString()),
                    ),
                )
            }
        if (developed != null) {
            decoded.recycle()
            developed
        } else {
            // No loader (JVM path) or unknown stock → honest indicative gradient tint.
            applyLookTint(decoded, filmLookId, effectStrength)
        }
    }

    return exportDeveloped(edited, id.file, exporter, exportQuality)
}

/**
 * Local-lab variant of [saveEdited]: develop image [bytes] picked from the device (no camera
 * involved) through the film stock and save the result as a new JPEG, exactly like an edited
 * camera frame. [baseFile] is only used for naming (`name.jpg` → `name_edit.jpg`) and the RAW
 * check; there is no camera metadata, so [iso] stays unknown (grain falls back to its default).
 *
 * [rotationDegrees] (0/90/180/270) is baked in before the develop. A `null` [filmLookId] skips
 * the develop entirely, giving a rotate-only save; the caller must pass at least one edit.
 */
suspend fun saveEditedLocal(
    bytes: ByteArray,
    baseFile: String,
    filmLookId: String?,
    exporter: PhotoExporter,
    loader: FilmLookLoader? = null,
    effectStrength: Float = 1f,
    renderingIntent: RenderingIntent = RenderingIntent.SMART,
    exportQuality: EditedExportQuality = EditedExportQuality.HIGH,
    rotationDegrees: Int = 0,
): SaveOutcome {
    require(filmLookId != null || rotationDegrees % 360 != 0) { "saveEditedLocal needs an edit" }
    val isRaw = baseFile.substringAfterLast('.', "").equals("DNG", true)
    val maxPixels = developmentPixelLimit(Runtime.getRuntime().maxMemory(), exportQuality)
    val decoded = withContext(Dispatchers.Default) { decodeBounded(bytes, maxPixels) }
        ?: throw java.io.IOException("Could not decode $baseFile for editing")
    val oriented = rotateBitmap(decoded, rotationDegrees)
    if (oriented !== decoded) decoded.recycle()

    val edited = withContext(Dispatchers.Default) {
        val developed = filmLookId?.let { loader?.resolve(it) }
            ?.let { (film, lut) ->
                developForSave(
                    oriented, film, lut,
                    raw = isRaw,
                    iso = null,
                    effectStrength = effectStrength,
                    options = DevelopOptions(
                        intent = renderingIntent,
                        renderSeed = stableRenderSeed("local:$baseFile"),
                    ),
                )
            }
        if (developed != null) {
            oriented.recycle()
            developed
        } else if (filmLookId != null) {
            applyLookTint(oriented, filmLookId, effectStrength)
        } else {
            // Rotate-only save: nothing to bake, keep the oriented decode.
            oriented
        }
    }
    return exportDeveloped(edited, baseFile, exporter, exportQuality)
}

/**
 * Rotate [src] by [degrees] (any multiple of 90; normalized to 0/90/180/270). Returns [src]
 * itself when no rotation is needed; otherwise a new filtered bitmap — the caller owns both and
 * must recycle whichever it drops.
 */
internal fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
    val normalized = ((degrees % 360) + 360) % 360
    if (normalized == 0) return src
    val matrix = android.graphics.Matrix().apply { postRotate(normalized.toFloat()) }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
}

/**
 * Compress a developed rendition to JPEG, write it under the `_edit` name derived from
 * [baseFile], recycle it, and report the outcome. Shared tail of [saveEdited]/[saveEditedLocal].
 */
private suspend fun exportDeveloped(
    edited: Bitmap,
    baseFile: String,
    exporter: PhotoExporter,
    exportQuality: EditedExportQuality,
): SaveOutcome {
    // DNG develops are finished renditions → always JPEG (see saveEdited kdoc). JPEG keeps .jpg.
    val name = editedName(baseFile)
    val width = edited.width
    val height = edited.height
    try {
        exporter.saveBitmap(edited, name, quality = exportQuality.jpegQuality)
    } finally {
        edited.recycle()
    }
    return SaveOutcome(
        displayName = name,
        edited = true,
        width = width,
        height = height,
        jpegQuality = exportQuality.jpegQuality,
    )
}

/**
 * Develop [decoded] through the film [look]/[lut]. For [raw] DNG input, apply a mild base grade
 * first ([RawPreGrade]) so a flatter platform rendition is brought slightly closer to the
 * camera-JPEG-like tonal starting point the film models were authored against.
 * Leaves [decoded] untouched; returns a new bitmap.
 */
private fun developForSave(
    decoded: android.graphics.Bitmap,
    look: com.ricohgr3.app.looks.emulation.FilmLook,
    lut: com.ricohgr3.app.looks.emulation.LutCube,
    raw: Boolean,
    iso: Int?,
    effectStrength: Float,
    options: DevelopOptions,
): android.graphics.Bitmap =
    DevelopEngine.render(
        decoded,
        look,
        lut,
        preGrade = if (raw) RawPreGrade else null,
        iso = iso,
        effectStrength = effectStrength,
        options = options,
    )

private suspend fun fetchFull(id: PhotoId, repository: PhotoRepository): ByteArray =
    when (val r = repository.downloadPhoto(id, size = ImageSize.FULL)) {
        is PhotoResult.Success -> r.value
        is PhotoResult.Error -> throw (r.cause ?: java.io.IOException(r.message))
    }

/**
 * Download and decode in a separate call frame. Once this returns, the compressed full-resolution
 * payload is no longer live while the much larger float working set is allocated.
 */
private suspend fun fetchDecodedForEdit(
    id: PhotoId,
    repository: PhotoRepository,
    maxPixels: Int,
): Bitmap? {
    val bytes = fetchFull(id, repository)
    return withContext(Dispatchers.Default) {
        decodeBounded(bytes, maxPixels)
    }
}

/** Keep at least a VIEW-class output even on an unusually constrained test/runtime heap. */
internal const val MIN_EDIT_PIXELS = 720 * 480

/**
 * Conservative peak estimate for the decoded/output bitmaps, packed pixels, RGB planes, optical
 * diffusion, halation masks, and blur scratch. The exact peak varies by stock, so this includes a
 * margin above the currently measured arrays.
 */
private const val PEAK_DEVELOP_BYTES_PER_PIXEL = 34L

/**
 * Choose a development ceiling from the app process's actual maximum heap.
 *
 * At most 40% of the heap is assigned to the film working set, leaving the rest for Compose,
 * camera payloads, ML state, codecs, and VM overhead. Typical results are about 1.6 MP on a
 * 128 MiB heap, 3.1 MP on 256 MiB, 6.3 MP on 512 MiB, and 12.6 MP on 1 GiB. Compact
 * and High apply a lower preset cap; Maximum uses the full heap-derived limit.
 */
internal fun developmentPixelLimit(
    maxHeapBytes: Long,
    exportQuality: EditedExportQuality = EditedExportQuality.HIGH,
): Int {
    val nonNegativeHeap = maxHeapBytes.coerceAtLeast(0L)
    val workingBytes = (nonNegativeHeap / 10L) * 4L
    return (workingBytes / PEAK_DEVELOP_BYTES_PER_PIXEL)
        .coerceIn(MIN_EDIT_PIXELS.toLong(), exportQuality.pixelCap.toLong())
        .toInt()
}

/**
 * Decode [bytes] to an ARGB_8888 bitmap whose pixel count is at most [maxPixels]. BitmapFactory's
 * power-of-two sampling first bounds the intermediate decode, then a filtered resize uses the
 * selected budget instead of silently dropping to the next much smaller sampling tier. Returns
 * null if the bytes aren't a decodable image.
 *
 * JPEG/PNG go through [BitmapFactory]. **DNG** is not decodable by [BitmapFactory] on most
 * devices, so it falls through to [decodeRawBounded], which uses the platform [ImageDecoder]
 * DNG path (API 28+). On API 26-27, or if the platform can't decode the DNG, this returns null
 * and the caller reports that edited DNG export is unavailable.
 */
internal fun decodeBounded(bytes: ByteArray, maxPixels: Int): Bitmap? {    val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe)
    if (probe.outWidth <= 0 || probe.outHeight <= 0) {
        return decodeRawBounded(bytes, maxPixels)
    }

    val (targetWidth, targetHeight) =
        boundedDecodeDimensions(probe.outWidth, probe.outHeight, maxPixels)
    var sample = 1
    while (
        probe.outWidth / (sample * 2) >= targetWidth &&
        probe.outHeight / (sample * 2) >= targetHeight
    ) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
        // The develop math and LUT coordinates are sRGB. Decode embedded profiles into that
        // space explicitly so Display-P3 DNG previews are not interpreted as sRGB numbers.
        inPreferredColorSpace =
            android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
    }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        ?: return decodeRawBounded(bytes, maxPixels)
    if (decoded.width == targetWidth && decoded.height == targetHeight) return decoded
    return Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
        .also { scaled -> if (scaled !== decoded) decoded.recycle() }
}

/** Aspect-preserving decode target that never exceeds [maxPixels]. */
internal fun boundedDecodeDimensions(width: Int, height: Int, maxPixels: Int): Pair<Int, Int> {
    require(width > 0 && height > 0) { "Image dimensions must be positive" }
    require(maxPixels > 0) { "Pixel ceiling must be positive" }
    val sourcePixels = width.toLong() * height.toLong()
    if (sourcePixels <= maxPixels.toLong()) return width to height

    val scale = kotlin.math.sqrt(maxPixels.toDouble() / sourcePixels.toDouble())
    val targetWidth = (width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (height * scale).toInt().coerceAtLeast(1)
    check(targetWidth.toLong() * targetHeight <= maxPixels.toLong())
    return targetWidth to targetHeight
}

/**
 * Render RAW/DNG [bytes] through platform [android.graphics.ImageDecoder] (API 28+),
 * downsampled so the result is at most [maxPixels] and forced to software ARGB_8888 (the develop
 * engine reads pixels off the CPU). Returns null on API < 28 or if the platform can't render the
 * DNG — the caller then reports the edited-save failure. Never throws.
 */
private fun decodeRawBounded(bytes: ByteArray, maxPixels: Int): Bitmap? {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return null
    return runCatching {
        val src = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
        android.graphics.ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            // Force CPU-readable software pixels (HARDWARE bitmaps can't be getPixels'd).
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            decoder.setTargetColorSpace(
                android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB),
            )
            val w = info.size.width
            val h = info.size.height
            if (w > 0 && h > 0) {
                val (targetWidth, targetHeight) = boundedDecodeDimensions(w, h, maxPixels)
                if (targetWidth != w || targetHeight != h) {
                    decoder.setTargetSize(targetWidth, targetHeight)
                }
            }
        }.let { bmp ->
            // Ensure ARGB_8888 for the engine's getPixels/setPixels.
            if (bmp.config == Bitmap.Config.ARGB_8888) bmp
            else bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() }
        }
    }.getOrNull()
}

/**
 * A nearly neutral base grade applied to Android's display-RGB **DNG rendition** before the film
 * look. This is not a scene-linear RAW stage; it only aligns a sometimes flatter platform output
 * with the camera-JPEG-like base used to author the looks. See [DevelopPipeline.PreGrade].
 */
private val RawPreGrade = com.ricohgr3.app.looks.emulation.DevelopPipeline.PreGrade(
    // ImageDecoder's DNG output is already a display rendering on current Android devices.
    // Keep this nearly neutral and let scene analysis do the bounded tonal work.
    contrast = 0.05f, saturation = 1.02f,
)

/** Composite the film stock's indicative vertical-gradient tint onto a copy of [src]. */
private fun applyLookTint(src: Bitmap, filmLookId: String?, effectStrength: Float = 1f): Bitmap {
    val stops = LookSwatch.stopsFor(filmLookId)
    val out = src.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(out)
    val paint = Paint().apply {
        // Same alphas as the on-screen PhotoStage overlay (top 0.16, bottom 0.24).
        val strength = effectStrength.coerceIn(0f, 1.5f)
        shader = LinearGradient(
            0f, 0f, 0f, out.height.toFloat(),
            stops.top.copy(alpha = (0.16f * strength).coerceAtMost(0.36f)).toArgb(),
            stops.bottom.copy(alpha = (0.24f * strength).coerceAtMost(0.36f)).toArgb(),
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), paint)
    if (out !== src) src.recycle()
    return out
}

/** `R0000123.JPG` -> `R0000123_edit.jpg` so the original file isn't overwritten. */
private fun editedName(file: String): String {
    val dot = file.lastIndexOf('.')
    return if (dot > 0) file.substring(0, dot) + "_edit.jpg" else file + "_edit.jpg"
}

private fun mimeTypeFor(file: String): String =
    when (file.substringAfterLast('.', "").lowercase()) {
        "dng" -> "image/x-adobe-dng"
        "png" -> "image/png"
        else -> "image/jpeg"
    }
