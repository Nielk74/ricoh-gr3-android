package com.ricohgr3.app.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ricohgr3.app.data.PhotoId
import com.ricohgr3.app.data.PhotoItem
import com.ricohgr3.app.data.PhotoRepository
import com.ricohgr3.app.data.PhotoResult
import com.ricohgr3.app.looks.EditState
import com.ricohgr3.app.looks.StickyLookStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Immutable UI state for the photo gallery. Mirrors the BLE `BleState` style: a single
 * data class snapshot exposed via a [StateFlow], mutated only through the ViewModel.
 */
data class GalleryUiState(
    val photos: List<PhotoItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    /** Currently multi-selected photos (for batch look apply / future download/delete). */
    val selected: Set<PhotoId> = emptySet(),
    /** Which frames have a look applied, and which — drives the "edited mark". */
    val edits: EditState = EditState(),
    /** Last-used look, pre-selected for the next frames ("sticky default"). */
    val stickyLook: String? = null,
) {
    val hasSelection: Boolean get() = selected.isNotEmpty()
    val selectionCount: Int get() = selected.size
    fun isSelected(id: PhotoId): Boolean = id in selected

    /** True if [id] has a non-Standard look applied. */
    fun isEdited(id: PhotoId): Boolean = edits.isEdited(id.toString())

    /** The film-stock id applied to [id], or null (Standard) if none. */
    fun lookFor(id: PhotoId): String? = edits.lookFor(id.toString())

    /** Number of frames with a look applied. */
    val editedCount: Int get() = edits.applied.size
}

/**
 * Owns the gallery's [GalleryUiState] and drives loads through a [PhotoRepository].
 *
 * Also owns the shared **edit core** used by both the gallery and viewer screens: the
 * per-frame look map ([EditState], → the "edited mark") and the **sticky** last-used look
 * (persisted via [StickyLookStore]). Applying a look records it as sticky so the next frame
 * pre-selects it — the app's one-tap batch-styling rule (PHASE7-LOOKS.md §7.2).
 *
 * A plain [ViewModel] (not `AndroidViewModel`) so the repository — and therefore the whole
 * gallery state layer — can be unit-tested on the JVM with a fake `CameraWifiController` and
 * no [StickyLookStore], with no Android radio. Construct it directly in tests; use [Factory]
 * for the Android side, which wires in a real [StickyLookStore].
 */
class GalleryViewModel(
    private val repository: PhotoRepository,
    /** Optional persistence for the sticky look; null in JVM tests (in-memory only). */
    private val stickyLookStore: StickyLookStore? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(GalleryUiState())
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()

    init {
        // Hydrate the sticky look from persisted preferences, if we have a store.
        stickyLookStore?.let { store ->
            viewModelScope.launch {
                store.lookFlow.collect { persisted ->
                    _state.update { it.copy(stickyLook = persisted) }
                }
            }
        }
    }

    /** (Re)load the photo list from the camera, replacing the current list on success. */
    fun refresh() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.loadPhotos()) {
                is PhotoResult.Success -> _state.update {
                    // Drop any selections that no longer exist after a refresh.
                    val validIds = result.value.mapTo(HashSet()) { p -> p.id }
                    it.copy(
                        photos = result.value,
                        isLoading = false,
                        error = null,
                        selected = it.selected.intersect(validIds),
                    )
                }

                is PhotoResult.Error -> _state.update {
                    it.copy(isLoading = false, error = result.message)
                }
            }
        }
    }

    /** Toggle [id] in/out of the selection set. */
    fun toggleSelect(id: PhotoId) {
        _state.update {
            val next = it.selected.toMutableSet()
            if (!next.add(id)) next.remove(id)
            it.copy(selected = next)
        }
    }

    /** Clear the entire selection. */
    fun clearSelection() {
        _state.update { it.copy(selected = emptySet()) }
    }

    /**
     * Apply film-stock [look] to a single [id] and make it the sticky default. Applying
     * `null` (Standard) resets the frame (clears its edited mark) — but Standard is still
     * remembered as sticky, so "reset the roll" pre-selects Standard next.
     */
    fun applyLook(id: PhotoId, look: String?) {
        _state.update { it.copy(edits = it.edits.apply(id.toString(), look)) }
        persistSticky(look)
    }

    /**
     * Apply [look] to every currently selected frame (batch styling) and make it sticky.
     * No-op on the edit map if nothing is selected, but still records the sticky look.
     */
    fun applyLookToSelection(look: String?) {
        _state.update {
            val ids = it.selected.map { id -> id.toString() }
            it.copy(edits = it.edits.applyAll(ids, look))
        }
        persistSticky(look)
    }

    /** Clear the look on [id], returning it to Standard (unedited). */
    fun resetLook(id: PhotoId) {
        _state.update { it.copy(edits = it.edits.reset(id.toString())) }
    }

    /** Set the sticky (last-used) look without touching any frame. */
    fun setStickyLook(look: String?) {
        _state.update { it.copy(stickyLook = look) }
        persistSticky(look)
    }

    private fun persistSticky(look: String?) {
        _state.update { it.copy(stickyLook = look) }
        stickyLookStore?.let { store ->
            viewModelScope.launch { store.setLook(look) }
        }
    }

    /**
     * Factory for constructing the ViewModel with a concrete [PhotoRepository] (and optional
     * [StickyLookStore]) from the Android side (e.g. `viewModels { GalleryViewModel.Factory(...) }`).
     */
    class Factory(
        private val repository: PhotoRepository,
        private val stickyLookStore: StickyLookStore? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(GalleryViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return GalleryViewModel(repository, stickyLookStore) as T
        }
    }
}
