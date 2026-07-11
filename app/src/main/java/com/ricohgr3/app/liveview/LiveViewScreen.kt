package com.ricohgr3.app.liveview

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ricohgr3.app.looks.CameraLook
import com.ricohgr3.app.ui.theme.GrTheme

/**
 * Wi-Fi live-view + remote shutter screen (Concept A "Contact Sheet"). The MJPEG live frame
 * fills the stage; a large round Ricoh-red shutter button sits at the bottom, with a mono chip
 * for the active look and minimal mono chrome (frame counter / battery). Before the first frame
 * (or on stream error) a "waiting for live view" placeholder shows on paper-edge ground.
 *
 * The stage re-decodes the latest [LiveViewUiState.frame] `ByteArray` to an [ImageBitmap] on
 * every frame — mirroring the decode pattern in [com.ricohgr3.app.ui.PhotoThumbnail].
 */
@Composable
fun LiveViewScreen(
    viewModel: LiveViewViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LiveViewContent(
        state = state,
        onShoot = viewModel::shoot,
        onRetry = viewModel::retryLiveview,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Stateless content — split out so previews / future tests can drive it with a plain state. */
@Composable
private fun LiveViewContent(
    state: LiveViewUiState,
    onShoot: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(GrTheme.colors.paper)) {
        LiveViewHeader(state = state, onBack = onBack)
        HorizontalDivider(color = GrTheme.colors.hair)

        // The live-view stage.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(GrTheme.colors.paperEdge),
            contentAlignment = Alignment.Center,
        ) {
            LiveFrame(frame = state.frame)

            if (!state.hasStream) {
                WaitingOverlay(error = state.error, onRetry = onRetry)
            }
            state.lastShot?.let { ShotToast(it) }
        }

        HorizontalDivider(color = GrTheme.colors.hair)
        ShutterBar(state = state, onShoot = onShoot)
    }
}

/** Decodes the latest JPEG frame and draws it filling the stage. */
@Composable
private fun LiveFrame(frame: ByteArray?) {
    val bitmap: ImageBitmap? = remember(frame) {
        frame?.let {
            runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull()
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "Live view",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun WaitingOverlay(error: String?, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
        Text(
            text = if (error != null) "LIVE VIEW UNAVAILABLE" else "WAITING FOR LIVE VIEW",
            style = MaterialTheme.typography.labelSmall,
            color = GrTheme.colors.inkSoft,
            textAlign = TextAlign.Center,
        )
        error?.let {
            Spacer(Modifier.size(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = GrTheme.colors.inkSoft,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(8.dp))
            TextButton(onClick = onRetry) { Text("Retry", color = GrTheme.colors.accent) }
        }
    }
}

/** Header: model + a mono look chip on the left, frame counter / battery + Done on the right. */
@Composable
private fun LiveViewHeader(state: LiveViewUiState, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.model ?: "LIVE VIEW",
                    style = MaterialTheme.typography.titleMedium,
                    color = GrTheme.colors.ink,
                )
                Spacer(Modifier.size(10.dp))
                LookChip(look = state.look)
            }
            Text(
                text = liveMeta(state),
                style = MaterialTheme.typography.labelSmall,
                color = GrTheme.colors.inkSoft,
            )
        }
        TextButton(onClick = onBack) { Text("Done", color = GrTheme.colors.accent) }
    }
}

/** Mono frame-counter + battery readout, e.g. "FRAME 042 · BATT 87%". */
private fun liveMeta(state: LiveViewUiState): String {
    val parts = buildList {
        if (state.frameCount > 0) add("FRAME %03d".format(state.frameCount))
        state.battery?.let { add("BATT $it%") }
    }
    return if (parts.isEmpty()) "· · ·" else parts.joinToString("  ·  ")
}

/** A small mono chip naming the active look, ringed in the hairline (accent for non-Standard). */
@Composable
private fun LookChip(look: CameraLook) {
    val ring = if (look == CameraLook.STANDARD) GrTheme.colors.hair else GrTheme.colors.accent
    val label = if (look == CameraLook.STANDARD) GrTheme.colors.inkSoft else GrTheme.colors.accent
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, ring, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = look.displayName.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = label,
        )
    }
}

/** Transient outcome tag over the stage after a shutter press. */
@Composable
private fun ShotToast(result: ShotResult) {
    val (text, color) = when (result) {
        ShotResult.SUCCESS -> "CAPTURED" to GrTheme.colors.good
        ShotResult.FAILURE -> "SHOT FAILED" to GrTheme.colors.accent
    }
    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = GrTheme.colors.paper,
            modifier = Modifier
                .background(color.copy(alpha = 0.85f), RoundedCornerShape(3.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** The shutter bar: a large round Ricoh-red button centered on paper. */
@Composable
private fun ShutterBar(state: LiveViewUiState, onShoot: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShutterButton(isShooting = state.isShooting, onClick = onShoot)
    }
}

@Composable
private fun ShutterButton(isShooting: Boolean, onClick: () -> Unit) {
    val fill = if (isShooting) GrTheme.colors.accentPressed else GrTheme.colors.accent
    Box(contentAlignment = Alignment.Center) {
        // Outer ring echoes a camera shutter release.
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .border(2.dp, GrTheme.colors.hair, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(fill)
                    .clickable(enabled = !isShooting, onClick = onClick),
            )
        }
    }
}
