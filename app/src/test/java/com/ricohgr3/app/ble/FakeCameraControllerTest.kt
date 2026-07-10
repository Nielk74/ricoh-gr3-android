package com.ricohgr3.app.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * State-transition tests exercising the [CameraController] contract through the
 * in-memory [FakeCameraController]: scanning -> device discovered -> connected ->
 * shutterCount increments. No Android BLE radio involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeCameraControllerTest {

    @Test
    fun `startScan discovers the fake camera`() = runTest {
        val controller = FakeCameraController()
        assertTrue(controller.state.value.devices.isEmpty())

        controller.startScan()

        val s = controller.state.value
        assertTrue(s.isScanning)
        assertEquals(listOf(controller.fakeCamera), s.devices)
    }

    @Test
    fun `connect walks to CONNECTED and populates device info`() = runTest {
        val controller = FakeCameraController()
        controller.startScan()

        controller.connect(controller.fakeCamera.address)

        val s = controller.state.value
        assertFalse(s.isScanning)
        assertEquals(ConnectionState.CONNECTED, s.connectionState)
        assertTrue(s.shutterAvailable)
        assertEquals("GR III", s.deviceInfo?.model)
    }

    @Test
    fun `fireShutter increments shutterCount when connected`() = runTest {
        val controller = FakeCameraController()
        controller.startScan()
        controller.connect(controller.fakeCamera.address)
        assertEquals(0, controller.state.value.shutterCount)

        controller.fireShutter(af = true)
        controller.fireShutter(af = false)

        val s = controller.state.value
        assertEquals(2, s.shutterCount)
        assertEquals(true, s.lastShutterOk)
    }

    @Test
    fun `fireShutter is a no-op when not connected`() = runTest {
        val controller = FakeCameraController()

        controller.fireShutter(af = true)

        assertEquals(0, controller.state.value.shutterCount)
        assertNull(controller.state.value.lastShutterOk)
    }

    @Test
    fun `full lifecycle scan connect shoot disconnect`() = runTest {
        val controller = FakeCameraController()

        controller.startScan()
        assertEquals(1, controller.state.value.devices.size)

        controller.connect(controller.state.value.devices.first().address)
        assertEquals(ConnectionState.CONNECTED, controller.state.value.connectionState)

        controller.fireShutter()
        assertEquals(1, controller.state.value.shutterCount)

        controller.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, controller.state.value.connectionState)
        assertNull(controller.state.value.deviceInfo)

        controller.close()
        assertTrue(controller.closed)
    }
}
