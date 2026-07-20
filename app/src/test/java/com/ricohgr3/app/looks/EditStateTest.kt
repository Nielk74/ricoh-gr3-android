package com.ricohgr3.app.looks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditStateTest {

    @Test
    fun emptyStateHasNoEditedFrames() {
        val state = EditState()
        assertFalse(state.isEdited("R0000001.JPG"))
        assertNull(state.lookFor("R0000001.JPG"))
    }

    @Test
    fun applyMarksFrameEditedAndRecordsLook() {
        val state = EditState().apply("a", "provia")
        assertTrue(state.isEdited("a"))
        assertEquals("provia", state.lookFor("a"))
        assertEquals(1f, state.intensityFor("a"))
        assertFalse(state.isEdited("b"))
    }

    @Test
    fun intensityIsStoredClampedAndClearedWithTheLook() {
        val strong = EditState().apply("a", "portra400", intensity = 2f)
        assertEquals(1.5f, strong.intensityFor("a"))
        val reset = strong.reset("a")
        assertEquals(1f, reset.intensityFor("a"))
        assertTrue(reset.intensities.isEmpty())
    }

    @Test
    fun applyIsImmutable() {
        val original = EditState()
        original.apply("a", "velvia")
        assertFalse("original must be unchanged", original.isEdited("a"))
    }

    @Test
    fun applyOverwritesExistingLook() {
        val state = EditState()
            .apply("a", "velvia")
            .apply("a", "bleach_bypass")
        assertEquals("bleach_bypass", state.lookFor("a"))
        assertEquals(1, state.applied.size)
    }

    @Test
    fun applyStandardResetsFrame() {
        val state = EditState()
            .apply("a", "velvia")
            .apply("a", null)
        assertFalse(state.isEdited("a"))
        assertNull(state.lookFor("a"))
        assertTrue("Standard entries are not stored", state.applied.isEmpty())
        assertTrue("Standard intensities are not stored", state.intensities.isEmpty())
    }

    @Test
    fun applyAllMarksEveryFrame() {
        val ids = listOf("a", "b", "c")
        val state = EditState().applyAll(ids, "nostalgic_neg")
        for (id in ids) {
            assertTrue(state.isEdited(id))
            assertEquals("nostalgic_neg", state.lookFor(id))
        }
    }

    @Test
    fun applyAllWithStandardResetsAllTargets() {
        val state = EditState()
            .applyAll(listOf("a", "b", "c"), "velvia")
            .applyAll(listOf("a", "b"), null)
        assertFalse(state.isEdited("a"))
        assertFalse(state.isEdited("b"))
        assertTrue("untouched frame keeps its look", state.isEdited("c"))
    }

    @Test
    fun resetClearsMark() {
        val state = EditState()
            .apply("a", "bleach_bypass")
            .reset("a")
        assertFalse(state.isEdited("a"))
    }

    @Test
    fun resetUnknownIdIsNoOp() {
        val state = EditState().apply("a", "velvia")
        val after = state.reset("missing")
        assertEquals(state, after)
    }
}
