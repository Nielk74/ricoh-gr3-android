package com.ricohgr3.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ricohgr3.app.ble.BleState
import com.ricohgr3.app.ble.ConnectionState
import com.ricohgr3.app.ui.theme.GrTheme
import com.ricohgr3.app.wifi.CameraWifiSession

/**
 * A step in the connect flow.
 */
enum class StepStatus { PENDING, ACTIVE, DONE, FAILED }

/**
 * The unified BLE → Wi-Fi connect flow (Concept A). Drives the MVP happy path as an explicit,
 * legible sequence — pair over BLE, wake + join the camera Wi-Fi — then surfaces entries to the
 * Gallery (photo offload/edit) and Live View once the camera plane is up.
 *
 * Pure presentation over [BleState] + the optional [CameraWifiSession.State]; all actions are
 * hoisted callbacks so this stays testable and free of ViewModel/Android coupling.
 */
@Composable
fun ConnectScreen(
    ble: BleState,
    wifi: CameraWifiSession.State?,
    permissionsGranted: Boolean,
    wifiSupported: Boolean,
    onRequestPermissions: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnectDevice: (String) -> Unit,
    onStartWifiHandoff: () -> Unit,
    onRetryWifi: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenLiveView: () -> Unit,
    onFireShutter: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(GrTheme.colors.paper)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("GR", style = MaterialTheme.typography.displaySmall, color = GrTheme.colors.accent)
        Text(
            "REMOTE",
            style = MaterialTheme.typography.labelSmall,
            color = GrTheme.colors.inkSoft,
        )
        Spacer(Modifier.height(20.dp))

        if (!permissionsGranted) {
            PermissionGate(onRequestPermissions)
            return@Column
        }

        val bleConnected = ble.connectionState == ConnectionState.CONNECTED
        val wifiConnected = wifi is CameraWifiSession.State.Connected

        // The step ladder — always visible so the user can see where they are.
        ConnectSteps(ble = ble, wifi = wifi, wifiSupported = wifiSupported)

        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = GrTheme.colors.hair)
        Spacer(Modifier.height(20.dp))

        // The single contextual action for the current step.
        when {
            ble.connectionState == ConnectionState.DISCONNECTED ->
                ScanSection(ble, onStartScan, onStopScan, onConnectDevice)

            ble.connectionState == ConnectionState.CONNECTING ||
                ble.connectionState == ConnectionState.DISCOVERING ->
                BusyRow("Pairing over Bluetooth…")

            bleConnected && !wifiConnected ->
                WifiHandoffSection(
                    ble = ble,
                    wifi = wifi,
                    wifiSupported = wifiSupported,
                    onStartWifiHandoff = onStartWifiHandoff,
                    onRetryWifi = onRetryWifi,
                )

            wifiConnected -> ReadySection(
                onOpenGallery = onOpenGallery,
                onOpenLiveView = onOpenLiveView,
            )
        }

        // BLE shutter stays available the moment BLE is up (works without Wi-Fi).
        if (bleConnected && ble.shutterAvailable) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { onFireShutter(true) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("BLE shutter" + if (ble.shutterCount > 0) "  ·  ${ble.shutterCount}" else "")
            }
        }

        Spacer(Modifier.weight(1f))

        if (bleConnected) {
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            ) {
                Text("Disconnect", color = GrTheme.colors.inkSoft)
            }
        }

        ble.error?.let {
            Text(
                "⚠ $it",
                style = MaterialTheme.typography.bodyMedium,
                color = GrTheme.colors.accent,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun ConnectSteps(ble: BleState, wifi: CameraWifiSession.State?, wifiSupported: Boolean) {
    val bleConnected = ble.connectionState == ConnectionState.CONNECTED
    val bleBusy = ble.connectionState == ConnectionState.CONNECTING ||
        ble.connectionState == ConnectionState.DISCOVERING

    val bleStatus = when {
        bleConnected -> StepStatus.DONE
        bleBusy -> StepStatus.ACTIVE
        else -> StepStatus.PENDING
    }

    val hasCreds = ble.wlanCredentials != null
    val wifiStatus = when {
        wifi is CameraWifiSession.State.Connected -> StepStatus.DONE
        wifi is CameraWifiSession.State.Failed || wifi is CameraWifiSession.State.Lost -> StepStatus.FAILED
        wifi is CameraWifiSession.State.Joining || ble.wifiEnabling -> StepStatus.ACTIVE
        bleConnected && hasCreds -> StepStatus.ACTIVE
        else -> StepStatus.PENDING
    }

    Column {
        StepRow(1, "Pair over Bluetooth", bleStatus, detail = ble.deviceInfo?.model)
        StepRow(
            2,
            if (wifiSupported) "Wake + join camera Wi-Fi" else "Camera Wi-Fi (needs Android 10+)",
            if (wifiSupported) wifiStatus else StepStatus.PENDING,
            detail = wifi.detailText(),
        )
    }
}

@Composable
private fun StepRow(index: Int, title: String, status: StepStatus, detail: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepBadge(index, status)
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (status == StepStatus.PENDING) GrTheme.colors.inkSoft else GrTheme.colors.ink,
            )
            detail?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = GrTheme.colors.inkSoft)
            }
        }
        if (status == StepStatus.ACTIVE) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = GrTheme.colors.accent,
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun StepBadge(index: Int, status: StepStatus) {
    val (bg, fg) = when (status) {
        StepStatus.DONE -> GrTheme.colors.accent to GrTheme.colors.paper
        StepStatus.ACTIVE -> GrTheme.colors.accentWash to GrTheme.colors.accent
        StepStatus.FAILED -> Color.Transparent to GrTheme.colors.accent
        StepStatus.PENDING -> Color.Transparent to GrTheme.colors.inkSoft
    }
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(bg)
            .border(
                width = if (status == StepStatus.DONE) 0.dp else 1.dp,
                color = fg,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            when (status) {
                StepStatus.DONE -> "✓"
                StepStatus.FAILED -> "!"
                else -> index.toString()
            },
            color = fg,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun ScanSection(
    ble: BleState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnectDevice: (String) -> Unit,
) {
    if (ble.isScanning) {
        BusyRow("Scanning for camera…")
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onStopScan, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
    } else {
        Button(
            onClick = onStartScan,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GrTheme.colors.accent),
        ) {
            Text("Scan for camera", color = GrTheme.colors.paper)
        }
    }

    if (ble.devices.isEmpty() && !ble.isScanning) {
        Spacer(Modifier.height(12.dp))
        Text(
            "No cameras yet. On the GR III set Bluetooth to \"enable anytime\", then scan.",
            style = MaterialTheme.typography.bodyMedium,
            color = GrTheme.colors.inkSoft,
        )
    }

    LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        items(ble.devices) { device ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(0.5.dp, GrTheme.colors.hair, RoundedCornerShape(8.dp))
                    .clickable { onConnectDevice(device.address) }
                    .padding(14.dp),
            ) {
                Column {
                    Text(device.name, style = MaterialTheme.typography.titleMedium, color = GrTheme.colors.ink)
                    Text(device.address, style = MaterialTheme.typography.labelSmall, color = GrTheme.colors.inkSoft)
                }
            }
        }
    }
}

@Composable
private fun WifiHandoffSection(
    ble: BleState,
    wifi: CameraWifiSession.State?,
    wifiSupported: Boolean,
    onStartWifiHandoff: () -> Unit,
    onRetryWifi: () -> Unit,
) {
    if (!wifiSupported) {
        Text(
            "This device is below Android 10, so the app can't join the camera's Wi-Fi. " +
                "BLE shutter still works.",
            style = MaterialTheme.typography.bodyMedium,
            color = GrTheme.colors.inkSoft,
        )
        return
    }

    when {
        wifi is CameraWifiSession.State.Joining ->
            BusyRow("Joining camera Wi-Fi — accept the system prompt…")

        wifi is CameraWifiSession.State.Failed || wifi is CameraWifiSession.State.Lost -> {
            Text(
                if (wifi is CameraWifiSession.State.Lost) "Camera Wi-Fi dropped." else "Couldn't join the camera Wi-Fi.",
                style = MaterialTheme.typography.bodyMedium,
                color = GrTheme.colors.inkSoft,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onRetryWifi,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GrTheme.colors.accent),
            ) {
                Text("Retry Wi-Fi", color = GrTheme.colors.paper)
            }
        }

        ble.wifiEnabling -> BusyRow("Waking camera Wi-Fi…")

        else -> Button(
            onClick = onStartWifiHandoff,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GrTheme.colors.accent),
        ) {
            Text("Wake + join Wi-Fi", color = GrTheme.colors.paper)
        }
    }
}

@Composable
private fun ReadySection(onOpenGallery: () -> Unit, onOpenLiveView: () -> Unit) {
    Text(
        "Camera ready",
        style = MaterialTheme.typography.titleMedium,
        color = GrTheme.colors.good,
    )
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = onOpenGallery,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = GrTheme.colors.accent),
    ) {
        Text("Library", color = GrTheme.colors.paper, style = MaterialTheme.typography.titleMedium)
    }
    Spacer(Modifier.height(10.dp))
    OutlinedButton(onClick = onOpenLiveView, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("Live view", color = GrTheme.colors.ink)
    }
}

@Composable
private fun PermissionGate(onRequestPermissions: () -> Unit) {
    Column {
        Text(
            "Bluetooth permission is required to find and pair the camera.",
            style = MaterialTheme.typography.bodyLarge,
            color = GrTheme.colors.ink,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onRequestPermissions,
            colors = ButtonDefaults.buttonColors(containerColor = GrTheme.colors.accent),
        ) {
            Text("Grant Bluetooth permission", color = GrTheme.colors.paper)
        }
    }
}

@Composable
private fun BusyRow(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = GrTheme.colors.accent,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.size(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = GrTheme.colors.ink)
    }
}

/** Short human label for the Wi-Fi step's current state, or null when there's nothing to say. */
private fun CameraWifiSession.State?.detailText(): String? = when (this) {
    is CameraWifiSession.State.Connected -> "192.168.0.1"
    is CameraWifiSession.State.Joining -> "joining…"
    is CameraWifiSession.State.Failed -> "join failed"
    is CameraWifiSession.State.Lost -> "connection lost"
    else -> null
}
