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
import com.ricohgr3.app.looks.CameraLook
import com.ricohgr3.app.looks.emulation.CameraLookMapping
import com.ricohgr3.app.looks.emulation.DevelopEngine
import com.ricohgr3.app.looks.emulation.FilmLookLoader
import com.ricohgr3.app.ui.LookSwatch
import com.ricohgr3.app.wifi.ImageSize

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
 * When the look maps to a client-side film emulation ([CameraLookMapping]) and a [loader] is
 * provided, the frame is genuinely **developed** through the film engine ([DevelopEngine]:
 * LUT + split-tone + halation + grain). Otherwise it falls back to the honest indicative
 * gradient tint that mirrors [PhotoStage]'s on-screen overlay. RAW/DNG originals can't be
 * decoded into a Bitmap, so they fall back to saving the original.
 */
suspend fun saveEdited(
    id: PhotoId,
    look: CameraLook,
    repository: PhotoRepository,
    exporter: PhotoExporter,
    loader: FilmLookLoader? = null,
): SaveOutcome {
    // Nothing to bake for Standard, or for RAW that we can't decode into a Bitmap.
    if (look == CameraLook.STANDARD || id.file.substringAfterLast('.', "").equals("DNG", true)) {
        return saveOriginal(id, repository, exporter)
    }
    val bytes = fetchFull(id, repository)
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: throw java.io.IOException("Could not decode ${id.file} for editing")

    // Prefer a real film develop; fall back to the indicative gradient tint.
    // `DevelopEngine.render` leaves `decoded` untouched, so recycle it after; `applyLookTint`
    // already consumes and recycles its source, so we must not recycle again in that path.
    val filmId = loader?.let { CameraLookMapping.filmLookId(look) }
    val developed = filmId?.let { loader.resolve(it) }
        ?.let { (film, lut) -> DevelopEngine.render(decoded, film, lut) }
    val edited = if (developed != null) {
        decoded.recycle()
        developed
    } else {
        applyLookTint(decoded, look)
    }

    val name = editedName(id.file)
    exporter.saveBitmap(edited, name)
    return SaveOutcome(displayName = name, edited = true)
}

private suspend fun fetchFull(id: PhotoId, repository: PhotoRepository): ByteArray =
    when (val r = repository.downloadPhoto(id, size = ImageSize.FULL)) {
        is PhotoResult.Success -> r.value
        is PhotoResult.Error -> throw (r.cause ?: java.io.IOException(r.message))
    }

/** Composite the look's indicative vertical-gradient tint onto a copy of [src]. */
private fun applyLookTint(src: Bitmap, look: CameraLook): Bitmap {
    val stops = LookSwatch.stopsFor(look)
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
