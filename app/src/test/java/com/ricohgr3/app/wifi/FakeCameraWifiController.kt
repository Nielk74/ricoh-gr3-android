package com.ricohgr3.app.wifi

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory [CameraWifiController] test double, mirroring the BLE
 * [com.ricohgr3.app.ble.FakeCameraController] style. Serves canned responses with no OkHttp /
 * network involved, so the [com.ricohgr3.app.data.PhotoRepository] and
 * [com.ricohgr3.app.gallery.GalleryViewModel] are unit-testable on the JVM.
 *
 * Set [failWith] to make every call throw, exercising the error path.
 */
class FakeCameraWifiController(
    private val photoList: PhotoList = defaultPhotoList(),
    private val info: PhotoInfo = defaultPhotoInfo(),
    private val photoBytes: ByteArray = ByteArray(4) { it.toByte() },
    /** When non-null, every call throws this — drives repository/VM error handling. */
    var failWith: Exception? = null,
) : CameraWifiController {

    var listPhotosCount = 0
        private set

    private fun failIfConfigured() {
        failWith?.let { throw it }
    }

    override suspend fun ping(): CameraTime {
        failIfConfigured()
        return CameraTime(datetime = "2026-07-11T12:00:00+09:00")
    }

    override suspend fun props(): CameraProps {
        failIfConfigured()
        return CameraProps(errCode = 200, model = "GR III")
    }

    override suspend fun listPhotos(storage: String?, limit: Int?, after: String?): PhotoList {
        listPhotosCount++
        failIfConfigured()
        return photoList
    }

    override suspend fun photoInfo(folder: String, file: String, storage: String?): PhotoInfo {
        failIfConfigured()
        return info.copy(dir = folder, file = file)
    }

    override suspend fun downloadPhoto(
        folder: String,
        file: String,
        size: ImageSize,
        storage: String?,
    ): ByteArray {
        failIfConfigured()
        return photoBytes
    }

    override suspend fun setCameraParams(params: CaptureParams): CameraProps {
        failIfConfigured()
        return CameraProps(errCode = 200)
    }

    override suspend fun shoot(): ShootResult {
        failIfConfigured()
        return ShootResult(errCode = 200, captureId = 1, captured = true)
    }

    override fun liveview(): Flow<ByteArray> = flowOf(photoBytes)

    companion object {
        fun defaultPhotoList(): PhotoList = PhotoList(
            errCode = 200,
            dirs = listOf(
                PhotoDir(name = "100RICOH", files = listOf("R0000001.JPG", "R0000002.DNG")),
                PhotoDir(name = "101RICOH", files = listOf("R0000101.JPG")),
            ),
        )

        fun defaultPhotoInfo(): PhotoInfo = PhotoInfo(
            errCode = 200,
            cameraModel = "GR III",
            size = 36_000_000L,
            datetime = "2026-07-11T12:00:00+09:00",
        )
    }
}
