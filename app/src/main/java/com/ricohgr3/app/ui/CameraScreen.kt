package com.ricohgr3.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ricohgr3.app.MainViewModel
import com.ricohgr3.app.ble.ConnectionState

@Composable
fun CameraScreen(
    viewModel: MainViewModel,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text("Ricoh GR III — BLE PoC", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Remote shutter over Bluetooth LE", fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))

        if (!permissionsGranted) {
            Text("Bluetooth permission is required to scan for the camera.")
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRequestPermissions) { Text("Grant Bluetooth permission") }
            return@Column
        }

        when (state.connectionState) {
            ConnectionState.DISCONNECTED -> ScanSection(viewModel, state)
            ConnectionState.CONNECTING,
            ConnectionState.DISCOVERING -> ConnectingSection(state.connectionState)
            ConnectionState.CONNECTED -> ConnectedSection(viewModel, state)
        }

        state.error?.let {
            Spacer(Modifier.height(16.dp))
            Text("⚠ $it", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ScanSection(viewModel: MainViewModel, state: com.ricohgr3.app.ble.BleState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (state.isScanning) {
            OutlinedButton(onClick = { viewModel.stopScan() }) { Text("Stop scan") }
            Spacer(Modifier.height(0.dp).then(Modifier))
            CircularProgressIndicator(modifier = Modifier.height(20.dp).padding(start = 12.dp))
        } else {
            Button(onClick = { viewModel.startScan() }) { Text("Scan for camera") }
        }
    }
    Spacer(Modifier.height(12.dp))

    if (state.devices.isEmpty() && !state.isScanning) {
        Text("No cameras yet. Enable Bluetooth on the GR III (set BLE to \"enable anytime\") and scan.")
    }

    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(state.devices) { device ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { viewModel.connect(device.address) }
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(device.name, fontWeight = FontWeight.Medium)
                    Text(device.address, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun ConnectingSection(connectionState: ConnectionState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.height(24.dp))
        Spacer(Modifier.height(0.dp))
        Text(
            when (connectionState) {
                ConnectionState.CONNECTING -> "  Connecting…"
                ConnectionState.DISCOVERING -> "  Discovering services…"
                else -> ""
            },
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun ConnectedSection(viewModel: MainViewModel, state: com.ricohgr3.app.ble.BleState) {
    val info = state.deviceInfo
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Connected ✓", fontWeight = FontWeight.Bold, color = androidx.compose.material3.MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            InfoRow("Manufacturer", info?.manufacturer)
            InfoRow("Model", info?.model)
            InfoRow("Firmware", info?.firmware)
            InfoRow("Serial", info?.serial)
        }
    }

    Spacer(Modifier.height(24.dp))

    if (state.shutterAvailable) {
        Button(
            onClick = { viewModel.fireShutter(af = true) },
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            Text("📸  Shutter (AF)", fontSize = 18.sp)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { viewModel.fireShutter(af = false) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Shutter (no AF)")
        }
        Spacer(Modifier.height(8.dp))
        Text("Shots fired this session: ${state.shutterCount}")
        state.lastShutterOk?.let {
            Text(if (it) "Last write: OK" else "Last write: FAILED")
        }
    } else {
        Text("Shooting service not found on this device.")
    }

    Spacer(Modifier.height(24.dp))
    Divider()
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = { viewModel.disconnect() }) { Text("Disconnect") }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 13.sp)
        Text(value ?: "…", fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}
