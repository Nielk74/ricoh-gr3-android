package com.ricohgr3.app.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoSaveTest {
    @Test
    fun `development size grows with heap and reaches a hard six megapixel ceiling`() {
        val mib = 1024L * 1024L
        val lowMemory = developmentPixelLimit(128L * mib)
        val midMemory = developmentPixelLimit(256L * mib)
        val highMemory = developmentPixelLimit(512L * mib)
        val veryHighMemory = developmentPixelLimit(2_048L * mib)

        assertTrue(lowMemory in 1_000_000 until 2_000_000)
        assertTrue(midMemory > lowMemory)
        assertEquals(MAX_EDIT_PIXELS, highMemory)
        assertEquals(MAX_EDIT_PIXELS, veryHighMemory)
    }

    @Test
    fun `development size retains a usable floor for constrained runtimes`() {
        assertEquals(MIN_EDIT_PIXELS, developmentPixelLimit(0L))
        assertEquals(MIN_EDIT_PIXELS, developmentPixelLimit(-1L))
        assertEquals(MIN_EDIT_PIXELS, developmentPixelLimit(16L * 1024L * 1024L))
    }
}
