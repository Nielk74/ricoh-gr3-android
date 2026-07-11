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

/**
 * Wi-Fi access-point credentials read from the camera's WLAN Control service over BLE.
 * Used to join the camera's AP for the HTTP `/v1/` API (see FEASIBILITY.md §6).
 */
data class WlanCredentials(
    val ssid: String,
    val passphrase: String,
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
    /** True once the WLAN Control service is present on the connected camera. */
    val wlanControlAvailable: Boolean = false,
    /** Wi-Fi AP credentials read over BLE, or null until [WlanCredentials] is fetched. */
    val wlanCredentials: WlanCredentials? = null,
    /** True while an enable-AP (network type → AP mode) write is in flight. */
    val wifiEnabling: Boolean = false,
    /** True once the camera acknowledged switching its network type to AP mode. */
    val wifiEnabled: Boolean = false,
    val error: String? = null,
    /**
     * Human-readable trace of the last BLE handoff step, shown on-screen for diagnosis when
     * logcat isn't available. Appended to as the GATT queue progresses.
     */
    val debug: String? = null,
)
