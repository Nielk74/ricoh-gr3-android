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
 * Joins the camera's own Wi-Fi access point and exposes its [Network], so camera HTTP
 * requests can reach `http://192.168.0.1/` even though that network has NO internet.
 *
 * ## How it works (API 29+)
 * The camera AP is internet-less. On modern Android you cannot just "switch Wi-Fi" and expect
 * app sockets to use it — the OS keeps the default network (cellular/other Wi-Fi) for anything
 * with an `INTERNET` capability. Instead we:
 *   1. Build a [WifiNetworkSpecifier] for the camera SSID + passphrase (obtained over BLE from
 *      the camera's WLAN Control service — see the roadmap).
 *   2. Ask [ConnectivityManager.requestNetwork] for a Wi-Fi network matching that specifier,
 *      WITHOUT the `NET_CAPABILITY_INTERNET` requirement (the AP has none).
 *   3. Pass the granted [Network] to [CameraHttpClient.forNetwork], which pins only camera HTTP
 *      sockets to the AP through [Network.getSocketFactory]. The process remains on Android's
 *      normal internet network, so unrelated traffic such as GitHub update checks keeps working.
 *
 * ## Teardown
 * [disconnect] unregisters the callback and releases the requested AP. ALWAYS call it (e.g. from
 * `onCleared`/`onStop`).
 *
 * ## API-level note
 * [WifiNetworkSpecifier] requires **API 29 (Android 10)**. The app's `minSdk` is 26; on API
 * 26–28 this class throws from [connect] — the caller must gate on [isSupported]. (A legacy
 * pre-29 path would need the deprecated `WifiManager.enableNetwork` APIs and is out of scope for
 * this scaffold — see TODO below.)
 *
 * ## Manual test steps (cannot be unit-tested on the host — needs a real device + camera)
 *  1. Wake the camera AP (via BLE WLAN Control, or manually on the camera) and note SSID/key.
 *  2. Ensure the phone has mobile data ON so you can prove the split: internet keeps working.
 *  3. Call [connect] with the SSID/passphrase; accept the system "join this Wi-Fi?" dialog.
 *  4. On [onAvailable], hit `GET http://192.168.0.1/v1/ping` via [CameraHttpClient] — expect 200.
 *  5. While still connected, run the in-app GitHub update check — it must use the normal default
 *     network and must not fail DNS resolution against the camera AP.
 *  6. Call [disconnect]; confirm `/v1/ping` now fails.
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
        /** The camera AP is joined. Use this [Network] to create a network-scoped HTTP client. */
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
                if (myGen != generation.get()) return // superseded request — don't publish it.
                listener.onAvailable(network)
            }

            override fun onUnavailable() {
                if (myGen != generation.get()) return
                listener.onUnavailable()
            }

            override fun onLost(network: Network) {
                if (myGen != generation.get()) return // stale drop — don't replace live state.
                listener.onLost()
            }
        }

        activeCallback.set(callback)
        // With a timeout so a wrong passphrase or dismissed system dialog surfaces as
        // onUnavailable() instead of hanging the "joining…" state indefinitely.
        connectivityManager.requestNetwork(request, callback, JOIN_TIMEOUT_MS)
    }

    /**
     * Adopt a Wi-Fi network the phone is **already** connected to (e.g. the user joined the camera
     * AP manually in Android Settings), rather than initiating a new [WifiNetworkSpecifier] join.
     *
     * Returns the [Network] if a currently-connected Wi-Fi network was found, or null if there is
     * no connected Wi-Fi at all. The caller creates a network-scoped camera client from it, then
     * probes `/v1/ping` to confirm it is actually the camera and not some other Wi-Fi. The app's
     * normal internet traffic is deliberately left on Android's default network.
     *
     * Note: this does NOT verify the network is the camera — it only finds *a* Wi-Fi network. The
     * probe is the caller's responsibility (a non-camera Wi-Fi will simply fail the ping).
     */
    @SuppressLint("MissingPermission") // ACCESS_NETWORK_STATE is declared in the manifest.
    fun adoptCurrentWifi(): Network? {
        // Prefer the active network if it's Wi-Fi; else scan all networks for a Wi-Fi transport.
        val wifiNetwork = connectivityManager.activeNetwork
            ?.takeIf { it.hasWifi() }
            ?: connectivityManager.allNetworks.firstOrNull { it.hasWifi() }
            ?: return null

        // Adopting an existing network supersedes any prior request/adopt, so bump the generation
        // to invalidate stale callbacks, and drop any WifiNetworkSpecifier request we own.
        generation.incrementAndGet()
        activeCallback.getAndSet(null)?.let { cb ->
            runCatching { connectivityManager.unregisterNetworkCallback(cb) }
        }
        return wifiNetwork
    }

    private fun Network.hasWifi(): Boolean =
        connectivityManager.getNetworkCapabilities(this)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

    /**
     * Release the camera AP request and unregister its callback. Safe to call when not connected
     * (no-op). Camera requests stop immediately because the session drops its scoped controller.
     */
    fun disconnect() {
        generation.incrementAndGet() // invalidate any in-flight callbacks from a prior connect().
        activeCallback.getAndSet(null)?.let { cb ->
            runCatching { connectivityManager.unregisterNetworkCallback(cb) }
        }
    }

    companion object {
        /** Max time to wait for the camera AP join before reporting onUnavailable(). */
        private const val JOIN_TIMEOUT_MS = 30_000

        /** True when this device supports the WifiNetworkSpecifier join path (API 29+). */
        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        // TODO(scaffold): pre-API-29 fallback (WifiManager.addNetwork/enableNetwork) is not
        // implemented; API 26–28 devices cannot join the AP yet.
    }
}
