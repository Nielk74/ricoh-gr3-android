package com.ricohgr3.app.looks

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Pure mapping between a [CameraLook] and the string persisted for the "sticky" (last-used)
 * look. Kept separate from any Android/DataStore call so it is JVM-unit-testable.
 *
 * We persist the enum [CameraLook.name] (stable identifier) rather than the `effect` value:
 * [CameraLook.STANDARD] has a null effect, and names survive display-name tweaks. Unknown or
 * missing values decode to [CameraLook.STANDARD] (the default).
 */
object LookPreferenceCodec {
    fun encode(look: CameraLook): String = look.name

    fun decode(value: String?): CameraLook =
        value?.let { stored -> CameraLook.entries.firstOrNull { it.name == stored } }
            ?: CameraLook.STANDARD
}

/** The app-wide DataStore for look preferences. */
private val Context.looksDataStore: DataStore<Preferences> by preferencesDataStore(name = "looks")

/**
 * Persists the last-used [CameraLook] so it can be pre-selected for the next frames
 * ("sticky default", PHASE7-LOOKS.md §7.1). Thin wrapper over Jetpack DataStore
 * (Preferences); all non-trivial logic lives in [LookPreferenceCodec] for testability.
 */
class StickyLookStore(private val context: Context) {

    /** Emits the persisted look, defaulting to [CameraLook.STANDARD] when unset. */
    val lookFlow: Flow<CameraLook> = context.looksDataStore.data.map { prefs ->
        LookPreferenceCodec.decode(prefs[LAST_LOOK_KEY])
    }

    /** Persist [look] as the sticky last-used look. */
    suspend fun setLook(look: CameraLook) {
        context.looksDataStore.edit { prefs ->
            prefs[LAST_LOOK_KEY] = LookPreferenceCodec.encode(look)
        }
    }

    private companion object {
        val LAST_LOOK_KEY = stringPreferencesKey("last_look")
    }
}
