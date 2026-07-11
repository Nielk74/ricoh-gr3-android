package com.ricohgr3.app.looks.emulation

import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The pure-Kotlin film-develop pipeline. Operates on a planar RGB float buffer (values in
 * `[0,1]`, sRGB-encoded on input/output) of [width]×[height], with **no Android
 * dependencies**, so the entire look-rendering math is JVM-unit-testable — the device/GPU
 * `Bitmap` path (see the Android glue in `DevelopEngine`) merely marshals pixels in and out.
 *
 * Pipeline order (`research/FILM_EMULATION.md` §2): linearise → LUT → split-tone → halation
 * → re-encode → grain. Grain runs last in display space; halation is computed in linear
 * light for a physically-plausible bloom.
 */
object DevelopPipeline {

    private const val GAMMA = 2.2f

    private fun srgbToLinear(c: Float): Float = if (c <= 0f) 0f else exp(GAMMA * kotlin.math.ln(c))
    private fun linearToSrgb(c: Float): Float =
        if (c <= 0f) 0f else exp((1f / GAMMA) * kotlin.math.ln(c.coerceAtMost(1f)))

    private fun luma(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

    /**
     * Render [look] over the RGB planes [r],[g],[b] in place. Each array is row-major,
     * length `width*height`, sRGB `[0,1]`. [lut] is the parsed colour table (identity if the
     * look has no `lutAsset`).
     */
    fun apply(
        r: FloatArray, g: FloatArray, b: FloatArray,
        width: Int, height: Int,
        look: FilmLook, lut: LutCube,
    ) {
        val n = width * height
        val tmp = FloatArray(3)

        // 1–2. Colour LUT (captures tone + colour response + cross-talk together).
        for (i in 0 until n) {
            lut.sample(r[i], g[i], b[i], tmp)
            r[i] = tmp[0]; g[i] = tmp[1]; b[i] = tmp[2]
        }

        // 3. Split toning (in display space, luminance-weighted).
        val st = look.splitTone
        if (st.amount > 0f) {
            for (i in 0 until n) {
                val l = luma(r[i], g[i], b[i])
                // Shadow weight peaks at black, highlight weight peaks at white.
                val hw = l * st.amount
                val sw = (1f - l) * st.amount
                r[i] = (r[i] + st.shadowR * sw + st.highR * hw).coerceIn(0f, 1f)
                g[i] = (g[i] + st.shadowG * sw + st.highG * hw).coerceIn(0f, 1f)
                b[i] = (b[i] + st.shadowB * sw + st.highB * hw).coerceIn(0f, 1f)
            }
        }

        // 4. Halation: threshold highlights in linear light, blur, tint, screen back.
        val h = look.halation
        if (h.enabled) applyHalation(r, g, b, width, height, h)

        // 5. Grain (display space, last).
        val gr = look.grain
        if (gr.enabled) applyGrain(r, g, b, width, height, gr)
    }

    /**
     * Red-orange highlight bloom. Extracts a luminance-thresholded highlight mask (in linear
     * light), separably Gaussian-blurs it, and screen-composites a tinted, [strength]-scaled
     * copy back over the image. Exposed for unit testing.
     */
    fun applyHalation(
        r: FloatArray, g: FloatArray, b: FloatArray,
        width: Int, height: Int, h: HalationParams,
    ) {
        val n = width * height
        val mask = FloatArray(n)
        for (i in 0 until n) {
            val lin = srgbToLinear(luma(r[i], g[i], b[i]))
            mask[i] = (lin - h.threshold).coerceAtLeast(0f) / (1f - h.threshold + 1e-4f)
        }
        gaussianBlur(mask, width, height, h.radius)
        for (i in 0 until n) {
            val m = (mask[i] * h.strength).coerceIn(0f, 1f)
            // Screen blend the tinted bloom: out = 1 - (1-a)(1-b).
            r[i] = 1f - (1f - r[i]) * (1f - m * h.tintR)
            g[i] = 1f - (1f - g[i]) * (1f - m * h.tintG)
            b[i] = 1f - (1f - b[i]) * (1f - m * h.tintB)
        }
    }

    /**
     * Luminance-weighted, spatially-correlated monochrome grain. Builds a Gaussian noise
     * field, blurs it by [GrainParams.size] for correlation (real grain isn't per-pixel
     * white noise), then adds it scaled by a shadow-biased luminance weight. Deterministic
     * given [GrainParams.seed]. Exposed for unit testing.
     */
    fun applyGrain(
        r: FloatArray, g: FloatArray, b: FloatArray,
        width: Int, height: Int, gp: GrainParams,
    ) {
        val n = width * height
        val rng = Random(gp.seed)
        val noise = FloatArray(n) { (rng.nextFloat() - 0.5f) + (rng.nextFloat() - 0.5f) } // ~Gaussian
        val blurR = (gp.size - 1f).roundToInt().coerceAtLeast(0)
        if (blurR > 0) gaussianBlur(noise, width, height, blurR)
        for (i in 0 until n) {
            val l = luma(r[i], g[i], b[i])
            // More grain in mid/shadow structure; falls off in bright highlights.
            val w = (1f - l * (1f - gp.shadowBias)).coerceIn(0f, 1f)
            val d = noise[i] * gp.amount * w
            r[i] = (r[i] + d).coerceIn(0f, 1f)
            g[i] = (g[i] + d).coerceIn(0f, 1f)
            b[i] = (b[i] + d).coerceIn(0f, 1f)
        }
    }

    /**
     * Separable box-approximated Gaussian blur of a single-channel [plane] in place, using
     * three box passes (a standard fast Gaussian approximation). [radius] in pixels; a
     * radius of 0 is a no-op.
     */
    fun gaussianBlur(plane: FloatArray, width: Int, height: Int, radius: Int) {
        if (radius <= 0) return
        repeat(3) {
            boxBlurH(plane, width, height, radius)
            boxBlurV(plane, width, height, radius)
        }
    }

    private fun boxBlurH(src: FloatArray, w: Int, h: Int, radius: Int) {
        val out = FloatArray(src.size)
        val norm = 1f / (2 * radius + 1)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var sum = 0f
                for (k in -radius..radius) {
                    val xx = (x + k).coerceIn(0, w - 1)
                    sum += src[row + xx]
                }
                out[row + x] = sum * norm
            }
        }
        System.arraycopy(out, 0, src, 0, src.size)
    }

    private fun boxBlurV(src: FloatArray, w: Int, h: Int, radius: Int) {
        val out = FloatArray(src.size)
        val norm = 1f / (2 * radius + 1)
        for (x in 0 until w) {
            for (y in 0 until h) {
                var sum = 0f
                for (k in -radius..radius) {
                    val yy = (y + k).coerceIn(0, h - 1)
                    sum += src[yy * w + x]
                }
                out[y * w + x] = sum * norm
            }
        }
        System.arraycopy(out, 0, src, 0, src.size)
    }
}
