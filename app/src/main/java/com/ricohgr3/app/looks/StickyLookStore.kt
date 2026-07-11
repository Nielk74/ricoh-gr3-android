package com.ricohgr3.app.looks

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ricohgr3.app.looks.emulation.FilmLookCatalog
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

    /** Persist [look] as the sticky last-used film-stock id (`null` = Standard). */
    suspend fun setLook(look: String?) {
        context.looksDataStore.edit { prefs ->
            prefs[LAST_LOOK_KEY] = LookPreferenceCodec.encode(look)
        }
    }

    private companion object {
        val LAST_LOOK_KEY = stringPreferencesKey("last_look")
    }
}
