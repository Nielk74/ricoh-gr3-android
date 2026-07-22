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
            if (state.isActive && state.total > 0) {
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
        } else if (state.total > 0) {
            val progressColor =
                if (state.phase == TransferPhase.COMPLETED && state.failures.isEmpty()) {
                    GrTheme.colors.good
                } else {
                    GrTheme.colors.accent
                }
            TransferProgressTrack(
                label = if (state.diskBacked) "DOWNLOADS" else "CAMERA",
                value = state.downloadProgress,
                count = "${state.downloadCompleted} / " +
                    "${state.downloadTotal.takeIf { it > 0 } ?: state.total}",
                color = progressColor,
            )
            Spacer(Modifier.height(9.dp))
            TransferProgressTrack(
                label = "FINISHED",
                value = state.saveProgress,
                count = "${state.completed} / ${state.total}",
                color = progressColor,
            )
            Spacer(Modifier.height(9.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (state.diskBacked) {
                        "FULL-SIZE · DISK QUEUE"
                    } else if (state.pipelineDepth > 1) {
                        "FULL-SIZE · PIPELINED"
                    } else {
                        "FULL-SIZE · MEMORY-SAFE"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = GrTheme.colors.inkSoft,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                state.heapHeadroomMb?.let { headroom ->
                    Text(
                        "MEMORY BUDGET $headroom MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = GrTheme.colors.inkSoft,
                        maxLines = 1,
                    )
                }
            }

            if (state.isActive) {
                val download = state.downloading
                val processing = state.processing
                if (download != null || processing != null) {
                    Spacer(Modifier.height(11.dp))
                    download?.let {
                        TransferActivityLine(
                            label = "DOWNLOADING",
                            file = it.file,
                            suffix = downloadAmount(state),
                        )
                    }
                    processing?.let {
                        TransferActivityLine(
                            label = when (state.workStage) {
                                TransferWorkStage.PREPARING -> "PREPARING"
                                TransferWorkStage.DEVELOPING -> "DEVELOPING"
                                TransferWorkStage.SAVING -> "SAVING"
                                null -> "PREPARING"
                            },
                            file = it.file,
                            suffix = if (state.processingParts > 0) {
                                "${state.processingPart}/${state.processingParts}"
                            } else {
                                null
                            },
                        )
                    }
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
private fun TransferProgressTrack(
    label: String,
    value: Float,
    count: String,
    color: androidx.compose.ui.graphics.Color,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = GrTheme.colors.inkSoft,
            modifier = Modifier.weight(1f),
        )
        Text(count, style = MaterialTheme.typography.labelSmall, color = GrTheme.colors.ink)
    }
    Spacer(Modifier.height(4.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(GrTheme.colors.paperEdge),
    ) {
        if (value > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(value.coerceIn(0f, 1f))
                    .background(color),
            )
        }
    }
}

@Composable
private fun TransferActivityLine(label: String, file: String, suffix: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = GrTheme.colors.accent,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            file,
            style = MaterialTheme.typography.labelSmall,
            color = GrTheme.colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        suffix?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = GrTheme.colors.inkSoft)
        }
    }
}

private fun downloadAmount(state: TransferUiState): String? {
    val total = state.downloadTotalBytes?.takeIf { it > 0L }
    if (total != null) {
        return "${((state.downloadBytes * 100L) / total).coerceIn(0L, 100L)}%"
    }
    if (state.downloadBytes <= 0L) return null
    val wholeMib = state.downloadBytes / BYTES_PER_MIB
    val tenth = ((state.downloadBytes % BYTES_PER_MIB) * 10L) / BYTES_PER_MIB
    return "$wholeMib.$tenth MB"
}

private const val BYTES_PER_MIB = 1024L * 1024L

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
            "PREPARING BULK IMPORT",
            "Finding your frames",
            state.message ?: "Pairing JPEG/DNG exposures and checking storage before download.",
        )
        TransferPhase.DOWNLOADING -> TransferProgressCopy(
            "STEP 1 OF 2 · BULK DOWNLOAD",
            if (state.stopRequested) {
                "Pausing safely"
            } else {
                "Downloading file ${state.downloadingNumber.coerceAtLeast(1)} of ${state.downloadTotal}"
            },
            if (state.stopRequested) {
                "Finishing the active camera file; completed downloads stay safely on disk."
            } else {
                state.downloading?.file ?: "Preparing the next camera file…"
            },
        )
        TransferPhase.PROCESSING -> TransferProgressCopy(
            "STEP 2 OF 2 · LOCAL PROCESSING",
            if (state.stopRequested) {
                "Pausing safely"
            } else if (state.workStage == TransferWorkStage.DEVELOPING) {
                "Developing output ${state.processingNumber} of ${state.total}"
            } else {
                "Saving output ${state.processingNumber} of ${state.total}"
            },
            when {
                state.stopRequested -> "Finishing the current safe region before pausing."
                state.processingParts > 0 ->
                    "${state.processing?.file.orEmpty()} · region ${state.processingPart} of ${state.processingParts}"
                state.processing != null -> state.processing.file
                else -> "The camera phase is complete; working from the disk queue."
            },
        )
        TransferPhase.TRANSFERRING -> TransferProgressCopy(
            "SAVING SELECTION",
            if (state.stopRequested) {
                "Pausing safely"
            } else {
                when {
                    state.processing != null && state.workStage == TransferWorkStage.DEVELOPING ->
                        "Developing frame ${state.processingNumber} of ${state.total}"
                    state.processing != null ->
                        "Saving frame ${state.processingNumber} of ${state.total}"
                    state.downloading != null ->
                        "Downloading frame ${state.downloadingNumber} of ${state.total}"
                    else -> "Preparing the next frame"
                }
            },
            if (state.stopRequested) {
                "Finishing the active frame and releasing any prefetched file."
            } else {
                when {
                    state.processing != null && state.downloading != null ->
                        "${state.processing.file} · fetching ${state.downloading.file} in parallel"
                    state.current != null -> state.current?.file.orEmpty()
                    else -> "Preparing the next frame…"
                }
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
