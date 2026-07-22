package com.ricohgr3.app.data

import com.ricohgr3.app.wifi.CameraWifiController
import com.ricohgr3.app.wifi.ImageSize
import com.ricohgr3.app.wifi.PhotoInfo
import kotlinx.coroutines.CancellationException
import java.io.OutputStream

/**
 * Stable identity for one photo on the camera. The Wi-Fi API addresses photos by a
 * `folder` + `file` pair (there is no opaque server id), so we combine them into one value
 * the repository / ViewModel / UI can treat as a single key (e.g. for selection sets).
 */
data class PhotoId(val folder: String, val file: String) {
    /** `100RICOH/R0000001.JPG` — human-readable, and unique per photo. */
    override fun toString(): String = "$folder/$file"

    /**
     * Encode this id into a **single** navigation path segment. The `folder/file` shape contains
     * a `/`, which Navigation-Compose treats as a segment separator — passing it raw makes the
     * `viewer/{photoId}` route fail to match and crashes navigation. We percent-encode the slash
     * (and anything else unsafe) so the whole id survives as one opaque segment.
     *
     * Pure JVM string logic (no `android.net.Uri`) so it round-trips under unit test.
     */
    fun toRouteArg(): String = buildString {
        // NB: capture `this@PhotoId.toString()` outside the builder lambda — inside `buildString`,
        // a bare `toString()` resolves to the StringBuilder's (empty) toString, not the id's.
        for (b in "$folder/$file".encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            // RFC 3986 unreserved set — everything else is percent-escaped.
            if (c.toChar().isUnreservedRouteChar()) append(c.toChar())
            else append('%').append(HEX[c ushr 4]).append(HEX[c and 0x0F])
        }
    }

    companion object {
        private val HEX = "0123456789ABCDEF".toCharArray()

        private fun Char.isUnreservedRouteChar(): Boolean =
            this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' ||
                this == '-' || this == '_' || this == '.' || this == '~'

        /**
         * Inverse of [toRouteArg]. Returns null for a malformed or empty argument (e.g. a bad
         * percent-escape, or a value with no `folder/file` split) so the caller can show an error
         * screen instead of crashing.
         */
        fun fromRouteArg(raw: String): PhotoId? {
            if (raw.isEmpty()) return null
            val decoded = decodePercent(raw) ?: return null
            val slash = decoded.indexOf('/')
            if (slash <= 0 || slash == decoded.length - 1) return null
            return PhotoId(folder = decoded.substring(0, slash), file = decoded.substring(slash + 1))
        }

        private fun decodePercent(raw: String): String? {
            val out = java.io.ByteArrayOutputStream(raw.length)
            var i = 0
            while (i < raw.length) {
                when (val ch = raw[i]) {
                    '%' -> {
                        if (i + 2 >= raw.length) return null
                        val hi = Character.digit(raw[i + 1], 16)
                        val lo = Character.digit(raw[i + 2], 16)
                        if (hi < 0 || lo < 0) return null
                        out.write((hi shl 4) or lo)
                        i += 3
                    }
                    else -> {
                        out.write(ch.code)
                        i++
                    }
                }
            }
            return out.toByteArray().decodeToString()
        }
    }
}

/**
 * A single photo entry in the gallery: its [id] plus the [ImageSize.THUMB] hint the UI can
 * later use to fetch a thumbnail. Kept deliberately thin — richer metadata is fetched
 * on-demand via [PhotoRepository.photoInfo].
 */
data class PhotoItem(val id: PhotoId) {
    val folder: String get() = id.folder
    val file: String get() = id.file
    /** True for RAW/DNG originals (no in-camera downscaled rendition). */
    val isRaw: Boolean get() = file.substringAfterLast('.', "").equals("DNG", ignoreCase = true)
}

/**
 * Clean result wrapper isolating the ViewModel from transport exceptions. Every repository
 * call returns one of these instead of throwing, so callers branch on the result rather than
 * wrapping each call in try/catch.
 */
sealed interface PhotoResult<out T> {
    data class Success<T>(val value: T) : PhotoResult<T>
    data class Error(val message: String, val cause: Throwable? = null) : PhotoResult<Nothing>

    companion object {
        /** Run [block], mapping any thrown exception to [Error]. */
        inline fun <T> runCatchingResult(block: () -> T): PhotoResult<T> =
            try {
                Success(block())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Error(e.message ?: e::class.simpleName ?: "Unknown error", e)
            }
    }
}

/**
 * Data layer over [CameraWifiController] for browsing the camera's stored photos.
 *
 * Flattens the API's folders-of-files [com.ricohgr3.app.wifi.PhotoList] shape into a flat
 * [PhotoItem] list keyed by [PhotoId], and maps transport errors to [PhotoResult]. Holds no
 * mutable state itself — state lives in the ViewModel — so it is safe to share.
 *
 * All calls are `suspend` and delegate straight to the (background-dispatched) controller.
 */
class PhotoRepository(
    private val controller: CameraWifiController,
) {

    /**
     * `GET /v1/photos` → flat list of every photo across all folders on [storage].
     * The API returns newest-folder-last / newest-file-last; we preserve that order.
     */
    suspend fun loadPhotos(storage: String? = null): PhotoResult<List<PhotoItem>> =
        PhotoResult.runCatchingResult {
            val list = controller.listPhotos(storage = storage)
            list.dirs.flatMap { dir ->
                val folder = dir.name ?: return@flatMap emptyList()
                dir.files.map { file -> PhotoItem(PhotoId(folder, file)) }
            }
        }

    /** `GET /v1/photos/{folder}/{file}/info` — metadata for one photo. */
    suspend fun photoInfo(id: PhotoId, storage: String? = null): PhotoResult<PhotoInfo> =
        PhotoResult.runCatchingResult {
            controller.photoInfo(folder = id.folder, file = id.file, storage = storage)
        }

    /**
     * `GET /v1/photos/{folder}/{file}?size=thumb` — raw thumbnail bytes (~30 KiB, 160x120).
     * Decoding to a Bitmap is intentionally left to the Android UI layer.
     */
    suspend fun downloadThumbnail(id: PhotoId, storage: String? = null): PhotoResult<ByteArray> =
        downloadPhoto(id, size = ImageSize.THUMB, storage = storage)

    /**
     * `GET /v1/photos/{folder}/{file}` — full (or [size]-downscaled) photo bytes.
     * WARNING: full-resolution originals are large; see [CameraWifiController.downloadPhoto].
     */
    suspend fun downloadPhoto(
        id: PhotoId,
        size: ImageSize = ImageSize.FULL,
        storage: String? = null,
    ): PhotoResult<ByteArray> =
        PhotoResult.runCatchingResult {
            controller.downloadPhoto(folder = id.folder, file = id.file, size = size, storage = storage)
        }

    /** Full download with transport-level byte progress for long-running saves/imports. */
    suspend fun downloadPhotoWithProgress(
        id: PhotoId,
        size: ImageSize = ImageSize.FULL,
        storage: String? = null,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
    ): PhotoResult<ByteArray> =
        PhotoResult.runCatchingResult {
            controller.downloadPhotoWithProgress(
                folder = id.folder,
                file = id.file,
                size = size,
                storage = storage,
                onProgress = onProgress,
            )
        }

    /** Stream a full camera response directly into a disk spool. */
    suspend fun downloadPhotoTo(
        id: PhotoId,
        destination: OutputStream,
        size: ImageSize = ImageSize.FULL,
        storage: String? = null,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
    ): PhotoResult<Long> = PhotoResult.runCatchingResult {
        controller.downloadPhotoTo(
            folder = id.folder,
            file = id.file,
            destination = destination,
            size = size,
            storage = storage,
            onProgress = onProgress,
        )
    }
}
