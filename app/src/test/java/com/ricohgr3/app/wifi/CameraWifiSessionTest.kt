package com.ricohgr3.app.wifi

import android.net.Network
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure state-machine tests for [CameraWifiSession]. No Android radio is involved: a
 * [FakeWifiApJoiner] captures the [WifiApConnector.Listener] and lets the test drive
 * onAvailable/onUnavailable/onLost synchronously.
 *
 * The real [android.net.Network]-binding path (bindProcessToNetwork + socketFactory) is
 * on-device-only and is NOT exercised here — see [WifiApConnector] manual test steps. We inject a
 * [FakeCameraWifiController] via `controllerFactory` so no [android.net.Network] method is called.
 */
class CameraWifiSessionTest {

    /** In-memory [WifiApJoiner]: records calls and exposes the captured listener to the test. */
    private class FakeWifiApJoiner : WifiApJoiner {
        var listener: WifiApConnector.Listener? = null
        var connectCount = 0
        var disconnectCount = 0
        var lastSsid: String? = null
        var lastPassphrase: String? = null

        override fun connect(ssid: String, passphrase: String, listener: WifiApConnector.Listener) {
            connectCount++
            lastSsid = ssid
            lastPassphrase = passphrase
            this.listener = listener
        }

        override fun disconnect() {
            disconnectCount++
        }
    }

    /**
     * A [Network] instance for tests. `android.net.Network` has no public constructor and its
     * methods throw "not mocked" under unit tests, but the session only passes it through to the
     * (faked) controllerFactory, so allocating via the stub android.jar's package-private no-arg
     * ctor is enough — no methods are ever invoked on it. [tag] is unused (distinct objects are
     * distinguished by identity), kept for call-site readability.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun stubNetwork(tag: Int = 42): Network {
        val ctor = Network::class.java.getDeclaredConstructor()
        ctor.isAccessible = true
        return ctor.newInstance()
    }

    private val fakeController = FakeCameraWifiController()

    private fun newSession(joiner: WifiApJoiner) =
        CameraWifiSession(joiner = joiner, controllerFactory = { fakeController })

    @Test
    fun startsIdle() {
        val session = newSession(FakeWifiApJoiner())
        assertTrue(session.state.value is CameraWifiSession.State.Idle)
        assertNull(session.controller)
    }

    @Test
    fun connectMovesToJoiningAndForwardsCredentials() {
        val joiner = FakeWifiApJoiner()
        val session = newSession(joiner)

        session.connect("GR_A1B2C3", "secretpass")

        assertTrue(session.state.value is CameraWifiSession.State.Joining)
        assertEquals(1, joiner.connectCount)
        assertEquals("GR_A1B2C3", joiner.lastSsid)
        assertEquals("secretpass", joiner.lastPassphrase)
    }

    @Test
    fun onAvailableMovesToConnectedWithBoundController() {
        val joiner = FakeWifiApJoiner()
        val session = newSession(joiner)
        session.connect("GR", "pw")
        val network = stubNetwork()

        joiner.listener!!.onAvailable(network)

        val state = session.state.value
        assertTrue(state is CameraWifiSession.State.Connected)
        state as CameraWifiSession.State.Connected
        assertSame(network, state.network)
        assertSame(fakeController, state.controller)
        assertSame(fakeController, session.controller)
    }

    @Test
    fun onUnavailableMovesToFailed() {
        val joiner = FakeWifiApJoiner()
        val session = newSession(joiner)
        session.connect("GR", "pw")

        joiner.listener!!.onUnavailable()

        assertTrue(session.state.value is CameraWifiSession.State.Failed)
        assertNull(session.controller)
    }

    @Test
    fun onLostAfterConnectedMovesToLost() {
        val joiner = FakeWifiApJoiner()
        val session = newSession(joiner)
        session.connect("GR", "pw")
        joiner.listener!!.onAvailable(stubNetwork())

        joiner.listener!!.onLost()

        assertTrue(session.state.value is CameraWifiSession.State.Lost)
        assertNull(session.controller)
    }

    @Test
    fun disconnectReturnsToIdleAndReleasesAp() {
        val joiner = FakeWifiApJoiner()
        val session = newSession(joiner)
        session.connect("GR", "pw")
        joiner.listener!!.onAvailable(stubNetwork())

        session.disconnect()

        assertTrue(session.state.value is CameraWifiSession.State.Idle)
        assertEquals(1, joiner.disconnectCount)
        assertNull(session.controller)
    }

    @Test
    fun fullHappyPathIdleJoiningConnectedLostIdle() {
        val joiner = FakeWifiApJoiner()
        val session = newSession(joiner)
        val seen = mutableListOf<String>()

        fun record() = seen.add(session.state.value::class.simpleName!!)

        record() // Idle
        session.connect("GR", "pw"); record() // Joining
        joiner.listener!!.onAvailable(stubNetwork()); record() // Connected
        joiner.listener!!.onLost(); record() // Lost
        session.disconnect(); record() // Idle

        assertEquals(listOf("Idle", "Joining", "Connected", "Lost", "Idle"), seen)
    }

    @Test
    fun staleCallbackAfterDisconnectIsIgnored() {
        val joiner = FakeWifiApJoiner()
        val session = newSession(joiner)
        session.connect("GR", "pw")
        val staleListener = joiner.listener!!
        joiner.listener!!.onAvailable(stubNetwork())

        session.disconnect() // bumps generation, back to Idle

        // A late onLost from the superseded request must not resurrect Lost state.
        staleListener.onLost()
        assertTrue(session.state.value is CameraWifiSession.State.Idle)

        // A late onAvailable likewise ignored.
        staleListener.onAvailable(stubNetwork(99))
        assertTrue(session.state.value is CameraWifiSession.State.Idle)
    }

    @Test
    fun reconnectSupersedesPreviousRequest() {
        val joiner = FakeWifiApJoiner()
        val session = newSession(joiner)
        session.connect("GR", "pw")
        val firstListener = joiner.listener!!

        // Second connect before the first resolves.
        session.connect("GR2", "pw2")
        assertEquals(2, joiner.connectCount)
        assertTrue(session.state.value is CameraWifiSession.State.Joining)

        // First request's late callback is dropped.
        firstListener.onUnavailable()
        assertTrue(session.state.value is CameraWifiSession.State.Joining)

        // Second request resolves normally.
        joiner.listener!!.onAvailable(stubNetwork())
        assertTrue(session.state.value is CameraWifiSession.State.Connected)
    }

    @Test
    fun failedPathDoesNotYieldController() {
        val joiner = FakeWifiApJoiner()
        val session = newSession(joiner)
        session.connect("GR", "pw")

        joiner.listener!!.onUnavailable()

        assertNull(session.controller)
        assertTrue(session.state.value is CameraWifiSession.State.Failed)
    }
}
