package com.ricohgr3.app.looks.emulation

import kotlin.math.pow

/**
 * Builds 3D [LutCube]s procedurally from a small parametric colour model, so the v1 looks are
 * genuinely differentiated **without bundling third-party `.cube` assets** (whose
 * redistribution rights must be cleared first — see `research/FILM_EMULATION.md` §5). When a
 * real film-stock `.cube` is later dropped into `assets/`, the catalog prefers it and this
 * factory is bypassed; until then these give an authentic-enough film response.
 *
 * The model composes: a film **tone S-curve** (toe + shoulder), per-channel **gain/gamma**
 * for colour balance, and a **saturation** scale — the pointwise part of a film look, baked
 * into a LUT so it interpolates smoothly and runs through the same engine path as real LUTs.
 *
 * Pure Kotlin → JVM-testable.
 */
object FilmLutFactory {

    /**
     * @property contrast S-curve strength (0 = linear, ~0.25 gentle film contrast).
     * @property lift shadow lift (raises the toe; faded/retro looks use this).
     * @property gainR/G/B per-channel multiplier (colour cast).
     * @property gammaR/G/B per-channel gamma (tonal colour balance).
     * @property saturation global saturation scale (1 = unchanged, 0 = mono).
     */
    data class Model(
        val contrast: Float = 0.22f,
        val lift: Float = 0f,
        val gainR: Float = 1f, val gainG: Float = 1f, val gainB: Float = 1f,
        val gammaR: Float = 1f, val gammaG: Float = 1f, val gammaB: Float = 1f,
        val saturation: Float = 1f,
    )

    /** Film S-curve on `[0,1]`: smooth toe+shoulder via a contrast-scaled sigmoid-ish shape. */
    private fun toneCurve(x: Float, contrast: Float, lift: Float): Float {
        // Smoothstep-blended contrast around mid-grey, then lift the toe.
        val c = contrast
        val s = x + c * (x - 0.5f) * (1f - kotlin.math.abs(2f * x - 1f)) * 2f
        val lifted = lift + (1f - lift) * s
        return lifted.coerceIn(0f, 1f)
    }

    private fun applyGamma(x: Float, gamma: Float): Float =
        if (x <= 0f) 0f else x.toDouble().pow((1f / gamma).toDouble()).toFloat()

    /** Build a [size]³ LUT realising [model]. */
    fun build(model: Model, size: Int = 33): LutCube {
        val max = (size - 1).toFloat()
        val data = FloatArray(size * size * size * 3)
        var i = 0
        for (bi in 0 until size) for (gi in 0 until size) for (ri in 0 until size) {
            var r = ri / max
            var g = gi / max
            var b = bi / max

            // Tone curve (shared shape, applied per channel for a filmic response).
            r = toneCurve(r, model.contrast, model.lift)
            g = toneCurve(g, model.contrast, model.lift)
            b = toneCurve(b, model.contrast, model.lift)

            // Per-channel colour balance.
            r = applyGamma(r, model.gammaR) * model.gainR
            g = applyGamma(g, model.gammaG) * model.gainG
            b = applyGamma(b, model.gammaB) * model.gainB

            // Saturation around luma.
            if (model.saturation != 1f) {
                val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
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
}
