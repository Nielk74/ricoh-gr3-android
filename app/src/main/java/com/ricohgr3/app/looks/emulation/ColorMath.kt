package com.ricohgr3.app.looks.emulation

import kotlin.math.pow

/**
 * Shared colour-science primitives for the film pipeline.
 *
 * The develop buffers are display-referred sRGB unless a caller explicitly says otherwise, but
 * light-energy operations must not apply Rec.709 coefficients directly to gamma-encoded values.
 * These helpers therefore make the transfer boundary explicit and keep all luminance-preserving
 * operations anchored to linear-light Y.
 */
object ColorMath {
    private const val KR = 0.2126f
    private const val KG = 0.7152f
    private const val KB = 0.0722f

    /** IEC 61966-2-1 sRGB electro-optical transfer function. */
    fun srgbToLinear(value: Float): Float {
        val c = value.coerceAtLeast(0f)
        return if (c <= 0.04045f) c / 12.92f
        else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    /** IEC 61966-2-1 sRGB opto-electrical transfer function. */
    fun linearToSrgb(value: Float): Float {
        val c = value.coerceAtLeast(0f)
        return if (c <= 0.0031308f) 12.92f * c
        else 1.055f * c.pow(1f / 2.4f) - 0.055f
    }

    /** Relative linear-light luminance of an sRGB triplet. Values above one are retained. */
    fun linearLuminance(r: Float, g: Float, b: Float): Float =
        KR * srgbToLinear(r) + KG * srgbToLinear(g) + KB * srgbToLinear(b)

    /** Relative luminance of an already-linear sRGB triplet. */
    fun luminanceOfLinear(r: Float, g: Float, b: Float): Float = KR * r + KG * g + KB * b

    /**
     * Convert display sRGB to OKLab. [out] receives L, a, b.
     *
     * OKLab is used only for perceptual hue/chroma decisions; exposure, density, halation, and
     * energy preservation remain linear-light calculations.
     */
    fun srgbToOklab(r: Float, g: Float, b: Float, out: FloatArray) {
        require(out.size >= 3)
        val lr = srgbToLinear(r)
        val lg = srgbToLinear(g)
        val lb = srgbToLinear(b)

        val l = 0.4122214708f * lr + 0.5363325363f * lg + 0.0514459929f * lb
        val m = 0.2119034982f * lr + 0.6806995451f * lg + 0.1073969566f * lb
        val s = 0.0883024619f * lr + 0.2817188376f * lg + 0.6299787005f * lb
        val lRoot = Math.cbrt(l.toDouble()).toFloat()
        val mRoot = Math.cbrt(m.toDouble()).toFloat()
        val sRoot = Math.cbrt(s.toDouble()).toFloat()

        out[0] = 0.2104542553f * lRoot + 0.7936177850f * mRoot - 0.0040720468f * sRoot
        out[1] = 1.9779984951f * lRoot - 2.4285922050f * mRoot + 0.4505937099f * sRoot
        out[2] = 0.0259040371f * lRoot + 0.7827717662f * mRoot - 0.8086757660f * sRoot
    }

    /** Convert OKLab L, a, b to display sRGB. Out-of-gamut values are intentionally retained. */
    fun oklabToSrgb(l: Float, a: Float, b: Float, out: FloatArray) {
        require(out.size >= 3)
        val lRoot = l + 0.3963377774f * a + 0.2158037573f * b
        val mRoot = l - 0.1055613458f * a - 0.0638541728f * b
        val sRoot = l - 0.0894841775f * a - 1.2914855480f * b
        val ll = lRoot * lRoot * lRoot
        val mm = mRoot * mRoot * mRoot
        val ss = sRoot * sRoot * sRoot

        val lr = 4.0767416621f * ll - 3.3077115913f * mm + 0.2309699292f * ss
        val lg = -1.2684380046f * ll + 2.6097574011f * mm - 0.3413193965f * ss
        val lb = -0.0041960863f * ll - 0.7034186147f * mm + 1.7076147010f * ss
        out[0] = linearToSrgb(lr)
        out[1] = linearToSrgb(lg)
        out[2] = linearToSrgb(lb)
    }

    /**
     * Scale an sRGB triplet in linear light to [targetY], then encode and gamut-compress it without
     * independently clipping channels. [out] receives display sRGB.
     */
    fun putAtLinearLuminance(
        r: Float,
        g: Float,
        b: Float,
        targetY: Float,
        out: FloatArray,
    ) {
        require(out.size >= 3)
        val y = linearLuminance(r, g, b)
        var lr = srgbToLinear(r)
        var lg = srgbToLinear(g)
        var lb = srgbToLinear(b)
        val scale = if (y > 1e-8f) targetY.coerceAtLeast(0f) / y else 0f
        lr *= scale
        lg *= scale
        lb *= scale

        val target = targetY.coerceIn(0f, 1f)
        val max = maxOf(lr, lg, lb)
        if (max > 1f && max > target) {
            val mix = ((1f - target) / (max - target)).coerceIn(0f, 1f)
            lr = target + (lr - target) * mix
            lg = target + (lg - target) * mix
            lb = target + (lb - target) * mix
        }
        val min = minOf(lr, lg, lb)
        if (min < 0f && min < target) {
            val mix = (target / (target - min)).coerceIn(0f, 1f)
            lr = target + (lr - target) * mix
            lg = target + (lg - target) * mix
            lb = target + (lb - target) * mix
        }

        out[0] = linearToSrgb(lr).coerceIn(0f, 1f)
        out[1] = linearToSrgb(lg).coerceIn(0f, 1f)
        out[2] = linearToSrgb(lb).coerceIn(0f, 1f)
    }
}
