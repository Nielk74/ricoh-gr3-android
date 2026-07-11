package com.ricohgr3.app.looks.emulation

import kotlin.math.exp
import kotlin.math.pow
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

    /**
     * A mild base grade applied **before** the film LUT, used to bring a flat RAW/DNG preview up
     * to the camera-JPEG-like tonal base the film models expect (see `PhotoSave`). Applied in
     * display space: an S-curve contrast around mid-grey plus a saturation scale.
     *
     * @property contrast S-curve strength around 0.5 (0 = none, ~0.2 gentle).
     * @property saturation display-space saturation scale (1 = unchanged).
     */
    data class PreGrade(val contrast: Float, val saturation: Float)

    private const val GAMMA = 2.2f

    /** `x^e` guarded for the `[0,1]` develop domain (negatives → 0). */
    private fun pow(x: Float, e: Float): Float = if (x <= 0f) 0f else x.toDouble().pow(e.toDouble()).toFloat()

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
        preGrade: PreGrade? = null,
    ) {
        val n = width * height
        val tmp = FloatArray(3)

        // 0. Optional RAW base grade (before the LUT) — lifts a flat DNG to a JPEG-like base.
        if (preGrade != null) applyPreGrade(r, g, b, preGrade)

        // 1–2. Colour LUT (captures tone + colour response + cross-talk together). Some LUTs
        // (the bundled Fuji `.cube`s) expect a linear-ish input and bake their own tone curve —
        // pre-warp the sRGB input by `lutInputGamma` so mid-grey lands right; output is taken as
        // display-referred. `lutInputGamma == 1` (our procedural LUTs) is a plain pass-through.
        val g0 = look.lutInputGamma
        if (g0 == 1f) {
            for (i in 0 until n) {
                lut.sample(r[i], g[i], b[i], tmp)
                r[i] = tmp[0]; g[i] = tmp[1]; b[i] = tmp[2]
            }
        } else {
            for (i in 0 until n) {
                lut.sample(pow(r[i], g0), pow(g[i], g0), pow(b[i], g0), tmp)
                r[i] = tmp[0]; g[i] = tmp[1]; b[i] = tmp[2]
            }
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
     * Apply a [PreGrade] to the RGB planes in place (display space): an S-curve contrast pivot
     * around mid-grey (smoothstep-blended so it doesn't clip) plus a luminance-preserving
     * saturation scale. Exposed for unit testing.
     */
    fun applyPreGrade(r: FloatArray, g: FloatArray, b: FloatArray, pg: PreGrade) {
        val c = pg.contrast
        val sat = pg.saturation
        fun contrast(x: Float): Float {
            if (c == 0f) return x
            // Same shape as the film S-curve: push away from 0.5, tapering at the extremes.
            val s = x + c * (x - 0.5f) * (1f - kotlin.math.abs(2f * x - 1f)) * 2f
            return s.coerceIn(0f, 1f)
        }
        for (i in r.indices) {
            var rr = contrast(r[i]); var gg = contrast(g[i]); var bb = contrast(b[i])
            if (sat != 1f) {
                val l = luma(rr, gg, bb)
                rr = (l + (rr - l) * sat).coerceIn(0f, 1f)
                gg = (l + (gg - l) * sat).coerceIn(0f, 1f)
                bb = (l + (bb - l) * sat).coerceIn(0f, 1f)
            }
            r[i] = rr; g[i] = gg; b[i] = bb
        }
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
     * Physically-motivated film grain: `I_out = I + A(I)·G` per channel, where `G` is
     * **spatially-correlated, multi-scale, correlated-RGB** grain and `A(I)` is a
     * **midtone-peaked density response**. Deterministic given [GrainParams.seed]. Exposed for
     * unit testing. See `research/FILM_EMULATION.md`.
     *
     * The grain field `G`:
     *  - a **shared luma** octave (fine) + a **coarser** octave summed for clumping / size
     *    variety (not one uniform speckle size);
     *  - plus a small **per-channel independent (chroma)** component so R/G/B are
     *    correlated-but-distinct — real film grain, not identical mono noise (too flat) nor
     *    fully-independent RGB (too electronic).
     *
     * The strength `A(I)`:
     *  - a **hump that peaks in the midtones** and falls off in both the deepest shadows and the
     *    brightest highlights (real silver-grain density), biasable toward shadows by
     *    [GrainParams.shadowBias];
     *  - times a subtle **smooth-region visibility** factor ([GrainParams.smoothBoost]) — grain
     *    reads slightly stronger in flat/defocused areas than in busy detail. This is a *secondary*
     *    modulation of visibility, **not** proportional to blur.
     */
    fun applyGrain(
        r: FloatArray, g: FloatArray, b: FloatArray,
        width: Int, height: Int, gp: GrainParams,
    ) {
        val n = width * height
        if (n == 0) return
        val rng = Random(gp.seed)

        // --- G: build the correlated, multi-scale grain field, per channel. ---
        val fineR = (gp.size - 1f).roundToInt().coerceAtLeast(0)
        val coarseR = (gp.size * gp.coarseSizeMul - 1f).roundToInt().coerceAtLeast(0)

        // Shared luma grain = fine octave + coarse octave (clumping / size variety).
        val luma = octave(rng, n, width, height, fineR)
        if (gp.coarseAmount > 0f) {
            val coarse = octave(rng, n, width, height, coarseR)
            val cw = gp.coarseAmount
            val norm = 1f / kotlin.math.sqrt(1f + cw * cw) // keep unit-ish variance when summing
            for (i in 0 until n) luma[i] = (luma[i] + cw * coarse[i]) * norm
        }

        // Per-channel chroma grain: two INDEPENDENT fine octaves (R, B); the G component is
        // derived as -(chR+chB)/2 so all three correlate-but-differ without a 3rd full buffer
        // (bounds peak memory — grain runs on the ~6MP edit buffer). chroma=0 → pure mono.
        val c = gp.chroma.coerceIn(0f, 1f)
        val chR: FloatArray?; val chB: FloatArray?
        if (c > 0f) {
            chR = octave(rng, n, width, height, fineR)
            chB = octave(rng, n, width, height, fineR)
        } else { chR = null; chB = null }
        val lumaMix = 1f - 0.5f * c // as chroma rises, lean a little less on the shared field

        // --- A(I): midtone-peaked density × subtle smooth-region visibility. ---
        // Local detail: |luma - blurred luma|, so busy areas read as high detail.
        val detail = if (gp.smoothBoost > 0f) localDetail(r, g, b, width, height) else null

        for (i in 0 until n) {
            val l = luma(r[i], g[i], b[i])
            var a = grainDensity(l, gp.shadowBias)
            if (detail != null) {
                // Smooth (low-detail) regions read grainier; busy regions slightly less. Bounded,
                // secondary — visibility only, never proportional to blur.
                a *= 1f + gp.smoothBoost * (0.5f - detail[i]).coerceIn(-0.5f, 0.5f)
            }
            val amp = gp.amount * a
            val sh = luma[i] * lumaMix
            val cr = chR?.get(i) ?: 0f
            val cb = chB?.get(i) ?: 0f
            val cg = -(cr + cb) * 0.5f
            r[i] = (r[i] + amp * (sh + cr * c)).coerceIn(0f, 1f)
            g[i] = (g[i] + amp * (sh + cg * c)).coerceIn(0f, 1f)
            b[i] = (b[i] + amp * (sh + cb * c)).coerceIn(0f, 1f)
        }
    }

    /**
     * Midtone-peaked grain density `A(I)` on luminance `l∈[0,1]`. A hump centred near mid-grey
     * that falls off in both the deepest shadows and the brightest highlights (real film grain is
     * strongest in the midtones). [shadowBias] (0..1) slides the peak toward the shadows and lifts
     * the shadow side, so a stock can lean grainier in the low-mids without losing the highlight
     * roll-off. Returns ~[0,1].
     */
    fun grainDensity(l: Float, shadowBias: Float): Float {
        // Peak position: mid-grey at bias 0, moving down toward ~0.3 as bias→1.
        val peak = 0.5f - 0.2f * shadowBias.coerceIn(0f, 1f)
        // Asymmetric Gaussian-ish hump: wider on the shadow side when biased.
        val width = if (l < peak) (0.32f + 0.25f * shadowBias) else 0.30f
        val d = (l - peak) / width
        val hump = exp(-0.5f * d * d)
        // Roll grain off in the brightest highlights (thin negative → little silver) and, more
        // gently, in the very deepest blacks (dense negative → grain clumps but the printed
        // black hides it). Both are partial, not hard cuts.
        val highlightRolloff = (1f - (l - 0.75f).coerceAtLeast(0f) / 0.25f).coerceIn(0.15f, 1f)
        val shadowRolloff = (0.45f + l / 0.06f).coerceIn(0.45f, 1f) // ~0.45 at black → 1 by luma 0.03
        return (hump * highlightRolloff * shadowRolloff).coerceIn(0f, 1f)
    }

    /**
     * One normalised grain octave: a ~Gaussian white-noise field blurred by [radius] for spatial
     * correlation (real grain isn't per-pixel white noise), then renormalised back to ~unit std —
     * blurring otherwise collapses the amplitude (a 3-pass box blur of radius 2 cuts std ~5-8×),
     * which is what made grain invisible before. Mean-centred.
     */
    private fun octave(rng: Random, n: Int, width: Int, height: Int, radius: Int): FloatArray {
        val f = FloatArray(n) { (rng.nextFloat() - 0.5f) + (rng.nextFloat() - 0.5f) } // ~Gaussian
        if (radius > 0) {
            gaussianBlur(f, width, height, radius)
            var sum = 0.0; var sq = 0.0
            for (v in f) { sum += v; sq += v.toDouble() * v }
            val mean = sum / n
            val std = kotlin.math.sqrt((sq / n - mean * mean).coerceAtLeast(1e-12))
            val scale = (0.5 / std).toFloat() // target std ≈ the un-blurred field's (~0.41)
            for (i in 0 until n) f[i] = (f[i] - mean.toFloat()) * scale
        }
        return f
    }

    /**
     * A cheap normalised local-detail measure in `[0,1]`: `|luma − blur(luma)|` scaled by a soft
     * constant, so flat regions → ~0 and busy/edgy regions → higher. Used only to modulate grain
     * *visibility* subtly (smooth areas read grainier).
     */
    private fun localDetail(
        r: FloatArray, g: FloatArray, b: FloatArray, width: Int, height: Int,
    ): FloatArray {
        val n = width * height
        val lum = FloatArray(n) { luma(r[it], g[it], b[it]) }
        val blur = lum.copyOf()
        gaussianBlur(blur, width, height, 2)
        val out = FloatArray(n)
        for (i in 0 until n) {
            // ~0.08 luma delta counts as "full detail"; clamp to [0,1].
            out[i] = (kotlin.math.abs(lum[i] - blur[i]) / 0.08f).coerceIn(0f, 1f)
        }
        return out
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
