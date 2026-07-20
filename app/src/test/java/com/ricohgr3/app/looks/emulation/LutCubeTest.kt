package com.ricohgr3.app.looks.emulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LutCubeTest {

    private fun sample(lut: LutCube, r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val out = FloatArray(3)
        lut.sample(r, g, b, out)
        return Triple(out[0], out[1], out[2])
    }

    @Test fun identityReturnsInputAtVertices() {
        val lut = LutCube.identity(17)
        val (r, g, b) = sample(lut, 0.25f, 0.5f, 0.75f)
        assertEquals(0.25f, r, 1e-4f)
        assertEquals(0.5f, g, 1e-4f)
        assertEquals(0.75f, b, 1e-4f)
    }

    @Test fun identityInterpolatesBetweenVertices() {
        // A value that falls between grid points must still round-trip through the identity.
        val lut = LutCube.identity(9)
        val (r, g, b) = sample(lut, 0.123f, 0.456f, 0.789f)
        assertEquals(0.123f, r, 1e-4f)
        assertEquals(0.456f, g, 1e-4f)
        assertEquals(0.789f, b, 1e-4f)
    }

    @Test fun sampleClampsOutOfRange() {
        val lut = LutCube.identity(5)
        val (r, _, b) = sample(lut, -1f, 0.5f, 2f)
        assertEquals(0f, r, 1e-4f)
        assertEquals(1f, b, 1e-4f)
    }

    @Test fun parseRoundTripsIdentityCube() {
        val text = buildString {
            appendLine("# a comment")
            appendLine("TITLE \"id\"")
            appendLine("LUT_3D_SIZE 2")
            appendLine("DOMAIN_MIN 0 0 0")
            for (b in 0..1) for (g in 0..1) for (r in 0..1) {
                appendLine("${r.toFloat()} ${g.toFloat()} ${b.toFloat()}")
            }
        }
        val lut = LutCube.parse(text)
        assertEquals(2, lut.size)
        val (r, g, b) = sample(lut, 1f, 0f, 1f)
        assertEquals(1f, r, 1e-4f)
        assertEquals(0f, g, 1e-4f)
        assertEquals(1f, b, 1e-4f)
    }

    @Test fun parseRejects1dLut() {
        assertThrows(IllegalArgumentException::class.java) {
            LutCube.parse("LUT_1D_SIZE 4\n0 0 0\n1 1 1\n0.5 0.5 0.5\n0.2 0.2 0.2")
        }
    }

    @Test fun parseRejectsWrongValueCount() {
        assertThrows(IllegalArgumentException::class.java) {
            LutCube.parse("LUT_3D_SIZE 2\n0 0 0\n1 1 1")
        }
    }

    @Test fun factoryMonochromeHasZeroSaturation() {
        val lut = FilmLutFactory.build(FilmLutFactory.Model(saturation = 0f), size = 17)
        // A saturated input must come out grey (R≈G≈B).
        val (r, g, b) = sample(lut, 0.8f, 0.2f, 0.2f)
        assertEquals(r, g, 1e-3f)
        assertEquals(g, b, 1e-3f)
    }

    @Test fun factoryPreservesBlackAndWhiteEndpoints() {
        val lut = FilmLutFactory.build(FilmLutFactory.Model(), size = 33)
        val black = sample(lut, 0f, 0f, 0f)
        val white = sample(lut, 1f, 1f, 1f)
        assertTrue("black stays near black", black.first < 0.05f)
        assertTrue("white stays near white", white.first > 0.9f)
    }

    @Test fun strongGradeShapesQuarterTonesWithoutMovingTheGreyPivot() {
        // Camera JPEGs already have a base tone curve. The print transform should shape lower/
        // upper tones visibly while keeping neutral mid-grey stable instead of double-developing.
        val model = FilmLutFactory.Model(
            r = FilmLutFactory.Channel(contrast = 0.5f, shoulder = 0.85f),
            g = FilmLutFactory.Channel(contrast = 0.5f, shoulder = 0.85f),
            b = FilmLutFactory.Channel(contrast = 0.5f, shoulder = 0.85f),
            saturation = 1.25f,
        )
        val lut = FilmLutFactory.build(model)
        val (mid, _, _) = sample(lut, 0.5f, 0.5f, 0.5f)
        assertEquals("mid-grey is a stable pivot", 0.5f, mid, 0.035f)
        val (quarter, _, _) = sample(lut, 0.25f, 0.25f, 0.25f)
        assertTrue("lower quarter receives visible print contrast ($quarter)", quarter < 0.23f)
        val red = sample(lut, 0.7f, 0.2f, 0.2f)
        assertTrue("high-sat model widens red-green separation", (red.first - red.second) > (0.7f - 0.2f) * 0.5f)
    }

    @Test fun channelsCanDivergeForColourCrossover() {
        // A blue-weighted model (cool/tungsten cast) must make a neutral grey pick up a blue cast.
        val model = FilmLutFactory.Model(
            r = FilmLutFactory.Channel(contrast = 0.4f, gain = 0.9f),
            g = FilmLutFactory.Channel(contrast = 0.4f, gain = 1.0f),
            b = FilmLutFactory.Channel(contrast = 0.4f, gain = 1.12f),
        )
        val lut = FilmLutFactory.build(model)
        val (r, _, b) = sample(lut, 0.4f, 0.4f, 0.4f)
        assertTrue("neutral grey gains a cool/blue cast (b=$b r=$r)", b > r)
    }
}
