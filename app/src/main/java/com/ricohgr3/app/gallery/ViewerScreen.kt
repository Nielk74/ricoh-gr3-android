package com.ricohgr3.app.gallery

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ricohgr3.app.data.PhotoExporter
import com.ricohgr3.app.data.PhotoId
import com.ricohgr3.app.data.PhotoRepository
import com.ricohgr3.app.data.PhotoResult
import com.ricohgr3.app.looks.emulation.FilmLookCatalog
import kotlinx.coroutines.launch
import com.ricohgr3.app.ui.LookStrip
import com.ricohgr3.app.ui.LookSwatch
import com.ricohgr3.app.ui.theme.GrTheme
import com.ricohgr3.app.wifi.ImageSize
import com.ricohgr3.app.wifi.PhotoInfo
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Full-minimal single-photo viewer. Shows one frame large on paper ground, with:
 *  - a **look strip** pre-selected to the frame's current look (or the sticky default),
 *  - a **press-and-hold** "before" gesture that drops the applied-look preview to reveal the
 *    original,
 *  - the **edited mark** echoed as a small red dot + applied-look tag,
 *  - film-rebate metadata (frame id, ISO/shutter/aperture) in mono,
 *  - Reset / Apply, where Apply also updates the sticky default.
 *
 * This is a real in-app develop preview, not an indicative tint: the same adaptive pipeline,
 * stock intensity, non-tiling density grain, and halation model are used by edited export.
 * On-camera `effect` remains a separate capture-time feature and is not retroactive.
 */
@Composable
fun ViewerScreen(
    id: PhotoId,
    repository: PhotoRepository,
    exporter: PhotoExporter,
    filmLookLoader: com.ricohgr3.app.looks.emulation.FilmLookLoader? = null,
    appliedLook: String?,
    appliedIntensity: Float,
    stickyLook: String?,
    stickyIntensity: Float,
    onApplyLook: (String?, Float) -> Unit,
    onResetLook: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(id) { mutableStateOf<ImageBitmap?>(null) }
    var info by remember(id) { mutableStateOf<PhotoInfo?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    // The picker starts on the frame's current look, or the sticky default if unedited.
    var picked by remember(id) {
        mutableStateOf(appliedLook ?: stickyLook)
    }
    var effectStrength by remember(id) {
        mutableStateOf(
            (if (appliedLook != null) appliedIntensity else stickyIntensity).coerceIn(0.5f, 1.5f),
        )
    }
    var showingBefore by remember { mutableStateOf(false) }

    // Save-to-device state: a status line surfaced to the UI so a failed download is visible
    // rather than swallowed. `saving` blocks re-taps while a full-res fetch is in flight.
    val scope = rememberCoroutineScope()
    var saving by remember(id) { mutableStateOf(false) }
    var saveStatus by remember(id) { mutableStateOf<String?>(null) }

    fun runSave(edited: Boolean) {
        if (saving) return
        saving = true
        saveStatus = if (edited) "Saving edited…" else "Saving original…"
        scope.launch {
            saveStatus = try {
                val outcome =
                    if (edited) {
                        saveEdited(
                            id,
                            picked,
                            repository,
                            exporter,
                            filmLookLoader,
                            iso = parseIso(info?.sv),
                            effectStrength = effectStrength,
                        )
                    }
                    else saveOriginal(id, repository, exporter)
                if (outcome.edited) "Saved ${outcome.displayName} to Pictures/GR3"
                else "Saved to Pictures/GR3"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // never swallow structured-concurrency cancellation
            } catch (t: Throwable) {
                // A save must NEVER crash the app. Catch Throwable (not just Exception) so an
                // OutOfMemoryError from developing a large image is surfaced, not fatal.
                val reason = if (t is OutOfMemoryError) "not enough memory" else (t.message ?: t::class.simpleName)
                "Save failed: $reason"
            } finally {
                saving = false
            }
        }
    }

    // The raw (undeveloped) VIEW bitmap, kept so the preview can be re-developed as the picked
    // look changes and shown as the "before" while holding. `android.graphics.Bitmap` (not
    // ImageBitmap) because the develop engine reads pixels off it.
    var rawBitmap by remember(id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    // The developed preview for the currently-picked look (null = show the raw bitmap).
    var developedPreview by remember(id) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(id) {
        // A VIEW-sized rendition (720x480, ~500 KiB) is plenty for the viewer and far cheaper
        // than pulling the full-resolution original.
        when (val result = repository.downloadPhoto(id, size = ImageSize.VIEW)) {
            is PhotoResult.Success -> {
                val bytes = result.value
                val raw = runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
                rawBitmap = raw
                bitmap = raw?.asImageBitmap()
            }
            is PhotoResult.Error -> error = result.message
        }
        when (val infoResult = repository.photoInfo(id)) {
            is PhotoResult.Success -> info = infoResult.value
            is PhotoResult.Error -> Unit // metadata is best-effort
        }
    }

    // Render the REAL film develop onto the small VIEW bitmap whenever the picked look (or the
    // loaded frame) changes, so the on-screen preview matches what "Save with …" will produce —
    // not just an indicative tint. Runs off the main thread; failures fall back to the raw frame
    // (never crash, never block the UI). The develop is on a ~720×480 bitmap, so it's cheap.
    LaunchedEffect(picked, effectStrength, rawBitmap, filmLookLoader, info?.sv) {
        // Debounce slider drags. Superseded effects cancel during this delay, so the CPU renderer
        // only develops the settled value instead of queueing every intermediate tick.
        kotlinx.coroutines.delay(90)
        val src = rawBitmap
        val loader = filmLookLoader
        val stockId = picked
        developedPreview = if (src == null || loader == null || stockId == null) {
            null // Standard, or nothing to develop → show the raw frame
        } else {
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    loader.resolve(stockId)?.let { (film, lut) ->
                        com.ricohgr3.app.looks.emulation.DevelopEngine
                            .render(
                                src,
                                film,
                                lut,
                                iso = parseIso(info?.sv),
                                effectStrength = effectStrength,
                            )
                            .asImageBitmap()
                    }
                }
            }.getOrNull()
        }
    }

    Column(modifier = modifier.fillMaxSize().background(GrTheme.colors.paper)) {
        ViewerHeader(
            id = id,
            isEdited = appliedLook != null,
            appliedLook = appliedLook,
            appliedIntensity = appliedIntensity,
            onBack = onBack,
        )
        HorizontalDivider(color = GrTheme.colors.hair)

        // The image stage.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(GrTheme.colors.paperEdge),
            contentAlignment = Alignment.Center,
        ) {
            when {
                bitmap != null -> PhotoStage(
                    // Show the REAL developed preview for the picked look; the raw frame while
                    // holding "before", or when there's no develop yet (Standard / still
                    // rendering / fell back).
                    bitmap = if (!showingBefore) (developedPreview ?: bitmap!!) else bitmap!!,
                    look = picked,
                    developed = developedPreview != null,
                    effectStrength = effectStrength,
                    showingBefore = showingBefore,
                    onPressChange = { showingBefore = it },
                )
                error != null -> Text(error!!, color = GrTheme.colors.inkSoft, textAlign = TextAlign.Center)
                else -> CircularProgressIndicator(color = GrTheme.colors.accent)
            }

            if (showingBefore) {
                Text(
                    text = "ORIGINAL",
                    style = MaterialTheme.typography.labelSmall,
                    color = GrTheme.colors.paper,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(12.dp)
                        .background(GrTheme.colors.ink.copy(alpha = 0.6f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }

        HorizontalDivider(color = GrTheme.colors.hair)
        MetadataRebate(id = id, info = info, look = picked)
        HorizontalDivider(color = GrTheme.colors.hair)

        // Look picker — press-and-hold the photo above to compare against the original.
        Text(
            text = "LOOK  ·  hold photo to compare",
            style = MaterialTheme.typography.labelSmall,
            color = GrTheme.colors.inkSoft,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
        )
        LookStrip(
            selected = picked,
            onSelect = {
                picked = it
                if (it == null) effectStrength = 1f
            },
            modifier = Modifier.fillMaxWidth(),
        )

        if (picked != null) {
            EffectStrengthControl(
                value = effectStrength,
                onValueChange = { effectStrength = it },
            )
        }

        ViewerActions(
            picked = picked,
            appliedLook = appliedLook,
            effectStrength = effectStrength,
            appliedIntensity = appliedIntensity,
            onApply = { onApplyLook(picked, effectStrength) },
            onReset = {
                picked = null
                effectStrength = 1f
                onResetLook()
            },
        )

        SaveBar(
            picked = picked,
            effectStrength = effectStrength,
            saving = saving,
            status = saveStatus,
            onSaveOriginal = { runSave(edited = false) },
            onSaveEdited = { runSave(edited = true) },
        )
    }
}

/**
 * Download / save-to-device row. "Save original" pulls the full-resolution file untouched;
 * "Save with <look>" bakes the selected adaptive stock and intensity into a copy (only offered when a
 * non-Standard look is picked). Both fetch over the camera-AP-bound HTTP client and write into
 * `Pictures/GR3` via MediaStore. The [status] line makes success — and any failure — visible.
 */
@Composable
private fun SaveBar(
    picked: String?,
    effectStrength: Float,
    saving: Boolean,
    status: String?,
    onSaveOriginal: () -> Unit,
    onSaveEdited: () -> Unit,
) {
    HorizontalDivider(color = GrTheme.colors.hair)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (saving) {
            CircularProgressIndicator(
                color = GrTheme.colors.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(8.dp))
        }
        TextButton(onClick = onSaveOriginal, enabled = !saving) {
            Text("Save original", color = GrTheme.colors.ink)
        }
        Spacer(Modifier.weight(1f))
        if (picked != null) {
            TextButton(onClick = onSaveEdited, enabled = !saving) {
                Text(
                    "Save with ${FilmLookCatalog.displayNameFor(picked)} · " +
                        "${(effectStrength * 100f).roundToInt()}%",
                    color = GrTheme.colors.accent,
                )
            }
        }
    }
    status?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.labelSmall,
            color = GrTheme.colors.inkSoft,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun ViewerHeader(
    id: PhotoId,
    isEdited: Boolean,
    appliedLook: String?,
    appliedIntensity: Float,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(id.file, style = MaterialTheme.typography.titleMedium, color = GrTheme.colors.ink)
                if (isEdited) {
                    Spacer(Modifier.size(8.dp))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(GrTheme.colors.accent),
                    )
                }
            }
            val tag = if (isEdited) {
                "${FilmLookCatalog.displayNameFor(appliedLook)} · " +
                    "${(appliedIntensity * 100f).roundToInt()}%"
            } else {
                "As shot"
            }
            Text(tag, style = MaterialTheme.typography.labelSmall, color = GrTheme.colors.inkSoft)
        }
        TextButton(onClick = onBack) { Text("Done", color = GrTheme.colors.accent) }
    }
}

/**
 * The photo with an indicative look tint overlaid. Press-and-hold drops the tint (and reports
 * [onPressChange]) so the viewer can label the "before" state.
 */
@Composable
private fun PhotoStage(
    bitmap: ImageBitmap,
    look: String?,
    developed: Boolean,
    effectStrength: Float,
    showingBefore: Boolean,
    onPressChange: (Boolean) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPressChange(true)
                        tryAwaitRelease()
                        onPressChange(false)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        // Indicative look tint — ONLY when we couldn't render the real develop (no loader / JVM
        // fallback). When `developed` is true the bitmap already carries the real look, so no
        // overlay. Skipped for Standard and while holding "before".
        if (!showingBefore && look != null && !developed) {
            val stops = LookSwatch.stopsFor(look)
            val strength = effectStrength.coerceIn(0.5f, 1.5f)
            Box(
                modifier = Modifier
                    .matchImageOverlay()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                stops.top.copy(alpha = (0.16f * strength).coerceAtMost(0.36f)),
                                stops.bottom.copy(alpha = (0.24f * strength).coerceAtMost(0.36f)),
                            ),
                        ),
                    ),
            )
        }
    }
}

/** Overlay fills the stage; kept as a helper so the tint tracks the image box. */
private fun Modifier.matchImageOverlay(): Modifier = this.fillMaxSize()

/** Ricoh reports ISO as strings such as "500"; unknown/auto values intentionally stay null. */
private fun parseIso(value: String?): Int? = value?.trim()?.toIntOrNull()?.takeIf { it > 0 }

/** Film-edge metadata readout: frame id and, when known, the exposure triad. */
@Composable
private fun MetadataRebate(id: PhotoId, info: PhotoInfo?, look: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = id.folder,
            style = MaterialTheme.typography.labelSmall,
            color = GrTheme.colors.inkSoft,
        )
        Spacer(Modifier.weight(1f))
        val exposure = info?.let { formatExposure(it) }
        Text(
            text = exposure ?: "· · ·",
            style = MaterialTheme.typography.labelSmall,
            color = GrTheme.colors.ink,
        )
    }
}

/** "ISO 400 · 1/250 · ƒ2.8" from a [PhotoInfo], omitting fields the camera didn't report. */
private fun formatExposure(info: PhotoInfo): String {
    val parts = buildList {
        info.sv?.let { add("ISO $it") }
        info.tv?.let { add(it) }
        info.av?.let { add("ƒ$it") }
    }
    return if (parts.isEmpty()) "· · ·" else parts.joinToString("  ·  ")
}

/** 50–150% stock intensity. 100% is calibrated; 5% ticks plus debounce bound preview churn. */
@Composable
private fun EffectStrengthControl(
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
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
                color = if (abs(value - 1f) < 0.01f) GrTheme.colors.inkSoft else GrTheme.colors.accent,
            )
        }
        Slider(
            value = value,
            onValueChange = { raw ->
                onValueChange((raw * 20f).roundToInt() / 20f)
            },
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

@Composable
private fun ViewerActions(
    picked: String?,
    appliedLook: String?,
    effectStrength: Float,
    appliedIntensity: Float,
    onApply: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onReset,
            enabled = appliedLook != null || picked != null,
        ) {
            Text("Reset", color = GrTheme.colors.inkSoft)
        }
        Spacer(Modifier.weight(1f))
        // Applying makes `picked` sticky, so the next frame opens pre-set to it.
        val intensityChanged =
            picked != null && abs(effectStrength - appliedIntensity.coerceIn(0.5f, 1.5f)) > 0.01f
        TextButton(onClick = onApply, enabled = picked != appliedLook || intensityChanged) {
            Text(
                text = if (picked == null) {
                    "Apply"
                } else {
                    "Apply ${FilmLookCatalog.displayNameFor(picked)} · " +
                        "${(effectStrength * 100f).roundToInt()}%"
                },
                color = GrTheme.colors.accent,
            )
        }
    }
}
