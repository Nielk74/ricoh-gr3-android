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
class CameraBleManager(private val context: Context) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private val _state = MutableStateFlow(BleState())
    val state: StateFlow<BleState> = _state.asStateFlow()

    private val scanner get() = adapter?.bluetoothLeScanner
    private var gatt: BluetoothGatt? = null

    /** Serialized queue of characteristic reads (Android GATT allows one op at a time). */
    private val readQueue = ArrayDeque<java.util.UUID>()

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    // ---- Scanning -----------------------------------------------------------

    fun startScan() {
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

    fun stopScan() {
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

    fun connect(address: String) {
        stopScan()
        val device: BluetoothDevice = adapter?.getRemoteDevice(address) ?: return
        _state.update { it.copy(connectionState = ConnectionState.CONNECTING, error = null) }
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    fun disconnect() {
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
            _state.update {
                it.copy(
                    connectionState = ConnectionState.CONNECTED,
                    shutterAvailable = hasShooting,
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
            if (characteristic.uuid == RicohGattProfile.OPERATION_REQUEST) {
                _state.update {
                    it.copy(
                        lastShutterOk = status == BluetoothGatt.GATT_SUCCESS,
                        shutterCount = it.shutterCount + if (status == BluetoothGatt.GATT_SUCCESS) 1 else 0,
                    )
                }
            }
        }
    }

    private fun handleRead(uuid: java.util.UUID, value: ByteArray?, status: Int, g: BluetoothGatt) {
        if (status == BluetoothGatt.GATT_SUCCESS && value != null) {
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
        dequeueRead(g)
    }

    private fun dequeueRead(g: BluetoothGatt) {
        val next = readQueue.poll() ?: return
        val svc = g.getService(RicohGattProfile.DEVICE_INFORMATION_SERVICE)
        val ch = svc?.getCharacteristic(next)
        if (ch == null) {
            // Characteristic not present on this model — skip to the next one.
            dequeueRead(g)
        } else {
            g.readCharacteristic(ch)
        }
    }

    // ---- Shutter ------------------------------------------------------------

    /** Fire the shutter. [af] triggers autofocus before capture. */
    fun fireShutter(af: Boolean = true) {
        val g = gatt ?: return
        val ch = g.getService(RicohGattProfile.SHOOTING_SERVICE)
            ?.getCharacteristic(RicohGattProfile.OPERATION_REQUEST)
        if (ch == null) {
            _state.update { it.copy(error = "Shutter characteristic not found") }
            return
        }
        val param = if (af) RicohGattProfile.OperationParameter.AF
        else RicohGattProfile.OperationParameter.NO_AF
        val payload = byteArrayOf(RicohGattProfile.OperationCode.START_SHOOTING, param)

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

    fun close() {
        stopScan()
        gatt?.close()
        gatt = null
    }

    companion object {
        private const val TAG = "CameraBleManager"
    }
}
