package com.ricohgr3.app.looks.emulation

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Builds 3D [LutCube]s procedurally from an explicit negative → print/scan model, without
 * bundling third-party `.cube` assets (whose redistribution rights must be cleared first — see
 * `research/FILM_EMULATION.md` §5). When a licensed `.cube` is later dropped into `assets/`, the
 * catalog prefers it and this factory is bypassed.
 *
 * The input is a camera JPEG or a platform-rendered DNG, not untouched scene-linear sensor data.
 * It is decoded to linear light before entering the density model, but the response is deliberately
 * restrained: applying an aggressive RAW-style curve to an already-developed JPEG was the main
 * reason earlier filters crushed shadows, clipped colour, and looked synthetic.
 *
 * ## The model this factory realises (per `research/FILM_EMULATION.md` §2)
 * For each LUT vertex, in order:
 *  1. **Negative exposure** — sRGB is decoded to linear exposure.
 *  2. **Dye-layer density** — each R/G/B layer has its own speed, straight-line slope, toe and
 *     shoulder in bounded log-exposure space. Different layer shapes create genuine
 *     exposure-dependent colour crossover instead of a constant tint.
 *  3. **Inter-layer coupling** — a 3×3 matrix mixes the formed dye densities.
 *  4. **Print / scan response** — the coupled density is mapped through a second characteristic
 *     curve with an independent contrast, toe, shoulder, black point, paper white and channel
 *     balance.
 *  5. **Scanner colour** — saturation is adjusted around Rec.709 luma (0 = monochrome).
 * The spatial layers (split-tone, halation, grain) are layered around the LUT by
 * [DevelopPipeline]; they are not part of this pointwise map.
 *
 * Pure Kotlin → JVM-testable.
 */
object FilmLutFactory {

    /**
     * An author-friendly per-channel negative dye-layer calibration.
     *
     * The existing compact catalog uses normalized controls rather than pretending that JPEG
     * code values are laboratory density measurements. [contrast] is converted to a small
     * straight-line density-slope increase; [toe] and [shoulder] bend the two ends in log-exposure
     * space; [gain] becomes an exposure-speed offset for the layer.
     *
     * Defaults are neutral so `Model()` is an exact round-trip.
     */
    data class Channel(
        val contrast: Float = 0f,
        val toe: Float = 0f,
        val shoulder: Float = 0f,
        val gain: Float = 1f,
    )

    /**
     * Positive print / scanner response applied after negative dye formation.
     *
     * [contrast] controls the print straight-line slope. [toe] opens useful shadow separation;
     * [shoulder] controls paper-highlight compression. [exposureEv] and per-channel [biasR],
     * [biasG], [biasB] are print-light/scanner balance in stops. [blackPoint] and [paperWhite]
     * are linear-light output endpoints.
     */
    data class PrintStage(
        val contrast: Float = 1f,
        val toe: Float = 0f,
        val shoulder: Float = 0f,
        val exposureEv: Float = 0f,
        val biasR: Float = 0f,
        val biasG: Float = 0f,
        val biasB: Float = 0f,
        val blackPoint: Float = 0f,
        val paperWhite: Float = 1f,
    )

    /**
     * A full stock model: negative dye layers, density cross-talk, positive print, and scanner
     * saturation.
     *
     * @property r/g/b per-channel negative dye-layer characteristics.
     * @property crossTalk row-major 3×3 coupling matrix applied to formed dye density; identity
     *   means no coupling. Rows sum to approximately one to preserve neutral exposure.
     * @property print positive print/scanner characteristic.
     * @property saturation global saturation scale in display space (1 = unchanged, 0 = mono).
     */
    data class Model(
        val r: Channel = Channel(),
        val g: Channel = Channel(),
        val b: Channel = Channel(),
        val crossTalk: FloatArray = IDENTITY_3X3,
        val print: PrintStage = PrintStage(),
        val saturation: Float = 1f,
    ) {
        // Value semantics not needed (models are static catalog data); silence the array warning.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private val IDENTITY_3X3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private const val LN_2 = 0.69314718056f
    private const val DENSITY_EPSILON = 0.000001f

    private fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    private fun linearToSrgb(c: Float): Float {
        val x = c.coerceAtLeast(0f)
        return if (x <= 0.0031308f) 12.92f * x
        else 1.055f * x.pow(1f / 2.4f) - 0.055f
    }

    private fun logit(value: Float): Float {
        val x = value.coerceIn(DENSITY_EPSILON, 1f - DENSITY_EPSILON)
        return ln(x / (1f - x))
    }

    private fun logistic(value: Float): Float =
        (1f / (1f + exp(-value))).coerceIn(0f, 1f)

    /**
     * Form normalized dye density from linear scene exposure. Logit space is a bounded proxy for
     * log exposure: it gives a long straight section while retaining stable zero/one endpoints.
     * The stock controls are intentionally small because the source already contains a camera
     * rendering curve.
     */
    private fun negativeDensity(exposure: Float, channel: Channel): Float {
        if (exposure <= 0f) return 0f
        if (exposure >= 1f) return 1f
        val x = exposure.coerceIn(DENSITY_EPSILON, 1f - DENSITY_EPSILON)
        val slope = 1f + channel.contrast.coerceIn(-0.5f, 1f) * 0.34f
        val speed = ln(channel.gain.coerceIn(0.5f, 2f))
        val toe = channel.toe.coerceIn(-0.12f, 0.12f) * 4f * (1f - x) * (1f - x)
        val shoulder =
            channel.shoulder.coerceIn(0f, 1.2f) * 0.46f * x * x
        return logistic(logit(x) * slope + speed + toe - shoulder)
    }

    /** Convert coupled negative dye density into linear positive-print light. */
    private fun printPositive(density: Float, stage: PrintStage, channelBiasEv: Float): Float {
        if (density <= 0f) return stage.blackPoint.coerceIn(0f, 0.2f)
        if (density >= 1f) return stage.paperWhite.coerceIn(0.5f, 1f)
        val x = density.coerceIn(DENSITY_EPSILON, 1f - DENSITY_EPSILON)
        val toe = stage.toe.coerceIn(-0.5f, 0.8f) * (1f - x) * (1f - x)
        val shoulder = stage.shoulder.coerceIn(0f, 1.2f) * x * x
        val exposure = (stage.exposureEv + channelBiasEv).coerceIn(-1f, 1f) * LN_2
        val positive = logistic(
            logit(x) * stage.contrast.coerceIn(0.65f, 1.35f) +
                exposure + toe - shoulder,
        )
        val black = stage.blackPoint.coerceIn(0f, 0.2f)
        val white = stage.paperWhite.coerceIn(black + 0.1f, 1f)
        return black + positive * (white - black)
    }

    private fun luma(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

    /** Build a [size]³ LUT realising [model]. */
    fun build(model: Model, size: Int = 33): LutCube {
        val max = (size - 1).toFloat()
        val data = FloatArray(size * size * size * 3)
        val m = model.crossTalk
        var i = 0
        for (bi in 0 until size) for (gi in 0 until size) for (ri in 0 until size) {
            // 1. Decode display RGB to linear exposure and form negative dye density.
            val rd = negativeDensity(srgbToLinear(ri / max), model.r)
            val gd = negativeDensity(srgbToLinear(gi / max), model.g)
            val bd = negativeDensity(srgbToLinear(bi / max), model.b)

            // 2. Couple the formed dye layers. Small negative coefficients are allowed for
            // calibrated colour separation, then bounded before the positive print.
            val mr = (m[0] * rd + m[1] * gd + m[2] * bd).coerceIn(0f, 1f)
            val mg = (m[3] * rd + m[4] * gd + m[5] * bd).coerceIn(0f, 1f)
            val mb = (m[6] * rd + m[7] * gd + m[8] * bd).coerceIn(0f, 1f)

            // 3. Print/scan the negative into a positive, then encode for the display LUT.
            var r = linearToSrgb(printPositive(mr, model.print, model.print.biasR))
            var g = linearToSrgb(printPositive(mg, model.print, model.print.biasG))
            var b = linearToSrgb(printPositive(mb, model.print, model.print.biasB))

            if (model.saturation != 1f) {
                val l = luma(r, g, b)
                r = l + (r - l) * model.saturation
                g = l + (g - l) * model.saturation
                b = l + (b - l) * model.saturation
            }

            data[i++] = r.coerceIn(0f, 1f)
            data[i++] = g.coerceIn(0f, 1f)
            data[i++] = b.coerceIn(0f, 1f)
        }
        return LutCube(size, data)
    }

    /**
     * Convenience for a symmetric dye-coupling matrix: each channel keeps `1 - 2*amt` of itself
     * and leaks `amt` into each of the other two (rows sum to 1, preserving exposure). Positive
     * [warm] additionally biases coupling toward red/yellow (a common film trait).
     */
    fun crossTalk(amount: Float, warm: Float = 0f): FloatArray {
        val a = amount
        // Row = output channel; columns = R,G,B contribution.
        return floatArrayOf(
            1f - 2f * a + warm, a, a - warm,
            a, 1f - 2f * a, a,
            a - warm, a + warm, 1f - 2f * a,
        )
    }
}
