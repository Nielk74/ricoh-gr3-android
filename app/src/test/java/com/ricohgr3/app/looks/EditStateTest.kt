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
        val state = EditState().apply("a", "portra400")
        assertTrue(state.isEdited("a"))
        assertEquals("portra400", state.lookFor("a"))
        assertFalse(state.isEdited("b"))
    }

    @Test
    fun applyIsImmutable() {
        val original = EditState()
        original.apply("a", "ektar100")
        assertFalse("original must be unchanged", original.isEdited("a"))
    }

    @Test
    fun applyOverwritesExistingLook() {
        val state = EditState()
            .apply("a", "ektar100")
            .apply("a", "trix400")
        assertEquals("trix400", state.lookFor("a"))
        assertEquals(1, state.applied.size)
    }

    @Test
    fun applyStandardResetsFrame() {
        val state = EditState()
            .apply("a", "ektar100")
            .apply("a", null)
        assertFalse(state.isEdited("a"))
        assertNull(state.lookFor("a"))
        assertTrue("Standard entries are not stored", state.applied.isEmpty())
    }

    @Test
    fun applyAllMarksEveryFrame() {
        val ids = listOf("a", "b", "c")
        val state = EditState().applyAll(ids, "gold200")
        for (id in ids) {
            assertTrue(state.isEdited(id))
            assertEquals("gold200", state.lookFor(id))
        }
    }

    @Test
    fun applyAllWithStandardResetsAllTargets() {
        val state = EditState()
            .applyAll(listOf("a", "b", "c"), "ektar100")
            .applyAll(listOf("a", "b"), null)
        assertFalse(state.isEdited("a"))
        assertFalse(state.isEdited("b"))
        assertTrue("untouched frame keeps its look", state.isEdited("c"))
    }

    @Test
    fun resetClearsMark() {
        val state = EditState()
            .apply("a", "trix400")
            .reset("a")
        assertFalse(state.isEdited("a"))
    }

    @Test
    fun resetUnknownIdIsNoOp() {
        val state = EditState().apply("a", "ektar100")
        val after = state.reset("missing")
        assertEquals(state, after)
    }
}
