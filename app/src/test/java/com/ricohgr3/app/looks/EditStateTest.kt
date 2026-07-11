package com.ricohgr3.app.looks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditStateTest {

    @Test
    fun emptyStateHasNoEditedFrames() {
        val state = EditState()
        assertFalse(state.isEdited("R0000001.JPG"))
        assertEquals(CameraLook.STANDARD, state.lookFor("R0000001.JPG"))
    }

    @Test
    fun applyMarksFrameEditedAndRecordsLook() {
        val state = EditState().apply("a", CameraLook.POSITIVE_FILM)
        assertTrue(state.isEdited("a"))
        assertEquals(CameraLook.POSITIVE_FILM, state.lookFor("a"))
        assertFalse(state.isEdited("b"))
    }

    @Test
    fun applyIsImmutable() {
        val original = EditState()
        original.apply("a", CameraLook.VIVID)
        assertFalse("original must be unchanged", original.isEdited("a"))
    }

    @Test
    fun applyOverwritesExistingLook() {
        val state = EditState()
            .apply("a", CameraLook.VIVID)
            .apply("a", CameraLook.MONOCHROME)
        assertEquals(CameraLook.MONOCHROME, state.lookFor("a"))
        assertEquals(1, state.applied.size)
    }

    @Test
    fun applyStandardResetsFrame() {
        val state = EditState()
            .apply("a", CameraLook.VIVID)
            .apply("a", CameraLook.STANDARD)
        assertFalse(state.isEdited("a"))
        assertEquals(CameraLook.STANDARD, state.lookFor("a"))
        assertTrue("Standard entries are not stored", state.applied.isEmpty())
    }

    @Test
    fun applyAllMarksEveryFrame() {
        val ids = listOf("a", "b", "c")
        val state = EditState().applyAll(ids, CameraLook.RETRO)
        for (id in ids) {
            assertTrue(state.isEdited(id))
            assertEquals(CameraLook.RETRO, state.lookFor(id))
        }
    }

    @Test
    fun applyAllWithStandardResetsAllTargets() {
        val state = EditState()
            .applyAll(listOf("a", "b", "c"), CameraLook.VIVID)
            .applyAll(listOf("a", "b"), CameraLook.STANDARD)
        assertFalse(state.isEdited("a"))
        assertFalse(state.isEdited("b"))
        assertTrue("untouched frame keeps its look", state.isEdited("c"))
    }

    @Test
    fun resetClearsMark() {
        val state = EditState()
            .apply("a", CameraLook.HARD_MONOCHROME)
            .reset("a")
        assertFalse(state.isEdited("a"))
    }

    @Test
    fun resetUnknownIdIsNoOp() {
        val state = EditState().apply("a", CameraLook.VIVID)
        val after = state.reset("missing")
        assertEquals(state, after)
    }
}
