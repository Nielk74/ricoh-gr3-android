package com.ricohgr3.app.wifi

import android.net.Network
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for [SessionBoundWifiController] — the delegate that routes camera calls to whatever
 * controller the [CameraWifiSession] currently exposes. This is the integration seam that a prior
 * version got wrong (the screens held an *unbound* client while the session's network-bound one
 * went unused); these tests pin the wiring so it can't silently regress.
 */
class SessionBoundWifiControllerTest {

    /** Records that it was the one invoked, so we can prove the delegate forwarded to it. */
    private class MarkerController(private val marker: String) : CameraWifiController {
        override suspend fun ping(): CameraTime = CameraTime(datetime = marker)
        override suspend fun props(): CameraProps = CameraProps(errCode = 200, model = marker)
        override suspend fun listPhotos(storage: String?, limit: Int?, after: String?) =
            PhotoList(errCode = 200)
        override suspend fun photoInfo(folder: String, file: String, storage: String?) =
            PhotoInfo(errCode = 200, file = marker)
        override suspend fun downloadPhoto(folder: String, file: String, size: ImageSize, storage: String?) =
            marker.toByteArray()
        override suspend fun setCameraParams(params: CaptureParams) = CameraProps(errCode = 200)
        override suspend fun shoot() = ShootResult(errCode = 200, captured = true)
        override fun liveview() = kotlinx.coroutines.flow.flowOf(marker.toByteArray())
    }

    private class FakeJoiner : WifiApJoiner {
        var listener: WifiApConnector.Listener? = null
        override fun connect(ssid: String, passphrase: String, listener: WifiApConnector.Listener) {
            this.listener = listener
        }
        override fun disconnect() {}
    }

    private fun stubNetwork(): Network {
        val ctor = Network::class.java.getDeclaredConstructor()
        ctor.isAccessible = true
        return ctor.newInstance()
    }

    @Test
    fun `forwards to the session's live controller when connected`() = runTest {
        val joiner = FakeJoiner()
        val marker = MarkerController("bound-A")
        val session = CameraWifiSession(joiner = joiner, controllerFactory = { marker })
        val delegate = SessionBoundWifiController(session)

        session.connect("GR_X", "pw")
        joiner.listener!!.onAvailable(stubNetwork())

        assertEquals("bound-A", delegate.ping().datetime)
        assertEquals("bound-A", delegate.props().model)
        assertEquals("bound-A", delegate.liveview().toList().single().toString(Charsets.UTF_8))
    }

    @Test
    fun `picks up a new bound controller after a reconnect`() = runTest {
        val joiner = FakeJoiner()
        var next = MarkerController("first")
        val session = CameraWifiSession(joiner = joiner, controllerFactory = { next })
        val delegate = SessionBoundWifiController(session)

        session.connect("GR_X", "pw")
        joiner.listener!!.onAvailable(stubNetwork())
        assertEquals("first", delegate.props().model)

        // Reconnect: the factory now yields a different (freshly network-bound) controller.
        next = MarkerController("second")
        session.connect("GR_X", "pw")
        joiner.listener!!.onAvailable(stubNetwork())
        assertEquals("second", delegate.props().model)
    }

    @Test
    fun `fails fast with NotConnectedException when no session is connected`() = runTest {
        val session = CameraWifiSession(joiner = FakeJoiner(), controllerFactory = { MarkerController("x") })
        val delegate = SessionBoundWifiController(session)

        try {
            delegate.props()
            fail("expected NotConnectedException")
        } catch (e: SessionBoundWifiController.NotConnectedException) {
            // expected
        }
    }

    @Test
    fun `liveview yields empty flow when disconnected`() = runTest {
        val session = CameraWifiSession(joiner = FakeJoiner(), controllerFactory = { MarkerController("x") })
        val delegate = SessionBoundWifiController(session)

        assertTrue(delegate.liveview().toList().isEmpty())
    }

    @Test
    fun `stops forwarding after the session drops the connection`() = runTest {
        val joiner = FakeJoiner()
        val session = CameraWifiSession(joiner = joiner, controllerFactory = { MarkerController("live") })
        val delegate = SessionBoundWifiController(session)

        session.connect("GR_X", "pw")
        joiner.listener!!.onAvailable(stubNetwork())
        assertEquals("live", delegate.props().model)

        joiner.listener!!.onLost() // AP dropped -> State.Lost, controller no longer available
        try {
            delegate.props()
            fail("expected NotConnectedException after drop")
        } catch (e: SessionBoundWifiController.NotConnectedException) {
            // expected
        }
    }
}
