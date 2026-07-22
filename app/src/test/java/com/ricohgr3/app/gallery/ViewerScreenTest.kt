package com.ricohgr3.app.gallery

import com.ricohgr3.app.data.EditedExportQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerScreenTest {
    @Test
    fun `edited DNG export requires the platform RAW renderer`() {
        assertFalse(supportsPlatformDngDevelop(26))
        assertFalse(supportsPlatformDngDevelop(27))
        assertTrue(supportsPlatformDngDevelop(28))
        assertTrue(supportsPlatformDngDevelop(34))
    }

    @Test
    fun `quality summaries disclose resolution policy and JPEG setting`() {
        assertEquals(
            "Up to 1.5 MP · JPEG 92",
            exportQualitySummary(EditedExportQuality.COMPACT),
        )
        assertEquals("Up to 6 MP · JPEG 97", exportQualitySummary(EditedExportQuality.HIGH))
        assertTrue(exportQualitySummary(EditedExportQuality.MAXIMUM).contains("JPEG 100"))
        assertTrue(exportQualitySummary(EditedExportQuality.MAXIMUM).contains("original dimensions"))
        assertTrue(exportQualitySummary(EditedExportQuality.MAXIMUM).contains("device-safe maximum"))
    }

    @Test
    fun `edited save confirmation reports actual output decisions`() {
        assertEquals(
            "Saved R0000001_edit.jpg to Pictures/GR3 · 3000×2000 · JPEG 100",
            saveOutcomeMessage(
                SaveOutcome(
                    displayName = "R0000001_edit.jpg",
                    edited = true,
                    width = 3_000,
                    height = 2_000,
                    jpegQuality = 100,
                ),
            ),
        )
        assertEquals(
            "Saved to Pictures/GR3",
            saveOutcomeMessage(SaveOutcome("R0000001.JPG", edited = false)),
        )
    }
}
