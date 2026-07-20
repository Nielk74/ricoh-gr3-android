package com.ricohgr3.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ricohgr3.app.Transport
import com.ricohgr3.app.ble.BleState
import com.ricohgr3.app.ble.ConnectionState
import com.ricohgr3.app.ui.theme.GrTheme
import com.ricohgr3.app.wifi.CameraWifiSession

/**
 * The connect flow. The GR III's BLE and Wi-Fi planes are mutually exclusive, so the user first
 * chooses a [Transport]:
 *  - **Bluetooth** — pair over BLE for the remote shutter + basic control.
 *  - **Wi-Fi** — turn Wi-Fi on at the camera, join its AP (credentials cached from a prior BLE
 *    pairing), then live view / gallery / transfer over HTTP.
 * "Change" returns to the chooser, disconnecting the active transport.
 *
 * Pure presentation over [BleState] + the optional [CameraWifiSession.State]; all actions are
 * hoisted callbacks so this stays testable and free of ViewModel/Android coupling.
 */
@Composable
fun ConnectScreen(
    ble: BleState,
    wifi: CameraWifiSession.State?,
    transport: Transport?,
    hasCachedCreds: Boolean,
    permissionsGranted: Boolean,
    wifiSupported: Boolean,
    onRequestPermissions: () -> Unit,
    onSelectBluetooth: () -> Unit,
    onSelectWifi: () -> Unit,
    onChangeTransport: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnectDevice: (String) -> Unit,
    onStartWifiHandoff: () -> Unit,
    onRetryWifi: () -> Unit,
    onUseCurrentWifi: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenAutoImport: () -> Unit,
    onOpenLiveView: () -> Unit,
    onOpenAppUpdate: () -> Unit,
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "GR",
                    style = MaterialTheme.typography.displaySmall,
                    color = GrTheme.colors.accent,
                )
                Text(
                    "REMOTE",
                    style = MaterialTheme.typography.labelSmall,
                    color = GrTheme.colors.inkSoft,
                )
            }
            OutlinedButton(
                onClick = onOpenAppUpdate,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.SystemUpdate,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(7.dp))
                Text("App update", style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(20.dp))

        if (!permissionsGranted) {
            PermissionGate(onRequestPermissions)
            return@Column
        }

        // Step 1: choose a transport. BLE and Wi-Fi are mutually exclusive on the GR III.
        if (transport == null) {
            TransportChooser(
                wifiSupported = wifiSupported,
                hasCachedCreds = hasCachedCreds,
                onSelectBluetooth = onSelectBluetooth,
                onSelectWifi = onSelectWifi,
            )
            return@Column
        }

        // The active mode, with a way back to the chooser.
        TransportHeader(transport = transport, onChangeTransport = onChangeTransport)
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = GrTheme.colors.hair)
        Spacer(Modifier.height(20.dp))

        when (transport) {
            Transport.BLUETOOTH -> BluetoothMode(
                ble = ble,
                onStartScan = onStartScan,
                onStopScan = onStopScan,
                onConnectDevice = onConnectDevice,
                onDisconnect = onDisconnect,
                onFireShutter = onFireShutter,
            )

            Transport.WIFI -> WifiMode(
                wifi = wifi,
                ble = ble,
                hasCachedCreds = hasCachedCreds,
                onStartWifiHandoff = onStartWifiHandoff,
                onRetryWifi = onRetryWifi,
                onUseCurrentWifi = onUseCurrentWifi,
                onOpenGallery = onOpenGallery,
                onOpenAutoImport = onOpenAutoImport,
                onOpenLiveView = onOpenLiveView,
                onDisconnect = onDisconnect,
            )
        }

        ble.error?.let {
            Spacer(Modifier.weight(1f))
            Text(
                "⚠ $it",
                style = MaterialTheme.typography.bodyMedium,
                color = GrTheme.colors.accent,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }
}

/** The two mutually-exclusive transports, presented as a first choice. */
@Composable
private fun TransportChooser(
    wifiSupported: Boolean,
    hasCachedCreds: Boolean,
    onSelectBluetooth: () -> Unit,
    onSelectWifi: () -> Unit,
) {
    Text(
        "How do you want to connect?",
        style = MaterialTheme.typography.titleMedium,
        color = GrTheme.colors.ink,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "The GR III uses Bluetooth or Wi-Fi — one at a time.",
        style = MaterialTheme.typography.bodyMedium,
        color = GrTheme.colors.inkSoft,
    )
    Spacer(Modifier.height(20.dp))

    TransportCard(
        title = "Bluetooth",
        subtitle = "Remote shutter + basic control. Low power, no live view.",
        onClick = onSelectBluetooth,
    )
    Spacer(Modifier.height(12.dp))
    TransportCard(
        title = "Wi-Fi",
        subtitle = if (wifiSupported) {
            "Live view, gallery & photo transfer. Turn Wi-Fi on at the camera." +
                if (hasCachedCreds) " Network remembered." else ""
        } else {
            "Needs Android 10+ — unavailable on this device."
        },
        enabled = wifiSupported,
        onClick = onSelectWifi,
    )
}

@Composable
private fun TransportCard(
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.5f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, GrTheme.colors.hair, RoundedCornerShape(12.dp))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(18.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = GrTheme.colors.ink.copy(alpha = alpha),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = GrTheme.colors.inkSoft.copy(alpha = alpha),
        )
    }
}

@Composable
private fun TransportHeader(transport: Transport, onChangeTransport: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (transport == Transport.BLUETOOTH) "Bluetooth" else "Wi-Fi",
            style = MaterialTheme.typography.titleMedium,
            color = GrTheme.colors.ink,
            modifier = Modifier.weight(1f),
        )
        Text(
            "Change",
            style = MaterialTheme.typography.labelSmall,
            color = GrTheme.colors.accent,
            modifier = Modifier.clickable { onChangeTransport() }.padding(8.dp),
        )
    }
}

/** Bluetooth transport: scan → pair → remote shutter. */
@Composable
private fun BluetoothMode(
    ble: BleState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnectDevice: (String) -> Unit,
    onDisconnect: () -> Unit,
    onFireShutter: (Boolean) -> Unit,
) {
    val bleConnected = ble.connectionState == ConnectionState.CONNECTED
    Column(modifier = Modifier.fillMaxSize()) {
        when (ble.connectionState) {
            ConnectionState.DISCONNECTED ->
                ScanSection(ble, onStartScan, onStopScan, onConnectDevice)

            ConnectionState.CONNECTING, ConnectionState.DISCOVERING ->
                BusyRow("Pairing over Bluetooth…")

            ConnectionState.CONNECTED -> {
                Text(
                    ble.deviceInfo?.model ?: "Connected",
                    style = MaterialTheme.typography.titleMedium,
                    color = GrTheme.colors.good,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Remote shutter ready. Switch to Wi-Fi for live view & transfer.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GrTheme.colors.inkSoft,
                )
            }
        }

        if (bleConnected && ble.shutterAvailable) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onFireShutter(true) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GrTheme.colors.accent),
            ) {
                Text(
                    "Shutter" + if (ble.shutterCount > 0) "  ·  ${ble.shutterCount}" else "",
                    color = GrTheme.colors.paper,
                )
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
    }
}

/** Wi-Fi transport: enable Wi-Fi on the camera → join AP → Library / Live View. */
@Composable
private fun WifiMode(
    wifi: CameraWifiSession.State?,
    ble: BleState,
    hasCachedCreds: Boolean,
    onStartWifiHandoff: () -> Unit,
    onRetryWifi: () -> Unit,
    onUseCurrentWifi: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenAutoImport: () -> Unit,
    onOpenLiveView: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (wifi is CameraWifiSession.State.Connected) {
            ReadySection(
                onOpenGallery = onOpenGallery,
                onOpenAutoImport = onOpenAutoImport,
                onOpenLiveView = onOpenLiveView,
            )
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            ) {
                Text("Disconnect Wi-Fi", color = GrTheme.colors.inkSoft)
            }
        } else {
            WifiHandoffSection(
                ble = ble,
                wifi = wifi,
                hasCachedCreds = hasCachedCreds,
                onStartWifiHandoff = onStartWifiHandoff,
                onRetryWifi = onRetryWifi,
                onUseCurrentWifi = onUseCurrentWifi,
            )
        }
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
    hasCachedCreds: Boolean,
    onStartWifiHandoff: () -> Unit,
    onRetryWifi: () -> Unit,
    onUseCurrentWifi: () -> Unit,
) {
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
            Text(
                "Make sure Wi-Fi is turned on at the camera (and, if you joined it yourself in " +
                    "Android Settings, that it's still connected), then retry.",
                style = MaterialTheme.typography.labelSmall,
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
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onUseCurrentWifi,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text("Use current Wi-Fi", color = GrTheme.colors.ink)
            }
        }

        // Ready to join: instruct the user to enable Wi-Fi on the camera, then join its AP.
        // (The camera won't accept an over-BLE "wake Wi-Fi" command on shipping firmware —
        // see research/BLE_WIFI_WAKE_INVESTIGATION.md — so activation is manual on the body.)
        else -> {
            Text(
                "Turn Wi-Fi on from the camera",
                style = MaterialTheme.typography.titleMedium,
                color = GrTheme.colors.ink,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "On the GR III: press the Wireless button (or Playback → transfer) until the " +
                    "Wi-Fi icon lights up. Then tap Join below.",
                style = MaterialTheme.typography.bodyMedium,
                color = GrTheme.colors.inkSoft,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Tip: unplug the camera from any computer first — the GR III won't turn " +
                    "Wi-Fi on while it's connected over USB.",
                style = MaterialTheme.typography.labelSmall,
                color = GrTheme.colors.accent,
            )
            val canAutoJoin = hasCachedCreds || ble.wlanCredentials != null
            ble.wlanCredentials?.ssid?.takeIf { it.isNotBlank() }?.let { ssid ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "Camera network: $ssid",
                    style = MaterialTheme.typography.labelSmall,
                    color = GrTheme.colors.inkSoft,
                )
            } ?: if (!hasCachedCreds) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "No saved network yet — either connect your phone to the camera's Wi-Fi in " +
                        "Android Settings and tap \"Use current Wi-Fi\", or pair once over " +
                        "Bluetooth so the app can read the network name/password and join for you.",
                    style = MaterialTheme.typography.labelSmall,
                    color = GrTheme.colors.accent,
                )
            } else Unit

            // Primary path: app joins the AP itself using known credentials (BLE-read or cached).
            if (canAutoJoin) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onStartWifiHandoff,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GrTheme.colors.accent),
                ) {
                    Text("Join camera Wi-Fi", color = GrTheme.colors.paper)
                }
            }

            // Always-available escape hatch: the phone may already be on the camera Wi-Fi (joined
            // manually in Android Settings), in which case no credentials/BLE are needed at all.
            Spacer(Modifier.height(if (canAutoJoin) 8.dp else 12.dp))
            OutlinedButton(
                onClick = onUseCurrentWifi,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(
                    if (canAutoJoin) "Already connected? Use current Wi-Fi" else "Use current Wi-Fi",
                    color = GrTheme.colors.ink,
                )
            }
        }
    }
}

@Composable
private fun ReadySection(
    onOpenGallery: () -> Unit,
    onOpenAutoImport: () -> Unit,
    onOpenLiveView: () -> Unit,
) {
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
    OutlinedButton(onClick = onOpenAutoImport, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("Auto import", color = GrTheme.colors.accent)
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
