package com.ricohgr3.app.looks.emulation

import kotlin.math.abs
import kotlin.math.ln

/**
 * Robust, deliberately conservative scene statistics used by the adaptive film pipeline.
 *
 * This is not scene classification ("sunset", "portrait", …) and it never tries to neutralise
 * the atmosphere of a frame. It measures the properties that a develop decision can safely use:
 * tonal percentiles, clipped/crushed area, colourfulness, a low-chroma warm/cool estimate, and
 * micro-contrast. The analysis samples at most roughly 50k canonical positions, so its cost is
 * negligible next to the full-frame LUT pass and it allocates only a small linear-light histogram.
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
    val lowKey: Boolean get() = p50 < ColorMath.srgbToLinear(0.24f)
    val highKey: Boolean get() = p50 > ColorMath.srgbToLinear(0.62f)
    val highContrast: Boolean get() =
        p10 < ColorMath.srgbToLinear(0.12f) &&
            p90 > ColorMath.srgbToLinear(0.78f)
}

/**
 * Per-look controls for scene adaptation. The defaults are a restrained colour-negative grade;
 * stocks override only the character that genuinely differs (e.g. slide contrast or B&W mix).
 *
 * These parameters contain only runtime safeguards. Scene-invariant stock and texture strength
 * live in [StockRenderParams], so changing a Smart guard cannot silently alter the Stock render.
 */
data class AdaptiveParams(
    val enabled: Boolean = true,
    val autoExposure: Float = 0.75f,
    val shadowProtection: Float = 0.8f,
    val highlightProtection: Float = 0.9f,
    val saturationGuard: Float = 0.8f,
) {
    companion object {
        /** Useful for identity/unit-test looks and any deliberately literal transform. */
        val NONE = AdaptiveParams(
            enabled = false,
            autoExposure = 0f,
            shadowProtection = 0f,
            highlightProtection = 0f,
            saturationGuard = 0f,
        )
    }
}

/**
 * Authored scene-invariant rendering baseline for a stock.
 *
 * [lookStrength] is deliberately below one for most colour stocks because camera JPEGs and
 * platform-rendered DNGs already carry a display rendering. [grainScale] calibrates the stock's
 * texture amount independently from Smart's ISO/scene-noise guard.
 */
data class StockRenderParams(
    val lookStrength: Float = 1f,
    val grainScale: Float = 1f,
) {
    init {
        require(lookStrength.isFinite() && lookStrength in 0f..1f)
        require(grainScale.isFinite() && grainScale >= 0f)
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
    // A linear-light histogram needs finer shadow precision than an 8-bit display-value
    // histogram: one 1/255 linear bin spans several visible code values near black.
    private const val HISTOGRAM_SIZE = 4096
    private const val SAMPLE_AXIS = 224
    private const val MICRO_CONTRAST_LONG_EDGE = 720f
    private const val LN_2 = 0.69314718056f

    // The former thresholds were authored as neutral sRGB code values. Express them in
    // linear-light Y so scene labels and conservative guards retain their intended meaning.
    private val CLIPPED_LUMA = ColorMath.srgbToLinear(0.985f)
    private val CRUSHED_LUMA = ColorMath.srgbToLinear(0.02f)
    private val NEUTRAL_SHADOW = ColorMath.srgbToLinear(0.12f)
    private val NEUTRAL_HIGHLIGHT = ColorMath.srgbToLinear(0.92f)

    private fun sampleBilinear(
        values: FloatArray,
        width: Int,
        height: Int,
        normalizedX: Float,
        normalizedY: Float,
    ): Float {
        // Pixel centres live at (x + 0.5) / width. This mapping makes a canonical normalized
        // probe land on the same scene position in 720, 960, and 3000 px versions of a frame.
        val sourceX = (normalizedX.coerceIn(0f, 1f) * width - 0.5f)
            .coerceIn(0f, (width - 1).toFloat())
        val sourceY = (normalizedY.coerceIn(0f, 1f) * height - 0.5f)
            .coerceIn(0f, (height - 1).toFloat())
        val x0 = sourceX.toInt()
        val y0 = sourceY.toInt()
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val tx = sourceX - x0
        val ty = sourceY - y0
        val top = values[y0 * width + x0] +
            (values[y0 * width + x1] - values[y0 * width + x0]) * tx
        val bottom = values[y1 * width + x0] +
            (values[y1 * width + x1] - values[y1 * width + x0]) * tx
        return top + (bottom - top) * ty
    }

    private fun sampledLuma(
        r: FloatArray,
        g: FloatArray,
        b: FloatArray,
        width: Int,
        height: Int,
        normalizedX: Float,
        normalizedY: Float,
    ): Float = ColorMath.linearLuminance(
        sampleBilinear(r, width, height, normalizedX, normalizedY).coerceIn(0f, 1f),
        sampleBilinear(g, width, height, normalizedX, normalizedY).coerceIn(0f, 1f),
        sampleBilinear(b, width, height, normalizedX, normalizedY).coerceIn(0f, 1f),
    ).coerceIn(0f, 1f)

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
                p01 = 0f,
                p10 = ColorMath.srgbToLinear(0.1f),
                p50 = ColorMath.srgbToLinear(0.5f),
                p90 = ColorMath.srgbToLinear(0.9f),
                p99 = 1f,
                meanLuma = ColorMath.srgbToLinear(0.5f),
                meanSaturation = 0f,
                clippedHighlights = 0f, crushedShadows = 0f,
                neutralWarmth = 0f, neutralConfidence = 0f, microContrast = 0f,
            )
        }

        val histogram = IntArray(HISTOGRAM_SIZE)
        // Always sample the same normalized grid for normal-size inputs. A floor-based source
        // stride changes both the selected pixels and sample count at every output resolution.
        val sampleWidth = minOf(width, SAMPLE_AXIS)
        val sampleHeight = minOf(height, SAMPLE_AXIS)
        // Microcontrast is measured at a one-pixel offset on a canonical 720 px-long-edge frame.
        // In source pixels the corresponding offset grows with resolution, preserving the scene
        // scale instead of confusing export resolution with extra texture/noise.
        val canonicalPixelStep = maxOf(width, height) / MICRO_CONTRAST_LONG_EDGE
        val microDx = canonicalPixelStep / width
        val microDy = canonicalPixelStep / height
        var samples = 0
        var sumLuma = 0.0
        var sumSaturation = 0.0
        var clipped = 0
        var crushed = 0
        var neutralWeight = 0.0
        var neutralWarmth = 0.0
        var micro = 0.0
        var microSamples = 0

        for (sampleY in 0 until sampleHeight) {
            val normalizedY = (sampleY + 0.5f) / sampleHeight
            for (sampleX in 0 until sampleWidth) {
                val normalizedX = (sampleX + 0.5f) / sampleWidth
                val rr = sampleBilinear(r, width, height, normalizedX, normalizedY)
                    .coerceIn(0f, 1f)
                val gg = sampleBilinear(g, width, height, normalizedX, normalizedY)
                    .coerceIn(0f, 1f)
                val bb = sampleBilinear(b, width, height, normalizedX, normalizedY)
                    .coerceIn(0f, 1f)
                val lum = ColorMath.linearLuminance(rr, gg, bb).coerceIn(0f, 1f)
                val max = maxOf(rr, gg, bb)
                val min = minOf(rr, gg, bb)
                val sat = if (max > 1e-4f) (max - min) / max else 0f

                histogram[
                    (lum * (HISTOGRAM_SIZE - 1) + 0.5f).toInt()
                        .coerceIn(0, HISTOGRAM_SIZE - 1)
                ]++
                samples++
                sumLuma += lum
                sumSaturation += sat
                if (lum >= CLIPPED_LUMA) clipped++
                if (lum <= CRUSHED_LUMA) crushed++

                // Warm/cool estimate from low-chroma pixels only. It informs how strongly a stock
                // tint may be layered; it is never used as an automatic white-balance command.
                if (lum in NEUTRAL_SHADOW..NEUTRAL_HIGHLIGHT) {
                    val w = (1f - sat / 0.28f).coerceIn(0f, 1f)
                    if (w > 0f) {
                        neutralWeight += w
                        neutralWarmth += w * ((rr - bb) / (rr + gg + bb + 0.08f))
                    }
                }

                // The minimum of horizontal/vertical canonical-pixel differences rejects a normal
                // directional edge but responds to genuinely busy texture. Normalized positions
                // make this invariant to render resolution.
                if (normalizedX + microDx <= 1f && normalizedY + microDy <= 1f) {
                    val right = sampledLuma(
                        r, g, b, width, height,
                        normalizedX + microDx,
                        normalizedY,
                    )
                    val down = sampledLuma(
                        r, g, b, width, height,
                        normalizedX,
                        normalizedY + microDy,
                    )
                    micro += minOf(abs(lum - right), abs(lum - down))
                    microSamples++
                }
            }
        }

        fun percentile(fraction: Float): Float {
            val target = (samples * fraction).toInt().coerceIn(0, samples - 1)
            var cumulative = 0
            for (i in histogram.indices) {
                cumulative += histogram[i]
                if (cumulative > target) return i.toFloat() / (HISTOGRAM_SIZE - 1)
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
        stock: StockRenderParams = StockRenderParams(),
    ): SceneAdjustment {
        if (!params.enabled) {
            return SceneAdjustment.NONE.copy(
                lookStrength = stock.lookStrength,
                grainScale = stock.grainScale,
            )
        }

        val median = profile.p50.coerceIn(
            ColorMath.srgbToLinear(0.03f),
            ColorMath.srgbToLinear(0.97f),
        )
        val targetMedian = when {
            profile.lowKey && profile.p90 > ColorMath.srgbToLinear(0.62f) ->
                ColorMath.srgbToLinear(0.30f) // keep night/backlight recognisably dark
            profile.lowKey -> ColorMath.srgbToLinear(0.34f)
            profile.highKey -> ColorMath.srgbToLinear(0.55f)
            else -> ColorMath.srgbToLinear(0.42f)
        }
        val desiredEv = ln(targetMedian / median) / LN_2
        // Linear-light ratios produce larger, physically meaningful EV differences than ratios
        // between display code values; 16% keeps the actual correction as restrained as before.
        var exposureEv = (desiredEv * 0.16f * params.autoExposure).coerceIn(-0.28f, 0.32f)
        if (profile.highContrast && exposureEv > 0f) exposureEv *= 0.45f
        if (profile.lowKey) exposureEv = exposureEv.coerceAtMost(0.18f)
        if (profile.clippedHighlights > 0.025f) exposureEv = exposureEv.coerceAtMost(0f)

        val shadowReference = ColorMath.srgbToLinear(0.16f)
        val shadowNeed = ((shadowReference - profile.p10) / shadowReference).coerceIn(0f, 1f)
        val moodGuard = if (profile.lowKey) 0.58f else 1f
        val shadowLift = 0.10f * shadowNeed * params.shadowProtection * moodGuard

        // Slightly wider than the former display-code interval after conversion: verified against
        // the GR III reference set to preserve the established highlight protection.
        val highlightStart = ColorMath.srgbToLinear(0.68f)
        val highlightEnd = ColorMath.srgbToLinear(0.95f)
        val highlightNeed =
            ((profile.p90 - highlightStart) / (highlightEnd - highlightStart)).coerceIn(0f, 1f)
        val clippingNeed = (profile.clippedHighlights / 0.08f).coerceIn(0f, 1f)
        val highlightCompression =
            ((0.42f * highlightNeed + 0.16f * clippingNeed) * params.highlightProtection)
                .coerceIn(0f, 0.55f)

        val contrast = when {
            profile.dynamicRange < 0.18f -> 0.10f
            profile.dynamicRange > 0.65f -> -0.08f
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
            (stock.lookStrength * strengthScale).coerceIn(
                stock.lookStrength * 0.62f,
                stock.lookStrength,
            )

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
        // Recalibrated in linear-light units against the real GR III scene set. Smooth frames stay
        // at one; only visibly busy/noisy canonical texture progressively suppresses added grain.
        val textureScale =
            (1f - 0.28f * ((profile.microContrast - 0.010f) / 0.055f).coerceIn(0f, 1f))
        // Do not globally suppress a low-key frame. Local density in DevelopPipeline already
        // makes its shadows rougher while preserving true black; reducing the whole frame here
        // erased exactly the underexposed-film behaviour the model is meant to retain.
        val grainScale = stock.grainScale * isoScale * textureScale

        val halationScale = when {
            profile.p50 < ColorMath.srgbToLinear(0.34f) &&
                profile.p99 > ColorMath.srgbToLinear(0.82f) -> 1.15f
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
