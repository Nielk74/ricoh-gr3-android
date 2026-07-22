package com.ricohgr3.app.gallery

import com.ricohgr3.app.RicohApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal interface AutoImportController {
    val state: StateFlow<TransferUiState>
    fun start(preset: TransferPreset)
    fun pause()
    fun retry()
    fun dismiss()
}

/** Application-scoped bridge between the activity UI and the foreground import service. */
internal class AutoImportManager(
    private val app: RicohApplication,
) : AutoImportController {
    internal val store = AutoImportJobStore(app)
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(
        store.load()?.toUiState() ?: TransferUiState(),
    )
    override val state: StateFlow<TransferUiState> = _state.asStateFlow()

    override fun start(preset: TransferPreset) {
        if (_state.value.isActive) return
        val normalized = preset.normalized()
        _state.value = TransferUiState(
            phase = TransferPhase.SCANNING,
            source = TransferSource.AUTO_IMPORT,
            preset = normalized,
            diskBacked = true,
            message = "Starting background import…",
        )
        AutoImportService.start(app, normalized)
    }

    override fun pause() {
        if (_state.value.isActive) AutoImportService.pause(app)
    }

    override fun retry() {
        if (_state.value.isActive) return
        if (runCatching { store.load() }.getOrNull() == null) {
            _state.value.preset?.let(::start)
                ?: publishFailure("There is no auto-import to continue")
        } else {
            AutoImportService.resume(app)
        }
    }

    override fun dismiss() {
        if (_state.value.isActive) return
        val dismissedJobId = runCatching { store.load()?.id }.getOrNull()
        _state.value = TransferUiState()
        AutoImportService.dismissNotification(app)
        // A paused/failed spool may contain gigabytes. Keep recursive cleanup off the UI thread;
        // the job-id guard prevents an immediately-following import from being deleted by this
        // delayed cleanup.
        maintenanceScope.launch { runCatching { store.clearIfCurrentJob(dismissedJobId) } }
    }

    internal fun publish(
        job: AutoImportJob,
        live: AutoImportLiveProgress = AutoImportLiveProgress(),
    ) {
        _state.value = job.toUiState(live)
    }

    internal fun publishTransient(state: TransferUiState) {
        _state.value = state
    }

    internal fun publishFailure(message: String, preset: TransferPreset? = null) {
        _state.value = TransferUiState(
            phase = TransferPhase.FAILED,
            source = TransferSource.AUTO_IMPORT,
            preset = preset,
            diskBacked = true,
            message = message,
        )
    }
}
