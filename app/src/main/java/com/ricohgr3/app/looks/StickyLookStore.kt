package com.ricohgr3.app.looks

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ricohgr3.app.data.EditedExportQuality
import com.ricohgr3.app.looks.emulation.FilmLookCatalog
import com.ricohgr3.app.looks.emulation.RenderingIntent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Pure mapping between a film-stock id (a [FilmLookCatalog] id, or `null` for Standard) and the
 * string persisted for the "sticky" (last-used) look. Kept separate from any Android/DataStore
 * call so it is JVM-unit-testable.
 *
 * We persist the film-stock id directly. Standard is stored as the empty string; an unknown or
 * missing id decodes back to `null` (Standard, the default) so a removed stock degrades safely.
 */
object LookPreferenceCodec {
    fun encode(look: String?): String = look ?: ""

    fun decode(value: String?): String? =
        value?.takeIf { it.isNotEmpty() && FilmLookCatalog.entryFor(it) != null }

    fun encodeRenderingIntent(intent: RenderingIntent): String = intent.name

    fun decodeRenderingIntent(value: String?): RenderingIntent =
        value?.let { encoded ->
            RenderingIntent.entries.firstOrNull { it.name == encoded }
        } ?: RenderingIntent.SMART

    fun encodeEditedExportQuality(quality: EditedExportQuality): String = quality.name

    fun decodeEditedExportQuality(value: String?): EditedExportQuality =
        value?.let { encoded ->
            EditedExportQuality.entries.firstOrNull { it.name == encoded }
        } ?: EditedExportQuality.MAXIMUM
}

/** The app-wide DataStore for look preferences. */
private val Context.looksDataStore: DataStore<Preferences> by preferencesDataStore(name = "looks")

/**
 * Persists the last-used film-stock id so it can be pre-selected for the next frames
 * ("sticky default", PHASE7-LOOKS.md §7.1). Thin wrapper over Jetpack DataStore
 * (Preferences); all non-trivial logic lives in [LookPreferenceCodec] for testability.
 */
class StickyLookStore(private val context: Context) {

    /** Emits the persisted film-stock id, defaulting to `null` (Standard) when unset. */
    val lookFlow: Flow<String?> = context.looksDataStore.data.map { prefs ->
        LookPreferenceCodec.decode(prefs[LAST_LOOK_KEY])
    }

    /** Last-used intensity, independently guarded so old installs naturally migrate to 100%. */
    val intensityFlow: Flow<Float> = context.looksDataStore.data.map { prefs ->
        (prefs[LAST_INTENSITY_KEY] ?: 1f).coerceIn(0.5f, 1.5f)
    }

    /** Last-used renderer contract; old installs migrate to the protected Smart path. */
    val renderingIntentFlow: Flow<RenderingIntent> = context.looksDataStore.data.map { prefs ->
        LookPreferenceCodec.decodeRenderingIntent(prefs[LAST_RENDERING_INTENT_KEY])
    }

    /** Last-used physical-grain choice; old installs retain the authored grain by default. */
    val grainEnabledFlow: Flow<Boolean> = context.looksDataStore.data.map { prefs ->
        prefs[LAST_GRAIN_ENABLED_KEY] ?: true
    }

    /** Edited-export quality; unset installs default to full source dimensions. */
    val editedExportQualityFlow: Flow<EditedExportQuality> = context.looksDataStore.data.map { prefs ->
        LookPreferenceCodec.decodeEditedExportQuality(prefs[EDITED_EXPORT_QUALITY_KEY])
    }

    /** Persist [look] as the sticky last-used film-stock id (`null` = Standard). */
    suspend fun setLook(
        look: String?,
        intensity: Float = 1f,
        renderingIntent: RenderingIntent = RenderingIntent.SMART,
        grainEnabled: Boolean = true,
    ) {
        context.looksDataStore.edit { prefs ->
            prefs[LAST_LOOK_KEY] = LookPreferenceCodec.encode(look)
            prefs[LAST_INTENSITY_KEY] = intensity.coerceIn(0.5f, 1.5f)
            prefs[LAST_RENDERING_INTENT_KEY] =
                LookPreferenceCodec.encodeRenderingIntent(renderingIntent)
            prefs[LAST_GRAIN_ENABLED_KEY] = grainEnabled
        }
    }

    /** Persist the user's edited-export resolution/encoding preference independently. */
    suspend fun setEditedExportQuality(quality: EditedExportQuality) {
        context.looksDataStore.edit { prefs ->
            prefs[EDITED_EXPORT_QUALITY_KEY] = LookPreferenceCodec.encodeEditedExportQuality(quality)
        }
    }

    private companion object {
        val LAST_LOOK_KEY = stringPreferencesKey("last_look")
        val LAST_INTENSITY_KEY = floatPreferencesKey("last_look_intensity")
        val LAST_RENDERING_INTENT_KEY = stringPreferencesKey("last_look_rendering_intent")
        val LAST_GRAIN_ENABLED_KEY = booleanPreferencesKey("last_look_grain_enabled")
        val EDITED_EXPORT_QUALITY_KEY = stringPreferencesKey("edited_export_quality")
    }
}
