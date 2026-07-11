package com.ricohgr3.app.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.concurrent.atomic.AtomicReference

/**
 * Joins the camera's own Wi-Fi access point and routes the app's traffic to it, so HTTP
 * requests reach `http://192.168.0.1/` even though that network has NO internet.
 *
 * ## How it works (API 29+)
 * The camera AP is internet-less. On modern Android you cannot just "switch Wi-Fi" and expect
 * app sockets to use it — the OS keeps the default network (cellular/other Wi-Fi) for anything
 * with an `INTERNET` capability. Instead we:
 *   1. Build a [WifiNetworkSpecifier] for the camera SSID + passphrase (obtained over BLE from
 *      the camera's WLAN Control service — see the roadmap).
 *   2. Ask [ConnectivityManager.requestNetwork] for a Wi-Fi network matching that specifier,
 *      WITHOUT the `NET_CAPABILITY_INTERNET` requirement (the AP has none).
 *   3. When granted, call [ConnectivityManager.bindProcessToNetwork] so every socket in this
 *      process (including OkHttp) is pinned to the camera AP. The phone's normal connectivity
 *      (mobile data) stays up for the rest of the system.
 *
 * ## Teardown
 * [disconnect] unbinds the process and unregisters the callback, releasing the AP so the phone
 * returns to its normal default network. ALWAYS call it (e.g. from `onCleared`/`onStop`).
 *
 * ## API-level note
 * [WifiNetworkSpecifier] requires **API 29 (Android 10)**. The app's `minSdk` is 26; on API
 * 26–28 this class throws from [connect] — the caller must gate on [isSupported]. (A legacy
 * pre-29 path would need the deprecated `WifiManager.enableNetwork` + `bindProcessToNetwork`
 * and is out of scope for this scaffold — see TODO below.)
 *
 * ## Manual test steps (cannot be unit-tested on the host — needs a real device + camera)
 *  1. Wake the camera AP (via BLE WLAN Control, or manually on the camera) and note SSID/key.
 *  2. Ensure the phone has mobile data ON so you can prove the split: internet keeps working.
 *  3. Call [connect] with the SSID/passphrase; accept the system "join this Wi-Fi?" dialog.
 *  4. On [onAvailable], hit `GET http://192.168.0.1/v1/ping` via [CameraHttpClient] — expect 200.
 *  5. Confirm a normal internet request from another app still succeeds (default net intact).
 *  6. Call [disconnect]; confirm `/v1/ping` now fails and the app's default network is restored.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class WifiApConnector(context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val activeCallback = AtomicReference<ConnectivityManager.NetworkCallback?>(null)

    /**
     * Bumped on every [connect]/[disconnect]. Each callback captures the generation live when it
     * was registered and no-ops if it has since changed — so a late [onLost]/[onAvailable] from a
     * superseded request can never unbind or resurrect the network a newer request now owns.
     */
    private val generation = java.util.concurrent.atomic.AtomicInteger(0)

    /** Callbacks for the join lifecycle. All invoked on a binder thread — marshal to UI yourself. */
    interface Listener {
        /** The camera AP is joined and the process is bound to it. Safe to make HTTP calls. */
        fun onAvailable(network: Network)

        /** The join failed or was declined by the user. */
        fun onUnavailable()

        /** The AP was lost after being available (camera slept, out of range, etc.). */
        fun onLost()
    }

    /**
     * Request to join the camera AP [ssid] with WPA2 [passphrase]. Non-blocking; results arrive
     * via [listener]. Only one active connection is supported — calling again while connected
     * first tears down the previous request.
     */
    @SuppressLint("MissingPermission") // CHANGE_NETWORK_STATE is declared in the manifest.
    fun connect(ssid: String, passphrase: String, listener: Listener) {
        disconnect() // idempotent: drop any prior request first.
        val myGen = generation.incrementAndGet()

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(passphrase)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // Do NOT require INTERNET — the camera AP has none, and requiring it would make the
            // request unsatisfiable.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (myGen != generation.get()) return // superseded request — don't bind.
                // Pin this process's sockets to the camera AP.
                connectivityManager.bindProcessToNetwork(network)
                listener.onAvailable(network)
            }

            override fun onUnavailable() {
                if (myGen != generation.get()) return
                listener.onUnavailable()
            }

            override fun onLost(network: Network) {
                if (myGen != generation.get()) return // stale drop — don't unbind the live network.
                // Best-effort unbind so we don't strand the process on a dead network.
                connectivityManager.bindProcessToNetwork(null)
                listener.onLost()
            }
        }

        activeCallback.set(callback)
        // With a timeout so a wrong passphrase or dismissed system dialog surfaces as
        // onUnavailable() instead of hanging the "joining…" state indefinitely.
        connectivityManager.requestNetwork(request, callback, JOIN_TIMEOUT_MS)
    }

    /**
     * Release the camera AP: unbind the process and unregister the network callback. Safe to
     * call when not connected (no-op). ALWAYS call this to restore normal connectivity.
     */
    fun disconnect() {
        generation.incrementAndGet() // invalidate any in-flight callbacks from a prior connect().
        connectivityManager.bindProcessToNetwork(null)
        activeCallback.getAndSet(null)?.let { cb ->
            runCatching { connectivityManager.unregisterNetworkCallback(cb) }
        }
    }

    companion object {
        /** Max time to wait for the camera AP join before reporting onUnavailable(). */
        private const val JOIN_TIMEOUT_MS = 30_000

        /** True when this device supports the WifiNetworkSpecifier join path (API 29+). */
        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        // TODO(scaffold): pre-API-29 fallback (WifiManager.addNetwork/enableNetwork +
        // bindProcessToNetwork) is not implemented; API 26–28 devices cannot join the AP yet.
    }
}
