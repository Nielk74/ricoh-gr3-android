package com.ricohgr3.app.wifi

import kotlinx.coroutines.flow.Flow
import java.io.OutputStream

/**
 * Transport-agnostic surface for the camera's Wi-Fi HTTP `/v1/` API, mirroring the house
 * style of the BLE [com.ricohgr3.app.ble.CameraController] interface so it can be faked in
 * tests and swapped in the ViewModel.
 *
 * [CameraHttpClient] is the OkHttp-backed implementation talking to `http://192.168.0.1/v1/`.
 * All calls are `suspend` and expected to be invoked on a background dispatcher; the camera
 * AP has no internet, so callers must first join it via
 * [com.ricohgr3.app.wifi.WifiApConnector].
 */
interface CameraWifiController {

    /** `GET /v1/ping` — connectivity check. Returns the camera clock on success. */
    suspend fun ping(): CameraTime

    /** `GET /v1/props` — full property blob (model, battery, current exposure, lists, SSID…). */
    suspend fun props(): CameraProps

    /**
     * `GET /v1/photos` — list folders + files.
     * @param storage optional memory slot ("in", "sd1", "sd2"); null = camera default.
     * @param limit optional max number of entries.
     * @param after optional ISO-8601 datetime to page after.
     */
    suspend fun listPhotos(
        storage: String? = null,
        limit: Int? = null,
        after: String? = null,
    ): PhotoList

    /** `GET /v1/photos/{folder}/{file}/info` — metadata for one photo. */
    suspend fun photoInfo(folder: String, file: String, storage: String? = null): PhotoInfo

    /**
     * `GET /v1/photos/{folder}/{file}` — download a photo's bytes (JPEG or RAW/DNG,
     * determined by the file extension). Optionally request a downscaled rendition via [size]
     * (ignored by the camera for DNG originals). Returns the raw response body bytes.
     *
     * WARNING: full-resolution files are large (~36 MiB JPEG, ~40+ MiB DNG). This returns a
     * `ByteArray`; batch callers must bound how many responses can be resident at once.
     */
    suspend fun downloadPhoto(
        folder: String,
        file: String,
        size: ImageSize = ImageSize.FULL,
        storage: String? = null,
    ): ByteArray

    /**
     * Download a photo while reporting response-body progress. Implementations that can stream the
     * body should report incremental updates; the default keeps test doubles and alternative
     * controllers source-compatible and reports one final update.
     *
     * [totalBytes] is null when the server did not provide a usable Content-Length.
     */
    suspend fun downloadPhotoWithProgress(
        folder: String,
        file: String,
        size: ImageSize = ImageSize.FULL,
        storage: String? = null,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
    ): ByteArray = downloadPhoto(folder, file, size, storage).also { bytes ->
        onProgress(bytes.size.toLong(), bytes.size.toLong())
    }

    /**
     * Stream a photo into [destination] without retaining the complete response in the app heap.
     * The default keeps fakes/source-compatible implementations simple; the OkHttp production
     * client overrides it with a true response-body stream.
     *
     * @return the exact number of bytes written.
     */
    suspend fun downloadPhotoTo(
        folder: String,
        file: String,
        destination: OutputStream,
        size: ImageSize = ImageSize.FULL,
        storage: String? = null,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
    ): Long {
        val bytes = downloadPhoto(folder, file, size, storage)
        destination.write(bytes)
        onProgress(bytes.size.toLong(), bytes.size.toLong())
        return bytes.size.toLong()
    }

    /**
     * `PUT /v1/params/camera` — set capture parameters (ISO / shutter / aperture / effect …).
     * Only the non-null fields of [params] are sent. Returns the updated properties.
     */
    suspend fun setCameraParams(params: CaptureParams): CameraProps

    /** `POST /v1/camera/shoot` — trigger a single capture. */
    suspend fun shoot(): ShootResult

    /**
     * `GET /v1/liveview` — the MJPEG (`multipart/x-mixed-replace`) live-view stream, emitted
     * as one [ByteArray] per complete JPEG frame. The flow runs until cancelled or the stream
     * closes. Collect on a background dispatcher.
     */
    fun liveview(): Flow<ByteArray>
}
