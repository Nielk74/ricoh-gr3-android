package com.ricohgr3.app.data

import com.ricohgr3.app.wifi.CameraWifiController
import com.ricohgr3.app.wifi.ImageSize
import com.ricohgr3.app.wifi.PhotoInfo

/**
 * Stable identity for one photo on the camera. The Wi-Fi API addresses photos by a
 * `folder` + `file` pair (there is no opaque server id), so we combine them into one value
 * the repository / ViewModel / UI can treat as a single key (e.g. for selection sets).
 */
data class PhotoId(val folder: String, val file: String) {
    /** `100RICOH/R0000001.JPG` — human-readable, and unique per photo. */
    override fun toString(): String = "$folder/$file"
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
}
