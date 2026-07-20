package com.ricohgr3.app.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ricohgr3.app.data.EditedExportQuality
import com.ricohgr3.app.data.PhotoExporter
import com.ricohgr3.app.data.PhotoId
import com.ricohgr3.app.data.PhotoItem
import com.ricohgr3.app.data.PhotoRepository
import com.ricohgr3.app.data.PhotoResult
import com.ricohgr3.app.looks.emulation.FilmLookLoader
import com.ricohgr3.app.looks.emulation.RenderingIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The settings frozen at the start of an auto-import or selection save. */
data class TransferPreset(
    val look: String?,
    val intensity: Float = 1f,
    val renderingIntent: RenderingIntent = RenderingIntent.SMART,
    val quality: EditedExportQuality = EditedExportQuality.HIGH,
) {
    /** Clamp values arriving from UI state before a long-running transfer captures them. */
    fun normalized(): TransferPreset = copy(intensity = intensity.coerceIn(0.5f, 1.5f))
}

enum class TransferSource {
    AUTO_IMPORT,
    SELECTION,
}

enum class TransferPhase {
    IDLE,
    SCANNING,
    TRANSFERRING,
    COMPLETED,
    CANCELLED,
    FAILED,
}

data class TransferFailure(
    val id: PhotoId,
    val reason: String,
)

/**
 * One observable transfer snapshot shared by the auto-import page and the gallery batch panel.
 * Progress is deliberately item-based: the camera API does not expose response byte counts, but
 * every finished frame advances a stable, useful total and the current filename remains visible.
 */
data class TransferUiState(
    val phase: TransferPhase = TransferPhase.IDLE,
    val source: TransferSource? = null,
    val preset: TransferPreset? = null,
    val total: Int = 0,
    val completed: Int = 0,
    val saved: Int = 0,
    val current: PhotoId? = null,
    val failures: List<TransferFailure> = emptyList(),
    val message: String? = null,
    val stopRequested: Boolean = false,
) {
    val isActive: Boolean
        get() = phase == TransferPhase.SCANNING || phase == TransferPhase.TRANSFERRING

    val isTerminal: Boolean
        get() = phase == TransferPhase.COMPLETED || phase == TransferPhase.CANCELLED ||
            phase == TransferPhase.FAILED

    val progress: Float
        get() = when {
            phase == TransferPhase.COMPLETED -> 1f
            total <= 0 -> 0f
            else -> (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }

    val currentNumber: Int
        get() = if (total == 0) 0 else (completed + 1).coerceAtMost(total)
}

/**
 * Runs full-resolution saves strictly one at a time. Sequential work is important here: a single
 * film develop can use a sizeable, heap-bounded working set, so parallel exports would trade a
 * small speed gain for a much greater out-of-memory risk.
 */
class TransferViewModel internal constructor(
    private val photoLister: suspend () -> PhotoResult<List<PhotoItem>>,
    private val photoSaver: suspend (PhotoId, TransferPreset) -> SaveOutcome,
) : ViewModel() {

    constructor(
        repository: PhotoRepository,
        exporter: PhotoExporter,
        filmLookLoader: FilmLookLoader,
    ) : this(
        photoLister = { repository.loadPhotos() },
        photoSaver = { id, preset ->
            // ISO is best-effort metadata. A missing info response must not prevent the image from
            // importing; it only means the grain model uses its neutral fallback.
            val iso = if (preset.look == null) {
                null
            } else {
                when (val info = repository.photoInfo(id)) {
                    is PhotoResult.Success -> parseTransferIso(info.value.sv)
                    is PhotoResult.Error -> null
                }
            }
            saveEdited(
                id = id,
                filmLookId = preset.look,
                repository = repository,
                exporter = exporter,
                loader = filmLookLoader,
                iso = iso,
                effectStrength = preset.intensity,
                renderingIntent = preset.renderingIntent,
                exportQuality = preset.quality,
            )
        },
    )

    private val _state = MutableStateFlow(TransferUiState())
    val state: StateFlow<TransferUiState> = _state.asStateFlow()

    private var resumableIds: List<PhotoId> = emptyList()

    /** Scan the connected camera, then save every listed frame with one frozen preset. */
    fun startAutoImport(preset: TransferPreset) {
        if (_state.value.isActive) return
        val safePreset = preset.normalized()
        resumableIds = emptyList()
        _state.value = TransferUiState(
            phase = TransferPhase.SCANNING,
            source = TransferSource.AUTO_IMPORT,
            preset = safePreset,
        )
        viewModelScope.launch {
            runTransferGuarded {
                when (val result = photoLister()) {
                    is PhotoResult.Success -> process(
                        ids = result.value.map { it.id }.distinct(),
                        preset = safePreset,
                        source = TransferSource.AUTO_IMPORT,
                    )

                    is PhotoResult.Error -> _state.update {
                        it.copy(
                            phase = TransferPhase.FAILED,
                            current = null,
                            message = result.message,
                        )
                    }
                }
            }
        }
    }

    /** Save the supplied contact-sheet selection in its visible order. */
    fun startSelection(ids: List<PhotoId>, preset: TransferPreset) {
        startKnownIds(ids, preset, TransferSource.SELECTION)
    }

    /** Finish the in-flight frame, then pause before the next one to avoid duplicate saves. */
    fun cancel() {
        if (!_state.value.isActive) return
        _state.update { it.copy(stopRequested = true) }
    }

    /** Retry failed frames, or repeat a camera scan when the scan itself failed. */
    fun retry() {
        val previous = _state.value
        if (previous.isActive) return
        val preset = previous.preset ?: return
        val failedIds = previous.failures.map { it.id }
        when {
            previous.phase == TransferPhase.CANCELLED &&
                (failedIds.isNotEmpty() || resumableIds.isNotEmpty()) ->
                startKnownIds(
                    ids = (failedIds + resumableIds).distinct(),
                    preset = preset,
                    source = previous.source ?: TransferSource.SELECTION,
                )

            failedIds.isNotEmpty() -> startKnownIds(
                ids = failedIds,
                preset = preset,
                source = previous.source ?: TransferSource.SELECTION,
            )

            previous.source == TransferSource.AUTO_IMPORT -> startAutoImport(preset)
        }
    }

    /** Return the panels to their preset state after the user has reviewed the result. */
    fun dismiss() {
        if (!_state.value.isActive) {
            resumableIds = emptyList()
            _state.value = TransferUiState()
        }
    }

    private fun startKnownIds(
        ids: List<PhotoId>,
        preset: TransferPreset,
        source: TransferSource,
    ) {
        if (_state.value.isActive) return
        val safeIds = ids.distinct()
        if (safeIds.isEmpty()) return
        val safePreset = preset.normalized()
        resumableIds = safeIds
        _state.value = TransferUiState(
            phase = TransferPhase.TRANSFERRING,
            source = source,
            preset = safePreset,
            total = safeIds.size,
        )
        viewModelScope.launch {
            runTransferGuarded {
                process(safeIds, safePreset, source)
            }
        }
    }

    private suspend fun runTransferGuarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            _state.update {
                it.copy(
                    phase = TransferPhase.CANCELLED,
                    current = null,
                    stopRequested = false,
                    message = "Stopped after ${it.completed} of ${it.total} frames",
                )
            }
            throw cancelled
        } catch (failure: Throwable) {
            _state.update {
                it.copy(
                    phase = TransferPhase.FAILED,
                    current = null,
                    stopRequested = false,
                    message = transferFailureReason(failure),
                )
            }
        }
    }

    private suspend fun process(
        ids: List<PhotoId>,
        preset: TransferPreset,
        source: TransferSource,
    ) {
        if (ids.isEmpty()) {
            resumableIds = emptyList()
            _state.value = TransferUiState(
                phase = TransferPhase.COMPLETED,
                source = source,
                preset = preset,
                message = "No photos found on the camera",
            )
            return
        }

        // A pause may have been requested while the camera roll itself was being scanned.
        if (_state.value.stopRequested) {
            resumableIds = ids
            _state.update {
                it.copy(
                    phase = TransferPhase.CANCELLED,
                    total = ids.size,
                    current = null,
                    stopRequested = false,
                    message = "Paused before the first frame",
                )
            }
            return
        }

        var saved = 0
        val failures = mutableListOf<TransferFailure>()
        ids.forEachIndexed { index, id ->
            resumableIds = ids.drop(index)
            _state.value = TransferUiState(
                phase = TransferPhase.TRANSFERRING,
                source = source,
                preset = preset,
                total = ids.size,
                completed = index,
                saved = saved,
                current = id,
                failures = failures.toList(),
            )

            try {
                photoSaver(id, preset)
                saved++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                failures += TransferFailure(id, transferFailureReason(failure))
            }

            _state.update {
                it.copy(
                    completed = index + 1,
                    saved = saved,
                    current = null,
                    failures = failures.toList(),
                )
            }

            resumableIds = ids.drop(index + 1)
            if (_state.value.stopRequested && resumableIds.isNotEmpty()) {
                _state.update {
                    it.copy(
                        phase = TransferPhase.CANCELLED,
                        current = null,
                        stopRequested = false,
                        message = "Paused after ${it.completed} of ${it.total} frames",
                    )
                }
                return
            }
        }

        resumableIds = emptyList()
        _state.update {
            it.copy(
                phase = TransferPhase.COMPLETED,
                completed = ids.size,
                saved = saved,
                current = null,
                failures = failures.toList(),
                stopRequested = false,
            )
        }
    }

    class Factory(
        private val repository: PhotoRepository,
        private val exporter: PhotoExporter,
        private val filmLookLoader: FilmLookLoader,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TransferViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return TransferViewModel(repository, exporter, filmLookLoader) as T
        }
    }
}

/** Ricoh reports ISO as strings such as "500"; unknown/auto values intentionally stay null. */
internal fun parseTransferIso(value: String?): Int? =
    value?.trim()?.toIntOrNull()?.takeIf { it > 0 }

private fun transferFailureReason(failure: Throwable): String =
    if (failure is OutOfMemoryError) {
        "Not enough memory to develop this frame"
    } else {
        failure.message?.takeIf { it.isNotBlank() } ?: failure::class.simpleName ?: "Unknown error"
    }
