package com.ricohgr3.app.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.updateDataStore: DataStore<Preferences> by preferencesDataStore(name = "updates")

class UpdatePreferences(private val context: Context) {
    val lastCheckMillis: Flow<Long> = context.updateDataStore.data.map { preferences ->
        preferences[LastCheckMillisKey] ?: 0L
    }

    suspend fun setLastCheckMillis(value: Long) {
        context.updateDataStore.edit { preferences ->
            preferences[LastCheckMillisKey] = value
        }
    }

    private companion object {
        val LastCheckMillisKey = longPreferencesKey("last_check_millis")
    }
}
