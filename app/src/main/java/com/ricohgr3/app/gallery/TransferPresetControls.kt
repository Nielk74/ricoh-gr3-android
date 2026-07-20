package com.ricohgr3.app.gallery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.ricohgr3.app.data.EditedExportQuality
import com.ricohgr3.app.looks.emulation.RenderingIntent
import com.ricohgr3.app.ui.LookStrip
import com.ricohgr3.app.ui.theme.GrTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/** Shared filter, render, intensity, and quality controls for both multi-photo workflows. */
@Composable
internal fun TransferPresetEditor(
    look: String?,
    intensity: Float,
    renderingIntent: RenderingIntent,
    quality: EditedExportQuality,
    onLookChange: (String?) -> Unit,
    onIntensityChange: (Float) -> Unit,
    onRenderingIntentChange: (RenderingIntent) -> Unit,
    onQualityChange: (EditedExportQuality) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "FILTER / LOOK",
            style = MaterialTheme.typography.labelSmall,
            color = GrTheme.colors.inkSoft,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LookStrip(
            selected = look,
            onSelect = { if (enabled) onLookChange(it) },
            modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f),
        )
        if (look != null) {
            RenderingIntentControl(
                value = renderingIntent,
                onValueChange = onRenderingIntentChange,
                enabled = enabled,
            )
            EffectStrengthControl(
                value = intensity,
                onValueChange = onIntensityChange,
                enabled = enabled,
            )
        }
        EditedExportQualityControl(
            value = quality,
            onValueChange = onQualityChange,
            enabled = enabled && look != null,
        )
        if (look == null) {
            Text(
                "Standard keeps the camera original untouched. Quality applies when a film look is selected.",
                style = MaterialTheme.typography.labelSmall,
                color = GrTheme.colors.inkSoft,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
            )
        }
    }
}

/** Explicit choice between a literal authored stock and bounded scene/subject protection. */
@Composable
internal fun RenderingIntentControl(
    value: RenderingIntent,
    onValueChange: (RenderingIntent) -> Unit,
    enabled: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .alpha(if (enabled) 1f else 0.5f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "RENDERING",
                style = MaterialTheme.typography.labelSmall,
                color = GrTheme.colors.inkSoft,
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            RenderingIntent.entries.forEach { intent ->
                TextButton(
                    onClick = { onValueChange(intent) },
                    enabled = enabled,
                ) {
                    Text(
                        intent.displayName.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (intent == value) {
                            GrTheme.colors.accent
                        } else {
                            GrTheme.colors.inkSoft
                        },
                    )
                }
            }
        }
        Text(
            text = if (value == RenderingIntent.STOCK) {
                "Authored stock core · no scene or subject correction"
            } else {
                "Stock response + bounded scene/subject protection · daylight warmth when eligible"
            },
            style = MaterialTheme.typography.labelSmall,
            color = GrTheme.colors.inkSoft,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

internal val RenderingIntent.displayName: String
    get() = if (this == RenderingIntent.STOCK) "Stock" else "Smart"

/** Explicit edited-copy resolution and JPEG choice; originals always remain untouched. */
@Composable
internal fun EditedExportQualityControl(
    value: EditedExportQuality,
    onValueChange: (EditedExportQuality) -> Unit,
    enabled: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .alpha(if (enabled) 1f else 0.5f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "EDITED EXPORT",
                style = MaterialTheme.typography.labelSmall,
                color = GrTheme.colors.inkSoft,
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            EditedExportQuality.entries.forEach { quality ->
                TextButton(
                    onClick = { onValueChange(quality) },
                    enabled = enabled,
                ) {
                    Text(
                        quality.displayName.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (quality == value) {
                            GrTheme.colors.accent
                        } else {
                            GrTheme.colors.inkSoft
                        },
                    )
                }
            }
        }
        Text(
            text = exportQualitySummary(value),
            style = MaterialTheme.typography.labelSmall,
            color = GrTheme.colors.inkSoft,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

internal fun exportQualitySummary(quality: EditedExportQuality): String =
    when (quality) {
        EditedExportQuality.COMPACT -> "Up to 1.5 MP · JPEG 92"
        EditedExportQuality.HIGH -> "Up to 6 MP · JPEG 97"
        EditedExportQuality.MAXIMUM ->
            "Highest resolution this phone can safely develop · JPEG 100"
    }

/** 50–150% stock intensity. 100% is authored; 5% ticks keep the choice deliberate. */
@Composable
internal fun EffectStrengthControl(
    value: Float,
    onValueChange: (Float) -> Unit,
    enabled: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .alpha(if (enabled) 1f else 0.5f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "EFFECT",
                style = MaterialTheme.typography.labelSmall,
                color = GrTheme.colors.inkSoft,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${(value * 100f).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = if (abs(value - 1f) < 0.01f) {
                    GrTheme.colors.inkSoft
                } else {
                    GrTheme.colors.accent
                },
            )
        }
        Slider(
            value = value.coerceIn(0.5f, 1.5f),
            onValueChange = { raw ->
                onValueChange((raw * 20f).roundToInt() / 20f)
            },
            enabled = enabled,
            valueRange = 0.5f..1.5f,
            steps = 19,
            colors = SliderDefaults.colors(
                thumbColor = GrTheme.colors.accent,
                activeTrackColor = GrTheme.colors.accent,
                inactiveTrackColor = GrTheme.colors.hair,
                activeTickColor = GrTheme.colors.paper,
                inactiveTickColor = GrTheme.colors.inkSoft,
            ),
            modifier = Modifier.fillMaxWidth().height(34.dp),
        )
    }
}
