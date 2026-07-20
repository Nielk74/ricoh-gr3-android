package com.ricohgr3.app.looks.emulation

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Resolution-independent film-plane grain.
 *
 * The random field is defined in physical film coordinates rather than output pixels. Random
 * crystal impulses live on an infinite, non-repeating hashed lattice and are convolved with a
 * compact tent response. This is a practical analytic shot-noise approximation to a stochastic
 * Boolean grain model: only the crystal sites whose compact support intersects a pixel contribute.
 *
 * Most importantly, the tent response is integrated analytically over every output pixel's film
 * footprint. A preview pixel therefore contains the area-average of the same crystal field that
 * a full-resolution export samples. Grain size does not silently change with bitmap dimensions,
 * and no texture plate or fixed 3x3 output-pixel kernel is involved.
 *
 * [GrainParams.size] remains backwards-compatible with the existing catalog: one size unit is one
 * reference sample at [FilmPlane.referenceSamplesPerMillimeter]. With the default 35 mm frame,
 * 3000 samples span 36 mm, so one unit is 12 micrometres on film.
 */
object PhysicalFilmGrain {

    /**
     * Maps an output frame onto the continuous emulsion.
     *
     * [longEdgeMillimeters] is the physical long edge represented by a full frame. The default is
     * a 35 mm still frame. [referenceSamplesPerMillimeter] calibrates the catalog's historical
     * [GrainParams.size] values to physical distance. [framingScale] is the fraction of that long
     * edge represented by the rendered crop; for example, `0.5` represents a crop whose long edge
     * spans 18 mm. Origins anchor crops to the same underlying field.
     */
    data class FilmPlane(
        val longEdgeMillimeters: Float = 36f,
        val referenceSamplesPerMillimeter: Float = 3000f / 36f,
        val originXMillimeters: Float = 0f,
        val originYMillimeters: Float = 0f,
        val framingScale: Float = 1f,
    ) {
        init {
            require(longEdgeMillimeters.isFinite() && longEdgeMillimeters > 0f) {
                "longEdgeMillimeters must be finite and positive"
            }
            require(
                referenceSamplesPerMillimeter.isFinite() &&
                    referenceSamplesPerMillimeter > 0f,
            ) {
                "referenceSamplesPerMillimeter must be finite and positive"
            }
            require(originXMillimeters.isFinite() && originYMillimeters.isFinite()) {
                "film-plane origins must be finite"
            }
            require(framingScale.isFinite() && framingScale > 0f) {
                "framingScale must be finite and positive"
            }
        }
    }

    /**
     * Apply grain in place to display-referred sRGB planes.
     *
     * [renderSeed] must be stable for one source photograph (for example a hash of its persistent
     * photo ID), but different across photographs. It is mixed with the stock seed in
     * [GrainParams], so a photograph keeps stable grain while two same-sized photographs no
     * longer receive an identical field.
     *
     * The pipeline only needs to provide a per-photo [renderSeed] and, for a crop, its
     * [filmPlane] mapping.
     */
    fun apply(
        r: FloatArray,
        g: FloatArray,
        b: FloatArray,
        width: Int,
        height: Int,
        params: GrainParams,
        renderSeed: Long,
        filmPlane: FilmPlane = FilmPlane(),
    ) {
        require(width >= 0 && height >= 0) { "dimensions must be non-negative" }
        val pixelCount = width.toLong() * height.toLong()
        require(pixelCount <= Int.MAX_VALUE) { "image is too large" }
        require(r.size >= pixelCount && g.size >= pixelCount && b.size >= pixelCount) {
            "RGB planes must contain width*height samples"
        }
        if (pixelCount == 0L || !params.enabled) return

        val longEdgePixels = maxOf(width, height).toFloat()
        val referenceLongEdge =
            filmPlane.longEdgeMillimeters * filmPlane.referenceSamplesPerMillimeter
        val pixelFootprint =
            referenceLongEdge * filmPlane.framingScale / longEdgePixels
        val originX = filmPlane.originXMillimeters * filmPlane.referenceSamplesPerMillimeter
        val originY = filmPlane.originYMillimeters * filmPlane.referenceSamplesPerMillimeter
        val pitch = params.size.coerceIn(MIN_CRYSTAL_PITCH, MAX_CRYSTAL_PITCH)

        val xKernels = buildAxisKernels(width, originX, pixelFootprint, pitch)
        val yKernels = buildAxisKernels(height, originY, pixelFootprint, pitch)
        val seed = mix64(params.seed xor java.lang.Long.rotateLeft(renderSeed, 23))
        val clumping = params.clumping.coerceIn(0f, 0.5f)
        val scratch = FloatArray(2)

        var index = 0
        for (y in 0 until height) {
            val yKernel = yKernels[y]
            for (x in 0 until width) {
                sampleCrystalField(xKernels[x], yKernel, seed, clumping, scratch)
                compositeDensityGrain(
                    r = r,
                    g = g,
                    b = b,
                    index = index,
                    grain = scratch[0],
                    secondary = scratch[1],
                    params = params,
                )
                index++
            }
        }
    }

    /**
     * Midtone-peaked visible-density response. It rolls off at both endpoints and lets a stock's
     * [shadowBias] move the peak toward the low mids without creating a separate cloudy octave.
     */
    fun densityResponse(encodedLuminance: Float, shadowBias: Float): Float {
        val l = encodedLuminance.coerceIn(0f, 1f)
        val bias = shadowBias.coerceIn(0f, 1f)
        val peak = 0.5f - 0.2f * bias
        val width = if (l < peak) 0.32f + 0.25f * bias else 0.30f
        val distance = (l - peak) / width
        val hump = exp(-0.5f * distance * distance)
        val highlight =
            (1f - (l - 0.75f).coerceAtLeast(0f) / 0.25f).coerceIn(0.15f, 1f)
        val shadow = (0.45f + l / 0.06f).coerceIn(0.45f, 1f)
        return (hump * highlight * shadow).coerceIn(0f, 1f)
    }

    private data class AxisKernel(
        val firstSite: Int,
        val weights: FloatArray,
    )

    /**
     * Exact integral of the tent basis over one output pixel. The weights form a partition of
     * unity; downsampling is therefore area integration of one continuous random field, not a
     * resolution-specific redraw.
     */
    private fun buildAxisKernels(
        outputSize: Int,
        origin: Float,
        pixelFootprint: Float,
        crystalPitch: Float,
    ): Array<AxisKernel> = Array(outputSize) { outputIndex ->
        val lower = (origin + outputIndex * pixelFootprint) / crystalPitch
        val upper = (origin + (outputIndex + 1) * pixelFootprint) / crystalPitch
        val first = floor(lower.toDouble()).toInt() - 1
        val last = ceil(upper.toDouble()).toInt() + 1
        val weights = FloatArray(last - first + 1)
        val inverseSpan = 1f / (upper - lower).coerceAtLeast(1e-8f)
        var sum = 0f
        for (offset in weights.indices) {
            val site = first + offset
            val weight = (
                tentPrimitive(upper - site) -
                    tentPrimitive(lower - site)
                ) * inverseSpan
            weights[offset] = weight.coerceAtLeast(0f)
            sum += weights[offset]
        }
        // Floating point endpoints can leave a few ulps of error. Normalising retains exact DC.
        if (sum > 0f && sum != 1f) {
            for (i in weights.indices) weights[i] /= sum
        }
        AxisKernel(firstSite = first, weights = weights)
    }

    /** Antiderivative of `max(1-|x|, 0)`, with total area one. */
    private fun tentPrimitive(x: Float): Float = when {
        x <= -1f -> 0f
        x < 0f -> {
            val t = x + 1f
            0.5f * t * t
        }
        x < 1f -> {
            val t = 1f - x
            1f - 0.5f * t * t
        }
        else -> 1f
    }

    /**
     * Sample the two correlated crystal planes. The first field gets an orthogonal cubic term,
     * applied to lattice impulses before integration, so clumping remains resolution-independent
     * instead of being synthesized after a preview has already averaged its pixel footprint.
     */
    private fun sampleCrystalField(
        xKernel: AxisKernel,
        yKernel: AxisKernel,
        seed: Long,
        clumping: Float,
        out: FloatArray,
    ) {
        val tailScale = 1.8f * clumping
        // For U[-1,1], u^3 - 3u/5 is orthogonal to u and has variance 4/175. Correcting the
        // variance means clumping changes the distribution's tails, not the amount control.
        val tailNormalization =
            1f / sqrt(1f + tailScale * tailScale * (12f / 175f))
        var primary = 0f
        var secondary = 0f
        for (yo in yKernel.weights.indices) {
            val wy = yKernel.weights[yo]
            if (wy <= 0f) continue
            val siteY = yKernel.firstSite + yo
            for (xo in xKernel.weights.indices) {
                val weight = wy * xKernel.weights[xo]
                if (weight <= 0f) continue
                val siteX = xKernel.firstSite + xo
                val hash = latticeHash(siteX, siteY, seed)
                val u = signedUnit(hash, 40)
                val v = signedUnit(hash, 8)
                val heavyTail = u * u * u - 0.6f * u
                primary += weight * (u + tailScale * heavyTail) * tailNormalization
                secondary += weight * v
            }
        }
        out[0] = primary * FIELD_GAIN
        out[1] = secondary * FIELD_GAIN
    }

    /**
     * Density-like luminance perturbation in exact linear light, followed by luminance-neutral,
     * tightly coupled RGB variation. The field is scalar-dominant, so it reads as emulsion rather
     * than independent per-channel sensor noise.
     */
    private fun compositeDensityGrain(
        r: FloatArray,
        g: FloatArray,
        b: FloatArray,
        index: Int,
        grain: Float,
        secondary: Float,
        params: GrainParams,
    ) {
        var linearR = ColorMath.srgbToLinear(r[index])
        var linearG = ColorMath.srgbToLinear(g[index])
        var linearB = ColorMath.srgbToLinear(b[index])
        val oldY = KR * linearR + KG * linearG + KB * linearB
        if (oldY <= 1e-7f || oldY >= 1f - 1e-7f) return

        val encodedY = ColorMath.linearToSrgb(oldY)
        val density = densityResponse(encodedY, params.shadowBias)
        val boundedY = oldY.coerceIn(0.00001f, 0.99999f)
        val oldLogOdds = kotlin.math.ln(boundedY / (1f - boundedY))
        val densityShift = grain * params.amount * density * DENSITY_GAIN
        val newY = (1f / (1f + exp(-(oldLogOdds + densityShift)))).coerceIn(0f, 1f)
        val luminanceScale = newY / oldY.coerceAtLeast(1e-8f)
        linearR *= luminanceScale
        linearG *= luminanceScale
        linearB *= luminanceScale

        val chromaAmount =
            params.amount * density * params.chroma.coerceIn(0f, 1f) * CHROMA_GAIN
        if (chromaAmount > 0f) {
            // Both chroma fields remain strongly tied to the luma crystal. The small secondary
            // component merely prevents every grain from carrying the same fixed colour.
            val chromaR = 0.86f * grain + 0.14f * secondary
            val chromaB = 0.86f * grain - 0.14f * secondary
            val chromaG = -(KR * chromaR + KB * chromaB) / KG
            val endpointEnvelope = 4f * newY * (1f - newY)
            val scale = chromaAmount * endpointEnvelope
            linearR += chromaR * scale
            linearG += chromaG * scale
            linearB += chromaB * scale
        }

        // Compress toward the new physical luminance instead of clipping channels independently.
        val maximum = maxOf(linearR, linearG, linearB)
        if (maximum > 1f && maximum > newY) {
            val mix = ((1f - newY) / (maximum - newY)).coerceIn(0f, 1f)
            linearR = newY + (linearR - newY) * mix
            linearG = newY + (linearG - newY) * mix
            linearB = newY + (linearB - newY) * mix
        }
        val minimum = minOf(linearR, linearG, linearB)
        if (minimum < 0f && minimum < newY) {
            val mix = (newY / (newY - minimum)).coerceIn(0f, 1f)
            linearR = newY + (linearR - newY) * mix
            linearG = newY + (linearG - newY) * mix
            linearB = newY + (linearB - newY) * mix
        }

        r[index] = ColorMath.linearToSrgb(linearR).coerceIn(0f, 1f)
        g[index] = ColorMath.linearToSrgb(linearG).coerceIn(0f, 1f)
        b[index] = ColorMath.linearToSrgb(linearB).coerceIn(0f, 1f)
    }

    /** Infinite coordinate hash: no texture dimensions, wrapping, or practical repeat period. */
    private fun latticeHash(x: Int, y: Int, seed: Long): Long {
        var value = seed +
            x.toLong() * -7046029254386353131L +
            y.toLong() * -4417276706812531889L
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        return value xor (value ushr 31)
    }

    private fun mix64(input: Long): Long {
        var value = input
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        return value xor (value ushr 31)
    }

    private fun signedUnit(hash: Long, shift: Int): Float {
        val bits = (hash ushr shift) and 0x00FF_FFFFL
        return bits.toFloat() / 8_388_607.5f - 1f
    }

    private const val KR = 0.2126f
    private const val KG = 0.7152f
    private const val KB = 0.0722f
    private const val FIELD_GAIN = 1.25f
    private const val DENSITY_GAIN = 2.65f
    private const val CHROMA_GAIN = 0.20f
    private const val MIN_CRYSTAL_PITCH = 0.5f
    private const val MAX_CRYSTAL_PITCH = 8f
}
