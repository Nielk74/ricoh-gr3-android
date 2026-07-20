package com.ricohgr3.app.looks.emulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinToneTest {
    private data class Frame(
        val width: Int,
        val height: Int,
        val r: FloatArray,
        val g: FloatArray,
        val b: FloatArray,
    ) {
        fun paint(left: Int, top: Int, right: Int, bottom: Int, colour: Triple<Float, Float, Float>) {
            for (y in top until bottom) {
                for (x in left until right) {
                    val index = y * width + x
                    r[index] = colour.first
                    g[index] = colour.second
                    b[index] = colour.third
                }
            }
        }
    }

    private fun frame(width: Int = 120, height: Int = 90): Frame {
        val size = width * height
        return Frame(
            width = width,
            height = height,
            r = FloatArray(size) { 0.18f },
            g = FloatArray(size) { 0.18f },
            b = FloatArray(size) { 0.18f },
        )
    }

    @Test fun connectedMaskFindsLightDarkAndCoolComplexions() {
        val image = frame()
        image.paint(8, 10, 28, 30, Triple(0.78f, 0.55f, 0.44f))
        image.paint(46, 12, 66, 34, Triple(0.30f, 0.18f, 0.12f))
        image.paint(82, 10, 104, 31, Triple(0.29f, 0.25f, 0.26f))

        val mask = SkinTone.detect(
            image.r,
            image.g,
            image.b,
            image.width,
            image.height,
            faceRegions = listOf(
                FaceRegion(0.05f, 0.06f, 0.26f, 0.37f),
                FaceRegion(0.36f, 0.07f, 0.59f, 0.44f),
                FaceRegion(0.67f, 0.05f, 0.91f, 0.42f),
            ),
        )

        assertTrue("light complexion is isolated", mask.weightAt(18, 20, 120, 90) > 0.70f)
        assertTrue("dark complexion is isolated", mask.weightAt(56, 22, 120, 90) > 0.70f)
        assertTrue("cool open-shade complexion is isolated", mask.weightAt(93, 20, 120, 90) > 0.45f)
        assertTrue("neutral background remains outside the mask", mask.weightAt(38, 65, 120, 90) < 0.02f)
    }

    @Test fun redVelvetGoldAndIsolatedWarmSpecksAreRejected() {
        val image = frame()
        image.paint(8, 10, 34, 34, Triple(0.72f, 0.48f, 0.36f))
        image.paint(45, 8, 78, 42, Triple(0.25f, 0.01f, 0.01f))
        image.paint(82, 8, 112, 42, Triple(0.40f, 0.20f, 0.04f))
        image.paint(20, 70, 21, 71, Triple(0.72f, 0.48f, 0.36f))

        val mask = SkinTone.detect(
            image.r,
            image.g,
            image.b,
            image.width,
            image.height,
            // Include every coloured patch in a semantic gate so this test proves the
            // chromaticity/component stages reject non-skin, not merely the face rectangle.
            faceRegions = listOf(
                FaceRegion(0.04f, 0.04f, 0.31f, 0.45f),
                FaceRegion(0.35f, 0.03f, 0.68f, 0.50f),
                FaceRegion(0.67f, 0.03f, 0.97f, 0.50f),
                FaceRegion(0.12f, 0.70f, 0.23f, 0.89f),
            ),
        )

        assertTrue("real coherent skin region is accepted", mask.weightAt(20, 20, 120, 90) > 0.70f)
        assertEquals("red velvet is not skin", 0f, mask.weightAt(60, 25, 120, 90), 1e-6f)
        assertEquals("yellow-gold trim is not skin", 0f, mask.weightAt(96, 25, 120, 90), 1e-6f)
        assertEquals("single warm pixel is not skin", 0f, mask.weightAt(20, 70, 120, 90), 1e-6f)
    }

    @Test fun missingFaceDetectionFailsClosedInsteadOfKeyingWarmObjectsGlobally() {
        val image = frame()
        image.paint(10, 10, 80, 70, Triple(0.72f, 0.48f, 0.36f))
        val mask = SkinTone.detect(
            image.r,
            image.g,
            image.b,
            image.width,
            image.height,
            faceRegions = emptyList(),
        )
        assertTrue(!mask.hasSkin)
        assertEquals(0f, mask.coverage, 0f)
    }

    @Test fun naturalisationReducesAnExcessiveStockPushWithoutChangingFaceLight() {
        val out = FloatArray(3)
        val scratch = FloatArray(3)
        val rendered = floatArrayOf(0.66f, 0.23f, 0.14f)
        val renderedLuma = luma(rendered[0], rendered[1], rendered[2])
        SkinTone.naturalize(
            sourceR = 0.52f,
            sourceG = 0.31f,
            sourceB = 0.23f,
            renderedR = rendered[0],
            renderedG = rendered[1],
            renderedB = rendered[2],
            maskWeight = 1f,
            params = SkinToneParams(
                protection = 0.65f,
                naturalness = 0.70f,
                saturationCeiling = 0.64f,
            ),
            effectStrength = 1f,
            out = out,
            scratch = scratch,
        )

        assertEquals("facial luminance is preserved", renderedLuma, luma(out[0], out[1], out[2]), 1e-5f)
        assertTrue("excess red dominance is reduced", out[0] - out[1] < rendered[0] - rendered[1])
        assertTrue("excess saturation is reduced", saturation(out) < saturation(rendered))
        assertTrue("the correction remains warm", out[0] > out[1] && out[1] > out[2])
    }

    @Test fun correctionDoesNotUndoAStocksBeneficialDesaturation() {
        val out = FloatArray(3)
        val scratch = FloatArray(3)
        val rendered = floatArrayOf(0.45f, 0.38f, 0.36f)
        SkinTone.naturalize(
            sourceR = 0.62f,
            sourceG = 0.39f,
            sourceB = 0.31f,
            renderedR = rendered[0],
            renderedG = rendered[1],
            renderedB = rendered[2],
            maskWeight = 1f,
            params = SkinToneParams(protection = 0.50f, naturalness = 0.50f),
            effectStrength = 1f,
            out = out,
            scratch = scratch,
        )

        val sourceAtRenderedLuma = floatArrayOf(0.0f, 0.0f, 0.0f)
        val scale = luma(rendered[0], rendered[1], rendered[2]) / luma(0.62f, 0.39f, 0.31f)
        sourceAtRenderedLuma[0] = 0.62f * scale
        sourceAtRenderedLuma[1] = 0.39f * scale
        sourceAtRenderedLuma[2] = 0.31f * scale
        assertTrue(
            "muted stock colour stays much closer to the render than the saturated source",
            saturation(out) - saturation(rendered) <
                (saturation(sourceAtRenderedLuma) - saturation(rendered)) * 0.35f,
        )
    }

    @Test fun greenHueRotationIsCorrectedEvenWhenTheStockIsNotMoreSaturated() {
        val out = FloatArray(3)
        val scratch = FloatArray(3)
        val source = floatArrayOf(0.46f, 0.36f, 0.35f)
        val rendered = floatArrayOf(0.37f, 0.39f, 0.34f)
        val sourceDirection = source[0] - source[1]
        val renderedDirection = rendered[0] - rendered[1]
        SkinTone.naturalize(
            sourceR = source[0],
            sourceG = source[1],
            sourceB = source[2],
            renderedR = rendered[0],
            renderedG = rendered[1],
            renderedB = rendered[2],
            maskWeight = 1f,
            params = SkinToneParams(protection = 0.60f, naturalness = 0.50f),
            effectStrength = 1f,
            out = out,
            scratch = scratch,
        )

        assertEquals(
            "hue correction preserves the rendered light",
            luma(rendered[0], rendered[1], rendered[2]),
            luma(out[0], out[1], out[2]),
            1e-5f,
        )
        assertTrue(
            "red-vs-green direction moves away from the green render and toward captured skin",
            out[0] - out[1] > renderedDirection &&
                kotlin.math.abs(sourceDirection - (out[0] - out[1])) <
                kotlin.math.abs(sourceDirection - renderedDirection),
        )
    }

    @Test fun zeroEffectAndZeroMaskAreExactNoOps() {
        fun run(mask: Float, effect: Float): FloatArray {
            val out = FloatArray(3)
            SkinTone.naturalize(
                sourceR = 0.50f,
                sourceG = 0.30f,
                sourceB = 0.20f,
                renderedR = 0.70f,
                renderedG = 0.20f,
                renderedB = 0.10f,
                maskWeight = mask,
                params = SkinToneParams(protection = 0.8f, naturalness = 0.8f),
                effectStrength = effect,
                out = out,
                scratch = FloatArray(3),
            )
            return out
        }

        val zeroEffect = run(mask = 1f, effect = 0f)
        val zeroMask = run(mask = 0f, effect = 1f)
        for (out in listOf(zeroEffect, zeroMask)) {
            assertEquals(0.70f, out[0], 0f)
            assertEquals(0.20f, out[1], 0f)
            assertEquals(0.10f, out[2], 0f)
        }
    }

    @Test fun fullPipelineChangesOnlyPixelsInsideDetectedFaces() {
        val width = 80
        val height = 60
        val size = width * height
        fun planes(): Triple<FloatArray, FloatArray, FloatArray> {
            val r = FloatArray(size) { 0.40f }
            val g = FloatArray(size) { 0.20f }
            val b = FloatArray(size) { 0.04f } // gold-like non-skin background
            for (y in 12 until 42) for (x in 14 until 40) {
                val index = y * width + x
                r[index] = 0.60f
                g[index] = 0.36f
                b[index] = 0.26f
            }
            return Triple(r, g, b)
        }
        val look = FilmLook(
            id = "skin-isolation-test",
            displayName = "Skin isolation test",
            lutAsset = null,
            skinTone = SkinToneParams(
                protection = 0.75f,
                naturalness = 0.75f,
                saturationCeiling = 0.62f,
            ),
            adaptive = AdaptiveParams.NONE,
        )
        val lut = FilmLutFactory.build(
            FilmLutFactory.Model(
                r = FilmLutFactory.Channel(contrast = 0.2f, gain = 1.12f),
                g = FilmLutFactory.Channel(contrast = 0.2f),
                b = FilmLutFactory.Channel(contrast = 0.2f, gain = 0.90f),
                saturation = 1.18f,
            ),
        )
        val withoutFace = planes()
        val withFace = planes()
        DevelopPipeline.apply(
            withoutFace.first,
            withoutFace.second,
            withoutFace.third,
            width,
            height,
            look,
            lut,
            faceRegions = emptyList(),
        )
        DevelopPipeline.apply(
            withFace.first,
            withFace.second,
            withFace.third,
            width,
            height,
            look,
            lut,
            faceRegions = listOf(FaceRegion(0.13f, 0.12f, 0.54f, 0.76f)),
        )

        val face = 26 * width + 26
        val gold = 48 * width + 65
        assertTrue(
            "accepted face receives a selective correction",
            kotlin.math.abs(withFace.first[face] - withoutFace.first[face]) > 0.005f,
        )
        assertEquals("gold red stays on the unmodified stock path", withoutFace.first[gold], withFace.first[gold], 0f)
        assertEquals("gold green stays on the unmodified stock path", withoutFace.second[gold], withFace.second[gold], 0f)
        assertEquals("gold blue stays on the unmodified stock path", withoutFace.third[gold], withFace.third[gold], 0f)
    }

    private fun luma(r: Float, g: Float, b: Float): Float =
        0.2126f * r + 0.7152f * g + 0.0722f * b

    private fun saturation(rgb: FloatArray): Float {
        val max = maxOf(rgb[0], rgb[1], rgb[2])
        return if (max > 1e-5f) (max - minOf(rgb[0], rgb[1], rgb[2])) / max else 0f
    }
}
