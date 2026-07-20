package com.ricohgr3.app.gallery

import com.ricohgr3.app.data.EditedExportQuality
import com.ricohgr3.app.data.PhotoId
import com.ricohgr3.app.data.PhotoRepository
import com.ricohgr3.app.looks.emulation.RenderingIntent
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

        vm.applyLook(id, "provia")

        val s = vm.state.value
        assertTrue(s.isEdited(id))
        assertEquals("provia", s.lookFor(id))
        assertEquals(1, s.editedCount)
        // Last-used look sticks for the next frame.
        assertEquals("provia", s.stickyLook)
    }

    @Test
    fun `applyLook keeps per-frame and sticky intensity`() = runTest {
        val vm = viewModel(FakeCameraWifiController())
        val id = PhotoId("100RICOH", "R0000001.JPG")

        vm.applyLook(id, "portra400", intensity = 1.4f)

        val state = vm.state.value
        assertEquals(1.4f, state.intensityFor(id))
        assertEquals(1.4f, state.stickyIntensity)
    }

    @Test
    fun `applyLook keeps per-frame and sticky rendering intent`() = runTest {
        val vm = viewModel(FakeCameraWifiController())
        val id = PhotoId("100RICOH", "R0000001.JPG")

        vm.applyLook(
            id,
            "portra400",
            renderingIntent = RenderingIntent.STOCK,
        )

        val state = vm.state.value
        assertEquals(RenderingIntent.STOCK, state.renderingIntentFor(id))
        assertEquals(RenderingIntent.STOCK, state.stickyRenderingIntent)
    }

    @Test
    fun `applyLook STANDARD clears the edited mark`() = runTest {
        val vm = viewModel(FakeCameraWifiController())
        val id = PhotoId("100RICOH", "R0000001.JPG")
        vm.applyLook(id, "velvia")
        assertTrue(vm.state.value.isEdited(id))

        vm.applyLook(id, null)

        val s = vm.state.value
        assertFalse(s.isEdited(id))
        assertEquals(0, s.editedCount)
        // Standard is still remembered as the sticky look.
        assertEquals(null, s.stickyLook)
    }

    @Test
    fun `resetLook returns a frame to Standard without changing sticky`() = runTest {
        val vm = viewModel(FakeCameraWifiController())
        val id = PhotoId("100RICOH", "R0000001.JPG")
        vm.applyLook(id, "nostalgic_neg")

        vm.resetLook(id)

        val s = vm.state.value
        assertFalse(s.isEdited(id))
        // resetLook does not touch the sticky look — RETRO remains pre-selected.
        assertEquals("nostalgic_neg", s.stickyLook)
    }

    @Test
    fun `applyLookToSelection styles every selected frame and updates sticky`() = runTest {
        val vm = viewModel(FakeCameraWifiController())
        val a = PhotoId("100RICOH", "R0000001.JPG")
        val b = PhotoId("101RICOH", "R0000101.JPG")
        vm.toggleSelect(a)
        vm.toggleSelect(b)

        vm.applyLookToSelection(
            "bleach_bypass",
            intensity = 1.35f,
            renderingIntent = RenderingIntent.STOCK,
        )

        val s = vm.state.value
        assertTrue(s.isEdited(a))
        assertTrue(s.isEdited(b))
        assertEquals(2, s.editedCount)
        assertEquals("bleach_bypass", s.lookFor(a))
        assertEquals(1.35f, s.intensityFor(a))
        assertEquals(1.35f, s.intensityFor(b))
        assertEquals(RenderingIntent.STOCK, s.renderingIntentFor(a))
        assertEquals(RenderingIntent.STOCK, s.renderingIntentFor(b))
        assertEquals("bleach_bypass", s.stickyLook)
        assertEquals(1.35f, s.stickyIntensity)
        assertEquals(RenderingIntent.STOCK, s.stickyRenderingIntent)
    }

    @Test
    fun `batch controls update visible sticky settings without editing frames`() = runTest {
        val vm = viewModel(FakeCameraWifiController())

        vm.setStickyLook("portra400")
        vm.setStickyIntensity(1.4f)
        vm.setStickyRenderingIntent(RenderingIntent.STOCK)

        val state = vm.state.value
        assertEquals("portra400", state.stickyLook)
        assertEquals(1.4f, state.stickyIntensity)
        assertEquals(RenderingIntent.STOCK, state.stickyRenderingIntent)
        assertEquals(0, state.editedCount)
    }

    @Test
    fun `batch intensity control clamps before becoming effective`() = runTest {
        val vm = viewModel(FakeCameraWifiController())

        vm.setStickyIntensity(2f)
        assertEquals(1.5f, vm.state.value.stickyIntensity)

        vm.setStickyIntensity(0.1f)
        assertEquals(0.5f, vm.state.value.stickyIntensity)
    }

    @Test
    fun `setStickyLook updates the sticky default without editing any frame`() = runTest {
        val vm = viewModel(FakeCameraWifiController())

        vm.setStickyLook("classic_neg")

        val s = vm.state.value
        assertEquals("classic_neg", s.stickyLook)
        assertEquals(0, s.editedCount)
    }

    @Test
    fun `edited export quality updates without editing a frame`() = runTest {
        val vm = viewModel(FakeCameraWifiController())

        assertEquals(EditedExportQuality.HIGH, vm.state.value.editedExportQuality)
        vm.setEditedExportQuality(EditedExportQuality.MAXIMUM)

        assertEquals(EditedExportQuality.MAXIMUM, vm.state.value.editedExportQuality)
        assertEquals(0, vm.state.value.editedCount)
    }
}
