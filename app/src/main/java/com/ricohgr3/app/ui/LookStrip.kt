package com.ricohgr3.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ricohgr3.app.looks.emulation.FilmLookCatalog
import com.ricohgr3.app.ui.theme.GrTheme

/**
 * Horizontal, tappable strip of every **film stock** (Portra 400, CineStill 800T, …) plus a
 * leading "Standard" (as-shot) chip. The [selected] stock — a [FilmLookCatalog] id, or `null`
 * for Standard — is ringed in the Ricoh-red accent; tapping a chip calls [onSelect]. Shared by
 * the gallery's batch-apply bar and the viewer's per-frame picker so the two stay identical.
 */
@Composable
fun LookStrip(
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    LazyRow(
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(FilmLookCatalog.pickerIds) { id ->
            LookChip(
                id = id,
                label = FilmLookCatalog.displayNameFor(id),
                isSelected = id == selected,
                onClick = { onSelect(id) },
            )
        }
    }
}

/** One swatch + label. Selected chips get an accent ring; others a hairline. */
@Composable
private fun LookChip(
    id: String?,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val stops = LookSwatch.stopsFor(id)
    val ring = if (isSelected) GrTheme.colors.accent else GrTheme.colors.hair
    val ringWidth = if (isSelected) 2.dp else 1.dp

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            // Wide enough for the mono "Standard" label without an orphaned final letter.
            .width(64.dp)
            .clickable(onClick = onClick),
    ) {
        Spacer(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Brush.verticalGradient(listOf(stops.top, stops.bottom)))
                .border(ringWidth, ring, RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) GrTheme.colors.accent else GrTheme.colors.inkSoft,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.height(28.dp),
        )
    }
}
