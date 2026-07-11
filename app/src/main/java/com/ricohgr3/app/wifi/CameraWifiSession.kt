package com.ricohgr3.app.wifi

import android.content.Context
import android.net.Network
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Minimal radio-facing surface the [CameraWifiSession] state machine drives. It mirrors the two
 * methods of [WifiApConnector] but WITHOUT its `@RequiresApi(Q)` gate, so the session logic can be
 * unit-tested on the JVM with an in-memory fake (no real `ConnectivityManager`).
 *
 * The one production implementation is [WifiApConnector.asJoiner]; on API < 29 there is none —
 * callers must gate on [WifiApConnector.isSupported] before constructing a real session.
 */
interface WifiApJoiner {

    /** See [WifiApConnector.connect]. Non-blocking; results arrive via [listener]. */
    fun connect(ssid: String, passphrase: String, listener: WifiApConnector.Listener)

    /** See [WifiApConnector.adoptCurrentWifi]. Returns the bound network, or null if no Wi-Fi. */
    fun adoptCurrentWifi(): Network?

    /** See [WifiApConnector.disconnect]. Idempotent. */
    fun disconnect()
}

/** Adapter exposing this connector as a transport-agnostic [WifiApJoiner]. */
@RequiresApi(Build.VERSION_CODES.Q)
fun WifiApConnector.asJoiner(): WifiApJoiner = object : WifiApJoiner {
    override fun connect(ssid: String, passphrase: String, listener: WifiApConnector.Listener) =
        this@asJoiner.connect(ssid, passphrase, listener)

    override fun adoptCurrentWifi(): Network? = this@asJoiner.adoptCurrentWifi()

    override fun disconnect() = this@asJoiner.disconnect()
}

/**
 * State machine for a single camera Wi-Fi AP session, exposed as a [StateFlow] the ViewModel/UI
 * can collect. It ties together joining the AP (via a [WifiApJoiner], normally backed by
 * [WifiApConnector]) and handing out a [CameraWifiController] bound to the joined
 * [android.net.Network].
 *
 * This is the Wi-Fi analogue of how the BLE side separates
 * [com.ricohgr3.app.ble.CameraController] (interface) from
 * [com.ricohgr3.app.ble.CameraBleManager] (impl): the radio work lives behind [WifiApJoiner] so
 * the transitions here are pure and JVM-testable.
 *
 * ## States
 *  - [State.Idle]      — no session; nothing joined.
 *  - [State.Joining]   — [connect] requested, awaiting the system's join result.
 *  - [State.Connected] — AP joined; carries a ready [CameraWifiController] and its [Network].
 *  - [State.Failed]    — the join was declined/unsatisfiable (from `onUnavailable`).
 *  - [State.Lost]      — a previously [Connected] AP dropped (from `onLost`).
 *
 * ## Transitions
 * ```
 *  Idle      --connect()-->      Joining
 *  Joining   --onAvailable-->    Connected
 *  Joining   --onUnavailable-->  Failed
 *  Connected --onLost-->         Lost
 *  any       --disconnect()-->   Idle
 * ```
 * Late/stale callbacks after [disconnect] are ignored (guarded by a generation token), so a
 * dropped-then-torn-down race can't resurrect a [Lost]/[Connected] state.
 *
 * All state mutation is confined to `@Synchronized` methods; [state] is safe to collect from any
 * thread. The [Network]-binding factory is injectable for tests.
 */
class CameraWifiSession(
    private val joiner: WifiApJoiner,
    /** Builds the bound controller once the AP [Network] is available. Injectable for tests. */
    private val controllerFactory: (Network) -> CameraWifiController = { network ->
        CameraHttpClient.forNetwork(network)
    },
) {

    sealed interface State {
        data object Idle : State
        data object Joining : State
        data class Connected(
            val controller: CameraWifiController,
            val network: Network,
        ) : State

        data object Failed : State
        data object Lost : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)

    /** Observable session state. Starts [State.Idle]. */
    val state: StateFlow<State> = _state.asStateFlow()

    /** Convenience: the live controller when [State.Connected], else null. */
    val controller: CameraWifiController?
        get() = (_state.value as? State.Connected)?.controller

    /**
     * A generation token bumped on every [connect]/[disconnect]. Callbacks capture the generation
     * live at request time and no-op if it has since changed, dropping stale/late radio events.
     */
    private var generation = 0

    /**
     * Request to join the camera AP [ssid] / [passphrase]. Moves to [State.Joining] and drives the
     * flow from the [WifiApJoiner]'s callbacks. Calling again supersedes any prior request.
     */
    @Synchronized
    fun connect(ssid: String, passphrase: String) {
        val myGen = ++generation
        _state.value = State.Joining
        joiner.connect(ssid, passphrase, object : WifiApConnector.Listener {
            override fun onAvailable(network: Network) = onAvailableInternal(myGen, network)
            override fun onUnavailable() = onUnavailableInternal(myGen)
            override fun onLost() = onLostInternal(myGen)
        })
    }

    /**
     * Adopt the Wi-Fi the phone is **already** connected to (the user joined the camera AP manually
     * in Android Settings) instead of initiating a new join. Binds the process to that network,
     * then probes it with `/v1/ping` to confirm it's actually the camera before declaring
     * [State.Connected]. Moves to [State.Joining] while probing; to [State.Failed] if there's no
     * Wi-Fi or the probe fails (e.g. the connected Wi-Fi isn't the camera). Supersedes any prior
     * request. Suspends until the probe resolves.
     */
    suspend fun adopt() {
        val myGen: Int
        val network: Network?
        synchronized(this) {
            myGen = ++generation
            network = joiner.adoptCurrentWifi()
            _state.value = State.Joining
        }
        if (network == null) {
            setIfCurrent(myGen, State.Failed)
            return
        }
        val controller = controllerFactory(network)
        val reachable = runCatching { controller.ping() }.isSuccess
        setIfCurrent(myGen, if (reachable) State.Connected(controller, network) else State.Failed)
    }

    /** Apply [next] only if [gen] is still the live generation (drops superseded adopt/connect). */
    @Synchronized
    private fun setIfCurrent(gen: Int, next: State) {
        if (gen == generation) _state.value = next
    }

    /** Tear down: release the AP and return to [State.Idle]. Safe to call any time. */
    @Synchronized
    fun disconnect() {
        generation++ // invalidate any in-flight callbacks
        joiner.disconnect()
        _state.value = State.Idle
    }

    @Synchronized
    private fun onAvailableInternal(gen: Int, network: Network) {
        if (gen != generation) return
        _state.value = State.Connected(controllerFactory(network), network)
    }

    @Synchronized
    private fun onUnavailableInternal(gen: Int) {
        if (gen != generation) return
        _state.value = State.Failed
    }

    @Synchronized
    private fun onLostInternal(gen: Int) {
        if (gen != generation) return
        _state.value = State.Lost
    }

    companion object {
        /**
         * Build a session backed by a real [WifiApConnector] for [context]. Returns null on
         * devices below API 29, where [WifiApConnector] is unsupported — callers should surface a
         * "Wi-Fi camera control needs Android 10+" message in that case.
         */
        fun createOrNull(context: Context): CameraWifiSession? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            return CameraWifiSession(WifiApConnector(context).asJoiner())
        }
    }
}
