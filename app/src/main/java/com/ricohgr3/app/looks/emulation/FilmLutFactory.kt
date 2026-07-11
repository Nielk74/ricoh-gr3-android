package com.ricohgr3.app.looks.emulation

import kotlin.math.ln
import kotlin.math.pow

/**
 * Builds 3D [LutCube]s procedurally from a film-density colour model, so the look set is
 * genuinely differentiated and **strong** — a real film grade, not a whisper of tint —
 * **without bundling third-party `.cube` assets** (whose redistribution rights must be cleared
 * first — see `research/FILM_EMULATION.md` §5). When a licensed `.cube` is later dropped into
 * `assets/`, the catalog prefers it and this factory is bypassed.
 *
 * ## Why the previous factory was too subtle
 * v1 applied a gentle mid-grey S-curve plus per-channel `gain*gamma` in **display (sRGB
 * gamma) space**. Colour math in gamma space compresses the effect of every operation, and the
 * gains were ~1.03 — barely a nudge. The result was correct but nearly invisible.
 *
 * ## The model this factory realises (per `research/FILM_EMULATION.md` §2)
 * For each LUT vertex, in order:
 *  1. **Linearise** sRGB → scene-linear. All tone/colour math is done in linear light, where a
 *     film's characteristic curve and dye coupling are physically meaningful (and where the
 *     same parameter reads far stronger than it would in gamma space).
 *  2. **White balance / channel gain** in linear (colour temperature of the stock).
 *  3. **Per-channel characteristic (density) curve** — film's S-curve, but with *independent*
 *     contrast, toe (shadow crush/lift) and shoulder (highlight roll-off) per R/G/B. Divergent
 *     per-channel curves are exactly what gives a stock its shadow/highlight colour crossover
 *     (e.g. CineStill's cyan shadows, Portra's warm highlights).
 *  4. **Dye cross-talk** — a 3×3 matrix mixing a little of each channel into the others, the
 *     way real film dye layers couple. This produces colour rotations a per-channel curve
 *     cannot (greens toward yellow, skies toward cyan).
 *  5. **Saturation** around Rec.709 luma (0 = monochrome for B&W stocks).
 * Then re-encode linear → sRGB and clamp. The spatial layers (split-tone, halation, grain) are
 * layered around the LUT by [DevelopPipeline]; they are not part of this pointwise map.
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

    private const val GAMMA = 2.4f
    private fun srgbToLinear(c: Float): Float = if (c <= 0f) 0f else c.pow(GAMMA)
    private fun linearToSrgb(c: Float): Float =
        if (c <= 0f) 0f else c.coerceAtMost(1f).pow(1f / GAMMA)

    /**
     * Film characteristic curve on scene-linear input, returning scene-linear output.
     *
     * Built as: a log-ish exposure mapping to place mid-grey, a contrast pivot around mid-grey,
     * a toe adjustment on the shadow end, and a Reinhard-style shoulder that rolls highlights
     * off toward (but never past) 1.0. All parameters are independent so channels can diverge.
     */
    private fun characteristic(xLin: Float, ch: Channel): Float {
        val x = (xLin * ch.gain).coerceAtLeast(0f)

        // 1. Contrast around mid-grey via a power pivot. Pivot at 0.18 *linear* (an 18% grey
        //    card ≈ 0.5 in sRGB gamma), so mid-grey is the fixed point of the contrast: values
        //    above it lift, below it drop, and mid-grey itself barely moves.
        val pivot = 0.18f
        val exponent = 1f + ch.contrast
        var y = pivot * (x / pivot).coerceAtLeast(0f).pow(exponent)

        // 2. Toe: shape the darkest values. Positive `toe` gently lifts the toe (a faded look);
        //    negative crushes it for deeper blacks. Weighted to only bite near black, and scaled
        //    small so even a lifted toe stays subtle (no milky shadows). At toe=0 (most stocks)
        //    this is a no-op, so blacks stay put.
        if (ch.toe != 0f) {
            val shadowWeight = (1f - (y / (y + 0.03f))) // ~1 at black, →0 by the low mid-tones
            y += ch.toe * 0.06f * shadowWeight
        }

        // 3. Shoulder: Reinhard-ish highlight roll-off that only bites *above* mid-grey, so it
        //    rolls highlights off toward 1.0 without inflating the mid-tones (a whole-range
        //    Reinhard brightens mids). Below the pivot it's a no-op; above it, the excess over
        //    the pivot is compressed. Higher `shoulder` = gentler, more film-like highlights.
        if (ch.shoulder > 0f && y > pivot) {
            val k = ch.shoulder * 2f
            val e = y - pivot                    // headroom above mid-grey
            val rolled = e / (1f + k * e) * (1f + k)
            y = pivot + rolled
        }
        return y.coerceAtLeast(0f)
    }

    private fun luma(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

    /** Build a [size]³ LUT realising [model]. */
    fun build(model: Model, size: Int = 33): LutCube {
        val max = (size - 1).toFloat()
        val data = FloatArray(size * size * size * 3)
        val m = model.crossTalk
        var i = 0
        for (bi in 0 until size) for (gi in 0 until size) for (ri in 0 until size) {
            // Linearise the grid coordinate (sRGB) into scene-linear.
            var r = srgbToLinear(ri / max)
            var g = srgbToLinear(gi / max)
            var b = srgbToLinear(bi / max)

            // Per-channel characteristic curves (white balance baked into each Channel.gain).
            r = characteristic(r, model.r)
            g = characteristic(g, model.g)
            b = characteristic(b, model.b)

            // Dye cross-talk (linear light).
            val mr = m[0] * r + m[1] * g + m[2] * b
            val mg = m[3] * r + m[4] * g + m[5] * b
            val mb = m[6] * r + m[7] * g + m[8] * b
            r = mr.coerceAtLeast(0f); g = mg.coerceAtLeast(0f); b = mb.coerceAtLeast(0f)

            // Back to display space for saturation + output.
            r = linearToSrgb(r); g = linearToSrgb(g); b = linearToSrgb(b)

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
