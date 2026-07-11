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

    fun startScan() = ble.startScan()
    fun stopScan() = ble.stopScan()
    fun connect(address: String) = ble.connect(address)
    fun disconnect() {
        wifiSession?.disconnect()
        _wifiJoinRequested.value = false
        ble.disconnect()
    }
    fun fireShutter(af: Boolean = true) = ble.fireShutter(af)

    fun bluetoothEnabled() = ble.isBluetoothEnabled()

    // --- BLE → Wi-Fi handoff orchestration -------------------------------------------

    /** Read the camera's Wi-Fi SSID/passphrase over BLE (lands in [state].wlanCredentials). */
    fun readWlanCredentials() = ble.readWlanCredentials()

    /** Command the camera to wake its Wi-Fi AP over BLE. */
    fun enableWifiAp() = ble.enableWifiAp(enable = true)

    /**
     * The full one-tap handoff: read credentials (if needed) and wake the AP. The connect flow
     * then observes [state] for the credentials + [wifiSession] state and calls [joinWifi] once
     * they're available.
     */
    fun startWifiHandoff() {
        readWlanCredentials()
        enableWifiAp()
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
