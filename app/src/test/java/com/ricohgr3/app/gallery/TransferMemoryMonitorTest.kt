package com.ricohgr3.app.gallery

import com.ricohgr3.app.data.EditedExportQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferMemoryMonitorTest {

    @Test
    fun `ample process and system memory enables a two-slot full-resolution pipeline`() {
        val snapshot = TransferMemorySnapshot(
            maxHeapBytes = 1024L * MIB,
            usedHeapBytes = 128L * MIB,
            systemAvailableBytes = 4L * 1024L * MIB,
            systemLowMemoryThresholdBytes = 256L * MIB,
            systemLowMemory = false,
        )

        val plan = chooseTransferPipeline(
            snapshot,
            TransferPreset(look = "portra400", quality = EditedExportQuality.MAXIMUM),
        )

        assertTrue(plan.isPipelined)
        assertEquals(2, plan.maxResidentDownloads)
        assertEquals(896L * MIB, plan.heapHeadroomBytes)
    }

    @Test
    fun `tight app heap keeps full-resolution processing strictly sequential`() {
        val snapshot = TransferMemorySnapshot(
            maxHeapBytes = 256L * MIB,
            usedHeapBytes = 200L * MIB,
            systemAvailableBytes = 4L * 1024L * MIB,
            systemLowMemoryThresholdBytes = 256L * MIB,
            systemLowMemory = false,
        )

        val plan = chooseTransferPipeline(snapshot, TransferPreset(look = "portra400"))

        assertFalse(plan.isPipelined)
        assertEquals(1, plan.maxResidentDownloads)
    }

    @Test
    fun `android low-memory signal overrides otherwise ample heap`() {
        val snapshot = TransferMemorySnapshot(
            maxHeapBytes = 1024L * MIB,
            usedHeapBytes = 64L * MIB,
            systemAvailableBytes = 200L * MIB,
            systemLowMemoryThresholdBytes = 256L * MIB,
            systemLowMemory = true,
        )

        assertEquals(
            1,
            chooseTransferPipeline(snapshot, TransferPreset(look = null)).maxResidentDownloads,
        )
    }

    @Test
    fun `full-resolution budget uses the tighter process or system limit`() {
        val heapLimited = TransferMemorySnapshot(
            maxHeapBytes = 512L * MIB,
            usedHeapBytes = 160L * MIB,
            systemAvailableBytes = 2_000L * MIB,
            systemLowMemoryThresholdBytes = 300L * MIB,
            systemLowMemory = false,
        )
        val systemLimited = heapLimited.copy(
            usedHeapBytes = 12L * MIB,
            systemAvailableBytes = 540L * MIB,
        )

        assertEquals(352L * MIB, fullResolutionRenderBudget(heapLimited))
        assertEquals(240L * MIB, fullResolutionRenderBudget(systemLimited))
        assertEquals(0L, fullResolutionRenderBudget(systemLimited.copy(systemLowMemory = true)))
    }

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
