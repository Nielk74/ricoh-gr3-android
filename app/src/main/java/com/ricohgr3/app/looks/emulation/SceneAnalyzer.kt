package com.ricohgr3.app.looks.emulation

import kotlin.math.abs
import kotlin.math.ln

/**
 * Robust, deliberately conservative scene statistics used by the adaptive film pipeline.
 *
 * This is not scene classification ("sunset", "portrait", …) and it never tries to neutralise
 * the atmosphere of a frame. It measures the properties that a develop decision can safely use:
 * tonal percentiles, clipped/crushed area, colourfulness, a low-chroma warm/cool estimate, and
 * micro-contrast. The analysis samples at most roughly 50k pixels, so its cost is negligible next
 * to the full-frame LUT pass and it allocates only a 256-bin histogram.
 */
data class SceneProfile(
    val p01: Float,
    val p10: Float,
    val p50: Float,
    val p90: Float,
    val p99: Float,
    val meanLuma: Float,
    val meanSaturation: Float,
    val clippedHighlights: Float,
    val crushedShadows: Float,
    val neutralWarmth: Float,
    val neutralConfidence: Float,
    val microContrast: Float,
) {
    val dynamicRange: Float get() = p90 - p10
    val lowKey: Boolean get() = p50 < 0.24f
    val highKey: Boolean get() = p50 > 0.62f
    val highContrast: Boolean get() = p10 < 0.12f && p90 > 0.78f
}

/**
 * Per-look controls for scene adaptation. The defaults are a restrained colour-negative grade;
 * stocks override only the character that genuinely differs (e.g. slide contrast or B&W mix).
 *
 * [lookStrength] is the maximum LUT mix. It intentionally stays below one for colour stocks:
 * camera JPEGs are already rendered photographs, so replacing every input colour with a second
 * baked tone curve is the source of the harsh, "filter app" look.
 */
data class AdaptiveParams(
    val enabled: Boolean = true,
    val lookStrength: Float = 0.82f,
    val autoExposure: Float = 0.75f,
    val shadowProtection: Float = 0.8f,
    val highlightProtection: Float = 0.9f,
    val saturationGuard: Float = 0.8f,
    val grainScale: Float = 1f,
) {
    companion object {
        /** Useful for identity/unit-test looks and any deliberately literal transform. */
        val NONE = AdaptiveParams(
            enabled = false,
            lookStrength = 1f,
            autoExposure = 0f,
            shadowProtection = 0f,
            highlightProtection = 0f,
            saturationGuard = 0f,
            grainScale = 1f,
        )
    }
}

/** Concrete decisions derived once per frame from [SceneProfile] and [AdaptiveParams]. */
data class SceneAdjustment(
    val exposureEv: Float,
    val shadowLift: Float,
    val highlightCompression: Float,
    val contrast: Float,
    val saturation: Float,
    val lookStrength: Float,
    val grainScale: Float,
    val halationScale: Float,
) {
    companion object {
        val NONE = SceneAdjustment(
            exposureEv = 0f,
            shadowLift = 0f,
            highlightCompression = 0f,
            contrast = 0f,
            saturation = 1f,
            lookStrength = 1f,
            grainScale = 1f,
            halationScale = 1f,
        )
    }
}

object SceneAnalyzer {
    private const val HISTOGRAM_SIZE = 256
    private const val LN_2 = 0.69314718056f

    private fun luma(r: Float, g: Float, b: Float): Float =
        0.2126f * r + 0.7152f * g + 0.0722f * b

    /**
     * Analyse an sRGB frame. Sampling is a regular 2D grid instead of a flat array stride, which
     * avoids accidentally over-representing a repeated row/column pattern.
     */
    fun analyze(
        r: FloatArray,
        g: FloatArray,
        b: FloatArray,
        width: Int,
        height: Int,
    ): SceneProfile {
        require(width >= 0 && height >= 0)
        require(r.size == g.size && g.size == b.size)
        require(r.size == width * height)
        if (r.isEmpty()) {
            return SceneProfile(
                p01 = 0f, p10 = 0.1f, p50 = 0.5f, p90 = 0.9f, p99 = 1f,
                meanLuma = 0.5f, meanSaturation = 0f,
                clippedHighlights = 0f, crushedShadows = 0f,
                neutralWarmth = 0f, neutralConfidence = 0f, microContrast = 0f,
            )
        }

        val histogram = IntArray(HISTOGRAM_SIZE)
        val targetAxis = 224 // <= 50,176 samples for a large image.
        val stepX = (width / targetAxis).coerceAtLeast(1)
        val stepY = (height / targetAxis).coerceAtLeast(1)
        var samples = 0
        var sumLuma = 0.0
        var sumSaturation = 0.0
        var clipped = 0
        var crushed = 0
        var neutralWeight = 0.0
        var neutralWarmth = 0.0
        var micro = 0.0
        var microSamples = 0

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val i = y * width + x
                val rr = r[i].coerceIn(0f, 1f)
                val gg = g[i].coerceIn(0f, 1f)
                val bb = b[i].coerceIn(0f, 1f)
                val lum = luma(rr, gg, bb).coerceIn(0f, 1f)
                val max = maxOf(rr, gg, bb)
                val min = minOf(rr, gg, bb)
                val sat = if (max > 1e-4f) (max - min) / max else 0f

                histogram[(lum * 255f + 0.5f).toInt().coerceIn(0, 255)]++
                samples++
                sumLuma += lum
                sumSaturation += sat
                if (lum >= 0.985f) clipped++
                if (lum <= 0.02f) crushed++

                // Warm/cool estimate from low-chroma pixels only. It informs how strongly a stock
                // tint may be layered; it is never used as an automatic white-balance command.
                if (lum in 0.12f..0.92f) {
                    val w = (1f - sat / 0.28f).coerceIn(0f, 1f)
                    if (w > 0f) {
                        neutralWeight += w
                        neutralWarmth += w * ((rr - bb) / (rr + gg + bb + 0.08f))
                    }
                }

                // The minimum of horizontal/vertical one-pixel differences rejects a normal
                // directional edge but responds to sensor/JPEG noise and genuinely busy texture.
                if (x + 1 < width && y + 1 < height) {
                    val right = luma(r[i + 1], g[i + 1], b[i + 1])
                    val down = luma(r[i + width], g[i + width], b[i + width])
                    micro += minOf(abs(lum - right), abs(lum - down))
                    microSamples++
                }
                x += stepX
            }
            y += stepY
        }

        fun percentile(fraction: Float): Float {
            val target = (samples * fraction).toInt().coerceIn(0, samples - 1)
            var cumulative = 0
            for (i in histogram.indices) {
                cumulative += histogram[i]
                if (cumulative > target) return i / 255f
            }
            return 1f
        }

        return SceneProfile(
            p01 = percentile(0.01f),
            p10 = percentile(0.10f),
            p50 = percentile(0.50f),
            p90 = percentile(0.90f),
            p99 = percentile(0.99f),
            meanLuma = (sumLuma / samples).toFloat(),
            meanSaturation = (sumSaturation / samples).toFloat(),
            clippedHighlights = clipped.toFloat() / samples,
            crushedShadows = crushed.toFloat() / samples,
            neutralWarmth = if (neutralWeight > 1.0) (neutralWarmth / neutralWeight).toFloat() else 0f,
            neutralConfidence = (neutralWeight / samples).toFloat().coerceIn(0f, 1f),
            microContrast = if (microSamples > 0) (micro / microSamples).toFloat() else 0f,
        )
    }

    /**
     * Turn measurements into bounded develop decisions. Every correction is partial by design:
     * the goal is consistent input for a film stock, not an "auto enhance" that erases low-key,
     * backlit, tungsten, or blue-hour intent.
     */
    fun adjustment(
        profile: SceneProfile,
        params: AdaptiveParams,
        iso: Int? = null,
    ): SceneAdjustment {
        if (!params.enabled) return SceneAdjustment.NONE

        val median = profile.p50.coerceIn(0.03f, 0.97f)
        val targetMedian = when {
            profile.lowKey && profile.p90 > 0.62f -> 0.30f // keep night/backlight recognisably dark
            profile.lowKey -> 0.34f
            profile.highKey -> 0.55f
            else -> 0.42f
        }
        val desiredEv = ln(targetMedian / median) / LN_2
        var exposureEv = (desiredEv * 0.28f * params.autoExposure).coerceIn(-0.28f, 0.32f)
        if (profile.highContrast && exposureEv > 0f) exposureEv *= 0.45f
        if (profile.lowKey) exposureEv = exposureEv.coerceAtMost(0.18f)
        if (profile.clippedHighlights > 0.025f) exposureEv = exposureEv.coerceAtMost(0f)

        val shadowNeed = ((0.16f - profile.p10) / 0.16f).coerceIn(0f, 1f)
        val moodGuard = if (profile.lowKey) 0.58f else 1f
        val shadowLift = 0.12f * shadowNeed * params.shadowProtection * moodGuard

        val highlightNeed = ((profile.p90 - 0.70f) / 0.27f).coerceIn(0f, 1f)
        val clippingNeed = (profile.clippedHighlights / 0.08f).coerceIn(0f, 1f)
        val highlightCompression =
            ((0.42f * highlightNeed + 0.16f * clippingNeed) * params.highlightProtection)
                .coerceIn(0f, 0.55f)

        val contrast = when {
            profile.dynamicRange < 0.40f -> 0.10f
            profile.dynamicRange > 0.72f -> -0.08f
            else -> 0f
        }

        val saturationTarget = when {
            profile.meanSaturation > 0.48f -> 0.90f
            profile.meanSaturation > 0.36f -> 0.95f
            profile.meanSaturation < 0.14f -> 1.035f
            else -> 1f
        }
        val saturation = 1f + (saturationTarget - 1f) * params.saturationGuard

        // Strong existing casts and highly saturated files get a little less *additional* stock
        // colour. This protects tungsten warmth and blue-hour cool rather than neutralising them.
        val castStress =
            ((abs(profile.neutralWarmth) - 0.035f) / 0.16f).coerceIn(0f, 1f) *
                profile.neutralConfidence
        val saturationStress = ((profile.meanSaturation - 0.34f) / 0.32f).coerceIn(0f, 1f)
        val clipStress = (profile.clippedHighlights / 0.10f).coerceIn(0f, 1f)
        val strengthScale = 1f - 0.16f * castStress - 0.10f * saturationStress - 0.08f * clipStress
        val lookStrength =
            (params.lookStrength * strengthScale).coerceIn(params.lookStrength * 0.62f, params.lookStrength)

        // The stock defines grain speed/size. Camera ISO is only a *source-noise guard*: these
        // inputs already contain digital sensor noise, so reduce the added density field gently rather
        // than pretending EXIF ISO is a pushed-film control. Keep enough emulsion structure at
        // high ISO that Portra/CineStill 800 do not collapse into plain sensor noise.
        val isoScale = when {
            iso == null || iso <= 200 -> 1f
            iso <= 400 -> 0.96f
            iso <= 800 -> 0.90f
            iso <= 1600 -> 0.82f
            iso <= 3200 -> 0.74f
            else -> 0.66f
        }
        val textureScale =
            (1f - 0.28f * ((profile.microContrast - 0.012f) / 0.055f).coerceIn(0f, 1f))
        // Do not globally suppress a low-key frame. Local density in DevelopPipeline already
        // makes its shadows rougher while preserving true black; reducing the whole frame here
        // erased exactly the underexposed-film behaviour the model is meant to retain.
        val grainScale = params.grainScale * isoScale * textureScale

        val halationScale = when {
            profile.p50 < 0.34f && profile.p99 > 0.82f -> 1.15f
            profile.highKey -> 0.68f
            else -> 0.90f
        }

        return SceneAdjustment(
            exposureEv = exposureEv,
            shadowLift = shadowLift,
            highlightCompression = highlightCompression,
            contrast = contrast,
            saturation = saturation,
            lookStrength = lookStrength,
            grainScale = grainScale,
            halationScale = halationScale,
        )
    }
}
