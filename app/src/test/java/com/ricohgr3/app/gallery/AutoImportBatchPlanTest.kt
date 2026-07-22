package com.ricohgr3.app.gallery

import com.ricohgr3.app.data.PhotoId
import com.ricohgr3.app.data.PhotoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoImportBatchPlanTest {
    private val jpeg = PhotoId("100RICOH", "R0000001.JPG")
    private val dng = PhotoId("100RICOH", "R0000001.DNG")
    private val jpegOnly = PhotoId("100RICOH", "R0000002.JPEG")
    private val movie = PhotoId("100RICOH", "R0000003.MP4")
    private val roll = listOf(jpeg, dng, jpegOnly, movie).map(::PhotoItem)

    @Test
    fun `edited-only plan prefers DNG and omits its paired JPEG`() {
        val plan = planAutoImport(
            roll,
            TransferPreset(
                look = "portra400",
                outputMode = TransferOutputMode.EDITED_ONLY,
            ),
        )

        assertEquals(listOf(dng, jpegOnly), plan.files.map { it.id })
        assertTrue(plan.files.all { it.developEdited })
        assertTrue(plan.files.none { it.saveOriginal })
        assertEquals(2, plan.downloadCount)
        assertEquals(2, plan.outputCount)
    }

    @Test
    fun `original plus edited keeps JPEG and DNG but develops the exposure once`() {
        val plan = planAutoImport(
            roll,
            TransferPreset(
                look = "portra800",
                outputMode = TransferOutputMode.ORIGINAL_AND_EDITED,
            ),
        )

        assertEquals(listOf(jpeg, dng, jpegOnly), plan.files.map { it.id })
        assertTrue(plan.files.all { it.saveOriginal })
        assertFalse(plan.files.single { it.id == jpeg }.developEdited)
        assertTrue(plan.files.single { it.id == dng }.developEdited)
        assertTrue(plan.files.single { it.id == jpegOnly }.developEdited)
        assertEquals(3, plan.downloadCount)
        assertEquals(5, plan.outputCount)
    }

    @Test
    fun `standard always saves every original regardless of requested edited mode`() {
        val plan = planAutoImport(
            roll,
            TransferPreset(
                look = null,
                outputMode = TransferOutputMode.EDITED_ONLY,
            ),
        )

        assertEquals(listOf(jpeg, dng, jpegOnly), plan.files.map { it.id })
        assertTrue(plan.files.all { it.saveOriginal })
        assertTrue(plan.files.none { it.developEdited })
        assertEquals(3, plan.outputCount)
    }
}
