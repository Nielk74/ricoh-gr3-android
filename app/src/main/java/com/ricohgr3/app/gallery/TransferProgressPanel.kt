package com.ricohgr3.app.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ricohgr3.app.ui.theme.GrTheme

/** A determinate, readable transfer panel shared by auto-import and selected-photo saves. */
@Composable
internal fun TransferProgressPanel(
    state: TransferUiState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String = "Done",
) {
    if (state.phase == TransferPhase.IDLE) return

    val copy = transferProgressCopy(state)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, GrTheme.colors.hair, RoundedCornerShape(18.dp))
            .background(GrTheme.colors.paper)
            .padding(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                copy.eyebrow,
                style = MaterialTheme.typography.labelSmall,
                color = when (state.phase) {
                    TransferPhase.COMPLETED -> GrTheme.colors.good
                    TransferPhase.FAILED -> GrTheme.colors.accent
                    else -> GrTheme.colors.inkSoft
                },
                modifier = Modifier.weight(1f),
            )
            if (state.phase == TransferPhase.TRANSFERRING && state.total > 0) {
                Text(
                    "${state.completed} / ${state.total}",
                    style = MaterialTheme.typography.labelSmall,
                    color = GrTheme.colors.ink,
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            copy.title,
            style = MaterialTheme.typography.headlineMedium,
            color = GrTheme.colors.ink,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            copy.detail,
            style = MaterialTheme.typography.bodyMedium,
            color = GrTheme.colors.inkSoft,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(16.dp))

        if (state.phase == TransferPhase.SCANNING) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = GrTheme.colors.accent,
                trackColor = GrTheme.colors.paperEdge,
            )
        } else {
            val progressColor =
                if (state.phase == TransferPhase.COMPLETED && state.failures.isEmpty()) {
                    GrTheme.colors.good
                } else {
                    GrTheme.colors.accent
                }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(GrTheme.colors.paperEdge),
            ) {
                if (state.progress > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(state.progress)
                            .background(progressColor),
                    )
                }
            }
        }

        if (state.total > 0) {
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProgressStat("SAVED", state.saved, Modifier.weight(1f))
                ProgressStat("FAILED", state.failures.size, Modifier.weight(1f))
                ProgressStat(
                    "LEFT",
                    (state.total - state.completed).coerceAtLeast(0),
                    Modifier.weight(1f),
                )
            }
        }

        if (state.failures.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            state.failures.take(3).forEach { failure ->
                Text(
                    "${failure.id.file} · ${failure.reason}",
                    style = MaterialTheme.typography.labelSmall,
                    color = GrTheme.colors.accent,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 3.dp),
                )
            }
            if (state.failures.size > 3) {
                Text(
                    "+ ${state.failures.size - 3} more",
                    style = MaterialTheme.typography.labelSmall,
                    color = GrTheme.colors.inkSoft,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        when {
            state.isActive -> {
                TextButton(
                    onClick = onCancel,
                    enabled = !state.stopRequested,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(
                        if (state.stopRequested) "Pausing…" else "Pause transfer",
                        color = GrTheme.colors.inkSoft,
                    )
                }
            }

            state.isTerminal -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val canRetry = state.failures.isNotEmpty() ||
                        (state.phase == TransferPhase.FAILED &&
                            state.source == TransferSource.AUTO_IMPORT) ||
                        (state.phase == TransferPhase.CANCELLED && state.completed < state.total)
                    if (canRetry) {
                        OutlinedButton(
                            onClick = onRetry,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                when {
                                    state.phase == TransferPhase.CANCELLED -> "Continue"
                                    state.failures.isNotEmpty() -> "Retry failed"
                                    else -> "Try again"
                                },
                                color = GrTheme.colors.ink,
                            )
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = GrTheme.colors.accent),
                    ) {
                        Text(dismissLabel, color = GrTheme.colors.paper)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressStat(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(GrTheme.colors.paperEdge)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = GrTheme.colors.inkSoft)
        Spacer(Modifier.height(2.dp))
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = GrTheme.colors.ink,
        )
    }
}

private data class TransferProgressCopy(
    val eyebrow: String,
    val title: String,
    val detail: String,
)

private fun transferProgressCopy(state: TransferUiState): TransferProgressCopy =
    when (state.phase) {
        TransferPhase.IDLE -> TransferProgressCopy("READY", "Ready", "Choose your settings")
        TransferPhase.SCANNING -> TransferProgressCopy(
            "STEP 1 OF 2 · READING CAMERA",
            "Finding your frames",
            "Reading the camera roll before the first save begins.",
        )
        TransferPhase.TRANSFERRING -> TransferProgressCopy(
            if (state.source == TransferSource.AUTO_IMPORT) {
                "STEP 2 OF 2 · AUTO IMPORT"
            } else {
                "SAVING SELECTION"
            },
            if (state.stopRequested) {
                "Pausing safely"
            } else {
                "Frame ${state.currentNumber} of ${state.total}"
            },
            if (state.stopRequested) {
                "Finishing the current frame before pausing."
            } else {
                state.current?.file ?: "Preparing the next frame…"
            },
        )
        TransferPhase.COMPLETED -> {
            val title = when {
                state.total == 0 -> "No frames to import"
                state.failures.isEmpty() -> "${state.saved} ${photoWord(state.saved)} saved"
                else -> "${state.saved} saved · ${state.failures.size} failed"
            }
            TransferProgressCopy(
                if (state.failures.isEmpty()) "TRANSFER COMPLETE" else "TRANSFER FINISHED",
                title,
                state.message ?: if (state.failures.isEmpty()) {
                    "Your photos are ready in Pictures/GR3."
                } else {
                    "Successful photos are in Pictures/GR3. You can retry only the failed frames."
                },
            )
        }
        TransferPhase.CANCELLED -> TransferProgressCopy(
            "TRANSFER PAUSED",
            "${state.saved} ${photoWord(state.saved)} saved",
            state.message ?: "Completed photos are safe. Continue whenever you're ready.",
        )
        TransferPhase.FAILED -> TransferProgressCopy(
            "TRANSFER NEEDS ATTENTION",
            "Couldn't start the transfer",
            state.message ?: "Check the camera connection and try again.",
        )
    }

private fun photoWord(count: Int): String = if (count == 1) "photo" else "photos"
