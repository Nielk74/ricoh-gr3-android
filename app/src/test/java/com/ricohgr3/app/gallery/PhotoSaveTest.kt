package com.ricohgr3.app.gallery

import com.ricohgr3.app.data.EditedExportQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoSaveTest {
    @Test
    fun `high quality preserves the previous heap-aware six megapixel ceiling`() {
        val mib = 1024L * 1024L
        val lowMemory = developmentPixelLimit(128L * mib, EditedExportQuality.HIGH)
        val midMemory = developmentPixelLimit(256L * mib, EditedExportQuality.HIGH)
        val highMemory = developmentPixelLimit(512L * mib, EditedExportQuality.HIGH)
        val veryHighMemory = developmentPixelLimit(2_048L * mib, EditedExportQuality.HIGH)

        assertTrue(lowMemory in 1_000_000 until 2_000_000)
        assertTrue(midMemory > lowMemory)
        assertEquals(6_000_000, highMemory)
        assertEquals(6_000_000, veryHighMemory)
    }

    @Test
    fun `quality presets expose distinct resolution and JPEG decisions`() {
        val largeHeap = 2_048L * 1024L * 1024L

        assertEquals(
            1_500_000,
            developmentPixelLimit(largeHeap, EditedExportQuality.COMPACT),
        )
        assertEquals(6_000_000, developmentPixelLimit(largeHeap, EditedExportQuality.HIGH))
        assertTrue(
            developmentPixelLimit(largeHeap, EditedExportQuality.MAXIMUM) > 24_000_000,
        )
        assertEquals(92, EditedExportQuality.COMPACT.jpegQuality)
        assertEquals(97, EditedExportQuality.HIGH.jpegQuality)
        assertEquals(100, EditedExportQuality.MAXIMUM.jpegQuality)
    }

    @Test
    fun `maximum removes the policy cap but keeps the heap safety limit`() {
        val mib = 1024L * 1024L
        val high = developmentPixelLimit(1_024L * mib, EditedExportQuality.HIGH)
        val maximum = developmentPixelLimit(1_024L * mib, EditedExportQuality.MAXIMUM)

        assertEquals(6_000_000, high)
        assertTrue(maximum in 12_000_000 until 13_000_000)
    }

    @Test
    fun `bounded decode uses the selected budget instead of a lower sampling tier`() {
        assertEquals(6_000 to 4_000, boundedDecodeDimensions(6_000, 4_000, 24_000_000))
        assertEquals(3_000 to 2_000, boundedDecodeDimensions(6_000, 4_000, 6_000_000))
        assertEquals(1_500 to 1_000, boundedDecodeDimensions(6_000, 4_000, 1_500_000))

        val intermediate = boundedDecodeDimensions(6_000, 4_000, 3_100_000)
        val intermediatePixels = intermediate.first.toLong() * intermediate.second
        assertTrue(intermediatePixels in 3_090_000L..3_100_000L)
        assertTrue(intermediatePixels > 1_500_000L)
    }

    @Test
    fun `development size retains a usable floor for constrained runtimes`() {
        for (quality in EditedExportQuality.entries) {
            assertEquals(MIN_EDIT_PIXELS, developmentPixelLimit(0L, quality))
            assertEquals(MIN_EDIT_PIXELS, developmentPixelLimit(-1L, quality))
            assertEquals(
                MIN_EDIT_PIXELS,
                developmentPixelLimit(16L * 1024L * 1024L, quality),
            )
        }
    }
}
