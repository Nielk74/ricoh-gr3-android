package com.ricohgr3.app.ble

/** A camera discovered during a BLE scan. */
data class DiscoveredCamera(
    val name: String,
    val address: String,
)

/** Device Information read from the camera over BLE (GATT 0x180A). */
data class DeviceInfo(
    val manufacturer: String? = null,
    val model: String? = null,
    val firmware: String? = null,
    val serial: String? = null,
)

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    DISCOVERING,
    CONNECTED,
}

/** Immutable snapshot of the BLE layer, surfaced to the UI as a StateFlow. */
data class BleState(
    val isScanning: Boolean = false,
    val devices: List<DiscoveredCamera> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val deviceInfo: DeviceInfo? = null,
    val shutterAvailable: Boolean = false,
    val lastShutterOk: Boolean? = null,
    val shutterCount: Int = 0,
    val error: String? = null,
)
