package com.ricohgr3.app.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ricohgr3.app.data.EditedExportQuality
import com.ricohgr3.app.looks.emulation.FilmLookCatalog
import com.ricohgr3.app.looks.emulation.RenderingIntent
import com.ricohgr3.app.ui.theme.GrTheme
import kotlin.math.roundToInt

/**
 * Preset-first import flow. The settings remain editable until Start is pressed; from then on the
 * frozen [TransferPreset] and explicit transfer progress replace the setup controls.
 */
@Composable
fun AutoImportScreen(
    settings: GalleryUiState,
    transfer: TransferUiState,
    onLookChange: (String?) -> Unit,
    onIntensityChange: (Float) -> Unit,
    onRenderingIntentChange: (RenderingIntent) -> Unit,
    onQualityChange: (EditedExportQuality) -> Unit,
    onStart: (TransferPreset) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(GrTheme.colors.paper)) {
        AutoImportHeader(
            transferActive = transfer.isActive,
            onBack = onBack,
        )
        HorizontalDivider(color = GrTheme.colors.hair)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 20.dp),
        ) {
            if (transfer.phase == TransferPhase.IDLE) {
                AutoImportIntro()
                Spacer(Modifier.height(20.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .border(1.dp, GrTheme.colors.hair, RoundedCornerShape(18.dp))
                        .background(GrTheme.colors.paper)
                        .padding(vertical = 10.dp),
                ) {
                    PresetHeading(settings)
                    TransferPresetEditor(
                        look = settings.stickyLook,
                        intensity = settings.stickyIntensity,
                        renderingIntent = settings.stickyRenderingIntent,
                        quality = settings.editedExportQuality,
                        onLookChange = onLookChange,
                        onIntensityChange = onIntensityChange,
                        onRenderingIntentChange = onRenderingIntentChange,
                        onQualityChange = onQualityChange,
                    )
                }
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = {
                        onStart(
                            TransferPreset(
                                look = settings.stickyLook,
                                intensity = settings.stickyIntensity,
                                renderingIntent = settings.stickyRenderingIntent,
                                quality = settings.editedExportQuality,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GrTheme.colors.accent),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        if (settings.stickyLook == null) {
                            "Import camera originals"
                        } else {
                            "Import all with ${FilmLookCatalog.displayNameFor(settings.stickyLook)}"
                        },
                        color = GrTheme.colors.paper,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Every frame is fetched at full camera resolution. When memory allows, the next " +
                        "download overlaps the current save; starting again creates another copy.",
                    style = MaterialTheme.typography.labelSmall,
                    color = GrTheme.colors.inkSoft,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            } else {
                TransferProgressPanel(
                    state = transfer,
                    onCancel = onCancel,
                    onRetry = onRetry,
                    onDismiss = onDismiss,
                    dismissLabel = "Done",
                )
                if (transfer.isActive) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "You can leave this page. The transfer continues while the app remains open.",
                        style = MaterialTheme.typography.labelSmall,
                        color = GrTheme.colors.inkSoft,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AutoImportHeader(transferActive: Boolean, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "AUTO IMPORT",
                style = MaterialTheme.typography.labelSmall,
                color = GrTheme.colors.inkSoft,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (transferActive) "Transfer in progress" else "Camera → phone",
                style = MaterialTheme.typography.bodyMedium,
                color = GrTheme.colors.ink,
            )
        }
        TextButton(onClick = onBack) {
            Text(if (transferActive) "Close" else "Back", color = GrTheme.colors.inkSoft)
        }
    }
}

@Composable
private fun AutoImportIntro() {
    Text(
        "ONE SETUP · THE WHOLE ROLL",
        style = MaterialTheme.typography.labelSmall,
        color = GrTheme.colors.accent,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Set the finish once.\nBring every frame home.",
        style = MaterialTheme.typography.headlineMedium,
        color = GrTheme.colors.ink,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        "Choose an untouched original or a developed film look. The app then reads, develops, " +
            "and saves each camera frame with live progress.",
        style = MaterialTheme.typography.bodyMedium,
        color = GrTheme.colors.inkSoft,
    )
    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlowStep("01", "FETCH", Modifier.weight(1f))
        Text("→", color = GrTheme.colors.grey)
        FlowStep("02", "DEVELOP", Modifier.weight(1f))
        Text("→", color = GrTheme.colors.grey)
        FlowStep("03", "SAVE", Modifier.weight(1f))
    }
}

@Composable
private fun FlowStep(number: String, label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(GrTheme.colors.paperEdge)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(number, style = MaterialTheme.typography.labelSmall, color = GrTheme.colors.accent)
        Spacer(Modifier.width(7.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = GrTheme.colors.ink)
    }
}

@Composable
private fun PresetHeading(settings: GalleryUiState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(GrTheme.colors.accent),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("IMPORT PRESET", style = MaterialTheme.typography.labelSmall, color = GrTheme.colors.inkSoft)
            Text(
                if (settings.stickyLook == null) {
                    "Original files"
                } else {
                    "${FilmLookCatalog.displayNameFor(settings.stickyLook)} · " +
                        "${(settings.stickyIntensity * 100f).roundToInt()}%"
                },
                style = MaterialTheme.typography.titleMedium,
                color = GrTheme.colors.ink,
            )
        }
        if (settings.stickyLook != null) {
            Text(
                settings.editedExportQuality.displayName.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = GrTheme.colors.accent,
            )
        }
    }
}
