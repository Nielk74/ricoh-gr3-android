package com.ricohgr3.app.gallery

import com.ricohgr3.app.data.EditedExportQuality
import com.ricohgr3.app.data.PhotoId
import com.ricohgr3.app.looks.emulation.RenderingIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoImportJobTest {
    private val jpeg = PhotoId("100RICOH", "R0000001.JPG")
    private val dng = PhotoId("100RICOH", "R0000001.DNG")

    @Test
    fun `stored presets without an explicit grain choice retain authored grain`() {
        val stored = StoredTransferPreset(
            look = "portra400",
            intensity = 1f,
            renderingIntent = RenderingIntent.SMART.name,
            quality = EditedExportQuality.MAXIMUM.name,
            outputMode = TransferOutputMode.EDITED_ONLY.name,
        )

        assertTrue(stored.restore().grainEnabled)
    }

    @Test
    fun `job totals count two originals and one DNG edit independently`() {
        val job = AutoImportJob.create(
            AutoImportBatchPlan(
                listOf(
                    PlannedImportFile(jpeg, "shot", saveOriginal = true, developEdited = false),
                    PlannedImportFile(dng, "shot", saveOriginal = true, developEdited = true),
                ),
            ),
            TransferPreset(
                look = "portra400",
                grainEnabled = false,
                quality = EditedExportQuality.MAXIMUM,
                outputMode = TransferOutputMode.ORIGINAL_AND_EDITED,
            ),
        )

        assertEquals(2, job.downloadTotal)
        assertEquals(3, job.outputTotal)
        assertEquals(0, job.saved)
        assertEquals(TransferOutputMode.ORIGINAL_AND_EDITED, job.preset.restore().outputMode)
        assertEquals(false, job.preset.restore().grainEnabled)
    }

    @Test
    fun `process recovery resets running work and exposes a resumable paused state`() {
        val original = AutoImportJob.create(
            AutoImportBatchPlan(
                listOf(PlannedImportFile(jpeg, "shot", saveOriginal = true, developEdited = false)),
            ),
            TransferPreset(look = null, outputMode = TransferOutputMode.ORIGINAL_ONLY),
        )
        val interrupted = original.copy(
            phase = AutoImportJobPhase.DOWNLOADING,
            files = listOf(
                original.files.single().copy(downloadStatus = AutoImportItemStatus.RUNNING),
            ),
        )

        val recovered = interrupted.recoverAfterProcessDeath()

        assertEquals(AutoImportJobPhase.PAUSED, recovered.phase)
        assertEquals(AutoImportItemStatus.PENDING, recovered.files.single().downloadStatus)
        assertEquals(TransferPhase.CANCELLED, recovered.toUiState().phase)
        assertTrue(recovered.toUiState().message.orEmpty().contains("interrupted"))
    }

    @Test
    fun `retry preserves successful work and resets only failed stages`() {
        val original = AutoImportJob.create(
            AutoImportBatchPlan(
                listOf(PlannedImportFile(dng, "shot", saveOriginal = true, developEdited = true)),
            ),
            TransferPreset(
                look = "gold200",
                outputMode = TransferOutputMode.ORIGINAL_AND_EDITED,
            ),
        )
        val failed = original.copy(
            phase = AutoImportJobPhase.COMPLETED,
            files = listOf(
                original.files.single().copy(
                    downloadStatus = AutoImportItemStatus.SUCCESS,
                    originalStatus = AutoImportItemStatus.SUCCESS,
                    editStatus = AutoImportItemStatus.FAILED,
                    editError = "render failed",
                ),
            ),
        )

        val retry = failed.resetFailuresForRetry()

        assertEquals(AutoImportJobPhase.PROCESSING, retry.phase)
        assertEquals(AutoImportItemStatus.SUCCESS, retry.files.single().originalStatus)
        assertEquals(AutoImportItemStatus.PENDING, retry.files.single().editStatus)
        assertEquals(null, retry.files.single().editError)
    }
}
