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
            grainEnabled = false,
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
        assertTrue(saved.none { it.second.grainEnabled })
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
    fun `two-slot plan downloads the next full payload while the first frame processes`() = runTest {
        val firstSaveGate = CompletableDeferred<Unit>()
        val downloaded = mutableListOf<PhotoId>()
        val saved = mutableListOf<PhotoId>()
        val vm = TransferViewModel(
            photoLister = { PhotoResult.Success(emptyList()) },
            photoDownloader = { id, _, onProgress ->
                downloaded += id
                onProgress(4L, 8L)
                onProgress(8L, 8L)
                DownloadedTransfer(id, iso = 400, payload = FullPhotoPayload(ByteArray(8)))
            },
            downloadedSaver = { transfer, _, onPayloadConsumed, onStage ->
                onStage(TransferWorkStage.DEVELOPING)
                if (transfer.id == first) firstSaveGate.await()
                transfer.payload.take()
                onPayloadConsumed()
                onStage(TransferWorkStage.SAVING)
                saved += transfer.id
                SaveOutcome(transfer.id.file, edited = true)
            },
            pipelinePlanner = {
                TransferPipelinePlan(maxResidentDownloads = 2, heapHeadroomBytes = 512L * 1024L * 1024L)
            },
        )

        vm.startSelection(listOf(first, second), TransferPreset("portra400"))
        dispatcher.scheduler.runCurrent()

        assertEquals(listOf(first, second), downloaded)
        assertEquals(first, vm.state.value.processing)
        assertEquals(2, vm.state.value.downloadCompleted)
        assertEquals(0, vm.state.value.completed)
        assertEquals(2, vm.state.value.pipelineDepth)

        firstSaveGate.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(first, second), saved)
        assertEquals(TransferPhase.COMPLETED, vm.state.value.phase)
    }

    @Test
    fun `one-slot memory plan does not download a second payload during processing`() = runTest {
        val firstSaveGate = CompletableDeferred<Unit>()
        val downloaded = mutableListOf<PhotoId>()
        val vm = TransferViewModel(
            photoLister = { PhotoResult.Success(emptyList()) },
            photoDownloader = { id, _, onProgress ->
                downloaded += id
                onProgress(8L, 8L)
                DownloadedTransfer(id, iso = null, payload = FullPhotoPayload(ByteArray(8)))
            },
            downloadedSaver = { transfer, _, onPayloadConsumed, onStage ->
                onStage(TransferWorkStage.SAVING)
                transfer.payload.take()
                onPayloadConsumed()
                // The one-slot coordinator must ignore the early "compressed bytes consumed"
                // signal and wait for the whole save before admitting another full payload.
                if (transfer.id == first) firstSaveGate.await()
                SaveOutcome(transfer.id.file, edited = false)
            },
            pipelinePlanner = {
                TransferPipelinePlan(maxResidentDownloads = 1, heapHeadroomBytes = 32L * 1024L * 1024L)
            },
        )

        vm.startSelection(listOf(first, second), TransferPreset(look = null))
        dispatcher.scheduler.runCurrent()

        assertEquals(listOf(first), downloaded)
        assertEquals(1, vm.state.value.pipelineDepth)

        firstSaveGate.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(first, second), downloaded)
        assertEquals(2, vm.state.value.saved)
    }

    @Test
    fun `pause discards a prefetched payload and continue never duplicates a save`() = runTest {
        val firstSaveGate = CompletableDeferred<Unit>()
        val downloaded = mutableListOf<PhotoId>()
        val saved = mutableListOf<PhotoId>()
        val vm = TransferViewModel(
            photoLister = { PhotoResult.Success(emptyList()) },
            photoDownloader = { id, _, onProgress ->
                downloaded += id
                onProgress(8L, 8L)
                DownloadedTransfer(id, iso = null, payload = FullPhotoPayload(ByteArray(8)))
            },
            downloadedSaver = { transfer, _, onPayloadConsumed, onStage ->
                onStage(TransferWorkStage.SAVING)
                if (transfer.id == first) firstSaveGate.await()
                transfer.payload.take()
                onPayloadConsumed()
                saved += transfer.id
                SaveOutcome(transfer.id.file, edited = false)
            },
            pipelinePlanner = { TransferPipelinePlan(2, 512L * 1024L * 1024L) },
        )

        vm.startSelection(listOf(first, second, third), TransferPreset(look = null))
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf(first, second), downloaded)

        vm.cancel()
        firstSaveGate.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(TransferPhase.CANCELLED, vm.state.value.phase)
        assertEquals(listOf(first), saved)

        vm.retry()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(first, second, third), saved)
        assertEquals(TransferPhase.COMPLETED, vm.state.value.phase)
        assertEquals(2, downloaded.count { it == second })
    }

    @Test
    fun `byte progress advances the camera bar before a frame finishes downloading`() = runTest {
        val downloadGate = CompletableDeferred<Unit>()
        val vm = TransferViewModel(
            photoLister = { PhotoResult.Success(emptyList()) },
            photoDownloader = { id, _, onProgress ->
                onProgress(512L, 1024L)
                downloadGate.await()
                onProgress(1024L, 1024L)
                DownloadedTransfer(id, iso = null, payload = FullPhotoPayload(ByteArray(8)))
            },
            downloadedSaver = { transfer, _, onPayloadConsumed, onStage ->
                onStage(TransferWorkStage.SAVING)
                transfer.payload.take()
                onPayloadConsumed()
                SaveOutcome(transfer.id.file, edited = false)
            },
            pipelinePlanner = { TransferPipelinePlan(1, 64L * 1024L * 1024L) },
        )

        vm.startSelection(listOf(first), TransferPreset(look = null))
        dispatcher.scheduler.runCurrent()

        val inFlight = vm.state.value
        assertEquals(first, inFlight.downloading)
        assertEquals(512L, inFlight.downloadBytes)
        assertEquals(1024L, inFlight.downloadTotalBytes)
        assertEquals(0.5f, inFlight.downloadProgress)
        assertEquals(0.25f, inFlight.progress)

        downloadGate.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1f, vm.state.value.progress)
        assertEquals(TransferPhase.COMPLETED, vm.state.value.phase)
    }

    @Test
    fun `ISO parser accepts positive numeric camera metadata only`() {
        assertEquals(1600, parseTransferIso(" 1600 "))
        assertNull(parseTransferIso("auto"))
        assertNull(parseTransferIso("0"))
        assertNull(parseTransferIso(null))
    }

    @Test
    fun `full-resolution regions advance local and overall progress before the output completes`() {
        val state = TransferUiState(
            phase = TransferPhase.PROCESSING,
            total = 2,
            completed = 0,
            downloadTotal = 1,
            downloadCompleted = 1,
            processing = first,
            processingPart = 2,
            processingParts = 4,
            workStage = TransferWorkStage.DEVELOPING,
        )

        assertEquals(0.25f, state.saveProgress)
        assertEquals(0.625f, state.progress)
    }
}
