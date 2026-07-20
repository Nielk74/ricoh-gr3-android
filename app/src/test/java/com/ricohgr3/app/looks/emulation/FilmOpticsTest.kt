package com.ricohgr3.app.looks.emulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmOpticsTest {

    @Test fun physicalRadiusScalesWithOutputResolution() {
        val format = FilmFormat.FULL_FRAME_35MM
        val preview = FilmOptics.sigmaPixels(8f, 720, format)
        val export = FilmOptics.sigmaPixels(8f, 3000, format)
        assertEquals(3000f / 720f, export / preview, 1e-5f)
    }

    @Test fun diffusionKeepsAConstantFieldAndLinearLuminanceNeutral() {
        val r = FloatArray(81) { 0.63f }
        val g = FloatArray(81) { 0.38f }
        val b = FloatArray(81) { 0.19f }
        val before = ColorMath.linearLuminance(r[0], g[0], b[0])
        FilmOptics.applyDiffusion(
            r,
            g,
            b,
            9,
            9,
            ImageStructureParams(diffusionMicrometres = 12f, strength = 0.7f),
            FilmFormat.FULL_FRAME_35MM,
        )
        assertTrue(r.all { kotlin.math.abs(it - 0.63f) < 1e-6f })
        assertTrue(g.all { kotlin.math.abs(it - 0.38f) < 1e-6f })
        assertTrue(b.all { kotlin.math.abs(it - 0.19f) < 1e-6f })
        assertEquals(before, ColorMath.linearLuminance(r[40], g[40], b[40]), 1e-6f)
    }

    @Test fun diffusionSpreadsAnImpulseWithoutChangingTotalLinearEnergyMaterially() {
        val width = 101
        val height = 67
        val r = FloatArray(width * height)
        val g = FloatArray(width * height)
        val b = FloatArray(width * height)
        val center = (height / 2) * width + width / 2
        r[center] = 1f
        g[center] = 1f
        b[center] = 1f
        FilmOptics.applyDiffusion(
            r,
            g,
            b,
            width,
            height,
            ImageStructureParams(diffusionMicrometres = 300f, strength = 1f),
            FilmFormat.FULL_FRAME_35MM,
        )
        assertTrue(r[center] < 1f)
        assertTrue(r[center + 1] > 0f)
        val energy = r.sumOf { ColorMath.srgbToLinear(it).toDouble() }
        assertEquals(1.0, energy, 2e-5)
        assertTrue(r.all { it.isFinite() && it in 0f..1f })
    }

    @Test fun catalogUsesRestrainedUnmeasuredImageStructureDefaults() {
        for (entry in FilmLookCatalog.entries) {
            val structure = entry.look.imageStructure
            assertTrue(structure.enabled)
            assertTrue(structure.diffusionMicrometres <= 10f)
            assertTrue(structure.strength <= 0.15f)
            assertTrue(entry.model.profile.provenance.basis != CalibrationBasis.LAB_MEASURED)
        }
    }
}
