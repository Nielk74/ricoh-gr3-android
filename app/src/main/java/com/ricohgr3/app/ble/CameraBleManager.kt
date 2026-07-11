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

    /** A single queued GATT operation. Reads and writes share one queue (one op in flight). */
    private sealed interface GattOp {
        val uuid: java.util.UUID
        data class Read(override val uuid: java.util.UUID) : GattOp
        data class Write(override val uuid: java.util.UUID, val value: ByteArray) : GattOp
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private val _state = MutableStateFlow(BleState())
    override val state: StateFlow<BleState> = _state.asStateFlow()

    private val scanner get() = adapter?.bluetoothLeScanner
    private var gatt: BluetoothGatt? = null

    /**
     * Serialized queue of GATT operations (Android allows exactly one in flight at a time).
     * Reads and writes MUST share one queue — issuing a write while a read is pending makes
     * Android silently drop the write (no callback), which strands the Wi-Fi handoff.
     */
    private val opQueue = ArrayDeque<GattOp>()

    /** True while an op is in flight; guards against kicking the queue re-entrantly. */
    private var opInFlight = false

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
                    opQueue.clear()
                    opInFlight = false
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
            opQueue.clear()
            opInFlight = false
            opQueue.addAll(
                listOf(
                    RicohGattProfile.MANUFACTURER_NAME_STRING,
                    RicohGattProfile.MODEL_NUMBER_STRING,
                    RicohGattProfile.FIRMWARE_REVISION_STRING,
                    RicohGattProfile.SERIAL_NUMBER_STRING,
                ).map { GattOp.Read(it) }
            )
            drainQueue(g)
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
            // Enable-AP goes through the op queue; advance it. (The shutter write is fired
            // directly, not queued, so it's a no-op for the queue when it completes.)
            if (characteristic.uuid == RicohGattProfile.WLAN_NETWORK_TYPE) {
                opInFlight = false
                drainQueue(g)
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
        opInFlight = false
        drainQueue(g)
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

    /**
     * Start the next queued GATT op if none is in flight. Android permits only one op at a time;
     * each op's completion callback ([handleRead] / [onCharacteristicWrite]) clears [opInFlight]
     * and calls this again to advance the queue.
     */
    private fun drainQueue(g: BluetoothGatt) {
        if (opInFlight) return
        while (true) {
            val op = opQueue.poll() ?: return
            val uuid = op.uuid
            val ch = g.getService(serviceForCharacteristic(uuid))?.getCharacteristic(uuid)
            if (ch == null) {
                // Characteristic not present on this model — skip and try the next op.
                if (op is GattOp.Write && uuid == RicohGattProfile.WLAN_NETWORK_TYPE) {
                    _state.update {
                        it.copy(wifiEnabling = false, error = "WLAN Network Type characteristic not found")
                    }
                }
                continue
            }
            opInFlight = true
            when (op) {
                is GattOp.Read -> g.readCharacteristic(ch)
                is GattOp.Write -> writeCharacteristicCompat(g, ch, op.value)
            }
            return
        }
    }

    /** Version-agnostic characteristic write (the API-33 signature vs. the deprecated one). */
    private fun writeCharacteristicCompat(
        g: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = value
                g.writeCharacteristic(ch)
            }
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
        opQueue.add(GattOp.Read(RicohGattProfile.WLAN_SSID))
        opQueue.add(GattOp.Read(RicohGattProfile.WLAN_PASSPHRASE))
        // drainQueue is a no-op if an op is already in flight; that op's completion
        // callback will pick these up.
        drainQueue(g)
    }

    /**
     * Wake (or turn off) the camera's Wi-Fi access point by writing the WLAN Control
     * "Network Type" characteristic. Result is reflected in [BleState.wifiEnabling] /
     * [BleState.wifiEnabled] from [onCharacteristicWrite].
     */
    override fun enableWifiAp(enable: Boolean) {
        val g = gatt ?: return
        if (g.getService(RicohGattProfile.WLAN_CONTROL_SERVICE) == null) {
            _state.update { it.copy(error = "WLAN Control service not found") }
            return
        }
        pendingWifiEnable = enable
        _state.update { it.copy(wifiEnabling = true, error = null) }
        // Queue the write behind any in-flight/pending reads (e.g. the credential reads from
        // startWifiHandoff). Firing it directly while a read is pending makes Android drop it
        // silently — no onCharacteristicWrite callback — stranding wifiEnabling forever.
        opQueue.add(GattOp.Write(RicohGattProfile.WLAN_NETWORK_TYPE, RicohGattProfile.networkTypePayload(enable)))
        drainQueue(g)
        Log.d(TAG, "Wi-Fi AP enable=$enable queued")
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
