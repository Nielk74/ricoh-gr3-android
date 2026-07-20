package com.ricohgr3.app.looks.emulation

import kotlin.math.exp
import kotlin.math.pow

/**
 * Builds 3D [LutCube]s procedurally from a film-density colour model, so the look set is
 * genuinely differentiated and **strong** — a real film grade, not a whisper of tint —
 * **without bundling third-party `.cube` assets** (whose redistribution rights must be cleared
 * first — see `research/FILM_EMULATION.md` §5). When a licensed `.cube` is later dropped into
 * `assets/`, the catalog prefers it and this factory is bypassed.
 *
 * The input is a camera JPEG or a platform-rendered DNG, not untouched scene-linear sensor data.
 * The factory therefore treats it as a display-referred photograph: a bounded print-style tone
 * curve supplies the stock character, while gain and dye coupling still happen in linear light.
 * Applying a second aggressive "RAW" density curve to an already-developed JPEG was the main
 * reason earlier filters crushed shadows, clipped colour, and looked synthetic.
 *
 * ## The model this factory realises (per `research/FILM_EMULATION.md` §2)
 * For each LUT vertex, in order:
 *  1. **White balance / channel gain** in linear (colour temperature of the stock).
 *  2. **Per-channel print characteristic** in display space — a bounded S-curve with independent
 *     contrast, toe (shadow crush/lift) and shoulder (highlight roll-off) per R/G/B. Divergent
 *     per-channel curves are exactly what gives a stock its shadow/highlight colour crossover
 *     (e.g. CineStill's cyan shadows, Portra's warm highlights).
 *  3. **Dye cross-talk** — a 3×3 matrix mixing a little of each channel into the others, the
 *     way real film dye layers couple. This produces colour rotations a per-channel curve
 *     cannot (greens toward yellow, skies toward cyan).
 *  4. **Saturation** around Rec.709 luma (0 = monochrome for B&W stocks).
 * The spatial layers (split-tone, halation, grain) are layered around the LUT by
 * [DevelopPipeline]; they are not part of this pointwise map.
 *
 * Pure Kotlin → JVM-testable.
 */
object FilmLutFactory {

    /**
     * A per-channel film characteristic ("density") curve. Models film's S-shape with
     * independent control of each end, so channels can diverge and create colour crossover.
     *
     * @property contrast overall S-curve strength around mid-grey (0 ≈ linear, ~0.5 punchy).
     * @property toe shadow behaviour: >0 lifts/softens the toe (faded look), <0 crushes it.
     * @property shoulder highlight roll-off strength (>0 rolls highlights off gently, film-like).
     * @property gain linear white-balance multiplier for this channel (colour temperature).
     */
    data class Channel(
        val contrast: Float = 0.4f,
        val toe: Float = 0f,
        val shoulder: Float = 0.5f,
        val gain: Float = 1f,
    )

    /**
     * A full stock model: three characteristic curves, a dye cross-talk matrix, and saturation.
     *
     * @property r/g/b per-channel characteristic curves (post-white-balance).
     * @property crossTalk row-major 3×3 dye-coupling matrix applied in linear light after the
     *   curves; identity = no coupling. Rows sum to ~1 to preserve exposure.
     * @property saturation global saturation scale in display space (1 = unchanged, 0 = mono).
     */
    data class Model(
        val r: Channel = Channel(),
        val g: Channel = Channel(),
        val b: Channel = Channel(),
        val crossTalk: FloatArray = IDENTITY_3X3,
        val saturation: Float = 1f,
    ) {
        // Value semantics not needed (models are static catalog data); silence the array warning.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private val IDENTITY_3X3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    private fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    private fun linearToSrgb(c: Float): Float {
        val x = c.coerceAtLeast(0f)
        return if (x <= 0.0031308f) 12.92f * x
        else 1.055f * x.pow(1f / 2.4f) - 0.055f
    }

    /**
     * Bounded print-film curve on display-referred input/output. Black and white remain stable
     * unless [Channel.toe] deliberately lifts/crushes the toe; the shoulder is monotonic and
     * always compresses instead of accidentally brightening upper mids.
     */
    private fun characteristic(input: Float, ch: Channel): Float {
        // A stock's colour-temperature gain belongs in linear light.
        val gained = linearToSrgb(srgbToLinear(input.coerceIn(0f, 1f)) * ch.gain)
            .coerceIn(0f, 1f)

        // Smoothstep is a stable S-curve with fixed black/white endpoints. Blend toward it rather
        // than applying a power pivot, which was much too destructive on rendered camera JPEGs.
        val smooth = gained * gained * (3f - 2f * gained)
        var y = gained + (smooth - gained) * ch.contrast.coerceIn(-0.5f, 1f)

        // Toe: small and heavily shadow-weighted. Positive values lift, negative values deepen.
        if (ch.toe != 0f) {
            val shadowWeight = (1f - y).coerceIn(0f, 1f).pow(3f)
            y += ch.toe * 0.5f * shadowWeight
        }

        // Shoulder: exponential easing below the linear segment, normalised to still reach white.
        // Blend is capped so even a strong stock retains separation in bright JPEG highlights.
        val knee = 0.55f
        if (ch.shoulder > 0f && y > knee) {
            val t = ((y - knee) / (1f - knee)).coerceIn(0f, 1f)
            val a = 2.2f
            val rolled = (exp(a * t) - 1f) / (exp(a) - 1f)
            val target = knee + (1f - knee) * rolled
            val mix = (ch.shoulder * 0.58f).coerceIn(0f, 0.72f)
            y += (target - y) * mix
        }
        return y.coerceIn(0f, 1f)
    }

    private fun luma(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

    /** Build a [size]³ LUT realising [model]. */
    fun build(model: Model, size: Int = 33): LutCube {
        val max = (size - 1).toFloat()
        val data = FloatArray(size * size * size * 3)
        val m = model.crossTalk
        var i = 0
        for (bi in 0 until size) for (gi in 0 until size) for (ri in 0 until size) {
            // Display-referred, bounded per-channel print curves (gain is applied in linear light
            // inside `characteristic`).
            var r = characteristic(ri / max, model.r)
            var g = characteristic(gi / max, model.g)
            var b = characteristic(bi / max, model.b)

            // Dye cross-talk still belongs in linear light.
            val rl = srgbToLinear(r); val gl = srgbToLinear(g); val bl = srgbToLinear(b)
            val mr = m[0] * rl + m[1] * gl + m[2] * bl
            val mg = m[3] * rl + m[4] * gl + m[5] * bl
            val mb = m[6] * rl + m[7] * gl + m[8] * bl

            // Back to display space for saturation + output.
            r = linearToSrgb(mr); g = linearToSrgb(mg); b = linearToSrgb(mb)

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
