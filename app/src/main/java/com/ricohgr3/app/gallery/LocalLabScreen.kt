package com.ricohgr3.app.gallery

import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ricohgr3.app.data.EditedExportQuality
import com.ricohgr3.app.data.PhotoExporter
import com.ricohgr3.app.looks.emulation.DevelopEngine
import com.ricohgr3.app.looks.emulation.DevelopOptions
import com.ricohgr3.app.looks.emulation.FilmLookCatalog
import com.ricohgr3.app.looks.emulation.FilmLookLoader
import com.ricohgr3.app.looks.emulation.RenderingIntent
import com.ricohgr3.app.looks.emulation.stableRenderSeed
import com.ricohgr3.app.ui.LookStrip
import com.ricohgr3.app.ui.theme.GrTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.roundToInt

/** Preview renders are cheap and quick to re-develop on slider drags; saves re-decode at full. */
private const val PREVIEW_MAX_PIXELS = 1280 * 1280

/**
 * The hidden **local lab** (triple-tap the "GR" wordmark on the connect screen): pick any image
 * from the device gallery, develop it through the same film engine the camera viewer uses
 * ([DevelopEngine] + [FilmLookLoader], same intent/strength/quality controls), and save the
 * result to `Pictures/GR3` via [PhotoExporter]. No camera connection is involved.
 *
 * Mirrors [ViewerScreen]'s contract: a small bounded decode drives the live preview (90 ms
 * debounce), while saving re-reads the picked Uri and develops a fresh memory-bounded decode, so
 * a full-resolution bitmap never sits on the heap. Saves never crash — failures surface as a
 * status line.
 */
@Composable
fun LocalLabScreen(
    exporter: PhotoExporter,
    filmLookLoader: FilmLookLoader,
    stickyLook: String?,
    stickyIntensity: Float,
    stickyRenderingIntent: RenderingIntent,
    stickyGrainEnabled: Boolean,
    editedExportQuality: EditedExportQuality,
    onGrainEnabledChange: (Boolean) -> Unit,
    onEditedExportQualityChange: (EditedExportQuality) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var baseName by remember { mutableStateOf<String?>(null) }
    var rawBitmap by remember(pickedUri) { mutableStateOf<Bitmap?>(null) }
    var error by remember(pickedUri) { mutableStateOf<String?>(null) }
    var loading by remember(pickedUri) { mutableStateOf(false) }

    var picked by remember(pickedUri) { mutableStateOf(stickyLook) }
    var effectStrength by remember(pickedUri) {
        mutableStateOf(stickyIntensity.coerceIn(0.5f, 1.5f))
    }
    var renderingIntent by remember(pickedUri) { mutableStateOf(stickyRenderingIntent) }
    var grainEnabled by remember(pickedUri) { mutableStateOf(stickyGrainEnabled) }
    // Clockwise rotation baked into both the preview and the edited save (0/90/180/270).
    var rotation by remember(pickedUri) { mutableIntStateOf(0) }
    var showingBefore by remember { mutableStateOf(false) }
    var developedPreview by remember(pickedUri) { mutableStateOf<ImageBitmap?>(null) }

    var saving by remember { mutableStateOf(false) }
    var saveStatus by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            pickedUri = uri
            saveStatus = null
        }
    }

    fun launchPicker() {
        picker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    // Resolve the display name once per pick (used for the header and the `_edit` save name).
    LaunchedEffect(pickedUri) {
        val uri = pickedUri ?: return@LaunchedEffect
        baseName = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
                )?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
                }
            }.getOrNull()
        } ?: "local_${System.currentTimeMillis()}.jpg"
    }

    // Decode a bounded preview rendition off the main thread (same decodeBounded the save path
    // uses, at a preview-sized pixel budget).
    LaunchedEffect(pickedUri) {
        val uri = pickedUri ?: return@LaunchedEffect
        loading = true
        rawBitmap = withContext(Dispatchers.Default) {
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw IOException("Could not read the selected image")
                decodeBounded(bytes, PREVIEW_MAX_PIXELS)
                    ?: throw IOException("Could not decode the selected image")
            }.onFailure { error = it.message ?: "Could not open the selected image" }
                .getOrNull()
        }
        loading = false
    }

    // Rotate the decoded preview off the main thread; kept separate from the develop so tapping
    // a rotate button doesn't re-run the film pipeline for the Standard (undeveloped) state.
    var rotatedBitmap by remember(pickedUri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(rawBitmap, rotation) {
        val src = rawBitmap
        rotatedBitmap = if (src == null || rotation % 360 == 0) {
            null
        } else {
            withContext(Dispatchers.Default) {
                runCatching { rotateBitmap(src, rotation) }.getOrNull()
            }
        }
    }
    val orientedBitmap = rotatedBitmap ?: rawBitmap

    // Re-develop the preview whenever the look/strength/intent changes — the same debounced,
    // never-crashing pattern as the camera viewer.
    LaunchedEffect(picked, effectStrength, renderingIntent, grainEnabled, orientedBitmap) {
        delay(90)
        val src = orientedBitmap
        val stockId = picked
        developedPreview = if (src == null || stockId == null) {
            null // Standard, or nothing loaded → show the raw frame
        } else {
            runCatching {
                withContext(Dispatchers.Default) {
                    filmLookLoader.resolve(stockId)?.let { (film, lut) ->
                        DevelopEngine.render(
                            src,
                            film,
                            lut,
                            effectStrength = effectStrength,
                            options = DevelopOptions(
                                intent = renderingIntent,
                                renderSeed = stableRenderSeed(
                                    "local:${baseName ?: pickedUri.toString()}",
                                ),
                                grainEnabled = grainEnabled,
                            ),
                        ).asImageBitmap()
                    }
                }
            }.getOrNull()
        }
    }

    fun runSave(edited: Boolean) {
        if (saving) return
        val uri = pickedUri ?: return
        if (edited && picked == null && rotation % 360 == 0) return
        saving = true
        saveStatus = if (edited) "Saving edited…" else "Saving original…"
        scope.launch {
            saveStatus = try {
                // Re-read and re-decode at the export pixel budget so the preview-sized bitmap
                // is never what gets baked.
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw IOException("Could not read the selected image")
                val name = baseName ?: "local_${System.currentTimeMillis()}.jpg"
                val outcome = if (edited) {
                    saveEditedLocal(
                        bytes,
                        name,
                        picked,
                        exporter,
                        filmLookLoader,
                        effectStrength = effectStrength,
                        renderingIntent = renderingIntent,
                        grainEnabled = grainEnabled,
                        exportQuality = editedExportQuality,
                        rotationDegrees = rotation,
                    )
                } else {
                    val mime = withContext(Dispatchers.IO) {
                        runCatching { context.contentResolver.getType(uri) }.getOrNull()
                    } ?: "image/jpeg"
                    exporter.saveBytes(bytes, name, mime)
                    SaveOutcome(displayName = name, edited = false)
                }
                saveOutcomeMessage(outcome)
            } catch (e: CancellationException) {
                throw e // never swallow structured-concurrency cancellation
            } catch (t: Throwable) {
                // A save must NEVER crash the app (see ViewerScreen.runSave).
                val reason =
                    if (t is OutOfMemoryError) "not enough memory" else (t.message ?: t::class.simpleName)
                "Save failed: $reason"
            } finally {
                saving = false
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().background(GrTheme.colors.paper)) {
        LocalLabHeader(
            baseName = baseName,
            onPickAnother = ::launchPicker,
            onBack = onBack,
        )
        HorizontalDivider(color = GrTheme.colors.hair)

        // The image stage — press-and-hold compares against the untouched original.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(GrTheme.colors.paperEdge),
            contentAlignment = Alignment.Center,
        ) {
            when {
                orientedBitmap != null -> {
                    Image(
                        bitmap = if (!showingBefore) {
                            developedPreview ?: orientedBitmap.asImageBitmap()
                        } else {
                            // "Before" keeps the rotation but drops the developed look.
                            orientedBitmap.asImageBitmap()
                        },
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(pickedUri) {
                                detectTapGestures(
                                    onPress = {
                                        showingBefore = true
                                        tryAwaitRelease()
                                        showingBefore = false
                                    },
                                )
                            },
                    )
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
                loading -> CircularProgressIndicator(color = GrTheme.colors.accent)
                error != null -> Text(
                    error!!,
                    color = GrTheme.colors.inkSoft,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Develop any photo from this phone with the film looks.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = GrTheme.colors.inkSoft,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = ::launchPicker) {
                        Text("Pick a photo", color = GrTheme.colors.accent)
                    }
                }
            }
        }

        HorizontalDivider(color = GrTheme.colors.hair)
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
            LookTuningTabs(
                renderingIntent = renderingIntent,
                onRenderingIntentChange = { renderingIntent = it },
                effectStrength = effectStrength,
                onEffectStrengthChange = { effectStrength = it },
                grainEnabled = grainEnabled,
                onGrainEnabledChange = {
                    grainEnabled = it
                    onGrainEnabledChange(it)
                },
                quality = editedExportQuality,
                onQualityChange = onEditedExportQualityChange,
                extraDevelopContent = {
                    RotationControl(
                        rotation = rotation,
                        enabled = orientedBitmap != null,
                        onRotate = { delta -> rotation = ((rotation + delta) % 360 + 360) % 360 },
                    )
                },
            )
        } else {
            RotationControl(
                rotation = rotation,
                enabled = orientedBitmap != null,
                onRotate = { delta -> rotation = ((rotation + delta) % 360 + 360) % 360 },
            )
        }

        LocalLabSaveBar(
            hasPhoto = orientedBitmap != null,
            picked = picked,
            rotation = rotation,
            effectStrength = effectStrength,
            renderingIntent = renderingIntent,
            grainEnabled = grainEnabled,
            saving = saving,
            status = saveStatus,
            onSaveOriginal = { runSave(edited = false) },
            onSaveEdited = { runSave(edited = true) },
        )
    }
}

/** 90° steps in either direction plus a 180° flip; deltas accumulate modulo 360. */
@Composable
private fun RotationControl(
    rotation: Int,
    enabled: Boolean,
    onRotate: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "ROTATE" + if (rotation % 360 != 0) "  ·  ${rotation % 360}°" else "",
            style = MaterialTheme.typography.labelSmall,
            color = GrTheme.colors.inkSoft,
            modifier = Modifier.padding(start = 4.dp),
        )
        Spacer(Modifier.weight(1f))
        listOf("−90°" to -90, "180°" to 180, "+90°" to 90).forEach { (label, delta) ->
            TextButton(onClick = { onRotate(delta) }, enabled = enabled) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = GrTheme.colors.inkSoft,
                )
            }
        }
    }
}

@Composable
private fun LocalLabHeader(
    baseName: String?,
    onPickAnother: () -> Unit,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "LOCAL LAB",
                style = MaterialTheme.typography.titleMedium,
                color = GrTheme.colors.ink,
            )
            Text(
                baseName ?: "No photo picked",
                style = MaterialTheme.typography.labelSmall,
                color = GrTheme.colors.inkSoft,
            )
        }
        TextButton(onClick = onPickAnother) {
            Text(if (baseName == null) "Pick" else "Change", color = GrTheme.colors.ink)
        }
        TextButton(onClick = onBack) { Text("Done", color = GrTheme.colors.accent) }
    }
}

/** Same save contract as the viewer's bar, minus the camera fetch. */
@Composable
private fun LocalLabSaveBar(
    hasPhoto: Boolean,
    picked: String?,
    rotation: Int,
    effectStrength: Float,
    renderingIntent: RenderingIntent,
    grainEnabled: Boolean,
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
        TextButton(onClick = onSaveOriginal, enabled = hasPhoto && !saving) {
            Text("Save original", color = GrTheme.colors.ink)
        }
        Spacer(Modifier.weight(1f))
        // The edited bake is offered whenever there's an edit to bake: a look and/or a rotation.
        if (picked != null) {
            TextButton(onClick = onSaveEdited, enabled = hasPhoto && !saving) {
                Text(
                    "Save with ${FilmLookCatalog.displayNameFor(picked)} · " +
                        "${(effectStrength * 100f).roundToInt()}% · " +
                        renderingIntent.displayName +
                        if (grainEnabled) "" else " · no grain",
                    color = GrTheme.colors.accent,
                )
            }
        } else if (rotation % 360 != 0) {
            TextButton(onClick = onSaveEdited, enabled = hasPhoto && !saving) {
                Text("Save rotated", color = GrTheme.colors.accent)
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
