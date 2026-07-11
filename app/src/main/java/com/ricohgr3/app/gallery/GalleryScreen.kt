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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import com.ricohgr3.app.data.PhotoId
import com.ricohgr3.app.data.PhotoItem
import com.ricohgr3.app.data.PhotoRepository
import com.ricohgr3.app.ui.LookStrip
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
    onApplyLookToSelection: (String?) -> Unit,
    onStickyLookChange: (String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(GrTheme.colors.paper)) {
        GalleryHeader(
            count = state.photos.size,
            editedCount = state.editedCount,
            selectionCount = state.selectionCount,
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
                    onOpenPhoto = onOpenPhoto,
                    onToggleSelect = onToggleSelect,
                )
            }
        }

        // Batch-apply bar slides up whenever frames are selected.
        AnimatedVisibility(
            visible = state.hasSelection,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            BatchApplyBar(
                selectionCount = state.selectionCount,
                stickyLook = state.stickyLook,
                onStickyLookChange = onStickyLookChange,
                onApply = onApplyLookToSelection,
            )
        }
    }
}

@Composable
private fun GalleryHeader(
    count: Int,
    editedCount: Int,
    selectionCount: Int,
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
            TextButton(onClick = onClearSelection) {
                Text("$selectionCount selected  ✕", color = GrTheme.colors.accent)
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
 * Sticky batch-apply bar. Shows the selection count, a [LookStrip] pre-selected to the sticky
 * look, and an Apply button that styles every selected frame with the chosen look.
 */
@Composable
private fun BatchApplyBar(
    selectionCount: Int,
    stickyLook: String?,
    onStickyLookChange: (String?) -> Unit,
    onApply: (String?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(GrTheme.colors.paperEdge),
    ) {
        HorizontalDivider(color = GrTheme.colors.hair)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "APPLY LOOK",
                style = MaterialTheme.typography.labelSmall,
                color = GrTheme.colors.inkSoft,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onApply(stickyLook) }) {
                Text("Apply to $selectionCount  →", color = GrTheme.colors.accent)
            }
        }
        LookStrip(
            selected = stickyLook,
            onSelect = onStickyLookChange,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        )
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
