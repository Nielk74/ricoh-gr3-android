package com.ricohgr3.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.ricohgr3.app.ble.CameraBleManager
import com.ricohgr3.app.ble.CameraController

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val ble: CameraController = CameraBleManager(app.applicationContext)

    val state = ble.state

    fun startScan() = ble.startScan()
    fun stopScan() = ble.stopScan()
    fun connect(address: String) = ble.connect(address)
    fun disconnect() = ble.disconnect()
    fun fireShutter(af: Boolean = true) = ble.fireShutter(af)

    fun bluetoothEnabled() = ble.isBluetoothEnabled()

    override fun onCleared() {
        ble.close()
        super.onCleared()
    }
}
