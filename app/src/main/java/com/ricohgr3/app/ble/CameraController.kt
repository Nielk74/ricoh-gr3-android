package com.ricohgr3.app.ble

import kotlinx.coroutines.flow.StateFlow

/**
 * Transport-agnostic surface the ViewModel / UI depend on to discover and control
 * a camera. [CameraBleManager] is the BLE implementation; test doubles (e.g.
 * FakeCameraController) implement the same contract in-memory.
 */
interface CameraController {

    /** Immutable snapshot of discovery/connection/shutter state. */
    val state: StateFlow<BleState>

    fun startScan()
    fun stopScan()
    fun connect(address: String)
    fun disconnect()
    fun fireShutter(af: Boolean = true)

    /**
     * Read the camera's Wi-Fi AP SSID + passphrase over the WLAN Control service.
     * The result is delivered asynchronously into [BleState.wlanCredentials]
     * (GATT reads are async, mirroring the device-info reads).
     */
    fun readWlanCredentials()

    /**
     * Command the camera to wake its Wi-Fi access point by writing the WLAN Control
     * "Network Type" characteristic to AP mode (or OFF). Progress/result is reflected
     * in [BleState.wifiEnabling] / [BleState.wifiEnabled].
     */
    fun enableWifiAp(enable: Boolean = true)

    fun isBluetoothEnabled(): Boolean
    fun close()
}
