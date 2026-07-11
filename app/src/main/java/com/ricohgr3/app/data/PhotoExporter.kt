package com.ricohgr3.app.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

/**
 * Persists downloaded / edited photos into the device's shared gallery (`Pictures/GR3`).
 *
 * Uses [MediaStore] so it works under scoped storage on API 29+ with no runtime permission,
 * and falls back to the legacy public-directory insert (covered by the
 * `WRITE_EXTERNAL_STORAGE` manifest permission, `maxSdkVersion=28`) on API 26-28.
 *
 * All writes run on [Dispatchers.IO]. Failures throw [IOException] so callers can surface a
 * real error to the UI instead of a silent no-op — the previous behaviour (no save path at
 * all) meant downloads appeared to do nothing.
 */
class PhotoExporter(private val context: Context) {

    /** Subfolder under Pictures/ where saved frames land, so they group in the gallery. */
    private val relativeDir = "${Environment.DIRECTORY_PICTURES}/GR3"

    /**
     * Write raw image [bytes] (an original JPEG/DNG straight from the camera) to the gallery
     * under [displayName]. [mimeType] should match the extension (e.g. `image/jpeg`,
     * `image/x-adobe-dng`). Returns the inserted content [Uri].
     */
    suspend fun saveBytes(
        bytes: ByteArray,
        displayName: String,
        mimeType: String,
    ): Uri = withContext(Dispatchers.IO) {
        insert(displayName, mimeType) { out -> out.write(bytes) }
    }

    /**
     * Compress [bitmap] to JPEG and write it to the gallery under [displayName]. Used for the
     * edited-export path (the look-tinted rendition). Returns the inserted content [Uri].
     */
    suspend fun saveBitmap(
        bitmap: Bitmap,
        displayName: String,
        quality: Int = 95,
    ): Uri = withContext(Dispatchers.IO) {
        insert(displayName, "image/jpeg") { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                throw IOException("Failed to encode JPEG for $displayName")
            }
        }
    }

    /**
     * Core MediaStore insert. Creates a pending image row, streams [write] into it, then clears
     * the pending flag (API 29+) so the picture becomes visible atomically. On failure the row
     * is deleted so no empty/half-written entry is left behind.
     */
    private fun insert(displayName: String, mimeType: String, write: (OutputStream) -> Unit): Uri {
        val resolver = context.contentResolver
        val collection =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, relativeDir)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore insert returned null for $displayName")

        try {
            resolver.openOutputStream(uri)?.use(write)
                ?: throw IOException("Could not open output stream for $displayName")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            return uri
        } catch (e: Exception) {
            // Roll back the pending/empty row so a failed save leaves nothing behind.
            runCatching { resolver.delete(uri, null, null) }
            if (e is IOException) throw e
            throw IOException("Failed to save $displayName: ${e.message}", e)
        }
    }
}
