package com.ricohgr3.app.wifi

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A [CameraWifiController] that forwards every call to whatever controller the given
 * [CameraWifiSession] currently exposes in [CameraWifiSession.State.Connected].
 *
 * ## Why this exists
 * The screens (gallery, live view) are constructed once, at app start, with a single controller
 * instance. But the controller that can actually reach the camera is the one bound to the joined
 * Wi-Fi [android.net.Network] — and that only exists *after* [CameraWifiSession] reaches
 * `Connected`, and is rebuilt on every reconnect. Handing the screens a plain, unbound
 * [CameraHttpClient] (as an earlier version did) meant their requests were never routed onto the
 * camera AP's network via its socket factory — they worked only by luck of the process-wide
 * `bindProcessToNetwork`, and broke the moment that binding was cleared (e.g. on `onLost`).
 *
 * This delegate closes that gap: it resolves the *live* bound controller from the session on each
 * call, so a single stable instance can be injected into the screens up front while the actual
 * transport tracks the session lifecycle.
 *
 * If no session is connected, calls fail fast with [NotConnectedException] and [liveview] yields an
 * empty flow — the caller surfaces its normal "camera unreachable" state.
 */
class SessionBoundWifiController(
    private val session: CameraWifiSession,
) : CameraWifiController {

    /** Thrown when a camera call is attempted with no live Wi-Fi session. */
    class NotConnectedException :
        IllegalStateException("Not joined to the camera Wi-Fi (no active session)")

    private fun require(): CameraWifiController =
        session.controller ?: throw NotConnectedException()

    override suspend fun ping(): CameraTime = require().ping()

    override suspend fun props(): CameraProps = require().props()

    override suspend fun listPhotos(storage: String?, limit: Int?, after: String?): PhotoList =
        require().listPhotos(storage, limit, after)

    override suspend fun photoInfo(folder: String, file: String, storage: String?): PhotoInfo =
        require().photoInfo(folder, file, storage)

    override suspend fun downloadPhoto(
        folder: String,
        file: String,
        size: ImageSize,
        storage: String?,
    ): ByteArray = require().downloadPhoto(folder, file, size, storage)

    override suspend fun setCameraParams(params: CaptureParams): CameraProps =
        require().setCameraParams(params)

    override suspend fun shoot(): ShootResult = require().shoot()

    // Resolve the live controller at collection time; if none, emit nothing (caller shows its
    // "unavailable" state rather than crashing).
    override fun liveview(): Flow<ByteArray> =
        session.controller?.liveview() ?: emptyFlow()
}
