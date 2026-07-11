package com.ricohgr3.app.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.graphics.toArgb
import com.ricohgr3.app.data.PhotoExporter
import com.ricohgr3.app.data.PhotoId
import com.ricohgr3.app.data.PhotoRepository
import com.ricohgr3.app.data.PhotoResult
import com.ricohgr3.app.looks.emulation.DevelopEngine
import com.ricohgr3.app.looks.emulation.FilmLookLoader
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

/** Result of a save: the display name it landed under in the gallery. */
data class SaveOutcome(val displayName: String, val edited: Boolean)

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
 * Download the full-resolution original, composite the applied [look]'s indicative tint over it
 * (matching the on-screen preview), and save the result as a new JPEG so the original is kept.
 *
 * [filmLookId] is a [com.ricohgr3.app.looks.emulation.FilmLookCatalog] stock id (or `null` for
 * Standard). When a [loader] is provided the frame is genuinely **developed** through the film
 * engine ([DevelopEngine]: LUT + split-tone + halation + grain). Without a loader (the JVM path)
 * it falls back to the honest indicative gradient tint that mirrors [PhotoStage]'s overlay.
 *
 * **DNG (RAW) originals** are decoded via the platform DNG decoder ([decodeBounded]) and
 * developed with a RAW-tuned variant of the pipeline (RAW previews come out flatter/lower
 * contrast than the camera JPEG, so a mild base grade is applied first — see [developForSave]).
 * The result is **always saved as JPEG**, because a developed rendition is a finished image, not
 * raw sensor data — keeping a `.dng` extension on baked pixels would be misleading and most
 * viewers wouldn't show it. The untouched original DNG is left on the camera.
 *
 * If a DNG cannot be decoded on this device (some sensors/firmware produce DNGs the platform
 * decoder rejects), the original is saved untouched rather than failing the whole action.
 */
suspend fun saveEdited(
    id: PhotoId,
    filmLookId: String?,
    repository: PhotoRepository,
    exporter: PhotoExporter,
    loader: FilmLookLoader? = null,
): SaveOutcome {
    // Standard (null) is the as-shot baseline — nothing to bake; keep the pristine original.
    if (filmLookId == null) {
        return saveOriginal(id, repository, exporter)
    }
    val isRaw = id.file.substringAfterLast('.', "").equals("DNG", true)
    val bytes = fetchFull(id, repository)

    // The develop runs on the CPU and allocates several full-resolution float buffers per
    // channel, so a naive 24MP decode blows the heap (OutOfMemoryError). Decode downsampled to
    // a bounded working resolution, and do all pixel work off the main thread.
    val edited = withContext(Dispatchers.Default) {
        val decoded = decodeBounded(bytes, MAX_EDIT_PIXELS)
        if (decoded == null) {
            // Undecodable DNG on this device → fall back to keeping the raw original rather than
            // crashing the action. A truly corrupt JPEG is a real error the caller should show.
            if (isRaw) return@withContext null
            throw java.io.IOException("Could not decode ${id.file} for editing")
        }

        // Develop the selected film stock directly. `developForSave` leaves `decoded` untouched,
        // so recycle it after; `applyLookTint` consumes and recycles its source, so we must not
        // recycle again in that path.
        val developed = loader?.resolve(filmLookId)
            ?.let { (film, lut) -> developForSave(decoded, film, lut, raw = isRaw, grain = loader.grainTexture()) }
        if (developed != null) {
            decoded.recycle()
            developed
        } else {
            // No loader (JVM path) or unknown stock → honest indicative gradient tint.
            applyLookTint(decoded, filmLookId)
        }
    } ?: return saveOriginal(id, repository, exporter)

    // DNG develops are finished renditions → always JPEG (see kdoc). JPEG originals keep .jpg.
    val name = editedName(id.file)
    exporter.saveBitmap(edited, name)
    return SaveOutcome(displayName = name, edited = true)
}

/**
 * Develop [decoded] through the film [look]/[lut]. For [raw] DNG input, apply a mild base grade
 * first ([RawPreGrade]) so the flat, low-contrast RAW preview matches the tonal starting point
 * the film models were tuned against (a camera-JPEG-like base) before the look is layered on.
 * Leaves [decoded] untouched; returns a new bitmap.
 */
private fun developForSave(
    decoded: android.graphics.Bitmap,
    look: com.ricohgr3.app.looks.emulation.FilmLook,
    lut: com.ricohgr3.app.looks.emulation.LutCube,
    raw: Boolean,
    grain: com.ricohgr3.app.looks.emulation.GrainTexture?,
): android.graphics.Bitmap =
    DevelopEngine.render(decoded, look, lut, preGrade = if (raw) RawPreGrade else null, grainTexture = grain)

private suspend fun fetchFull(id: PhotoId, repository: PhotoRepository): ByteArray =
    when (val r = repository.downloadPhoto(id, size = ImageSize.FULL)) {
        is PhotoResult.Success -> r.value
        is PhotoResult.Error -> throw (r.cause ?: java.io.IOException(r.message))
    }

/**
 * Ceiling on the working resolution for an on-device develop. The GR III shoots ~24MP; the
 * CPU pipeline holds several full-res float buffers at once, so we cap the edit buffer to keep
 * peak heap bounded and avoid `OutOfMemoryError`. ~6MP (e.g. 3000×2000) is far above what an
 * exported look needs to read as film. Kept generous but safe on low-RAM devices.
 */
private const val MAX_EDIT_PIXELS = 6_000_000

/**
 * Decode [bytes] to an ARGB_8888 bitmap whose pixel count is at most [maxPixels], using
 * `inSampleSize` so the full image is never fully materialised in memory. Returns null if the
 * bytes aren't a decodable image.
 *
 * JPEG/PNG go through [BitmapFactory]. **DNG** is not decodable by [BitmapFactory] on most
 * devices, so it falls through to [decodeRawBounded], which uses the platform [ImageDecoder]
 * DNG path (API 28+). On API 26-27, or if the platform can't decode the DNG, this returns null
 * and the caller keeps the untouched original.
 */
private fun decodeBounded(bytes: ByteArray, maxPixels: Int): Bitmap? {
    val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe)
    if (probe.outWidth <= 0 || probe.outHeight <= 0) {
        return decodeRawBounded(bytes, maxPixels)
    }

    var sample = 1
    while ((probe.outWidth / sample).toLong() * (probe.outHeight / sample) > maxPixels) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: decodeRawBounded(bytes, maxPixels)
}

/**
 * Decode a RAW/DNG [bytes] via the platform [android.graphics.ImageDecoder] (API 28+),
 * downsampled so the result is at most [maxPixels] and forced to software ARGB_8888 (the develop
 * engine reads pixels off the CPU). Returns null on API < 28 or if the platform can't render the
 * DNG — the caller then keeps the untouched original rather than failing. Never throws.
 */
private fun decodeRawBounded(bytes: ByteArray, maxPixels: Int): Bitmap? {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return null
    return runCatching {
        val src = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
        android.graphics.ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            // Force CPU-readable software pixels (HARDWARE bitmaps can't be getPixels'd).
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val w = info.size.width
            val h = info.size.height
            if (w > 0 && h > 0) {
                var sample = 1
                while ((w / sample).toLong() * (h / sample) > maxPixels) sample *= 2
                if (sample > 1) decoder.setTargetSize(w / sample, h / sample)
            }
        }.let { bmp ->
            // Ensure ARGB_8888 for the engine's getPixels/setPixels.
            if (bmp.config == Bitmap.Config.ARGB_8888) bmp
            else bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() }
        }
    }.getOrNull()
}

/**
 * A mild base grade applied to a decoded **RAW/DNG** frame before the film look, so the flat,
 * low-contrast RAW preview matches the tonal starting point (a camera-JPEG-like base) the film
 * models were tuned against. Contrast around mid-grey plus a small saturation lift; kept gentle
 * so it doesn't fight the look layered on top. See [DevelopPipeline.PreGrade].
 */
private val RawPreGrade = com.ricohgr3.app.looks.emulation.DevelopPipeline.PreGrade(
    contrast = 0.18f, saturation = 1.08f,
)

/** Composite the film stock's indicative vertical-gradient tint onto a copy of [src]. */
private fun applyLookTint(src: Bitmap, filmLookId: String?): Bitmap {
    val stops = LookSwatch.stopsFor(filmLookId)
    val out = src.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(out)
    val paint = Paint().apply {
        // Same alphas as the on-screen PhotoStage overlay (top 0.16, bottom 0.24).
        shader = LinearGradient(
            0f, 0f, 0f, out.height.toFloat(),
            stops.top.copy(alpha = 0.16f).toArgb(),
            stops.bottom.copy(alpha = 0.24f).toArgb(),
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
