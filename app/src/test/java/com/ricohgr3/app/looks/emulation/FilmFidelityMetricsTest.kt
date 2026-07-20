package com.ricohgr3.app.looks.emulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmFidelityMetricsTest {

    @Test fun identicalBuffersHaveZeroError() {
        val r = floatArrayOf(0f, 0.18f, 0.9f)
        val g = floatArrayOf(0f, 0.42f, 0.6f)
        val b = floatArrayOf(0f, 0.76f, 0.3f)
        val result = FilmFidelityEvaluator.compare(r, g, b, r, g, b)

        assertEquals(3, result.sampleCount)
        assertEquals(0f, result.meanOklabDistance, 0f)
        assertEquals(0f, result.p95OklabDistance, 0f)
        assertEquals(0f, result.maxOklabDistance, 0f)
        assertEquals(0f, result.linearLuminanceRmse, 0f)
    }

    @Test fun metricsExposeMagnitudeTailAndSignedColourBias() {
        val reference = FloatArray(20) { 0.35f }
        val candidateR = FloatArray(20) { 0.35f }
        val candidateG = FloatArray(20) { 0.35f }
        val candidateB = FloatArray(20) { 0.35f }
        for (i in 0 until 4) candidateR[i] = 0.55f
        candidateR[0] = 0.95f

        val result = FilmFidelityEvaluator.compare(
            reference,
            reference,
            reference,
            candidateR,
            candidateG,
            candidateB,
        )

        assertTrue(result.meanOklabDistance > 0f)
        assertTrue(result.p95OklabDistance > result.meanOklabDistance)
        assertTrue(result.maxOklabDistance >= result.p95OklabDistance)
        assertTrue(result.linearLuminanceRmse > 0f)
        assertTrue("red candidate should have positive OKLab a bias", result.meanABias > 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedPlanesAreRejected() {
        FilmFidelityEvaluator.compare(
            floatArrayOf(0f),
            floatArrayOf(0f),
            floatArrayOf(0f),
            floatArrayOf(),
            floatArrayOf(0f),
            floatArrayOf(0f),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun outOfDisplayRangeSamplesAreRejectedRatherThanSilentlyClamped() {
        FilmFidelityEvaluator.compare(
            floatArrayOf(0f),
            floatArrayOf(0f),
            floatArrayOf(0f),
            floatArrayOf(1.01f),
            floatArrayOf(0f),
            floatArrayOf(0f),
        )
    }
}
