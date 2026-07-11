package com.ricohgr3.app.liveview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ricohgr3.app.looks.CameraLook
import com.ricohgr3.app.wifi.CameraWifiController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
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
            }
        }
    }

    private fun collectLiveview() {
        viewModelScope.launch {
            controller.liveview()
                .catch { e -> _state.update { it.copy(error = e.message ?: "Live view stopped") } }
                .collect { bytes ->
                    _state.update {
                        it.copy(frame = bytes, frameCount = it.frameCount + 1, error = null)
                    }
                }
        }
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
            val outcome = if (result.getOrNull()?.captured == true) {
                ShotResult.SUCCESS
            } else {
                ShotResult.FAILURE
            }
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
}
