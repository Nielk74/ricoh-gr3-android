package com.ricohgr3.app.looks.emulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevelopPipelineTest {

    @Test fun identityLutNoLayersIsNoOp() {
        val look = FilmLook(id = "x", displayName = "X", lutAsset = null)
        val lut = LutCube.identity(9)
        val r = floatArrayOf(0.1f, 0.9f); val g = floatArrayOf(0.5f, 0.2f); val b = floatArrayOf(0.3f, 0.7f)
        DevelopPipeline.apply(r, g, b, width = 2, height = 1, look, lut)
        assertEquals(0.1f, r[0], 1e-3f)
        assertEquals(0.2f, g[1], 1e-3f)
        assertEquals(0.7f, b[1], 1e-3f)
    }

    @Test fun grainIsDeterministicForFixedSeed() {
        fun run(): FloatArray {
            val r = FloatArray(64) { 0.5f }; val g = FloatArray(64) { 0.5f }; val b = FloatArray(64) { 0.5f }
            DevelopPipeline.applyGrain(r, g, b, 8, 8, GrainParams(amount = 0.1f, size = 1f, shadowBias = 0.5f, seed = 7))
            return r
        }
        assertTrue("same seed -> identical grain", run().contentEquals(run()))
    }

    @Test fun grainIsActuallyVisibleAfterCorrelationBlur() {
        // Regression: blurring the noise field for correlation used to collapse grain amplitude
        // to <1 of 255 (invisible). With renormalisation, a size>1 grain must still land at a
        // clearly visible std (target: a few of 255), not near zero.
        val w = 128; val h = 128; val n = w * h
        val r = FloatArray(n) { 0.5f }; val g = FloatArray(n) { 0.5f }; val b = FloatArray(n) { 0.5f }
        DevelopPipeline.applyGrain(r, g, b, w, h, GrainParams(amount = 0.05f, size = 2.5f, shadowBias = 0.6f, seed = 9))
        var sum = 0.0; var sq = 0.0
        for (v in r) { val d = v - 0.5f; sum += d; sq += d.toDouble() * d }
        val std = kotlin.math.sqrt(sq / n - (sum / n) * (sum / n))
        assertTrue("grain must be visible (std=$std, ~${std * 255} of 255)", std * 255 > 2f)
    }

    @Test fun grainSeedChangesOutput() {
        fun run(seed: Long): FloatArray {
            val r = FloatArray(64) { 0.5f }; val g = FloatArray(64) { 0.5f }; val b = FloatArray(64) { 0.5f }
            DevelopPipeline.applyGrain(r, g, b, 8, 8, GrainParams(amount = 0.1f, size = 1f, shadowBias = 0.5f, seed = seed))
            return r
        }
        assertTrue("different seed -> different grain", !run(1).contentEquals(run(2)))
    }

    @Test fun halationBrightensNeighboursOfHighlight() {
        // A single bright pixel in a dark field must bleed red into its neighbours.
        val w = 9; val h = 9; val n = w * h
        val r = FloatArray(n) { 0.05f }; val g = FloatArray(n) { 0.05f }; val b = FloatArray(n) { 0.05f }
        val center = (h / 2) * w + (w / 2)
        r[center] = 1f; g[center] = 1f; b[center] = 1f
        val neighbour = center + 1
        val before = r[neighbour]
        DevelopPipeline.applyHalation(r, g, b, w, h,
            HalationParams(threshold = 0.5f, radius = 3, strength = 0.8f, tintR = 1f, tintG = 0.4f, tintB = 0.2f))
        assertTrue("neighbour red lifted by halation", r[neighbour] > before)
        assertTrue("halation is red-biased", r[neighbour] > b[neighbour])
    }

    @Test fun gaussianBlurConservesRoughMeanAndSpreads() {
        val w = 16; val h = 1
        val plane = FloatArray(w); plane[8] = 1f
        DevelopPipeline.gaussianBlur(plane, w, h, radius = 2)
        assertTrue("spike spread to neighbours", plane[7] > 0f && plane[9] > 0f)
        assertTrue("peak reduced", plane[8] < 1f)
    }

    @Test fun preGradeAddsContrastAndSaturation() {
        // The RAW pre-grade must push tones away from mid-grey (contrast) and boost saturation.
        val r = floatArrayOf(0.7f, 0.3f); val g = floatArrayOf(0.4f, 0.4f); val b = floatArrayOf(0.4f, 0.4f)
        DevelopPipeline.applyPreGrade(r, g, b, DevelopPipeline.PreGrade(contrast = 0.3f, saturation = 1.3f))
        // A bright, red-leaning pixel gets brighter+more saturated; a dark neutral gets darker.
        assertTrue("above-mid lifted", r[0] > 0.7f)
        assertTrue("below-mid pushed down", r[1] < 0.3f)
        assertTrue("saturation widened R vs B", (r[0] - b[0]) > (0.7f - 0.4f))
    }

    @Test fun preGradeNullIsAppliedOnlyWhenRequested() {
        // apply() with no preGrade must equal the identity path (no accidental base grade).
        val look = FilmLook(id = "x", displayName = "X", lutAsset = null)
        val lut = LutCube.identity(9)
        val r = floatArrayOf(0.6f); val g = floatArrayOf(0.6f); val b = floatArrayOf(0.6f)
        DevelopPipeline.apply(r, g, b, 1, 1, look, lut, preGrade = null)
        assertEquals(0.6f, r[0], 1e-3f)
    }

    @Test fun fullLookProducesDifferentiatedOutput() {
        // The Tri-X look (mono) must desaturate a colourful input.
        val entry = FilmLookCatalog.entryFor("trix400")!!
        val lut = FilmLutFactory.build(entry.model)
        val r = floatArrayOf(0.8f); val g = floatArrayOf(0.2f); val b = floatArrayOf(0.2f)
        DevelopPipeline.apply(r, g, b, 1, 1, entry.look, lut)
        assertEquals("mono look neutralises colour", r[0], g[0], 0.05f)
    }
}
