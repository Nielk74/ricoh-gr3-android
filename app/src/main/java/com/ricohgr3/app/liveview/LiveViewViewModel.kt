package com.ricohgr3.app.liveview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ricohgr3.app.looks.CameraLook
import com.ricohgr3.app.wifi.CameraWifiController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Immutable UI state for the Wi-Fi live-view + shutter screen. Mirrors the gallery's
 * `GalleryUiState` style: a single data-class snapshot exposed via a [StateFlow], mutated
 * only through the ViewModel.
 */
data class LiveViewUiState(
    /** Latest complete JPEG frame from the MJPEG stream, or null before the first frame. */
    val frame: ByteArray? = null,
    /** How many frames have arrived — drives the mono frame counter and the "has stream" flag. */
    val frameCount: Int = 0,
    /** Active look, resolved from the camera's current `effect` prop. */
    val look: CameraLook = CameraLook.STANDARD,
    /** Battery percentage from `/v1/props`, or null if unknown. */
    val battery: Int? = null,
    /** Camera model from `/v1/props`, or null if unknown. */
    val model: String? = null,
    /** A shutter capture is in flight (POST /v1/camera/shoot pending). */
    val isShooting: Boolean = false,
    /** Outcome of the most recent shutter press, or null if none fired yet. */
    val lastShot: ShotResult? = null,
    /** Live-view stream error message, if the flow failed. */
    val error: String? = null,
) {
    /** True once at least one live-view frame has been received. */
    val hasStream: Boolean get() = frame != null

    // ByteArray needs structural equals/hashCode by content for state-diffing correctness.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LiveViewUiState) return false
        return frame.contentEqualsNullable(other.frame) &&
            frameCount == other.frameCount &&
            look == other.look &&
            battery == other.battery &&
            model == other.model &&
            isShooting == other.isShooting &&
            lastShot == other.lastShot &&
            error == other.error
    }

    override fun hashCode(): Int {
        var result = frame?.contentHashCode() ?: 0
        result = 31 * result + frameCount
        result = 31 * result + look.hashCode()
        result = 31 * result + (battery ?: 0)
        result = 31 * result + (model?.hashCode() ?: 0)
        result = 31 * result + isShooting.hashCode()
        result = 31 * result + (lastShot?.hashCode() ?: 0)
        result = 31 * result + (error?.hashCode() ?: 0)
        return result
    }
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    if (this == null || other == null) this === other else this.contentEquals(other)

/** Outcome of one shutter press. */
enum class ShotResult { SUCCESS, FAILURE }

/**
 * Drives the live-view stage and the Wi-Fi shutter. Collects [CameraWifiController.liveview]
 * into [LiveViewUiState.frame], loads [CameraWifiController.props] for the active look + battery,
 * and exposes [shoot] with in-flight / last-result state.
 *
 * A plain [ViewModel] (not `AndroidViewModel`) constructor-injected with a
 * [CameraWifiController], so the whole state layer is JVM-unit-testable with a fake controller
 * and no Android radio. Construct it directly in tests; use [Factory] on the Android side.
 */
class LiveViewViewModel(
    private val controller: CameraWifiController,
) : ViewModel() {

    private val _state = MutableStateFlow(LiveViewUiState())
    val state: StateFlow<LiveViewUiState> = _state.asStateFlow()

    /** The running live-view collector, so a retry can cancel a stale one before restarting. */
    private var liveviewJob: Job? = null

    init {
        loadProps()
        collectLiveview()
    }

    /** Load current camera properties for the active look, battery and model. Best-effort. */
    fun loadProps() {
        viewModelScope.launch {
            runCatching { controller.props() }.onSuccess { props ->
                _state.update {
                    it.copy(
                        look = CameraLook.fromEffect(props.effect),
                        battery = props.battery,
                        model = props.model,
                    )
                }
            }.onFailure { e ->
                // props is best-effort (look/battery/model are decoration), but a failure here is
                // the first sign the camera is unreachable — record it rather than silently
                // keeping stale defaults, so the UI isn't misleadingly "fine".
                _state.update { it.copy(error = it.error ?: (e.message ?: "Camera unreachable")) }
            }
        }
    }

    /**
     * (Re)start the live-view collector. The MJPEG stream can fail on a transient AP hiccup;
     * without recovery a single blip permanently freezes the viewfinder. So on stream *error* we
     * retry with a short backoff, up to [MAX_LIVEVIEW_RETRIES] consecutive failures, then surface a
     * terminal error the user can [retryLiveview] past manually. A successfully received frame
     * resets the failure counter. A stream that ends *cleanly* (camera closed it, no exception) is
     * a legitimate stop — we leave the last frame and do not spin.
     */
    private fun collectLiveview() {
        liveviewJob?.cancel()
        liveviewJob = viewModelScope.launch {
            var failures = 0
            while (isActive) {
                val failed = try {
                    controller.liveview().collect { bytes ->
                        failures = 0
                        _state.update {
                            it.copy(frame = bytes, frameCount = it.frameCount + 1, error = null)
                        }
                    }
                    false // completed without error — a clean end, stop retrying.
                } catch (e: CancellationException) {
                    throw e // cooperative cancellation (onCleared / retry) — don't swallow.
                } catch (e: Exception) {
                    true // errored — eligible for a backoff retry below.
                }
                if (!failed || !isActive) return@launch
                failures++
                if (failures >= MAX_LIVEVIEW_RETRIES) {
                    _state.update { it.copy(error = "Live view stopped — tap to retry.") }
                    return@launch
                }
                delay(RETRY_BACKOFF_MS)
            }
        }
    }

    /** Manually restart live view after it gave up (e.g. from a "retry" affordance). */
    fun retryLiveview() {
        _state.update { it.copy(error = null) }
        collectLiveview()
    }

    /**
     * Fire the shutter over Wi-Fi. Flips [LiveViewUiState.isShooting] on immediately and records
     * the outcome in [LiveViewUiState.lastShot]. A capture counts as success when the camera
     * reports `captured == true`; any error or a non-captured response is a failure.
     */
    fun shoot() {
        if (_state.value.isShooting) return
        _state.update { it.copy(isShooting = true) }
        viewModelScope.launch {
            val result = runCatching { controller.shoot() }
            val shot = result.getOrNull()
            // Success = the call returned an OK status (errCode null or 200) AND the camera
            // signalled a capture, either explicitly (captured == true) or by handing back a
            // captureId. Firmware that omits `captured` but returns a captureId used to be
            // misreported as a failure. A non-OK errCode (e.g. 503) is always a failure.
            val ok = shot != null &&
                (shot.errCode == null || shot.errCode == HTTP_OK) &&
                (shot.captured == true || shot.captureId != null)
            val outcome = if (ok) ShotResult.SUCCESS else ShotResult.FAILURE
            _state.update { it.copy(isShooting = false, lastShot = outcome) }
            // A capture may change the camera's active effect; refresh props opportunistically.
            if (outcome == ShotResult.SUCCESS) loadProps()
        }
    }

    /**
     * Factory for constructing the ViewModel with a concrete [CameraWifiController] from the
     * Android side (e.g. `viewModel(factory = LiveViewViewModel.Factory(controller))`).
     */
    class Factory(
        private val controller: CameraWifiController,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(LiveViewViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return LiveViewViewModel(controller) as T
        }
    }

    private companion object {
        /** The Ricoh API's OK status in its `errCode` field (HTTP-style). */
        const val HTTP_OK = 200

        /** Consecutive stream failures before giving up and showing a manual-retry error. */
        const val MAX_LIVEVIEW_RETRIES = 4

        /** Delay between live-view reconnect attempts. */
        const val RETRY_BACKOFF_MS = 1_000L
    }
}
