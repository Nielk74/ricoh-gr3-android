package com.ricohgr3.app.looks.emulation

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalFilmGrainTest {

    private val grain = GrainParams(
        amount = 0.075f,
        size = 1.7f,
        shadowBias = 0.52f,
        chroma = 0.12f,
        clumping = 0.18f,
        seed = 400L,
    )

    @Test fun sameStockPhotoAndFramingAreExactlyDeterministic() {
        fun render(): FloatArray {
            val r = FloatArray(96 * 64) { 0.48f }
            val g = r.copyOf()
            val b = r.copyOf()
            PhysicalFilmGrain.apply(
                r, g, b, 96, 64, grain, renderSeed = 0x1234_5678L,
                filmPlane = canonicalCrop(96),
            )
            return r
        }

        assertTrue(render().contentEquals(render()))
    }

    @Test fun differentPhotoSeedsProduceDifferentFields() {
        fun render(renderSeed: Long): FloatArray {
            val r = FloatArray(96 * 64) { 0.48f }
            val g = r.copyOf()
            val b = r.copyOf()
            PhysicalFilmGrain.apply(
                r, g, b, 96, 64, grain, renderSeed,
                filmPlane = canonicalCrop(96),
            )
            return r
        }

        val first = render(1L)
        val second = render(2L)
        val meanDifference = first.indices.sumOf { abs(first[it] - second[it]).toDouble() } /
            first.size
        assertTrue("different photos need genuinely different grain ($meanDifference)", meanDifference > 0.005)
    }

    @Test fun densityVariationIsZeroMeanAndPreservesEndpoints() {
        val width = 384
        val height = 256
        val count = width * height
        val r = FloatArray(count) { 0.5f }
        val g = r.copyOf()
        val b = r.copyOf()
        PhysicalFilmGrain.apply(
            r, g, b, width, height, grain.copy(chroma = 0f), renderSeed = 91L,
            filmPlane = canonicalCrop(width),
        )

        assertEquals("grain must not veil neutral grey", 0.5, r.average(), 0.002)
        assertTrue(
            "monochrome density grain keeps neutrals neutral",
            r.indices.all { r[it] == g[it] && g[it] == b[it] },
        )

        val blackR = FloatArray(64)
        val blackG = blackR.copyOf()
        val blackB = blackR.copyOf()
        val whiteR = FloatArray(64) { 1f }
        val whiteG = whiteR.copyOf()
        val whiteB = whiteR.copyOf()
        val endpointPlane = canonicalCrop(8)
        PhysicalFilmGrain.apply(
            blackR, blackG, blackB, 8, 8, grain, 7L, endpointPlane,
        )
        PhysicalFilmGrain.apply(
            whiteR, whiteG, whiteB, 8, 8, grain, 7L, endpointPlane,
        )
        assertTrue(blackR.all { it == 0f })
        assertTrue(whiteR.all { it == 1f })
    }

    @Test fun sameFilmFieldMatchesAt720And3000Pixels() {
        // A shallow 15:1 strip keeps this literal 720-vs-3000 test inexpensive while retaining
        // exactly the same aspect ratio, full-frame mapping, and 4.1667x pixel-footprint ratio.
        val lowWidth = 720
        val lowHeight = 48
        val highWidth = 3000
        val highHeight = 200
        val low = renderNeutral(lowWidth, lowHeight, 0.47f, grain.copy(chroma = 0f), 334L)
        val high = renderNeutral(highWidth, highHeight, 0.47f, grain.copy(chroma = 0f), 334L)
        val highAreaAverage = areaDownsample(high, highWidth, highHeight, lowWidth, lowHeight)
        val lowResidual = FloatArray(low.size) { low[it] - 0.47f }
        val highResidual = FloatArray(low.size) { highAreaAverage[it] - 0.47f }
        val correlation = correlation(lowResidual, highResidual)
        val lowStd = standardDeviation(lowResidual)
        val highStd = standardDeviation(highResidual)

        assertTrue("preview and downsampled export must share the field (r=$correlation)", correlation > 0.97)
        assertTrue(
            "pixel-footprint integration must preserve the visible variance " +
                "(preview=$lowStd export=$highStd)",
            lowStd / highStd in 0.90..1.10,
        )
    }

    @Test fun responsePeaksInMidtonesAndRollsOffAtBothEnds() {
        fun standardDeviationAt(level: Float): Double {
            val result = renderNeutral(
                256, 192, level, grain.copy(chroma = 0f), renderSeed = 82L,
                plane = canonicalCrop(256),
            )
            return standardDeviation(FloatArray(result.size) { result[it] - level })
        }

        val black = standardDeviationAt(0.02f)
        val mid = standardDeviationAt(0.42f)
        val white = standardDeviationAt(0.96f)
        assertTrue("visible density response must peak in the mids ($black, $mid, $white)", mid > black * 2.0)
        assertTrue("highlight response must roll off ($white < $mid)", white < mid * 0.45)
    }

    @Test fun rgbVariationIsDistinctButTightlyCorrelated() {
        val width = 256
        val height = 192
        val count = width * height
        val r = FloatArray(count) { 0.46f }
        val g = r.copyOf()
        val b = r.copyOf()
        PhysicalFilmGrain.apply(
            r, g, b, width, height, grain.copy(chroma = 0.35f), renderSeed = 23L,
            filmPlane = canonicalCrop(width),
        )
        val rr = FloatArray(count) { r[it] - 0.46f }
        val gg = FloatArray(count) { g[it] - 0.46f }
        val bb = FloatArray(count) { b[it] - 0.46f }

        assertTrue(
            "colour grain should not collapse to identical channel values",
            r.indices.count { r[it] == g[it] && g[it] == b[it] } < count / 20,
        )
        assertTrue("red/green crystal variation must stay coupled", correlation(rr, gg) > 0.90)
        assertTrue("blue/green crystal variation must stay coupled", correlation(bb, gg) > 0.90)
    }

    @Test fun fieldDoesNotRepeatAtLegacyTexturePeriods() {
        val width = 1080
        val height = 24
        val result = renderNeutral(
            width, height, 0.5f, grain.copy(chroma = 0f), renderSeed = 55L,
            plane = canonicalCrop(width),
        )
        for (period in intArrayOf(256, 512)) {
            var difference = 0.0
            var samples = 0
            for (y in 0 until height) {
                for (x in 0 until width - period) {
                    difference += abs(result[y * width + x] - result[y * width + x + period])
                    samples++
                }
            }
            difference /= samples
            assertTrue("grain must not repeat every $period pixels ($difference)", difference > 0.006)
        }
    }

    @Test fun compactCrystalsDoNotCreateLowFrequencyClouds() {
        val width = 320
        val height = 320
        val result = renderNeutral(
            width, height, 0.5f, grain.copy(chroma = 0f), renderSeed = 909L,
            plane = canonicalCrop(width),
        )
        val pixelStd = standardDeviation(FloatArray(result.size) { result[it] - 0.5f })
        val blockSize = 16
        val blockMeans = ArrayList<Float>()
        for (top in 0 until height step blockSize) {
            for (left in 0 until width step blockSize) {
                var sum = 0.0
                for (y in top until top + blockSize) {
                    for (x in left until left + blockSize) sum += result[y * width + x]
                }
                blockMeans += (sum / (blockSize * blockSize)).toFloat()
            }
        }
        val blockMean = blockMeans.average()
        val blockStd = sqrt(blockMeans.sumOf {
            val difference = it - blockMean
            difference * difference
        } / blockMeans.size)

        assertTrue(
            "16px density drift must stay far below crystal texture " +
                "(blocks=$blockStd pixels=$pixelStd)",
            blockStd < pixelStd * 0.20,
        )
    }

    @Test fun physicalCropOriginSelectsTheSameFieldCoordinates() {
        val params = grain.copy(chroma = 0f)
        val full = renderNeutral(
            300, 200, 0.48f, params, renderSeed = 101L,
            plane = PhysicalFilmGrain.FilmPlane(
                longEdgeMillimeters = 36f,
                framingScale = 0.10f,
            ),
        )
        val crop = renderNeutral(
            100, 100, 0.48f, params, renderSeed = 101L,
            plane = PhysicalFilmGrain.FilmPlane(
                longEdgeMillimeters = 36f,
                originXMillimeters = 1.2f,
                originYMillimeters = 0.6f,
                framingScale = 1f / 30f,
            ),
        )
        // At 300 px over 3.6 mm, the crop begins at (100,50) and spans 100 px.
        var error = 0.0
        for (y in 0 until 100) {
            for (x in 0 until 100) {
                error += abs(crop[y * 100 + x] - full[(y + 50) * 300 + x + 100])
            }
        }
        assertTrue("film-coordinate crops must remain anchored (${error / crop.size})", error / crop.size < 1e-6)
    }

    private fun canonicalCrop(longEdgePixels: Int) = PhysicalFilmGrain.FilmPlane(
        framingScale = longEdgePixels / 3000f,
    )

    private fun renderNeutral(
        width: Int,
        height: Int,
        level: Float,
        params: GrainParams,
        renderSeed: Long,
        plane: PhysicalFilmGrain.FilmPlane = PhysicalFilmGrain.FilmPlane(),
    ): FloatArray {
        val r = FloatArray(width * height) { level }
        val g = r.copyOf()
        val b = r.copyOf()
        PhysicalFilmGrain.apply(r, g, b, width, height, params, renderSeed, plane)
        return r
    }

    /** Area resampler used only to compare a high-resolution render with its preview footprint. */
    private fun areaDownsample(
        source: FloatArray,
        sourceWidth: Int,
        sourceHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
    ): FloatArray {
        val output = FloatArray(outputWidth * outputHeight)
        val scaleX = sourceWidth.toDouble() / outputWidth
        val scaleY = sourceHeight.toDouble() / outputHeight
        for (oy in 0 until outputHeight) {
            val top = oy * scaleY
            val bottom = (oy + 1) * scaleY
            val firstY = floor(top).toInt()
            val lastY = ceil(bottom).toInt() - 1
            for (ox in 0 until outputWidth) {
                val left = ox * scaleX
                val right = (ox + 1) * scaleX
                val firstX = floor(left).toInt()
                val lastX = ceil(right).toInt() - 1
                var sum = 0.0
                var area = 0.0
                for (sy in firstY..lastY) {
                    val overlapY = minOf(bottom, sy + 1.0) - maxOf(top, sy.toDouble())
                    for (sx in firstX..lastX) {
                        val overlapX = minOf(right, sx + 1.0) - maxOf(left, sx.toDouble())
                        val weight = overlapX * overlapY
                        sum += source[sy * sourceWidth + sx] * weight
                        area += weight
                    }
                }
                output[oy * outputWidth + ox] = (sum / area).toFloat()
            }
        }
        return output
    }

    private fun standardDeviation(values: FloatArray): Double {
        val mean = values.average()
        return sqrt(values.sumOf {
            val difference = it - mean
            difference * difference
        } / values.size)
    }

    private fun correlation(first: FloatArray, second: FloatArray): Double {
        val firstMean = first.average()
        val secondMean = second.average()
        var covariance = 0.0
        var firstVariance = 0.0
        var secondVariance = 0.0
        for (i in first.indices) {
            val a = first[i] - firstMean
            val b = second[i] - secondMean
            covariance += a * b
            firstVariance += a * a
            secondVariance += b * b
        }
        return covariance / sqrt(firstVariance * secondVariance)
    }
}
