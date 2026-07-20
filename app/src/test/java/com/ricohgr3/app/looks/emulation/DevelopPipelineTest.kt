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

    @Test fun zeroEffectStrengthReturnsTheUnmodifiedScene() {
        val entry = FilmLookCatalog.entryFor("portra400")!!
        val look = entry.look.copy(
            grain = GrainParams.NONE,
            halation = HalationParams.NONE,
        )
        val r = FloatArray(64) { 0.72f }
        val g = FloatArray(64) { 0.36f }
        val b = FloatArray(64) { 0.24f }
        DevelopPipeline.apply(
            r, g, b, 8, 8, look, FilmLutFactory.build(entry.model),
            effectStrength = 0f,
        )
        assertTrue(r.all { kotlin.math.abs(it - 0.72f) < 1e-5f })
        assertTrue(g.all { kotlin.math.abs(it - 0.36f) < 1e-5f })
        assertTrue(b.all { kotlin.math.abs(it - 0.24f) < 1e-5f })
    }

    @Test fun strongerEffectMovesAnAdaptiveStockFurtherFromTheOriginal() {
        val entry = FilmLookCatalog.entryFor("portra400")!!
        val look = entry.look.copy(
            grain = GrainParams.NONE,
            halation = HalationParams.NONE,
        )
        fun render(strength: Float): Triple<Float, Float, Float> {
            val r = FloatArray(64) { 0.72f }
            val g = FloatArray(64) { 0.36f }
            val b = FloatArray(64) { 0.24f }
            DevelopPipeline.apply(
                r, g, b, 8, 8, look, FilmLutFactory.build(entry.model),
                effectStrength = strength,
            )
            return Triple(r[0], g[0], b[0])
        }
        fun distance(pixel: Triple<Float, Float, Float>): Float =
            kotlin.math.abs(pixel.first - 0.72f) +
                kotlin.math.abs(pixel.second - 0.36f) +
                kotlin.math.abs(pixel.third - 0.24f)
        assertTrue("150% must deepen the stock character", distance(render(1.5f)) > distance(render(1f)))
    }

    @Test fun grainIsDeterministicForFixedSeed() {
        fun run(): FloatArray {
            val r = FloatArray(64) { 0.5f }; val g = FloatArray(64) { 0.5f }; val b = FloatArray(64) { 0.5f }
            DevelopPipeline.applyGrain(r, g, b, 8, 8, GrainParams(amount = 0.1f, size = 1f, shadowBias = 0.5f, seed = 7))
            return r
        }
        assertTrue("same seed -> identical grain", run().contentEquals(run()))
    }

    @Test fun grainIsActuallyVisibleAtLargerCrystalSize() {
        // Correlating immediate neighbours must not collapse the field to sub-pixel invisibility.
        // A size>1 stock should still land at a few code values of standard deviation.
        val w = 128; val h = 128; val n = w * h
        val r = FloatArray(n) { 0.5f }; val g = FloatArray(n) { 0.5f }; val b = FloatArray(n) { 0.5f }
        DevelopPipeline.applyGrain(r, g, b, w, h, GrainParams(amount = 0.05f, size = 2.5f, shadowBias = 0.6f, seed = 9))
        var sum = 0.0; var sq = 0.0
        for (v in r) { val d = v - 0.5f; sum += d; sq += d.toDouble() * d }
        val std = kotlin.math.sqrt(sq / n - (sum / n) * (sum / n))
        assertTrue("grain must be visible (std=$std, ~${std * 255} of 255)", std * 255 > 2f)
    }

    @Test fun grainDensityPeaksInMidtonesAndRollsOffBothEnds() {
        // Real film grain: A(I) is a hump peaking in the midtones, weaker in the deepest shadows
        // AND the brightest highlights — not monotonic to black.
        val mid = DevelopPipeline.grainDensity(0.4f, shadowBias = 0.5f)
        val deepShadow = DevelopPipeline.grainDensity(0.02f, shadowBias = 0.5f)
        val highlight = DevelopPipeline.grainDensity(0.95f, shadowBias = 0.5f)
        assertTrue("peak is in the midtones", mid > deepShadow && mid > highlight)
        assertTrue("highlights nearly grain-free", highlight < 0.15f)
        assertTrue("deep shadow rolls off below the mid peak", deepShadow < mid)
    }

    @Test fun fasterColourStocksCarryLargerMoreVisibleGrain() {
        val ektar = FilmLookCatalog.entryFor("ektar100")!!.look.grain
        val portra400 = FilmLookCatalog.entryFor("portra400")!!.look.grain
        val portra800 = FilmLookCatalog.entryFor("portra800")!!.look.grain
        val cinestill800 = FilmLookCatalog.entryFor("cinestill800t")!!.look.grain
        assertTrue(ektar.amount < portra400.amount)
        assertTrue(portra400.amount < portra800.amount)
        assertTrue(portra400.size < portra800.size)
        assertTrue(ektar.amount < cinestill800.amount)
        assertTrue(ektar.size < cinestill800.size)
    }

    @Test fun grainIsCorrelatedRgbNotMonochrome() {
        // The channels must differ (not identical mono noise) yet stay correlated in amplitude.
        val w = 100; val h = 100; val n = w * h
        val r = FloatArray(n) { 0.5f }; val g = FloatArray(n) { 0.5f }; val b = FloatArray(n) { 0.5f }
        DevelopPipeline.applyGrain(r, g, b, w, h,
            GrainParams(amount = 0.06f, size = 2f, shadowBias = 0.5f, chroma = 0.4f, seed = 3))
        var identical = 0
        for (i in 0 until n) if (r[i] == g[i] && g[i] == b[i]) identical++
        assertTrue("RGB grain must not be identical mono (identical=$identical)", identical < n / 10)
        // ...but a pure-mono config (chroma=0) MUST put identical noise on all channels.
        val r2 = FloatArray(n) { 0.5f }; val g2 = FloatArray(n) { 0.5f }; val b2 = FloatArray(n) { 0.5f }
        DevelopPipeline.applyGrain(r2, g2, b2, w, h,
            GrainParams(amount = 0.06f, size = 2f, shadowBias = 0.5f, chroma = 0f, seed = 3))
        assertTrue("chroma=0 is monochrome", r2.indices.all { r2[it] == g2[it] && g2[it] == b2[it] })
    }

    @Test fun grainDoesNotRepeatAtTheFormerPlatePeriod() {
        // Regression: the retired 512px grain plate stamped the same structure across large
        // exports. Coordinate-hash grain must remain different 512 pixels later.
        val width = 1_032
        val height = 8
        val n = width * height
        val r = FloatArray(n) { 0.5f }; val g = r.copyOf(); val b = r.copyOf()
        DevelopPipeline.applyGrain(
            r, g, b, width, height,
            GrainParams(amount = 0.08f, size = 2f, shadowBias = 0.5f, chroma = 0f, seed = 5),
        )
        var difference = 0.0
        var samples = 0
        for (y in 0 until height) for (x in 0 until 512) {
            difference += kotlin.math.abs(r[y * width + x] - r[y * width + x + 512])
            samples++
        }
        val meanDifference = difference / samples
        assertTrue("grain must not repeat every 512px (difference=$meanDifference)", meanDifference > 0.005)
    }

    @Test fun grainHasNoLowFrequencyClouds() {
        // The user's 100% crops exposed large camouflage-like density clouds. For a flat field,
        // 16px block averages must be much quieter than the pixel-scale texture.
        val width = 320
        val height = 320
        val n = width * height
        val r = FloatArray(n) { 0.5f }; val g = r.copyOf(); val b = r.copyOf()
        DevelopPipeline.applyGrain(
            r, g, b, width, height,
            GrainParams(amount = 0.08f, size = 2f, shadowBias = 0.5f, chroma = 0f, seed = 37),
        )
        val globalMean = r.average()
        val globalStd = kotlin.math.sqrt(r.sumOf {
            val d = it - globalMean
            d * d
        } / n)
        val blockMeans = ArrayList<Double>()
        val blockSize = 16
        for (top in 0 until height step blockSize) {
            for (left in 0 until width step blockSize) {
                var sum = 0.0
                for (y in top until top + blockSize) {
                    for (x in left until left + blockSize) sum += r[y * width + x]
                }
                blockMeans += sum / (blockSize * blockSize)
            }
        }
        val blockMean = blockMeans.average()
        val blockStd = kotlin.math.sqrt(blockMeans.sumOf {
            val d = it - blockMean
            d * d
        } / blockMeans.size)
        assertTrue(
            "large-area density drift must stay below pixel texture " +
                "(block=$blockStd pixel=$globalStd)",
            blockStd < globalStd * 0.20,
        )
    }

    @Test fun proceduralGrainIsTonePeakedAndDeterministic() {
        val gp = GrainParams(
            amount = 0.08f, size = 1.8f, shadowBias = 0.4f, chroma = 0.2f, seed = 5,
        )
        fun run(level: Float): FloatArray {
            val n = 128 * 128
            val r = FloatArray(n) { level }; val g = FloatArray(n) { level }; val b = FloatArray(n) { level }
            DevelopPipeline.applyGrain(r, g, b, 128, 128, gp)
            return r
        }
        fun std(a: FloatArray): Float {
            var m = 0f; for (v in a) m += v; m /= a.size
            var s2 = 0f; for (v in a) s2 += (v - m) * (v - m); return kotlin.math.sqrt(s2 / a.size)
        }
        val mid = std(run(0.5f)); val hi = std(run(0.95f))
        assertTrue("midtone grain visible ($mid)", mid > 0.01f)
        assertTrue("highlight grain < midtone ($hi < $mid)", hi < mid)
        assertTrue("deterministic", run(0.5f).contentEquals(run(0.5f)))
    }

    @Test fun densityGrainDoesNotShiftNeutralMeanOrEndpoints() {
        val n = 128 * 128
        val r = FloatArray(n) { 0.5f }
        val g = r.copyOf()
        val b = r.copyOf()
        DevelopPipeline.applyGrain(
            r, g, b, 128, 128,
            GrainParams(amount = 0.08f, size = 1.7f, shadowBias = 0.5f, chroma = 0f, seed = 4),
        )
        val mean = r.average().toFloat()
        assertEquals("zero-mean density grain must not veil mid-grey", 0.5f, mean, 0.003f)
        assertTrue("monochrome grain keeps neutrals neutral", r.indices.all { r[it] == g[it] && g[it] == b[it] })

        val blackR = FloatArray(16) { 0f }
        val blackG = blackR.copyOf()
        val blackB = blackR.copyOf()
        val whiteR = FloatArray(16) { 1f }
        val whiteG = whiteR.copyOf()
        val whiteB = whiteR.copyOf()
        DevelopPipeline.applyGrain(
            blackR, blackG, blackB, 4, 4,
            GrainParams(amount = 0.2f, size = 1f, shadowBias = 0.5f),
        )
        DevelopPipeline.applyGrain(
            whiteR, whiteG, whiteB, 4, 4,
            GrainParams(amount = 0.2f, size = 1f, shadowBias = 0.5f),
        )
        assertTrue(blackR.all { it == 0f })
        assertTrue(whiteR.all { it == 1f })
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
        assertEquals("bright core stays clean", 1f, r[center], 1e-6f)
    }

    @Test fun halationDoesNotLayRedFogOverUniformHighlights() {
        val n = 24 * 24
        val r = FloatArray(n) { 0.94f }
        val g = r.copyOf()
        val b = r.copyOf()
        DevelopPipeline.applyHalation(
            r, g, b, 24, 24,
            HalationParams(
                threshold = 0.5f, radius = 5, strength = 1f,
                tintR = 1f, tintG = 0.4f, tintB = 0.1f,
            ),
        )
        assertTrue("uniform highlight should not become a red wash", r.all { kotlin.math.abs(it - 0.94f) < 1e-4f })
        assertTrue("uniform highlight keeps neutral RGB", r.indices.all {
            kotlin.math.abs(r[it] - g[it]) < 1e-5f && kotlin.math.abs(g[it] - b[it]) < 1e-5f
        })
    }

    @Test fun cinestillHalationIsRedRatherThanOrange() {
        val halation = FilmLookCatalog.entryFor("cinestill800t")!!.look.halation
        assertTrue("CineStill green fringe must be restrained", halation.tintG < 0.08f)
        assertTrue("CineStill blue fringe must be restrained", halation.tintB < 0.03f)

        val width = 31
        val height = 31
        val n = width * height
        val r = FloatArray(n) { 0.02f }
        val g = r.copyOf()
        val b = r.copyOf()
        val center = (height / 2) * width + width / 2
        r[center] = 1f; g[center] = 1f; b[center] = 1f
        val neighbour = center + 3
        DevelopPipeline.applyHalation(r, g, b, width, height, halation)
        val redLift = r[neighbour] - 0.02f
        val greenLift = g[neighbour] - 0.02f
        val blueLift = b[neighbour] - 0.02f
        assertTrue("CineStill fringe must visibly lift red", redLift > 0.01f)
        assertTrue("red must dominate green ($redLift vs $greenLift)", redLift > greenLift * 3f)
        assertTrue("red must dominate blue ($redLift vs $blueLift)", redLift > blueLift * 6f)
    }

    @Test fun portraSkyShiftTouchesOnlyTopConnectedBlueAndPreservesLuma() {
        val width = 8
        val height = 8
        val n = width * height
        val r = FloatArray(n) { 0.45f }
        val g = FloatArray(n) { 0.45f }
        val b = FloatArray(n) { 0.45f }
        fun paintBlue(index: Int) {
            r[index] = 0.18f
            g[index] = 0.48f
            b[index] = 0.82f
        }
        // A sky band reaches the top edge.
        for (y in 0..2) for (x in 0 until width) paintBlue(y * width + x)
        // Identically blue "shirt/sign" pixels are isolated below two neutral rows.
        for (y in 5..7) for (x in 2..5) paintBlue(y * width + x)

        val skyIndex = width + 3
        val objectIndex = 6 * width + 3
        val skyLumaBefore =
            0.2126f * r[skyIndex] + 0.7152f * g[skyIndex] + 0.0722f * b[skyIndex]
        val objectBefore = Triple(r[objectIndex], g[objectIndex], b[objectIndex])
        DevelopPipeline.applySkyCyanShift(r, g, b, width, height, cyanShift = 0.30f)
        val skyLumaAfter =
            0.2126f * r[skyIndex] + 0.7152f * g[skyIndex] + 0.0722f * b[skyIndex]

        assertTrue("connected blue sky moves toward cyan", g[skyIndex] > 0.48f)
        assertEquals("sky hue move keeps exposure", skyLumaBefore, skyLumaAfter, 1e-5f)
        assertEquals("isolated blue object's red stays untouched", objectBefore.first, r[objectIndex], 0f)
        assertEquals("isolated blue object's green stays untouched", objectBefore.second, g[objectIndex], 0f)
        assertEquals("isolated blue object's blue stays untouched", objectBefore.third, b[objectIndex], 0f)
    }

    @Test fun onlyPortraStocksEnableTheSelectiveSkyResponse() {
        assertTrue(FilmLookCatalog.entryFor("portra400")!!.look.skyTone.enabled)
        assertTrue(FilmLookCatalog.entryFor("portra800")!!.look.skyTone.enabled)
        assertTrue(!FilmLookCatalog.entryFor("cinestill800t")!!.look.skyTone.enabled)
        assertTrue(!FilmLookCatalog.entryFor("vision3_250d")!!.look.skyTone.enabled)
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
        // A mono model must desaturate a colourful input through the full pipeline.
        val look = FilmLook(id = "mono", displayName = "Mono", lutAsset = null)
        val lut = FilmLutFactory.build(FilmLutFactory.Model(saturation = 0f))
        val r = floatArrayOf(0.8f); val g = floatArrayOf(0.2f); val b = floatArrayOf(0.2f)
        DevelopPipeline.apply(r, g, b, 1, 1, look, lut)
        assertEquals("mono look neutralises colour", r[0], g[0], 0.05f)
    }

    @Test fun lutInputGammaPreWarpsBeforeSampling() {
        // With lutInputGamma != 1, the input is raised to that power before the LUT is sampled.
        // Use an identity LUT so the output equals the pre-warped input: x^2 for gamma 2.
        val look = FilmLook(id = "g", displayName = "G", lutAsset = null, lutInputGamma = 2f)
        val lut = LutCube.identity(33)
        val r = floatArrayOf(0.5f); val g = floatArrayOf(0.5f); val b = floatArrayOf(0.5f)
        DevelopPipeline.apply(r, g, b, 1, 1, look, lut)
        assertEquals("0.5^2 = 0.25 after pre-warp through identity LUT", 0.25f, r[0], 0.02f)
    }
}
