package com.ricohgr3.app.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ricohgr3.app.data.EditedExportQuality
import com.ricohgr3.app.data.PhotoId
import com.ricohgr3.app.data.PhotoItem
import com.ricohgr3.app.data.PhotoRepository
import com.ricohgr3.app.looks.emulation.RenderingIntent
import com.ricohgr3.app.ui.PhotoThumbnail
import com.ricohgr3.app.ui.theme.GrTheme

/**
 * The **hero** screen: a 3-column contact sheet of every frame on the camera.
 *
 * Realises the two locked UX rules:
 *  - **Edited mark** — a small Ricoh-red dot on any frame with a look applied.
 *  - **Sticky default** — the batch-apply bar always offers the last-used look pre-selected,
 *    so styling a roll is one tap per frame (or "Apply to selection").
 *
 * Tapping a frame opens the viewer ([onOpenPhoto]); long-press enters/extends multi-select.
 */
@Composable
fun GalleryScreen(
    state: GalleryUiState,
    repository: PhotoRepository,
    onOpenPhoto: (PhotoId) -> Unit,
    onToggleSelect: (PhotoId) -> Unit,
    onClearSelection: () -> Unit,
    onApplyLookToSelection: (String?, Float, RenderingIntent) -> Unit,
    onStickyLookChange: (String?) -> Unit,
    onStickyIntensityChange: (Float) -> Unit,
    onStickyRenderingIntentChange: (RenderingIntent) -> Unit,
    onEditedExportQualityChange: (EditedExportQuality) -> Unit,
    transfer: TransferUiState,
    onSaveSelection: (TransferPreset) -> Unit,
    onCancelTransfer: () -> Unit,
    onRetryTransfer: () -> Unit,
    onDismissTransfer: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(GrTheme.colors.paper)) {
        val selectionTransferVisible =
            transfer.source == TransferSource.SELECTION && transfer.phase != TransferPhase.IDLE
        GalleryHeader(
            count = state.photos.size,
            editedCount = state.editedCount,
            selectionCount = state.selectionCount,
            selectionLocked = selectionTransferVisible,
            onClearSelection = onClearSelection,
            onBack = onBack,
        )
        HorizontalDivider(color = GrTheme.colors.hair)

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.isLoading && state.photos.isEmpty() -> LoadingState()
                state.error != null && state.photos.isEmpty() ->
                    MessageState(title = "Can't reach the camera", detail = state.error)
                state.photos.isEmpty() ->
                    MessageState(title = "No frames", detail = "Connect over Wi-Fi and refresh.")
                else -> ContactSheet(
                    photos = state.photos,
                    state = state,
                    repository = repository,
                    interactionEnabled = !selectionTransferVisible,
                    onOpenPhoto = onOpenPhoto,
                    onToggleSelect = onToggleSelect,
                )
            }
        }

        // Batch-apply bar slides up whenever frames are selected.
        AnimatedVisibility(
            visible = state.hasSelection || selectionTransferVisible,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            BatchApplyBar(
                selectionCount = state.selectionCount,
                stickyLook = state.stickyLook,
                stickyIntensity = state.stickyIntensity,
                stickyRenderingIntent = state.stickyRenderingIntent,
                editedExportQuality = state.editedExportQuality,
                onStickyLookChange = onStickyLookChange,
                onStickyIntensityChange = onStickyIntensityChange,
                onStickyRenderingIntentChange = onStickyRenderingIntentChange,
                onEditedExportQualityChange = onEditedExportQualityChange,
                onApply = onApplyLookToSelection,
                transfer = transfer,
                onSave = onSaveSelection,
                onCancelTransfer = onCancelTransfer,
                onRetryTransfer = onRetryTransfer,
                onDismissTransfer = onDismissTransfer,
            )
        }
    }
}

@Composable
private fun GalleryHeader(
    count: Int,
    editedCount: Int,
    selectionCount: Int,
    selectionLocked: Boolean,
    onClearSelection: () -> Unit,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "LIBRARY",
                style = MaterialTheme.typography.labelSmall,
                color = GrTheme.colors.inkSoft,
            )
            Spacer(Modifier.height(2.dp))
            val meta = buildString {
                append("$count frames")
                if (editedCount > 0) append("  ·  $editedCount edited")
            }
            Text(text = meta, style = MaterialTheme.typography.bodyMedium, color = GrTheme.colors.ink)
        }
        if (selectionCount > 0) {
            if (selectionLocked) {
                Text(
                    "$selectionCount selected",
                    style = MaterialTheme.typography.labelSmall,
                    color = GrTheme.colors.inkSoft,
                )
            } else {
                TextButton(onClick = onClearSelection) {
                    Text("$selectionCount selected  ✕", color = GrTheme.colors.accent)
                }
            }
        } else {
            TextButton(onClick = onBack) { Text("Back", color = GrTheme.colors.inkSoft) }
        }
    }
}

@Composable
private fun ContactSheet(
    photos: List<PhotoItem>,
    state: GalleryUiState,
    repository: PhotoRepository,
    interactionEnabled: Boolean,
    onOpenPhoto: (PhotoId) -> Unit,
    onToggleSelect: (PhotoId) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(photos, key = { it.id.toString() }) { item ->
            ContactFrame(
                item = item,
                repository = repository,
                isSelected = state.isSelected(item.id),
                isEdited = state.isEdited(item.id),
                selecting = state.hasSelection,
                interactionEnabled = interactionEnabled,
                onOpen = { onOpenPhoto(item.id) },
                onToggleSelect = { onToggleSelect(item.id) },
            )
        }
    }
}

/** One frame in the contact sheet: thumbnail + edited-mark dot + selection ring. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactFrame(
    item: PhotoItem,
    repository: PhotoRepository,
    isSelected: Boolean,
    isEdited: Boolean,
    selecting: Boolean,
    interactionEnabled: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
) {
    val borderColor = if (isSelected) GrTheme.colors.accent else GrTheme.colors.hair
    val borderWidth = if (isSelected) 2.dp else 0.5.dp

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(androidx.compose.ui.graphics.RectangleShape)
            .border(borderWidth, borderColor)
            // Tap opens the viewer; while multi-selecting, tap toggles selection instead.
            // Long-press enters (or extends) multi-select.
            .combinedClickable(
                enabled = interactionEnabled,
                onClick = { if (selecting) onToggleSelect() else onOpen() },
                onLongClick = onToggleSelect,
            ),
    ) {
        PhotoThumbnail(
            id = item.id,
            repository = repository,
            modifier = Modifier.fillMaxSize(),
            placeholderColor = GrTheme.colors.paperEdge,
        )

        // Edited mark — the small red dot in the top-right corner.
        if (isEdited) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(GrTheme.colors.accent)
                    .border(1.dp, GrTheme.colors.paper, CircleShape),
            )
        }

        // RAW badge (DNG originals) — mono tag, bottom-left.
        if (item.isRaw) {
            Text(
                text = "RAW",
                style = MaterialTheme.typography.labelSmall,
                color = GrTheme.colors.paper,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(GrTheme.colors.ink.copy(alpha = 0.55f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }

        // Selection check — filled accent circle when selected.
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(GrTheme.colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", color = GrTheme.colors.paper, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Sticky batch panel. Shows the selection count, look and quality controls, plus separate actions
 * to apply the edit state or develop and save every selected frame.
 */
@Composable
private fun BatchApplyBar(
    selectionCount: Int,
    stickyLook: String?,
    stickyIntensity: Float,
    stickyRenderingIntent: RenderingIntent,
    editedExportQuality: EditedExportQuality,
    onStickyLookChange: (String?) -> Unit,
    onStickyIntensityChange: (Float) -> Unit,
    onStickyRenderingIntentChange: (RenderingIntent) -> Unit,
    onEditedExportQualityChange: (EditedExportQuality) -> Unit,
    onApply: (String?, Float, RenderingIntent) -> Unit,
    transfer: TransferUiState,
    onSave: (TransferPreset) -> Unit,
    onCancelTransfer: () -> Unit,
    onRetryTransfer: () -> Unit,
    onDismissTransfer: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(GrTheme.colors.paperEdge),
    ) {
        HorizontalDivider(color = GrTheme.colors.hair)
        if (transfer.source == TransferSource.SELECTION && transfer.phase != TransferPhase.IDLE) {
            TransferProgressPanel(
                state = transfer,
                onCancel = onCancelTransfer,
                onRetry = onRetryTransfer,
                onDismiss = onDismissTransfer,
                modifier = Modifier.padding(12.dp),
            )
        } else {
            val anotherTransferActive = transfer.isActive
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "BATCH PROCESS",
                        style = MaterialTheme.typography.labelSmall,
                        color = GrTheme.colors.inkSoft,
                    )
                    Text(
                        "$selectionCount selected · choose one finish",
                        style = MaterialTheme.typography.bodyMedium,
                        color = GrTheme.colors.ink,
                    )
                }
                Text(
                    editedExportQuality.displayName.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (stickyLook == null) GrTheme.colors.inkSoft else GrTheme.colors.accent,
                )
            }
            TransferPresetEditor(
                look = stickyLook,
                intensity = stickyIntensity,
                renderingIntent = stickyRenderingIntent,
                quality = editedExportQuality,
                onLookChange = onStickyLookChange,
                onIntensityChange = onStickyIntensityChange,
                onRenderingIntentChange = onStickyRenderingIntentChange,
                onQualityChange = onEditedExportQualityChange,
                enabled = !anotherTransferActive,
            )
            if (anotherTransferActive) {
                Text(
                    "Another transfer is already running. Finish or stop it before saving this selection.",
                    style = MaterialTheme.typography.labelSmall,
                    color = GrTheme.colors.accent,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        onApply(stickyLook, stickyIntensity, stickyRenderingIntent)
                    },
                    enabled = !anotherTransferActive,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Apply only", color = GrTheme.colors.ink)
                }
                Button(
                    onClick = {
                        onSave(
                            TransferPreset(
                                look = stickyLook,
                                intensity = stickyIntensity,
                                renderingIntent = stickyRenderingIntent,
                                quality = editedExportQuality,
                            ),
                        )
                    },
                    enabled = !anotherTransferActive,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GrTheme.colors.accent),
                ) {
                    Text(
                        if (stickyLook == null) {
                            "Save $selectionCount originals"
                        } else {
                            "Save $selectionCount photos"
                        },
                        color = GrTheme.colors.paper,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = GrTheme.colors.accent)
    }
}

@Composable
private fun MessageState(title: String, detail: String?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = GrTheme.colors.ink)
        detail?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = GrTheme.colors.inkSoft)
        }
    }
}
