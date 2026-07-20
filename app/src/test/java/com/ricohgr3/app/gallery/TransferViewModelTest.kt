package com.ricohgr3.app.gallery

import com.ricohgr3.app.data.EditedExportQuality
import com.ricohgr3.app.data.PhotoId
import com.ricohgr3.app.data.PhotoItem
import com.ricohgr3.app.data.PhotoResult
import com.ricohgr3.app.looks.emulation.RenderingIntent
import kotlinx.coroutines.CompletableDeferred
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

@OptIn(ExperimentalCoroutinesApi::class)
class TransferViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private val first = PhotoId("100RICOH", "R0000001.JPG")
    private val second = PhotoId("100RICOH", "R0000002.JPG")
    private val third = PhotoId("101RICOH", "R0000101.DNG")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `auto import scans then saves every camera frame in order with a frozen preset`() = runTest {
        val saved = mutableListOf<Pair<PhotoId, TransferPreset>>()
        var scans = 0
        val vm = TransferViewModel(
            photoLister = {
                scans++
                PhotoResult.Success(listOf(PhotoItem(first), PhotoItem(second), PhotoItem(third)))
            },
            photoSaver = { id, preset ->
                saved += id to preset
                SaveOutcome(id.file, edited = preset.look != null)
            },
        )
        val requested = TransferPreset(
            look = "portra400",
            intensity = 1.8f,
            renderingIntent = RenderingIntent.STOCK,
            quality = EditedExportQuality.MAXIMUM,
        )

        vm.startAutoImport(requested)
        assertEquals(TransferPhase.SCANNING, vm.state.value.phase)
        dispatcher.scheduler.advanceUntilIdle()

        val result = vm.state.value
        assertEquals(1, scans)
        assertEquals(listOf(first, second, third), saved.map { it.first })
        assertTrue(saved.all { it.second.intensity == 1.5f })
        assertTrue(saved.all { it.second.quality == EditedExportQuality.MAXIMUM })
        assertEquals(TransferPhase.COMPLETED, result.phase)
        assertEquals(3, result.total)
        assertEquals(3, result.completed)
        assertEquals(3, result.saved)
        assertEquals(1f, result.progress)
        assertTrue(result.failures.isEmpty())
        assertNull(result.current)
    }

    @Test
    fun `one failed frame does not stop the batch and retry touches only that frame`() = runTest {
        val attempts = mutableListOf<PhotoId>()
        var secondShouldFail = true
        val vm = TransferViewModel(
            photoLister = { PhotoResult.Success(emptyList()) },
            photoSaver = { id, _ ->
                attempts += id
                if (id == second && secondShouldFail) {
                    secondShouldFail = false
                    throw java.io.IOException("camera read failed")
                }
                SaveOutcome(id.file, edited = true)
            },
        )

        vm.startSelection(listOf(first, second, third), TransferPreset("gold200"))
        dispatcher.scheduler.advanceUntilIdle()

        val firstRun = vm.state.value
        assertEquals(TransferPhase.COMPLETED, firstRun.phase)
        assertEquals(3, firstRun.completed)
        assertEquals(2, firstRun.saved)
        assertEquals(listOf(second), firstRun.failures.map { it.id })
        assertEquals("camera read failed", firstRun.failures.single().reason)

        vm.retry()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(first, second, third, second), attempts)
        assertEquals(TransferPhase.COMPLETED, vm.state.value.phase)
        assertEquals(1, vm.state.value.total)
        assertEquals(1, vm.state.value.saved)
        assertTrue(vm.state.value.failures.isEmpty())
    }

    @Test
    fun `selection save keeps visible order drops duplicates and never scans`() = runTest {
        var scans = 0
        val saved = mutableListOf<PhotoId>()
        val vm = TransferViewModel(
            photoLister = {
                scans++
                PhotoResult.Success(emptyList())
            },
            photoSaver = { id, _ ->
                saved += id
                SaveOutcome(id.file, edited = false)
            },
        )

        vm.startSelection(
            listOf(third, first, third, second),
            TransferPreset(look = null),
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, scans)
        assertEquals(listOf(third, first, second), saved)
        assertEquals(TransferSource.SELECTION, vm.state.value.source)
        assertEquals(3, vm.state.value.saved)
    }

    @Test
    fun `pause finishes the in-flight frame and resumes without duplicating it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val saved = mutableListOf<PhotoId>()
        val vm = TransferViewModel(
            photoLister = { PhotoResult.Success(emptyList()) },
            photoSaver = { id, _ ->
                if (id == first) gate.await()
                saved += id
                SaveOutcome(id.file, edited = true)
            },
        )

        vm.startSelection(listOf(first, second), TransferPreset("portra800"))
        dispatcher.scheduler.runCurrent()
        assertEquals(first, vm.state.value.current)
        assertTrue(vm.state.value.isActive)

        vm.cancel()
        assertTrue(vm.state.value.stopRequested)
        gate.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()

        val stopped = vm.state.value
        assertEquals(TransferPhase.CANCELLED, stopped.phase)
        assertEquals(1, stopped.saved)
        assertEquals(1, stopped.completed)
        assertFalse(stopped.stopRequested)
        assertTrue(stopped.message.orEmpty().contains("1 of 2"))
        assertEquals(listOf(first), saved)

        vm.retry()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(first, second), saved)
        assertEquals(TransferPhase.COMPLETED, vm.state.value.phase)
        assertEquals(1, vm.state.value.total)
        assertEquals(1, vm.state.value.saved)
    }

    @Test
    fun `camera scan failure is retryable and does not invent a frame total`() = runTest {
        var fail = true
        var scans = 0
        val vm = TransferViewModel(
            photoLister = {
                scans++
                if (fail) PhotoResult.Error("camera offline")
                else PhotoResult.Success(listOf(PhotoItem(first)))
            },
            photoSaver = { id, _ -> SaveOutcome(id.file, edited = false) },
        )

        vm.startAutoImport(TransferPreset(look = null))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(TransferPhase.FAILED, vm.state.value.phase)
        assertEquals("camera offline", vm.state.value.message)
        assertEquals(0, vm.state.value.total)

        fail = false
        vm.retry()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, scans)
        assertEquals(TransferPhase.COMPLETED, vm.state.value.phase)
        assertEquals(1, vm.state.value.saved)
    }

    @Test
    fun `empty camera roll completes with an explicit no photos result`() = runTest {
        val vm = TransferViewModel(
            photoLister = { PhotoResult.Success(emptyList()) },
            photoSaver = { id, _ -> SaveOutcome(id.file, edited = false) },
        )

        vm.startAutoImport(TransferPreset(look = null))
        dispatcher.scheduler.advanceUntilIdle()

        val result = vm.state.value
        assertEquals(TransferPhase.COMPLETED, result.phase)
        assertEquals(0, result.total)
        assertEquals(0, result.saved)
        assertEquals("No photos found on the camera", result.message)
    }

    @Test
    fun `ISO parser accepts positive numeric camera metadata only`() {
        assertEquals(1600, parseTransferIso(" 1600 "))
        assertNull(parseTransferIso("auto"))
        assertNull(parseTransferIso("0"))
        assertNull(parseTransferIso(null))
    }
}
