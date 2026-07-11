package com.ricohgr3.app.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ricohgr3.app.data.PhotoId
import com.ricohgr3.app.data.PhotoItem
import com.ricohgr3.app.data.PhotoRepository
import com.ricohgr3.app.data.PhotoResult
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
    /** Currently multi-selected photos (for future batch download/delete). */
    val selected: Set<PhotoId> = emptySet(),
) {
    val hasSelection: Boolean get() = selected.isNotEmpty()
    val selectionCount: Int get() = selected.size
    fun isSelected(id: PhotoId): Boolean = id in selected
}

/**
 * Owns the gallery's [GalleryUiState] and drives loads through a [PhotoRepository].
 *
 * A plain [ViewModel] (not `AndroidViewModel`) so the repository — and therefore the whole
 * gallery state layer — can be unit-tested on the JVM with a fake [CameraWifiController],
 * with no Android radio. Construct it directly in tests; use [Factory] for the Android side.
 */
class GalleryViewModel(
    private val repository: PhotoRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(GalleryUiState())
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()

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
     * Factory for constructing the ViewModel with a concrete [PhotoRepository] from the
     * Android side (e.g. `viewModels { GalleryViewModel.Factory(repo) }`).
     */
    class Factory(private val repository: PhotoRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(GalleryViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return GalleryViewModel(repository) as T
        }
    }
}
