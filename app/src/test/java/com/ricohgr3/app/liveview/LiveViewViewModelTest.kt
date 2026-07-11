package com.ricohgr3.app.liveview

import com.ricohgr3.app.looks.CameraLook
import com.ricohgr3.app.wifi.CameraProps
import com.ricohgr3.app.wifi.CameraTime
import com.ricohgr3.app.wifi.CameraWifiController
import com.ricohgr3.app.wifi.CaptureParams
import com.ricohgr3.app.wifi.FakeCameraWifiController
import com.ricohgr3.app.wifi.ImageSize
import com.ricohgr3.app.wifi.PhotoInfo
import com.ricohgr3.app.wifi.PhotoList
import com.ricohgr3.app.wifi.ShootResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [LiveViewViewModel] driven by an in-memory [FakeCameraWifiController] — no
 * Android radio, no network. `viewModelScope` is pinned to a [StandardTestDispatcher] via
 * `Dispatchers.setMain` so the `init` collectors and `shoot()` coroutine run under the test
 * scheduler. Mirrors `GalleryViewModelTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * A [CameraWifiController] whose props carry a known effect + battery + model, with a
     * configurable shoot outcome and a single canned live-view frame. Implemented directly
     * (rather than subclassing the non-open `FakeCameraWifiController`) so the props/shoot
     * paths can be varied per test.
     */
    private class TestController(
        private val props: CameraProps,
        private val shootResult: ShootResult = ShootResult(errCode = 200, captured = true),
        private val frame: ByteArray = byteArrayOf(9, 9, 9),
        var throwOnShoot: Boolean = false,
    ) : CameraWifiController {
        override suspend fun ping(): CameraTime = CameraTime(datetime = "x")
        override suspend fun props(): CameraProps = props
        override suspend fun listPhotos(storage: String?, limit: Int?, after: String?): PhotoList =
            PhotoList(errCode = 200)
        override suspend fun photoInfo(folder: String, file: String, storage: String?): PhotoInfo =
            PhotoInfo(errCode = 200)
        override suspend fun downloadPhoto(
            folder: String,
            file: String,
            size: ImageSize,
            storage: String?,
        ): ByteArray = ByteArray(0)
        override suspend fun setCameraParams(params: CaptureParams): CameraProps = props
        override suspend fun shoot(): ShootResult {
            if (throwOnShoot) throw java.io.IOException("shoot boom")
            return shootResult
        }
        override fun liveview(): Flow<ByteArray> = flowOf(frame)
    }

    @Test
    fun `first live-view frame updates the state and frame counter`() = runTest {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val vm = LiveViewViewModel(FakeCameraWifiController(photoBytes = bytes))

        dispatcher.scheduler.advanceUntilIdle()

        val s = vm.state.value
        assertTrue(s.hasStream)
        assertNotNull(s.frame)
        assertArrayEqual(bytes, s.frame!!)
        assertEquals(1, s.frameCount)
        assertNull(s.error)
    }

    @Test
    fun `props load resolves active look, battery and model`() = runTest {
        val props = CameraProps(
            errCode = 200,
            model = "GR IIIx",
            battery = 87,
            effect = "efc_posiFilm",
        )
        val vm = LiveViewViewModel(TestController(props))

        dispatcher.scheduler.advanceUntilIdle()

        val s = vm.state.value
        assertEquals(CameraLook.POSITIVE_FILM, s.look)
        assertEquals(87, s.battery)
        assertEquals("GR IIIx", s.model)
    }

    @Test
    fun `shoot success flips in-flight then records success`() = runTest {
        val props = CameraProps(errCode = 200, model = "GR III")
        val vm = LiveViewViewModel(TestController(props))
        dispatcher.scheduler.advanceUntilIdle()

        vm.shoot()
        // isShooting flips on synchronously before the coroutine is dispatched.
        assertTrue(vm.state.value.isShooting)

        dispatcher.scheduler.advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.isShooting)
        assertEquals(ShotResult.SUCCESS, s.lastShot)
    }

    @Test
    fun `shoot that throws records failure and clears in-flight`() = runTest {
        val props = CameraProps(errCode = 200)
        val vm = LiveViewViewModel(TestController(props, throwOnShoot = true))
        dispatcher.scheduler.advanceUntilIdle()

        vm.shoot()
        dispatcher.scheduler.advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.isShooting)
        assertEquals(ShotResult.FAILURE, s.lastShot)
    }

    @Test
    fun `shoot with non-captured response records failure`() = runTest {
        val props = CameraProps(errCode = 200)
        val notCaptured = ShootResult(errCode = 503, captured = false)
        val vm = LiveViewViewModel(TestController(props, shootResult = notCaptured))
        dispatcher.scheduler.advanceUntilIdle()

        vm.shoot()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(ShotResult.FAILURE, vm.state.value.lastShot)
    }

    private fun assertArrayEqual(expected: ByteArray, actual: ByteArray) {
        assertTrue("byte arrays differ", expected.contentEquals(actual))
    }
}
