package com.ricohgr3.app.data

import com.ricohgr3.app.nav.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the photo-viewer route encoding.
 *
 * The library crash was: `PhotoId.toString()` is `folder/file` (e.g. `100RICOH/R0000001.JPG`),
 * which was passed *raw* into the single-segment `viewer/{photoId}` route. Navigation-Compose
 * splits routes on `/`, so the extra slash made the route un-matchable and threw on open.
 *
 * [PhotoId.toRouteArg] / [PhotoId.fromRouteArg] must round-trip every id into ONE opaque
 * path segment with no bare `/`.
 */
class PhotoIdRouteTest {

    @Test
    fun `toRouteArg contains no bare slash for a normal id`() {
        val id = PhotoId("100RICOH", "R0000001.JPG")
        val arg = id.toRouteArg()
        assertFalse("encoded arg must be a single segment", arg.contains('/'))
    }

    @Test
    fun `route arg round-trips a normal id`() {
        val id = PhotoId("100RICOH", "R0000001.JPG")
        assertEquals(id, PhotoId.fromRouteArg(id.toRouteArg()))
    }

    @Test
    fun `built route matches the viewer route shape`() {
        val id = PhotoId("100RICOH", "R0000001.JPG")
        val route = Screen.Viewer.buildRoute(id.toRouteArg())
        // Exactly two segments: "viewer" and the opaque id — anything else won't match
        // the "viewer/{photoId}" pattern.
        assertEquals(2, route.split('/').size)
        assertTrue(route.startsWith("viewer/"))
    }

    @Test
    fun `round-trips RAW and lower-case and multi-dot names`() {
        for (id in listOf(
            PhotoId("101RICOH", "R0000002.DNG"),
            PhotoId("100ricoh", "r0000001.jpg"),
            PhotoId("100RICOH", "R000.0001.JPG"),
        )) {
            assertEquals(id, PhotoId.fromRouteArg(id.toRouteArg()))
        }
    }

    @Test
    fun `round-trips ids containing spaces and percents and unicode`() {
        for (id in listOf(
            PhotoId("MY FOLDER", "odd name.jpg"),
            PhotoId("100RICOH", "50%_crop.jpg"),
            PhotoId("フォルダ", "写真.JPG"),
        )) {
            val arg = id.toRouteArg()
            assertFalse(arg.contains('/'))
            assertFalse(arg.contains(' '))
            assertEquals(id, PhotoId.fromRouteArg(arg))
        }
    }

    @Test
    fun `fromRouteArg rejects empty malformed and unsplittable input`() {
        assertNull(PhotoId.fromRouteArg(""))
        assertNull("dangling percent escape", PhotoId.fromRouteArg("100RICOH%2"))
        assertNull("non-hex percent escape", PhotoId.fromRouteArg("100RICOH%ZZR.JPG"))
        // Decodes to "noslash" — no folder/file split.
        assertNull(PhotoId.fromRouteArg("noslash"))
        // Decodes to "/leading" — empty folder.
        assertNull(PhotoId.fromRouteArg("%2Fleading"))
        // Decodes to "trailing/" — empty file.
        assertNull(PhotoId.fromRouteArg("trailing%2F"))
    }
}
