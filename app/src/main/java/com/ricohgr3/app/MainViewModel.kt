package com.ricohgr3.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.ricohgr3.app.ble.CameraBleManager
import com.ricohgr3.app.ble.CameraController
import com.ricohgr3.app.wifi.CameraWifiSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val ble: CameraController = CameraBleManager(app.applicationContext)

    val state = ble.state

    /**
     * The Wi-Fi AP session (null on API < 29, where [CameraWifiSession] is unsupported). Owns the
     * join-and-bind state machine; its [CameraWifiSession.state] drives the connect flow's Wi-Fi
     * steps and yields the bound [com.ricohgr3.app.wifi.CameraWifiController] once connected.
     */
    val wifiSession: CameraWifiSession? = CameraWifiSession.createOrNull(app.applicationContext)

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
     * User tapped "Join camera Wi-Fi" (after enabling Wi-Fi on the camera body). If we already
     * have credentials, join now; otherwise read them — the connect flow's `LaunchedEffect` joins
     * once they land. [resetWifiJoin] first so a prior failed/idle attempt can run again.
     */
    fun joinCameraWifi() {
        resetWifiJoin()
        _wifiJoinIntent.value = true
        val creds = state.value.wlanCredentials
        if (creds != null && creds.ssid.isNotBlank()) {
            joinWifi(creds.ssid, creds.passphrase)
        } else {
            readWlanCredentials()
        }
    }

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
