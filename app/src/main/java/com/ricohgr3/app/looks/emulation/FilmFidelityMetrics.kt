package com.ricohgr3.app.looks.emulation

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Objective comparison of two matched display-sRGB patch or image buffers.
 *
 * This deliberately reports both perceptual OKLab error and physical linear-luminance error.
 * It does not claim that one scalar proves a film match: callers should group results by
 * illuminant, exposure, material patch, and held-out scene as described in
 * `research/FILM_FIDELITY_CALIBRATION.md`.
 */
data class FilmFidelityMetrics(
    val sampleCount: Int,
    /** Mean Euclidean distance in OKLab's native approximately 0..1 scale. */
    val meanOklabDistance: Float,
    val p95OklabDistance: Float,
    val maxOklabDistance: Float,
    /** Root-mean-square absolute linear-light luminance error on the normalized 0..1 scale. */
    val linearLuminanceRmse: Float,
    /** Signed candidate-minus-reference OKLab component biases. */
    val meanLightnessBias: Float,
    val meanABias: Float,
    val meanBBias: Float,
) {
    companion object {
        val EMPTY = FilmFidelityMetrics(
            sampleCount = 0,
            meanOklabDistance = 0f,
            p95OklabDistance = 0f,
            maxOklabDistance = 0f,
            linearLuminanceRmse = 0f,
            meanLightnessBias = 0f,
            meanABias = 0f,
            meanBBias = 0f,
        )
    }
}

object FilmFidelityEvaluator {

    fun compare(
        referenceR: FloatArray,
        referenceG: FloatArray,
        referenceB: FloatArray,
        candidateR: FloatArray,
        candidateG: FloatArray,
        candidateB: FloatArray,
    ): FilmFidelityMetrics {
        val size = referenceR.size
        require(
            listOf(
                referenceG.size,
                referenceB.size,
                candidateR.size,
                candidateG.size,
                candidateB.size,
            ).all { it == size },
        ) { "Reference and candidate planes must have identical lengths" }
        if (size == 0) return FilmFidelityMetrics.EMPTY
        require(
            listOf(
                referenceR,
                referenceG,
                referenceB,
                candidateR,
                candidateG,
                candidateB,
            ).all { plane -> plane.all { it.isFinite() && it in 0f..1f } },
        ) {
            "Reference and candidate display-sRGB planes must contain only finite values in [0,1]"
        }

        val referenceLab = FloatArray(3)
        val candidateLab = FloatArray(3)
        val distances = FloatArray(size)
        var distanceSum = 0.0
        var luminanceSquaredError = 0.0
        var lightnessBias = 0.0
        var aBias = 0.0
        var bBias = 0.0
        var maximum = 0f

        for (i in 0 until size) {
            val referenceRed = referenceR[i]
            val referenceGreen = referenceG[i]
            val referenceBlue = referenceB[i]
            val candidateRed = candidateR[i]
            val candidateGreen = candidateG[i]
            val candidateBlue = candidateB[i]
            ColorMath.srgbToOklab(
                referenceRed,
                referenceGreen,
                referenceBlue,
                referenceLab,
            )
            ColorMath.srgbToOklab(
                candidateRed,
                candidateGreen,
                candidateBlue,
                candidateLab,
            )
            val dl = candidateLab[0] - referenceLab[0]
            val da = candidateLab[1] - referenceLab[1]
            val db = candidateLab[2] - referenceLab[2]
            val distance = sqrt(dl * dl + da * da + db * db)
            distances[i] = distance
            distanceSum += distance
            maximum = maxOf(maximum, distance)
            lightnessBias += dl
            aBias += da
            bBias += db

            val referenceY = ColorMath.linearLuminance(
                referenceRed,
                referenceGreen,
                referenceBlue,
            )
            val candidateY = ColorMath.linearLuminance(
                candidateRed,
                candidateGreen,
                candidateBlue,
            )
            val yError = candidateY - referenceY
            luminanceSquaredError += yError * yError
        }

        distances.sort()
        val p95Index = (ceil(size * 0.95).toInt() - 1).coerceIn(0, size - 1)
        return FilmFidelityMetrics(
            sampleCount = size,
            meanOklabDistance = (distanceSum / size).toFloat(),
            p95OklabDistance = distances[p95Index],
            maxOklabDistance = maximum,
            linearLuminanceRmse = sqrt(luminanceSquaredError / size).toFloat(),
            meanLightnessBias = (lightnessBias / size).toFloat(),
            meanABias = (aBias / size).toFloat(),
            meanBBias = (bBias / size).toFloat(),
        )
    }
}
