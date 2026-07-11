package com.ricohgr3.app.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory [CameraController] test double. Drives [BleState] transitions
 * synchronously with no Android BLE calls, so it can run on the JVM.
 *
 * Behaviour mirrors [CameraBleManager] closely enough for state-transition tests:
 *  - [startScan] flips scanning on and immediately "discovers" [fakeCamera],
 *  - [connect] walks CONNECTING → DISCOVERING → CONNECTED and populates device info,
 *  - [fireShutter] increments [BleState.shutterCount] and sets [BleState.lastShutterOk].
 */
class FakeCameraController(
    private val bluetoothEnabled: Boolean = true,
    val fakeCamera: DiscoveredCamera = DiscoveredCamera("GR_FAKE", "00:11:22:33:44:55"),
    private val fakeInfo: DeviceInfo = DeviceInfo(
        manufacturer = "RICOH IMAGING COMPANY, LTD.",
        model = "GR III",
        firmware = "1.60",
        serial = "FAKE-0001",
    ),
    private val fakeCredentials: WlanCredentials = WlanCredentials(
        ssid = "GR_FAKE0001",
        passphrase = "fakepass1234",
    ),
) : CameraController {

    private val _state = MutableStateFlow(BleState())
    override val state: StateFlow<BleState> = _state.asStateFlow()

    var closed = false
        private set

    override fun startScan() {
        _state.update {
            it.copy(
                isScanning = true,
                error = null,
                devices = listOf(fakeCamera),
            )
        }
    }

    override fun stopScan() {
        _state.update { it.copy(isScanning = false) }
    }

    override fun connect(address: String) {
        stopScan()
        _state.update { it.copy(connectionState = ConnectionState.CONNECTING, error = null) }
        _state.update { it.copy(connectionState = ConnectionState.DISCOVERING) }
        _state.update {
            it.copy(
                connectionState = ConnectionState.CONNECTED,
                shutterAvailable = true,
                deviceInfo = fakeInfo,
            )
        }
    }

    override fun disconnect() {
        _state.update {
            it.copy(connectionState = ConnectionState.DISCONNECTED, deviceInfo = null)
        }
    }

    override fun fireShutter(af: Boolean) {
        if (_state.value.connectionState != ConnectionState.CONNECTED) return
        _state.update {
            it.copy(lastShutterOk = true, shutterCount = it.shutterCount + 1)
        }
    }

    override fun readWlanCredentials() {
        if (_state.value.connectionState != ConnectionState.CONNECTED) return
        _state.update { it.copy(wlanCredentials = fakeCredentials) }
    }

    override fun enableWifiAp(enable: Boolean) {
        if (_state.value.connectionState != ConnectionState.CONNECTED) return
        // Drive the enabling -> enabled transition synchronously for JVM tests.
        _state.update { it.copy(wifiEnabling = true) }
        _state.update { it.copy(wifiEnabling = false, wifiEnabled = enable) }
    }

    override fun isBluetoothEnabled(): Boolean = bluetoothEnabled

    override fun close() {
        stopScan()
        closed = true
    }
}
