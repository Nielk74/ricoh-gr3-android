package com.ricohgr3.app.looks.emulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevelopPipelineTest {
    private fun neutralSceneProfile() = SceneProfile(
        p01 = 0.02f,
        p10 = 0.08f,
        p50 = 0.25f,
        p90 = 0.72f,
        p99 = 0.92f,
        meanLuma = 0.32f,
        meanSaturation = 0.08f,
        clippedHighlights = 0f,
        crushedShadows = 0f,
        neutralWarmth = 0f,
        neutralConfidence = 1f,
        microContrast = 0.01f,
    )

    @Test fun stockIntentIsSceneInvariantWhileSmartCanProtectTone() {
        val look = FilmLook(
            id = "intent",
            displayName = "Intent",
            lutAsset = null,
            stock = StockRenderParams(lookStrength = 1f),
            adaptive = AdaptiveParams(
                autoExposure = 1f,
                shadowProtection = 1f,
                highlightProtection = 1f,
            ),
        )
        val profile = SceneProfile(
            p01 = 0f,
            p10 = 0.002f,
            p50 = 0.01f,
            p90 = 0.08f,
            p99 = 0.15f,
            meanLuma = 0.025f,
            meanSaturation = 0.1f,
            clippedHighlights = 0f,
            crushedShadows = 0.4f,
            neutralWarmth = 0f,
            neutralConfidence = 1f,
            microContrast = 0f,
        )
        val stockR = floatArrayOf(0.20f)
        val stockG = floatArrayOf(0.20f)
        val stockB = floatArrayOf(0.20f)
        val smartR = stockR.copyOf()
        val smartG = stockG.copyOf()
        val smartB = stockB.copyOf()
        DevelopPipeline.apply(
            stockR,
            stockG,
            stockB,
            1,
            1,
            look,
            LutCube.identity(2),
            options = DevelopOptions(
                intent = RenderingIntent.STOCK,
                sceneProfile = profile,
            ),
        )
        DevelopPipeline.apply(
            smartR,
            smartG,
            smartB,
            1,
            1,
            look,
            LutCube.identity(2),
            options = DevelopOptions(
                intent = RenderingIntent.SMART,
                sceneProfile = profile,
            ),
        )
        assertEquals(0.20f, stockR[0], 1e-5f)
        assertTrue("Smart tone protection lifts the synthetic low-key scene", smartR[0] > stockR[0])
    }

    @Test fun stockIntentDoesNotApplySemanticFoliageCorrection() {
        val look = FilmLook(
            id = "intent-foliage",
            displayName = "Intent foliage",
            lutAsset = null,
            foliageTone = FoliageToneParams(cyanShift = 0.8f, saturationBoost = 0.2f),
            adaptive = AdaptiveParams.NONE,
        )
        val stockR = floatArrayOf(0.32f)
        val stockG = floatArrayOf(0.58f)
        val stockB = floatArrayOf(0.12f)
        val smartR = stockR.copyOf()
        val smartG = stockG.copyOf()
        val smartB = stockB.copyOf()
        DevelopPipeline.apply(
            stockR,
            stockG,
            stockB,
            1,
            1,
            look,
            LutCube.identity(2),
            options = DevelopOptions(intent = RenderingIntent.STOCK),
        )
        DevelopPipeline.apply(
            smartR,
            smartG,
            smartB,
            1,
            1,
            look,
            LutCube.identity(2),
            options = DevelopOptions(intent = RenderingIntent.SMART),
        )
        assertEquals(0.12f, stockB[0], 1e-5f)
        assertTrue("Smart semantic foliage rotates toward cyan-green", smartB[0] > stockB[0])
    }

    @Test fun identityLutNoLayersIsNoOp() {
        val look = FilmLook(id = "x", displayName = "X", lutAsset = null)
        val lut = LutCube.identity(9)
        val r = floatArrayOf(0.1f, 0.9f); val g = floatArrayOf(0.5f, 0.2f); val b = floatArrayOf(0.3f, 0.7f)
        DevelopPipeline.apply(r, g, b, width = 2, height = 1, look, lut)
        assertEquals(0.1f, r[0], 1e-3f)
        assertEquals(0.2f, g[1], 1e-3f)
        assertEquals(0.7f, b[1], 1e-3f)
    }

    @Test fun grainCanBeDisabledWithoutSkippingTheRestOfTheDevelop() {
        val look = FilmLook(
            id = "grain-toggle",
            displayName = "Grain toggle",
            lutAsset = null,
            adaptive = AdaptiveParams.NONE,
            grain = GrainParams(
                amount = 0.24f,
                size = 1.1f,
                shadowBias = 0.3f,
                seed = 42L,
            ),
        )
        val withGrainR = FloatArray(16 * 16) { 0.5f }
        val withGrainG = withGrainR.copyOf()
        val withGrainB = withGrainR.copyOf()
        val withoutGrainR = withGrainR.copyOf()
        val withoutGrainG = withGrainG.copyOf()
        val withoutGrainB = withGrainB.copyOf()

        DevelopPipeline.apply(
            withGrainR,
            withGrainG,
            withGrainB,
            16,
            16,
            look,
            LutCube.identity(2),
            options = DevelopOptions(intent = RenderingIntent.STOCK, renderSeed = 7L),
        )
        DevelopPipeline.apply(
            withoutGrainR,
            withoutGrainG,
            withoutGrainB,
            16,
            16,
            look,
            LutCube.identity(2),
            options = DevelopOptions(
                intent = RenderingIntent.STOCK,
                renderSeed = 7L,
                grainEnabled = false,
            ),
        )

        assertTrue("enabled grain changes the uniform frame", withGrainR.any { it != 0.5f })
        assertTrue(withoutGrainR.all { kotlin.math.abs(it - 0.5f) < 1e-6f })
        assertTrue(withoutGrainG.all { kotlin.math.abs(it - 0.5f) < 1e-6f })
        assertTrue(withoutGrainB.all { kotlin.math.abs(it - 0.5f) < 1e-6f })
    }

    @Test fun grainSwitchMatchesTheCompleteStockWithOnlyItsGrainLayerRemoved() {
        val entry = FilmLookCatalog.entryFor("portra400")!!
        val width = 24
        val height = 16
        val sourceR = FloatArray(width * height) { index -> 0.12f + (index % width) / 30f }
        val sourceG = FloatArray(width * height) { index -> 0.10f + (index / width) / 24f }
        val sourceB = FloatArray(width * height) { index -> 0.18f + (index % 9) / 20f }
        val disabledR = sourceR.copyOf()
        val disabledG = sourceG.copyOf()
        val disabledB = sourceB.copyOf()
        val authoredWithoutR = sourceR.copyOf()
        val authoredWithoutG = sourceG.copyOf()
        val authoredWithoutB = sourceB.copyOf()
        val options = DevelopOptions(
            intent = RenderingIntent.STOCK,
            renderSeed = 99L,
            grainEnabled = false,
        )

        DevelopPipeline.apply(
            disabledR,
            disabledG,
            disabledB,
            width,
            height,
            entry.look,
            FilmLutFactory.build(entry.model),
            options = options,
        )
        DevelopPipeline.apply(
            authoredWithoutR,
            authoredWithoutG,
            authoredWithoutB,
            width,
            height,
            entry.look.copy(grain = GrainParams.NONE),
            FilmLutFactory.build(entry.model),
            options = options.copy(grainEnabled = true),
        )

        for (index in disabledR.indices) {
            assertEquals(authoredWithoutR[index], disabledR[index], 0f)
            assertEquals(authoredWithoutG[index], disabledG[index], 0f)
            assertEquals(authoredWithoutB[index], disabledB[index], 0f)
        }
    }

    @Test fun slightPleasingWarmthMovesNeutralTowardRedWithoutChangingExposure() {
        val look = FilmLook(
            id = "warm-neutral",
            displayName = "Warm neutral",
            lutAsset = null,
            colorBalance = FilmColorBalance.DAYLIGHT,
            adaptive = AdaptiveParams.NONE,
        )
        val r = floatArrayOf(0.5f)
        val g = floatArrayOf(0.5f)
        val b = floatArrayOf(0.5f)
        val beforeY = ColorMath.linearLuminance(r[0], g[0], b[0])

        DevelopPipeline.apply(
            r,
            g,
            b,
            1,
            1,
            look,
            LutCube.identity(65),
            options = DevelopOptions(
                intent = RenderingIntent.SMART,
                sceneProfile = neutralSceneProfile(),
            ),
        )

        val afterY = ColorMath.linearLuminance(r[0], g[0], b[0])
        assertTrue("the neutral acquires only a warm ordering", r[0] > g[0] && g[0] > b[0])
        assertTrue("the bias stays visually slight", r[0] - b[0] < 0.02f)
        assertEquals("warmth must not become an exposure change", beforeY, afterY, 0.0015f)
    }

    @Test fun pleasingWarmthStaysInsideTheAuthoredPerceptualAndLuminanceBudget() {
        // Moderate chart-like display colours exercise neutrals, skin/earth, foliage, and sky
        // without making gamut clipping the thing under test.
        val referenceR = floatArrayOf(
            0.18f, 0.35f, 0.50f, 0.65f, 0.82f, 0.58f,
            0.30f, 0.72f, 0.24f, 0.62f, 0.42f, 0.76f,
        )
        val referenceG = floatArrayOf(
            0.18f, 0.35f, 0.50f, 0.65f, 0.82f, 0.40f,
            0.52f, 0.58f, 0.40f, 0.30f, 0.62f, 0.72f,
        )
        val referenceB = floatArrayOf(
            0.18f, 0.35f, 0.50f, 0.65f, 0.82f, 0.32f,
            0.24f, 0.36f, 0.68f, 0.52f, 0.70f, 0.44f,
        )
        val candidateR = referenceR.copyOf()
        val candidateG = referenceG.copyOf()
        val candidateB = referenceB.copyOf()

        DevelopPipeline.applyPleasingWarmth(candidateR, candidateG, candidateB, amount = 1f)
        val metrics = FilmFidelityEvaluator.compare(
            referenceR,
            referenceG,
            referenceB,
            candidateR,
            candidateG,
            candidateB,
        )

        assertTrue("mean colour change must stay subtle", metrics.meanOklabDistance < 0.008f)
        assertTrue("p95 colour change must stay subtle", metrics.p95OklabDistance < 0.015f)
        assertTrue("no chart patch may move strongly", metrics.maxOklabDistance < 0.025f)
        assertTrue("warmth must not become exposure", metrics.linearLuminanceRmse < 0.002f)
        assertTrue("the signed perceptual bias must be warm", metrics.meanBBias > 0.001f)
        assertTrue("the bias must not become magenta/green", kotlin.math.abs(metrics.meanABias) < 0.002f)
        assertTrue(
            "the bias must not become a lightness adjustment",
            kotlin.math.abs(metrics.meanLightnessBias) < 0.0015f,
        )
    }

    @Test fun pleasingWarmthDoesNotCollapseBrightSaturatedGamutEdges() {
        val referenceR = floatArrayOf(1f, 1f, 0f, 0f, 1f, 0f, 1f, 0f)
        val referenceG = floatArrayOf(0.9f, 0.8f, 0.9f, 0f, 0f, 0f, 0.6f, 0.5f)
        val referenceB = floatArrayOf(0f, 0f, 0.9f, 1f, 0f, 1f, 0.5f, 0f)
        val candidateR = referenceR.copyOf()
        val candidateG = referenceG.copyOf()
        val candidateB = referenceB.copyOf()

        DevelopPipeline.applyPleasingWarmth(candidateR, candidateG, candidateB, amount = 1f)

        for (i in referenceR.indices) {
            assertTrue(
                "red channel $i moved too far at the gamut boundary",
                kotlin.math.abs(candidateR[i] - referenceR[i]) < 0.015f,
            )
            assertTrue(
                "green channel $i moved too far at the gamut boundary",
                kotlin.math.abs(candidateG[i] - referenceG[i]) < 0.015f,
            )
            assertTrue(
                "blue channel $i moved too far at the gamut boundary",
                kotlin.math.abs(candidateB[i] - referenceB[i]) < 0.015f,
            )
        }
        // These were the failure cases for exact-Y neutral-axis gamut compression.
        assertTrue("bright yellow must not gain blue", candidateB[0] < 0.015f)
        assertTrue("bright cyan must not gain red", candidateR[2] < 0.015f)
        // Chroma 0.5 receives the full requested warmth, so these cases specifically exercise
        // the shared-vector cube intersection rather than the high-chroma early exit.
        assertEquals(referenceR[6], candidateR[6], 1e-6f)
        assertEquals(referenceB[7], candidateB[7], 1e-6f)
    }

    @Test fun intensityAboveOneDoesNotAmplifyPleasingWarmth() {
        fun render(effectStrength: Float): Triple<Float, Float, Float> {
            val r = floatArrayOf(0.5f)
            val g = floatArrayOf(0.5f)
            val b = floatArrayOf(0.5f)
            DevelopPipeline.apply(
                r,
                g,
                b,
                1,
                1,
                FilmLook(
                    id = "warmth-cap",
                    displayName = "Warmth cap",
                    lutAsset = null,
                    colorBalance = FilmColorBalance.DAYLIGHT,
                    adaptive = AdaptiveParams.NONE,
                ),
                LutCube.identity(65),
                effectStrength = effectStrength,
                options = DevelopOptions(
                    intent = RenderingIntent.SMART,
                    sceneProfile = neutralSceneProfile(),
                ),
            )
            return Triple(r[0], g[0], b[0])
        }

        val authored = render(1f)
        val stronger = render(1.5f)
        assertEquals(authored.first, stronger.first, 1e-7f)
        assertEquals(authored.second, stronger.second, 1e-7f)
        assertEquals(authored.third, stronger.third, 1e-7f)
    }

    @Test fun catalogExplicitlyClassifiesDaylightTungstenMonochromeAndUnspecifiedStocks() {
        assertEquals(
            FilmColorBalance.DAYLIGHT,
            FilmLookCatalog.entryFor("portra400")!!.look.colorBalance,
        )
        assertEquals(
            FilmColorBalance.TUNGSTEN,
            FilmLookCatalog.entryFor("cinestill800t")!!.look.colorBalance,
        )
        assertEquals(
            FilmColorBalance.MONOCHROME,
            FilmLookCatalog.entryFor("trix400")!!.look.colorBalance,
        )
        assertEquals(
            FilmColorBalance.UNSPECIFIED,
            FilmLookCatalog.entryFor("eterna")!!.look.colorBalance,
        )
        assertTrue(
            FilmLookCatalog.entries.all {
                it.look.colorBalance == it.model.profile.colorBalance
            },
        )
    }

    @Test fun stockIntentAndNonDaylightStocksDoNotReceiveProductWarmth() {
        fun render(intent: RenderingIntent, balance: FilmColorBalance): Triple<Float, Float, Float> {
            val look = FilmLook(
                id = "warm-contract",
                displayName = "Warm contract",
                lutAsset = null,
                colorBalance = balance,
                adaptive = AdaptiveParams.NONE,
            )
            val r = floatArrayOf(0.5f)
            val g = floatArrayOf(0.5f)
            val b = floatArrayOf(0.5f)
            DevelopPipeline.apply(
                r,
                g,
                b,
                1,
                1,
                look,
                LutCube.identity(65),
                options = DevelopOptions(
                    intent = intent,
                    sceneProfile = neutralSceneProfile(),
                ),
            )
            return Triple(r[0], g[0], b[0])
        }

        for (
            output in listOf(
                render(RenderingIntent.STOCK, FilmColorBalance.DAYLIGHT),
                render(RenderingIntent.SMART, FilmColorBalance.TUNGSTEN),
                render(RenderingIntent.SMART, FilmColorBalance.MONOCHROME),
                render(RenderingIntent.SMART, FilmColorBalance.UNSPECIFIED),
            )
        ) {
            assertEquals(0.5f, output.first, 1e-5f)
            assertEquals(0.5f, output.second, 1e-5f)
            assertEquals(0.5f, output.third, 1e-5f)
        }
    }

    @Test fun reliableExistingCastReducesPleasingWarmthWithoutReversingIt() {
        val neutral = neutralSceneProfile()
        val cast = neutral.copy(
            neutralWarmth = 0.16f,
            neutralConfidence = 0.95f,
        )
        assertEquals(1f, DevelopPipeline.pleasingWarmthScale(neutral), 0f)
        assertTrue(
            DevelopPipeline.pleasingWarmthScale(cast) <
                DevelopPipeline.pleasingWarmthScale(neutral) * 0.35f,
        )
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

    @Test fun filmExposureBracketActsBeforeTheNegative() {
        val entry = FilmLookCatalog.entryFor("portra400")!!
        val look = entry.look.copy(
            adaptive = AdaptiveParams.NONE,
            splitTone = SplitTone.NONE,
            skinTone = SkinToneParams.NONE,
            foliageTone = FoliageToneParams.NONE,
            skyTone = SkyToneParams.NONE,
            grain = GrainParams.NONE,
            halation = HalationParams.NONE,
        )
        val lut = FilmLutFactory.build(entry.model)
        fun render(ev: Float): Float {
            val r = floatArrayOf(0.5f)
            val g = floatArrayOf(0.5f)
            val b = floatArrayOf(0.5f)
            DevelopPipeline.apply(
                r, g, b, 1, 1, look, lut,
                filmExposureEv = ev,
            )
            return 0.2126f * r[0] + 0.7152f * g[0] + 0.0722f * b[0]
        }
        val under = render(-1f)
        val normal = render(0f)
        val over = render(1f)
        assertTrue("negative exposure bracket must be monotonic ($under $normal $over)",
            under < normal && normal < over)
        assertEquals("omitted exposure is the calibrated zero-stop path", normal, render(0f), 0f)
    }

    @Test fun positiveFilmExposureKeepsRenderedHighlightSeparation() {
        val look = FilmLook(
            id = "identity-negative",
            displayName = "Identity negative",
            lutAsset = null,
            adaptive = AdaptiveParams.NONE,
        )
        val r = floatArrayOf(0.80f, 0.90f)
        val g = r.copyOf()
        val b = r.copyOf()
        DevelopPipeline.apply(
            r, g, b, 2, 1, look, LutCube.identity(65),
            filmExposureEv = 1f,
        )
        assertTrue("one-stop bracket brightens upper tones", r[0] > 0.80f)
        assertTrue("already-rendered highlights must not pre-clip", r[1] < 0.999f)
        assertTrue("highlight ordering survives the negative input", r[1] > r[0] + 0.025f)
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
        assertTrue(portra800.highlightPersistence > portra400.highlightPersistence)
        assertTrue(FilmLookCatalog.entryFor("portra400")!!.look.whitePointRecovery.enabled)
        assertTrue(FilmLookCatalog.entryFor("portra800")!!.look.whitePointRecovery.enabled)
    }

    @Test fun diffuseWhiteRecoveryReExpandsOnlyTheCompressedUpperRange() {
        val count = 1_000
        val r = FloatArray(count) { index ->
            when {
                index < 400 -> 0.45f
                index < 700 -> 0.72f
                index < 995 -> 0.90f
                else -> 1f
            }
        }
        val g = r.copyOf()
        val b = r.copyOf()
        val applied = DevelopPipeline.recoverDiffuseWhite(
            r, g, b,
            sourceP99Linear = ColorMath.srgbToLinear(0.98f),
            params = WhitePointRecoveryParams(amount = 0.80f),
        )

        assertTrue("bright source scene should recover its white anchor", applied)
        assertEquals("lower midtones stay authored", 0.45f, r[0], 1e-6f)
        assertTrue("upper midtone ordering survives", r[400] in 0.72f..r[700])
        assertTrue("compressed diffuse white is re-expanded", r[700] > 0.96f)
        assertEquals("display white remains exact", 1f, r.last(), 1e-6f)
        assertTrue("neutral upper tones remain neutral", r.indices.all {
            kotlin.math.abs(r[it] - g[it]) < 1e-6f &&
                kotlin.math.abs(g[it] - b[it]) < 1e-6f
        })
    }

    @Test fun diffuseWhiteRecoveryDoesNotInventWhiteInLowKeyScenes() {
        val r = FloatArray(128) { 0.78f }
        val g = r.copyOf()
        val b = r.copyOf()
        val before = r.copyOf()
        val applied = DevelopPipeline.recoverDiffuseWhite(
            r, g, b,
            sourceP99Linear = ColorMath.srgbToLinear(0.78f),
            params = WhitePointRecoveryParams(amount = 1f),
        )

        assertTrue("low-key source has no credible white anchor", !applied)
        assertTrue(r.contentEquals(before) && g.contentEquals(before) && b.contentEquals(before))
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
            ColorMath.linearLuminance(r[skyIndex], g[skyIndex], b[skyIndex])
        val skyChromaBefore =
            maxOf(r[skyIndex], g[skyIndex], b[skyIndex]) -
                minOf(r[skyIndex], g[skyIndex], b[skyIndex])
        val objectBefore = Triple(r[objectIndex], g[objectIndex], b[objectIndex])
        DevelopPipeline.applySkyCyanShift(
            r, g, b, width, height,
            cyanShift = 0.30f,
            saturationBoost = 0.25f,
        )
        val skyLumaAfter =
            ColorMath.linearLuminance(r[skyIndex], g[skyIndex], b[skyIndex])
        val skyChromaAfter =
            maxOf(r[skyIndex], g[skyIndex], b[skyIndex]) -
                minOf(r[skyIndex], g[skyIndex], b[skyIndex])

        assertTrue("connected blue sky moves toward cyan", g[skyIndex] > 0.48f)
        assertTrue(
            "connected blue sky gains visible saturation ($skyChromaBefore -> $skyChromaAfter)",
            skyChromaAfter > skyChromaBefore + 0.05f,
        )
        assertEquals("sky hue move keeps exposure", skyLumaBefore, skyLumaAfter, 1e-5f)
        assertEquals("isolated blue object's red stays untouched", objectBefore.first, r[objectIndex], 0f)
        assertEquals("isolated blue object's green stays untouched", objectBefore.second, g[objectIndex], 0f)
        assertEquals("isolated blue object's blue stays untouched", objectBefore.third, b[objectIndex], 0f)
    }

    @Test fun portraFoliageShiftTouchesOnlyVegetationGreensAndPreservesLuma() {
        val r = floatArrayOf(0.32f, 0.72f, 0.45f, 0.10f)
        val g = floatArrayOf(0.58f, 0.36f, 0.45f, 0.50f)
        val b = floatArrayOf(0.12f, 0.24f, 0.45f, 0.48f)
        val originals = r.indices.map { Triple(r[it], g[it], b[it]) }
        val foliageLumaBefore = ColorMath.linearLuminance(r[0], g[0], b[0])
        val labBefore = FloatArray(3)
        ColorMath.srgbToOklab(r[0], g[0], b[0], labBefore)
        val foliageChromaBefore = kotlin.math.sqrt(
            labBefore[1] * labBefore[1] + labBefore[2] * labBefore[2],
        )
        fun greenHue(red: Float, green: Float, blue: Float): Float {
            val delta = maxOf(red, green, blue) - minOf(red, green, blue)
            return 60f * ((blue - red) / delta + 2f)
        }
        val foliageHueBefore = greenHue(r[0], g[0], b[0])

        DevelopPipeline.applyFoliageCyanShift(
            r, g, b,
            cyanShift = 0.55f,
            saturationBoost = 0.25f,
        )

        val foliageLumaAfter = ColorMath.linearLuminance(r[0], g[0], b[0])
        val labAfter = FloatArray(3)
        ColorMath.srgbToOklab(r[0], g[0], b[0], labAfter)
        val foliageChromaAfter = kotlin.math.sqrt(
            labAfter[1] * labAfter[1] + labAfter[2] * labAfter[2],
        )
        val foliageHueAfter = greenHue(r[0], g[0], b[0])
        assertTrue("vegetation green moves toward cyan-green", b[0] > originals[0].third)
        assertTrue("warm component is restrained", r[0] < originals[0].first)
        assertTrue(
            "foliage hue shift must be clearly visible ($foliageHueBefore -> $foliageHueAfter)",
            foliageHueAfter > foliageHueBefore + 25f,
        )
        assertTrue(
            "foliage gains saturation ($foliageChromaBefore -> $foliageChromaAfter)",
            foliageChromaAfter > foliageChromaBefore + 0.01f,
        )
        assertEquals("foliage hue move keeps exposure", foliageLumaBefore, foliageLumaAfter, 1e-5f)
        for (index in 1..3) {
            assertEquals("skin/neutral/cyan red stays untouched", originals[index].first, r[index], 0f)
            assertEquals("skin/neutral/cyan green stays untouched", originals[index].second, g[index], 0f)
            assertEquals("skin/neutral/cyan blue stays untouched", originals[index].third, b[index], 0f)
        }
    }

    @Test fun onlyPortraStocksEnableSelectiveFoliageAndSkyResponses() {
        val portra400 = FilmLookCatalog.entryFor("portra400")!!.look
        val portra800 = FilmLookCatalog.entryFor("portra800")!!.look
        assertTrue(portra400.skyTone.enabled)
        assertTrue(portra800.skyTone.enabled)
        assertTrue(portra400.foliageTone.enabled)
        assertTrue(portra800.foliageTone.enabled)
        assertTrue(portra400.skyTone.saturationBoost > 0f)
        assertTrue(portra400.foliageTone.saturationBoost > 0f)
        assertTrue(portra800.skyTone.saturationBoost > portra400.skyTone.saturationBoost)
        assertTrue(portra800.foliageTone.cyanShift > portra400.foliageTone.cyanShift)
        assertTrue(!FilmLookCatalog.entryFor("cinestill800t")!!.look.skyTone.enabled)
        assertTrue(!FilmLookCatalog.entryFor("vision3_250d")!!.look.skyTone.enabled)
        assertTrue(!FilmLookCatalog.entryFor("cinestill800t")!!.look.foliageTone.enabled)
        assertTrue(!FilmLookCatalog.entryFor("vision3_250d")!!.look.foliageTone.enabled)
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
