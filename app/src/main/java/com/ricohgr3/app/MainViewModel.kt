package com.ricohgr3.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ricohgr3.app.ble.CameraBleManager
import com.ricohgr3.app.ble.CameraController
import com.ricohgr3.app.ble.WlanCredentials
import com.ricohgr3.app.wifi.CameraCredentialStore
import com.ricohgr3.app.wifi.CameraWifiSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Which transport the user has chosen. BLE and Wi-Fi were assumed mutually exclusive on the GR III,
 * but that was concluded with the camera tethered over USB (a confounder that blocks its radios) —
 * so the exclusivity is currently UNVERIFIED. See [selectWifi] and BLE_WIFI_WAKE_INVESTIGATION.md.
 */
enum class Transport { BLUETOOTH, WIFI }

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val ble: CameraController = CameraBleManager(app.applicationContext)

    val state = ble.state

    /** Persisted camera Wi-Fi credentials (cached from a BLE pairing) for BLE-free Wi-Fi joins. */
    private val credentialStore = CameraCredentialStore(app.applicationContext)
    val cachedCredentials: StateFlow<WlanCredentials?> =
        credentialStore.credentialsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The chosen transport. Null until the user picks one on the chooser. */
    private val _transport = MutableStateFlow<Transport?>(null)
    val transport: StateFlow<Transport?> = _transport.asStateFlow()

    /**
     * The Wi-Fi AP session (null on API < 29, where [CameraWifiSession] is unsupported). Owns the
     * join-and-bind state machine; its [CameraWifiSession.state] drives the connect flow's Wi-Fi
     * steps and yields the bound [com.ricohgr3.app.wifi.CameraWifiController] once connected.
     */
    val wifiSession: CameraWifiSession? = CameraWifiSession.createOrNull(app.applicationContext)

    init {
        // Keep the join-flow flags consistent with the actual session state:
        //  - once Connected, the join succeeded → drop the one-shot intent so a later credential
        //    re-read can't re-trigger an auto-join (bug: intent outliving its action).
        //  - on Failed/Lost, clear the request guard so BOTH the manual "Retry" button AND the
        //    automatic LaunchedEffect can attempt a fresh join (bug: only the button could recover).
        wifiSession?.let { session ->
            viewModelScope.launch {
                session.state.collect { s ->
                    when (s) {
                        is CameraWifiSession.State.Connected -> _wifiJoinIntent.value = false
                        is CameraWifiSession.State.Failed,
                        is CameraWifiSession.State.Lost -> _wifiJoinRequested.value = false
                        else -> Unit
                    }
                }
            }
        }
    }

    /**
     * True once we've kicked off a Wi-Fi join for the current BLE-read credentials, so the
     * flow doesn't re-trigger `wifiSession.connect(...)` on every recomposition.
     */
    private val _wifiJoinRequested = MutableStateFlow(false)
    val wifiJoinRequested: StateFlow<Boolean> = _wifiJoinRequested.asStateFlow()

    /**
     * True once the user has asked to join the camera Wi-Fi (tapped "Join camera Wi-Fi"). Gates the
     * auto-join effect so we don't try to join before the user has turned Wi-Fi on at the camera.
     */
    private val _wifiJoinIntent = MutableStateFlow(false)
    val wifiJoinIntent: StateFlow<Boolean> = _wifiJoinIntent.asStateFlow()

    fun startScan() = ble.startScan()
    fun stopScan() = ble.stopScan()
    fun connect(address: String) = ble.connect(address)
    fun disconnect() {
        wifiSession?.disconnect()
        _wifiJoinRequested.value = false
        _wifiJoinIntent.value = false
        ble.disconnect()
    }
    fun fireShutter(af: Boolean = true) = ble.fireShutter(af)

    fun bluetoothEnabled() = ble.isBluetoothEnabled()

    // --- Transport selection ----------------------------------------------------------
    // BLE and Wi-Fi are mutually exclusive on the GR III, so choosing one tears down the other.

    init {
        // Cache the AP credentials whenever a BLE session reads them, so Wi-Fi mode can join
        // later without BLE (the two transports can't be up at once).
        viewModelScope.launch {
            state.collect { s ->
                s.wlanCredentials?.let { creds ->
                    if (creds.ssid.isNotBlank()) credentialStore.save(creds)
                }
            }
        }
    }

    /**
     * Choose the Bluetooth transport. We release any Wi-Fi AP session here — not because the two
     * transports are known to conflict (that assumption is unverified; see [Transport] and
     * [selectWifi]) but because holding the phone on the camera's internet-less AP while the user
     * has switched to BLE control serves no purpose and would keep the phone off its normal network.
     */
    fun selectBluetooth() {
        wifiSession?.disconnect()
        _wifiJoinRequested.value = false
        _wifiJoinIntent.value = false
        _transport.value = Transport.BLUETOOTH
    }

    /**
     * Choose the Wi-Fi transport.
     *
     * We intentionally do NOT tear down BLE here. The earlier "BLE keeps the camera AP off, so they
     * are mutually exclusive" premise was concluded while the camera was tethered over USB — a
     * confounder that itself blocks the camera's radios (see BLE_WIFI_WAKE_INVESTIGATION.md). Until
     * coexistence is confirmed/refuted on-device (camera on battery), we leave BLE connected so a
     * Wi-Fi session can't be broken by an assumption that may be false. If a specific camera really
     * does require BLE down for its AP, the join will simply fail and the user can disconnect BLE.
     */
    fun selectWifi() {
        _transport.value = Transport.WIFI
    }

    /** Return to the transport chooser, disconnecting everything. */
    fun clearTransport() {
        disconnect()
        _transport.value = null
    }

    // --- BLE → Wi-Fi handoff orchestration -------------------------------------------

    /** Read the camera's Wi-Fi SSID/passphrase over BLE (lands in [state].wlanCredentials). */
    fun readWlanCredentials() = ble.readWlanCredentials()

    /**
     * Best-effort BLE command to wake the camera's Wi-Fi AP. NOTE: on shipping GR III firmware
     * (tested 1.92 and 2.10) the camera *rejects* this Network Type write with GATT app error
     * 0x80 ("can't in this mode") — it is not the real trigger the official app uses. Kept for
     * cameras/firmware that may honor it, but the flow no longer depends on it; the user enables
     * Wi-Fi from the camera instead. See research/BLE_WIFI_WAKE_INVESTIGATION.md.
     */
    fun enableWifiAp() = ble.enableWifiAp(enable = true)

    /**
     * Prepare the Wi-Fi handoff: read the AP credentials (SSID/passphrase) over BLE — these reads
     * DO work. We deliberately do NOT issue the enable-AP write here (see [enableWifiAp]); the user
     * turns Wi-Fi on from the camera. Once credentials are read and the AP is up, the connect flow
     * joins it via [joinWifi].
     */
    fun startWifiHandoff() {
        readWlanCredentials()
    }

    /**
     * User tapped "Join camera Wi-Fi" (after enabling Wi-Fi on the camera body). Uses credentials
     * from the live BLE session if present, else the [cachedCredentials] saved from a prior BLE
     * pairing (the usual Wi-Fi-mode case, where BLE is disconnected). Falls back to reading over
     * BLE only if a session is up and we have nothing cached.
     */
    fun joinCameraWifi() {
        resetWifiJoin()
        _wifiJoinIntent.value = true
        val creds = state.value.wlanCredentials?.takeIf { it.ssid.isNotBlank() }
            ?: cachedCredentials.value
        if (creds != null && creds.ssid.isNotBlank()) {
            joinWifi(creds.ssid, creds.passphrase)
        } else {
            // Nothing cached — need a BLE session to read them first.
            readWlanCredentials()
        }
    }

    /**
     * User is already on the camera's Wi-Fi (joined it manually in Android Settings) and taps
     * "Use current Wi-Fi". Adopt the phone's existing Wi-Fi connection and probe it for the camera
     * — no credentials and no BLE pairing needed. On success the session goes Connected; if the
     * connected Wi-Fi isn't the camera, it goes Failed and the user can turn to the join path.
     */
    fun useCurrentWifi() {
        val session = wifiSession ?: return
        _wifiJoinIntent.value = true
        _wifiJoinRequested.value = true // this is the active attempt; block the auto-join effect.
        viewModelScope.launch { session.adopt() }
    }

    /** True if we can join Wi-Fi without a BLE session (credentials are cached). */
    fun hasCachedCredentials(): Boolean = cachedCredentials.value?.ssid?.isNotBlank() == true

    /**
     * Join the camera AP using the BLE-read [ssid]/[passphrase]. Idempotent for a given
     * credential set — guarded by [wifiJoinRequested] so repeated calls (e.g. from a
     * `LaunchedEffect`) don't restart the join. Call [resetWifiJoin] to allow a retry.
     */
    fun joinWifi(ssid: String, passphrase: String) {
        val session = wifiSession ?: return
        if (_wifiJoinRequested.value) return
        _wifiJoinRequested.value = true
        session.connect(ssid, passphrase)
    }

    /** Allow [joinWifi] to run again (e.g. after a failure, for a retry). */
    fun resetWifiJoin() {
        _wifiJoinRequested.value = false
    }

    override fun onCleared() {
        wifiSession?.disconnect()
        ble.close()
        super.onCleared()
    }
}
