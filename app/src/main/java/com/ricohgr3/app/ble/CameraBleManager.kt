package com.ricohgr3.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.ArrayDeque

/**
 * Drives BLE discovery and control of a Ricoh GR III / GR IIIx.
 *
 * Proof-of-concept scope:
 *  1. scan for the camera (advertises a name containing "GR" or "RICOH"),
 *  2. connect + read Device Information (model / firmware / serial),
 *  3. fire the shutter via the Operation Request characteristic.
 *
 * Callers must hold BLUETOOTH_SCAN + BLUETOOTH_CONNECT (API 31+) or
 * BLUETOOTH_ADMIN + ACCESS_FINE_LOCATION (API 26-30) before calling scan/connect.
 */
@SuppressLint("MissingPermission")
class CameraBleManager(private val context: Context) : CameraController {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private val _state = MutableStateFlow(BleState())
    override val state: StateFlow<BleState> = _state.asStateFlow()

    private val scanner get() = adapter?.bluetoothLeScanner
    private var gatt: BluetoothGatt? = null

    /** Serialized queue of characteristic reads (Android GATT allows one op at a time). */
    private val readQueue = ArrayDeque<java.util.UUID>()

    /** The [RicohGattProfile.NetworkType] value of the in-flight enable-AP write. */
    private var pendingWifiEnable = false

    override fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    // ---- Scanning -----------------------------------------------------------

    override fun startScan() {
        val scanner = scanner ?: run {
            _state.update { it.copy(error = "Bluetooth unavailable") }
            return
        }
        if (_state.value.isScanning) return
        _state.update { it.copy(isScanning = true, error = null, devices = emptyList()) }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // No service-UUID filter: the GR III advertises without the shooting service in
        // its advertisement, so we match on device name instead (in the callback).
        scanner.startScan(emptyList<ScanFilter>(), settings, scanCallback)
    }

    override fun stopScan() {
        if (!_state.value.isScanning) return
        scanner?.stopScan(scanCallback)
        _state.update { it.copy(isScanning = false) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: result.scanRecord?.deviceName ?: return
            if (!looksLikeRicoh(name)) return
            _state.update { s ->
                if (s.devices.any { it.address == device.address }) s
                else s.copy(devices = s.devices + DiscoveredCamera(name, device.address))
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _state.update { it.copy(isScanning = false, error = "Scan failed ($errorCode)") }
        }
    }

    private fun looksLikeRicoh(name: String): Boolean {
        val n = name.uppercase()
        return n.contains("GR") || n.contains("RICOH") || n.contains("PENTAX")
    }

    // ---- Connecting ---------------------------------------------------------

    override fun connect(address: String) {
        stopScan()
        val device: BluetoothDevice = adapter?.getRemoteDevice(address) ?: return
        _state.update { it.copy(connectionState = ConnectionState.CONNECTING, error = null) }
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    override fun disconnect() {
        gatt?.disconnect()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.update { it.copy(connectionState = ConnectionState.DISCOVERING) }
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    g.close()
                    gatt = null
                    _state.update {
                        it.copy(
                            connectionState = ConnectionState.DISCONNECTED,
                            deviceInfo = null,
                        )
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.update { it.copy(error = "Service discovery failed ($status)") }
                return
            }
            val hasShooting = g.getService(RicohGattProfile.SHOOTING_SERVICE) != null
            val hasWlan = g.getService(RicohGattProfile.WLAN_CONTROL_SERVICE) != null
            _state.update {
                it.copy(
                    connectionState = ConnectionState.CONNECTED,
                    shutterAvailable = hasShooting,
                    wlanControlAvailable = hasWlan,
                )
            }
            // Queue the Device Information reads.
            readQueue.clear()
            readQueue.addAll(
                listOf(
                    RicohGattProfile.MANUFACTURER_NAME_STRING,
                    RicohGattProfile.MODEL_NUMBER_STRING,
                    RicohGattProfile.FIRMWARE_REVISION_STRING,
                    RicohGattProfile.SERIAL_NUMBER_STRING,
                )
            )
            dequeueRead(g)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            @Suppress("DEPRECATION")
            handleRead(characteristic.uuid, characteristic.value, status, g)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handleRead(characteristic.uuid, value, status, g)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val ok = status == BluetoothGatt.GATT_SUCCESS
            when (characteristic.uuid) {
                RicohGattProfile.OPERATION_REQUEST -> _state.update {
                    it.copy(
                        lastShutterOk = ok,
                        shutterCount = it.shutterCount + if (ok) 1 else 0,
                    )
                }
                RicohGattProfile.WLAN_NETWORK_TYPE -> _state.update {
                    it.copy(
                        wifiEnabling = false,
                        wifiEnabled = ok && pendingWifiEnable,
                        error = if (ok) it.error else "Failed to switch Wi-Fi mode ($status)",
                    )
                }
            }
        }
    }

    private fun handleRead(uuid: java.util.UUID, value: ByteArray?, status: Int, g: BluetoothGatt) {
        if (status == BluetoothGatt.GATT_SUCCESS && value != null) {
            when (uuid) {
                RicohGattProfile.WLAN_SSID, RicohGattProfile.WLAN_PASSPHRASE ->
                    handleWlanRead(uuid, value)
                else -> {
                    val text = value.toString(Charsets.UTF_8).trim()
                    _state.update { s ->
                        val info = s.deviceInfo ?: DeviceInfo()
                        s.copy(
                            deviceInfo = when (uuid) {
                                RicohGattProfile.MANUFACTURER_NAME_STRING -> info.copy(manufacturer = text)
                                RicohGattProfile.MODEL_NUMBER_STRING -> info.copy(model = text)
                                RicohGattProfile.FIRMWARE_REVISION_STRING -> info.copy(firmware = text)
                                RicohGattProfile.SERIAL_NUMBER_STRING -> info.copy(serial = text)
                                else -> info
                            }
                        )
                    }
                }
            }
        }
        dequeueRead(g)
    }

    /** Fold an SSID or passphrase read into [BleState.wlanCredentials]. */
    private fun handleWlanRead(uuid: java.util.UUID, value: ByteArray) {
        val text = RicohGattProfile.parseWlanString(value)
        _state.update { s ->
            val creds = s.wlanCredentials ?: WlanCredentials(ssid = "", passphrase = "")
            s.copy(
                wlanCredentials = when (uuid) {
                    RicohGattProfile.WLAN_SSID -> creds.copy(ssid = text)
                    RicohGattProfile.WLAN_PASSPHRASE -> creds.copy(passphrase = text)
                    else -> creds
                }
            )
        }
    }

    private fun dequeueRead(g: BluetoothGatt) {
        val next = readQueue.poll() ?: return
        val ch = g.getService(serviceForCharacteristic(next))?.getCharacteristic(next)
        if (ch == null) {
            // Characteristic not present on this model — skip to the next one.
            dequeueRead(g)
        } else {
            g.readCharacteristic(ch)
        }
    }

    /** Map a queued characteristic UUID to the service that hosts it. */
    private fun serviceForCharacteristic(uuid: java.util.UUID): java.util.UUID = when (uuid) {
        RicohGattProfile.WLAN_SSID,
        RicohGattProfile.WLAN_PASSPHRASE,
        RicohGattProfile.WLAN_NETWORK_TYPE,
        RicohGattProfile.WLAN_CHANNEL -> RicohGattProfile.WLAN_CONTROL_SERVICE
        else -> RicohGattProfile.DEVICE_INFORMATION_SERVICE
    }

    // ---- Shutter ------------------------------------------------------------

    /** Fire the shutter. [af] triggers autofocus before capture. */
    override fun fireShutter(af: Boolean) {
        val g = gatt ?: return
        val ch = g.getService(RicohGattProfile.SHOOTING_SERVICE)
            ?.getCharacteristic(RicohGattProfile.OPERATION_REQUEST)
        if (ch == null) {
            _state.update { it.copy(error = "Shutter characteristic not found") }
            return
        }
        val payload = RicohGattProfile.shutterPayload(af)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = payload
                g.writeCharacteristic(ch)
            }
        }
        Log.d(TAG, "Shutter fired (af=$af)")
    }

    // ---- Wi-Fi handoff ------------------------------------------------------

    /**
     * Read the camera's Wi-Fi AP SSID + passphrase from the WLAN Control service.
     * Queues the two reads through the same serialized [readQueue] as device info;
     * results land in [BleState.wlanCredentials] via [handleWlanRead].
     */
    override fun readWlanCredentials() {
        val g = gatt ?: return
        if (g.getService(RicohGattProfile.WLAN_CONTROL_SERVICE) == null) {
            _state.update { it.copy(error = "WLAN Control service not found") }
            return
        }
        val busy = readQueue.isNotEmpty()
        readQueue.add(RicohGattProfile.WLAN_SSID)
        readQueue.add(RicohGattProfile.WLAN_PASSPHRASE)
        // Only kick the queue if nothing is currently draining it; otherwise the
        // in-flight read's completion callback will pick these up.
        if (!busy) dequeueRead(g)
    }

    /**
     * Wake (or turn off) the camera's Wi-Fi access point by writing the WLAN Control
     * "Network Type" characteristic. Result is reflected in [BleState.wifiEnabling] /
     * [BleState.wifiEnabled] from [onCharacteristicWrite].
     */
    override fun enableWifiAp(enable: Boolean) {
        val g = gatt ?: return
        val ch = g.getService(RicohGattProfile.WLAN_CONTROL_SERVICE)
            ?.getCharacteristic(RicohGattProfile.WLAN_NETWORK_TYPE)
        if (ch == null) {
            _state.update { it.copy(error = "WLAN Network Type characteristic not found") }
            return
        }
        pendingWifiEnable = enable
        _state.update { it.copy(wifiEnabling = true, error = null) }
        val payload = RicohGattProfile.networkTypePayload(enable)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = payload
                g.writeCharacteristic(ch)
            }
        }
        Log.d(TAG, "Wi-Fi AP enable=$enable requested")
    }

    override fun close() {
        stopScan()
        gatt?.close()
        gatt = null
    }

    companion object {
        private const val TAG = "CameraBleManager"
    }
}
