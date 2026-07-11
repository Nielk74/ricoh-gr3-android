package com.ricohgr3.app.gallery

import com.ricohgr3.app.data.PhotoId
import com.ricohgr3.app.data.PhotoRepository
import com.ricohgr3.app.looks.CameraLook
import com.ricohgr3.app.wifi.FakeCameraWifiController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [GalleryViewModel] driven through a [PhotoRepository] backed by an in-memory
 * [FakeCameraWifiController] — no Android radio, no network. `viewModelScope` is pinned to a
 * [StandardTestDispatcher] via `Dispatchers.setMain` so `refresh()`'s coroutine runs under the
 * test scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(controller: FakeCameraWifiController) =
        GalleryViewModel(PhotoRepository(controller))

    @Test
    fun `refresh success populates the photo list and clears loading`() = runTest {
        val vm = viewModel(FakeCameraWifiController())

        vm.refresh()
        // Loading flips on synchronously before the coroutine is dispatched.
        assertTrue(vm.state.value.isLoading)

        dispatcher.scheduler.advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.isLoading)
        assertNull(s.error)
        // 2 files in 100RICOH + 1 in 101RICOH = 3 photos, flattened in order.
        assertEquals(3, s.photos.size)
        assertEquals(PhotoId("100RICOH", "R0000001.JPG"), s.photos[0].id)
        assertEquals(PhotoId("101RICOH", "R0000101.JPG"), s.photos[2].id)
        assertTrue(s.photos[1].isRaw) // R0000002.DNG
    }

    @Test
    fun `refresh error sets the error message and leaves the list empty`() = runTest {
        val controller = FakeCameraWifiController(failWith = java.io.IOException("boom"))
        val vm = viewModel(controller)

        vm.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.isLoading)
        assertEquals("boom", s.error)
        assertTrue(s.photos.isEmpty())
    }

    @Test
    fun `toggleSelect adds then removes an id`() = runTest {
        val vm = viewModel(FakeCameraWifiController())
        val id = PhotoId("100RICOH", "R0000001.JPG")

        vm.toggleSelect(id)
        assertTrue(vm.state.value.isSelected(id))
        assertEquals(1, vm.state.value.selectionCount)

        vm.toggleSelect(id)
        assertFalse(vm.state.value.isSelected(id))
        assertFalse(vm.state.value.hasSelection)
    }

    @Test
    fun `clearSelection empties the selection set`() = runTest {
        val vm = viewModel(FakeCameraWifiController())
        vm.toggleSelect(PhotoId("100RICOH", "R0000001.JPG"))
        vm.toggleSelect(PhotoId("101RICOH", "R0000101.JPG"))
        assertEquals(2, vm.state.value.selectionCount)

        vm.clearSelection()

        assertFalse(vm.state.value.hasSelection)
        assertTrue(vm.state.value.selected.isEmpty())
    }

    @Test
    fun `refresh drops selections for photos no longer present`() = runTest {
        val vm = viewModel(FakeCameraWifiController())
        val stale = PhotoId("999OLD", "GONE.JPG")
        val valid = PhotoId("100RICOH", "R0000001.JPG")
        vm.toggleSelect(stale)
        vm.toggleSelect(valid)

        vm.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        val s = vm.state.value
        assertTrue(s.isSelected(valid))
        assertFalse(s.isSelected(stale))
    }

    // --- edit core: looks, edited mark, sticky default -------------------------------

    @Test
    fun `applyLook marks the frame edited and makes the look sticky`() = runTest {
        val vm = viewModel(FakeCameraWifiController())
        val id = PhotoId("100RICOH", "R0000001.JPG")

        vm.applyLook(id, CameraLook.POSITIVE_FILM)

        val s = vm.state.value
        assertTrue(s.isEdited(id))
        assertEquals(CameraLook.POSITIVE_FILM, s.lookFor(id))
        assertEquals(1, s.editedCount)
        // Last-used look sticks for the next frame.
        assertEquals(CameraLook.POSITIVE_FILM, s.stickyLook)
    }

    @Test
    fun `applyLook STANDARD clears the edited mark`() = runTest {
        val vm = viewModel(FakeCameraWifiController())
        val id = PhotoId("100RICOH", "R0000001.JPG")
        vm.applyLook(id, CameraLook.VIVID)
        assertTrue(vm.state.value.isEdited(id))

        vm.applyLook(id, CameraLook.STANDARD)

        val s = vm.state.value
        assertFalse(s.isEdited(id))
        assertEquals(0, s.editedCount)
        // Standard is still remembered as the sticky look.
        assertEquals(CameraLook.STANDARD, s.stickyLook)
    }

    @Test
    fun `resetLook returns a frame to Standard without changing sticky`() = runTest {
        val vm = viewModel(FakeCameraWifiController())
        val id = PhotoId("100RICOH", "R0000001.JPG")
        vm.applyLook(id, CameraLook.RETRO)

        vm.resetLook(id)

        val s = vm.state.value
        assertFalse(s.isEdited(id))
        // resetLook does not touch the sticky look — RETRO remains pre-selected.
        assertEquals(CameraLook.RETRO, s.stickyLook)
    }

    @Test
    fun `applyLookToSelection styles every selected frame and updates sticky`() = runTest {
        val vm = viewModel(FakeCameraWifiController())
        val a = PhotoId("100RICOH", "R0000001.JPG")
        val b = PhotoId("101RICOH", "R0000101.JPG")
        vm.toggleSelect(a)
        vm.toggleSelect(b)

        vm.applyLookToSelection(CameraLook.HARD_MONOCHROME)

        val s = vm.state.value
        assertTrue(s.isEdited(a))
        assertTrue(s.isEdited(b))
        assertEquals(2, s.editedCount)
        assertEquals(CameraLook.HARD_MONOCHROME, s.lookFor(a))
        assertEquals(CameraLook.HARD_MONOCHROME, s.stickyLook)
    }

    @Test
    fun `setStickyLook updates the sticky default without editing any frame`() = runTest {
        val vm = viewModel(FakeCameraWifiController())

        vm.setStickyLook(CameraLook.BLEACH_BYPASS)

        val s = vm.state.value
        assertEquals(CameraLook.BLEACH_BYPASS, s.stickyLook)
        assertEquals(0, s.editedCount)
    }
}
