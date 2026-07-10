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
    fun isBluetoothEnabled(): Boolean
    fun close()
}
