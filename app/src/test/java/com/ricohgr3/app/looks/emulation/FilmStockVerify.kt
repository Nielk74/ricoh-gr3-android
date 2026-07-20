package com.ricohgr3.app.looks.emulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Catalog-level regression checks for the hand-authored, scene-adaptive stock set. */
class FilmStockVerify {
    private fun bareDevelop(id: String, r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val entry = FilmLookCatalog.entryFor(id)!!
        val look = entry.look.copy(
            grain = GrainParams.NONE,
            halation = HalationParams.NONE,
            splitTone = SplitTone.NONE,
            adaptive = AdaptiveParams.NONE,
        )
        val rr = floatArrayOf(r)
        val gg = floatArrayOf(g)
        val bb = floatArrayOf(b)
        DevelopPipeline.apply(rr, gg, bb, 1, 1, look, FilmLutFactory.build(entry.model))
        return Triple(rr[0], gg[0], bb[0])
    }

    @Test fun catalogIsCuratedUniqueAndLicenseCleanAtRuntime() {
        assertEquals(FilmLookCatalog.ids.size, FilmLookCatalog.ids.toSet().size)
        assertTrue(FilmLookCatalog.ids.size in 9..14)
        for (entry in FilmLookCatalog.entries) {
            assertNull("${entry.look.id} unexpectedly depends on an external LUT", entry.look.lutAsset)
            assertTrue("${entry.look.id} must enable scene adaptation", entry.look.adaptive.enabled)
        }
        assertNotNull(FilmLookCatalog.entryFor("portra400"))
        assertNotNull(FilmLookCatalog.entryFor("vision3_500t"))
        assertNotNull(FilmLookCatalog.entryFor("trix400"))
    }

    @Test fun legacyFujiIdsResolveToIntentionalNewEquivalents() {
        assertEquals("ektar100", FilmLookCatalog.entryFor("velvia")?.look?.id)
        assertEquals("portra400", FilmLookCatalog.entryFor("astia")?.look?.id)
        assertEquals("vision3_250d", FilmLookCatalog.entryFor("classic_chrome")?.look?.id)
    }

    @Test fun everyStockKeepsNeutralMidGreyPhotographic() {
        for (id in FilmLookCatalog.ids) {
            val (r, g, b) = bareDevelop(id, 0.5f, 0.5f, 0.5f)
            val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
            assertTrue("$id crushed mid-grey ($lum)", lum > 0.34f)
            assertTrue("$id washed mid-grey ($lum)", lum < 0.66f)
        }
    }

    @Test fun softStocksHoldShadowDetail() {
        for (id in listOf("portra400", "vision3_250d", "vision3_500t", "eterna", "hp5")) {
            val (r, g, b) = bareDevelop(id, 0.18f, 0.18f, 0.18f)
            val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
            assertTrue("$id crushes a useful shadow ($lum)", lum > 0.08f)
        }
    }

    @Test fun vividAndSoftStocksAreActuallyDifferentiated() {
        fun separation(id: String): Float {
            val (r, g, _) = bareDevelop(id, 0.72f, 0.30f, 0.26f)
            return r - g
        }
        assertTrue(
            "Ektar must separate warm colour more than Eterna",
            separation("ektar100") > separation("eterna") + 0.025f,
        )
    }

    @Test fun monochromeStocksRemoveColour() {
        for (id in listOf("trix400", "hp5")) {
            val (r, g, b) = bareDevelop(id, 0.75f, 0.24f, 0.18f)
            assertEquals("$id R/G", r, g, 0.005f)
            assertEquals("$id G/B", g, b, 0.005f)
        }
    }
}
