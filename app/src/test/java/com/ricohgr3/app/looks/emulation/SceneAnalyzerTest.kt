package com.ricohgr3.app.looks.emulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneAnalyzerTest {
    private fun neutralFrame(width: Int, height: Int, value: (Int) -> Float): Triple<FloatArray, FloatArray, FloatArray> {
        val r = FloatArray(width * height) { value(it) }
        return Triple(r, r.copyOf(), r.copyOf())
    }

    @Test fun highKeyClippedSceneProtectsHighlightsAndDoesNotBrighten() {
        val (r, g, b) = neutralFrame(100, 100) { if (it < 8_000) 1f else 0.25f }
        val profile = SceneAnalyzer.analyze(r, g, b, 100, 100)
        val adjustment = SceneAnalyzer.adjustment(profile, AdaptiveParams())
        assertTrue(profile.highKey)
        assertTrue(profile.clippedHighlights > 0.5f)
        assertTrue("clipped scene must not be brightened", adjustment.exposureEv <= 0f)
        assertTrue("strong shoulder required", adjustment.highlightCompression > 0.4f)
    }

    @Test fun lowKeySceneGetsBoundedLiftWithoutLosingMood() {
        val (r, g, b) = neutralFrame(100, 100) { if (it < 300) 0.9f else 0.08f }
        val profile = SceneAnalyzer.analyze(r, g, b, 100, 100)
        val adjustment = SceneAnalyzer.adjustment(profile, AdaptiveParams())
        assertTrue(profile.lowKey)
        assertTrue(adjustment.exposureEv in 0f..0.18f)
        assertTrue(adjustment.shadowLift > 0f)
        assertTrue("mood guard keeps shadow lift restrained", adjustment.shadowLift < 0.08f)
    }

    @Test fun strongExistingWarmCastReducesAddedStockStrengthButIsNotNeutralised() {
        val n = 80 * 80
        val warmR = FloatArray(n) { 0.58f }
        val warmG = FloatArray(n) { 0.42f }
        val warmB = FloatArray(n) { 0.28f }
        val neutral = FloatArray(n) { 0.42f }
        val params = AdaptiveParams(lookStrength = 0.84f)
        val warmAdjustment = SceneAnalyzer.adjustment(
            SceneAnalyzer.analyze(warmR, warmG, warmB, 80, 80),
            params,
        )
        val neutralAdjustment = SceneAnalyzer.adjustment(
            SceneAnalyzer.analyze(neutral, neutral, neutral, 80, 80),
            params,
        )
        assertTrue(warmAdjustment.lookStrength < neutralAdjustment.lookStrength)

        val before = warmR[0] - warmB[0]
        DevelopPipeline.applySceneAdaptation(warmR, warmG, warmB, warmAdjustment)
        assertTrue("scene adaptation must preserve warm ordering", warmR[0] > warmG[0] && warmG[0] > warmB[0])
        assertTrue("scene adaptation must retain most of the cast", warmR[0] - warmB[0] > before * 0.75f)
    }

    @Test fun highIsoSourceNoiseProtectionIsGentleNotAFilmSpeedControl() {
        val (r, g, b) = neutralFrame(50, 50) { 0.45f }
        val profile = SceneAnalyzer.analyze(r, g, b, 50, 50)
        val low = SceneAnalyzer.adjustment(profile, AdaptiveParams(), iso = 100)
        val high = SceneAnalyzer.adjustment(profile, AdaptiveParams(), iso = 3200)
        assertTrue("existing sensor noise still reduces added density grain", high.grainScale < low.grainScale)
        assertTrue(
            "film structure must survive high ISO rather than being almost disabled",
            high.grainScale > low.grainScale * 0.65f,
        )
    }

    @Test fun lowKeySceneDoesNotGloballySuppressFilmGrain() {
        val (lowR, lowG, lowB) = neutralFrame(50, 50) { 0.12f }
        val (midR, midG, midB) = neutralFrame(50, 50) { 0.45f }
        val params = AdaptiveParams()
        val low = SceneAnalyzer.adjustment(
            SceneAnalyzer.analyze(lowR, lowG, lowB, 50, 50),
            params,
            iso = 100,
        )
        val mid = SceneAnalyzer.adjustment(
            SceneAnalyzer.analyze(midR, midG, midB, 50, 50),
            params,
            iso = 100,
        )
        assertEquals(
            "tone-dependent grain belongs in the local density model, not a low-key scene penalty",
            mid.grainScale,
            low.grainScale,
            1e-5f,
        )
    }

    @Test fun tonalAdaptationPreservesEndpointsAndCompressesNearWhite() {
        val r = floatArrayOf(0f, 0.10f, 0.90f, 1f)
        val g = r.copyOf()
        val b = r.copyOf()
        DevelopPipeline.applySceneAdaptation(
            r, g, b,
            SceneAdjustment(
                exposureEv = 0f,
                shadowLift = 0.08f,
                highlightCompression = 0.5f,
                contrast = 0f,
                saturation = 1f,
                lookStrength = 1f,
                grainScale = 1f,
                halationScale = 1f,
            ),
        )
        assertEquals(0f, r[0], 1e-6f)
        assertTrue("useful shadow lifted", r[1] > 0.10f)
        assertTrue("near-white compressed", r[2] < 0.90f)
        assertEquals(1f, r[3], 1e-6f)
    }
}
