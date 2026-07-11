package com.ricohgr3.app.wifi

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ricohgr3.app.ble.WlanCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The app-wide DataStore for cached camera Wi-Fi credentials. */
private val Context.credentialsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "camera_credentials")

/**
 * Persists the camera's Wi-Fi AP credentials (SSID + passphrase) read over BLE, so the Wi-Fi
 * transport can auto-join later WITHOUT a BLE session.
 *
 * This matters because the GR III's BLE and Wi-Fi planes are mutually exclusive — an active BLE
 * control session keeps the camera's Wi-Fi AP off. So we read the credentials once during a
 * Bluetooth pairing, cache them here, and reuse them to join the AP in Wi-Fi mode (where BLE is
 * disconnected). See research/BLE_WIFI_WAKE_INVESTIGATION.md.
 */
class CameraCredentialStore(private val context: Context) {

    /** The last cached credentials, or null if none saved yet. */
    val credentialsFlow: Flow<WlanCredentials?> = context.credentialsDataStore.data.map { prefs ->
        val ssid = prefs[SSID_KEY]
        val pass = prefs[PASS_KEY]
        if (!ssid.isNullOrBlank() && pass != null) WlanCredentials(ssid, pass) else null
    }

    /** Cache [creds] for later Wi-Fi joins. No-op if the SSID is blank (nothing useful to store). */
    suspend fun save(creds: WlanCredentials) {
        if (creds.ssid.isBlank()) return
        context.credentialsDataStore.edit { prefs ->
            prefs[SSID_KEY] = creds.ssid
            prefs[PASS_KEY] = creds.passphrase
        }
    }

    private companion object {
        val SSID_KEY = stringPreferencesKey("camera_ssid")
        val PASS_KEY = stringPreferencesKey("camera_passphrase")
    }
}
