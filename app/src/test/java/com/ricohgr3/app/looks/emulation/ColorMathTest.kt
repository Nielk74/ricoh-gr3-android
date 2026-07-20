package com.ricohgr3.app.looks.emulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorMathTest {

    @Test fun exactSrgbTransferMatchesReferenceMiddleGray() {
        assertEquals(0.21404114f, ColorMath.srgbToLinear(0.5f), 1e-6f)
        assertEquals(0.5f, ColorMath.linearToSrgb(0.21404114f), 1e-6f)
    }

    @Test fun oklabRoundTripPreservesInGamutSrgb() {
        val lab = FloatArray(3)
        val rgb = FloatArray(3)
        for (source in listOf(
            floatArrayOf(0.08f, 0.23f, 0.81f),
            floatArrayOf(0.72f, 0.39f, 0.22f),
            floatArrayOf(0.50f, 0.50f, 0.50f),
        )) {
            ColorMath.srgbToOklab(source[0], source[1], source[2], lab)
            ColorMath.oklabToSrgb(lab[0], lab[1], lab[2], rgb)
            assertEquals(source[0], rgb[0], 2e-5f)
            assertEquals(source[1], rgb[1], 2e-5f)
            assertEquals(source[2], rgb[2], 2e-5f)
        }
    }

    @Test fun luminancePlacementPreservesPhysicalLightAndGamut() {
        val out = FloatArray(3)
        ColorMath.putAtLinearLuminance(
            r = 0.92f,
            g = 0.12f,
            b = 0.04f,
            targetY = 0.18f,
            out = out,
        )
        assertEquals(0.18f, ColorMath.linearLuminance(out[0], out[1], out[2]), 1e-5f)
        out.forEach { assertTrue(it in 0f..1f) }
    }

    @Test fun renderSeedIsStablePerPhotoAndDistinctAcrossPhotos() {
        assertEquals(stableRenderSeed("100RICOH/R0000001.JPG"), stableRenderSeed("100RICOH/R0000001.JPG"))
        assertNotEquals(stableRenderSeed("100RICOH/R0000001.JPG"), stableRenderSeed("100RICOH/R0000002.JPG"))
    }

    @Test fun filmFormatRejectsNonPhysicalDimensions() {
        assertThrows(IllegalArgumentException::class.java) { FilmFormat(0f, 24f) }
        assertThrows(IllegalArgumentException::class.java) {
            FilmFormat(Float.POSITIVE_INFINITY, 24f)
        }
    }
}
