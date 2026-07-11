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
        grainTexture: GrainTexture? = null,
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

        // 5. Grain (display space, last). Prefer the real scanned-style grain PLATE overlaid
        // (looks like film); fall back to synthesised grain only if no plate was supplied.
        val gr = look.grain
        if (gr.enabled) {
            if (grainTexture != null) applyGrainTexture(r, g, b, width, height, gr, grainTexture)
            else applyGrain(r, g, b, width, height, gr)
        }
    }

    /**
     * Composite a real film-grain **plate** ([tex]) over the image (display space): tile the plate
     * across the frame, weight it by a **midtone-peaked** density (real grain is strongest in the
     * midtones, [GrainParams.shadowBias] pushes it toward shadows), and blend it **soft-light**
     * so grain modulates around the local tone (film texture) rather than adding a flat offset
     * (digital haze). Monochrome by default with a small [GrainParams.chroma] variation so it's
     * not perfectly flat. Deterministic (the plate is fixed); [GrainParams.seed] just offsets the
     * tile origin so different stocks don't share the identical grain position. Exposed for tests.
     */
    fun applyGrainTexture(
        r: FloatArray, g: FloatArray, b: FloatArray,
        width: Int, height: Int, gp: GrainParams, tex: GrainTexture,
    ) {
        // Offset the tile per-seed so stocks don't line up their grain identically.
        val ox = (gp.seed % tex.size).toInt()
        val oy = ((gp.seed / 7) % tex.size).toInt()
        val c = gp.chroma.coerceIn(0f, 1f)
        var i = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val l = luma(r[i], g[i], b[i])
                val a = grainDensity(l, gp.shadowBias) * gp.amount
                val gVal = tex.at(x + ox, y + oy)
                // Small per-channel variation from neighbouring plate taps (correlated, not
                // independent RGB) so grain isn't perfectly monochrome but never "electronic".
                val vr = gVal + c * (tex.at(x + ox + 1, y + oy) - gVal)
                val vb = gVal + c * (tex.at(x + ox, y + oy + 1) - gVal)
                r[i] = softLightGrain(r[i], a * vr)
                g[i] = softLightGrain(g[i], a * gVal)
                b[i] = softLightGrain(b[i], a * vb)
                i++
            }
        }
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
     * Film grain: `I_out = softlight(I, A(I)·G)`. Deliberately **fine, uniform, single-scale,
     * monochrome-dominant** — the approach that actually reads as film in smooth areas like sky.
     * (An earlier multi-octave "clumping" model produced ugly low-frequency blotches in flat
     * regions; big soft clumps read as stains, not grain. Real fine-grain film in a clear sky is
     * even and unobtrusive.) Deterministic given [GrainParams.seed]. Exposed for unit testing.
     * See `research/FILM_EMULATION.md`.
     *
     * - `G`: one fine grain field (a ~Gaussian noise field lightly blurred by [GrainParams.size]
     *   for correlation, then renormalised), shared across channels as luma grain, plus a **small**
     *   per-channel chroma component so R/G/B differ slightly (not identical mono, not electronic).
     * - `A(I)`: a **midtone-peaked** density response (real grain is strongest in the midtones),
     *   biasable toward shadows by [GrainParams.shadowBias].
     * - Composited with a **soft-light** blend (grain modulates around the local tone) rather than
     *   flat addition (which washes toward grey and looks like digital haze).
     *
     * [GrainParams.coarseAmount] and [GrainParams.smoothBoost] are honoured if set but default to
     * off — they were the source of the blotchy-sky look and are not used by the shipped stocks.
     */
    fun applyGrain(
        r: FloatArray, g: FloatArray, b: FloatArray,
        width: Int, height: Int, gp: GrainParams,
    ) {
        val n = width * height
        if (n == 0) return
        val rng = Random(gp.seed)

        // --- G: one fine grain field (shared luma), optionally a light coarse octave. ---
        val fineR = (gp.size - 1f).roundToInt().coerceAtLeast(0)
        val luma = octave(rng, n, width, height, fineR)
        if (gp.coarseAmount > 0f) {
            val coarseR = (gp.size * gp.coarseSizeMul - 1f).roundToInt().coerceAtLeast(0)
            val coarse = octave(rng, n, width, height, coarseR)
            val cw = gp.coarseAmount
            val norm = 1f / kotlin.math.sqrt(1f + cw * cw)
            for (i in 0 until n) luma[i] = (luma[i] + cw * coarse[i]) * norm
        }

        // Small per-channel chroma grain: two independent fine octaves (R, B); G = -(R+B)/2 so
        // channels differ slightly without a 3rd buffer. chroma=0 → pure monochrome grain.
        val c = gp.chroma.coerceIn(0f, 1f)
        val chR: FloatArray?; val chB: FloatArray?
        if (c > 0f) {
            chR = octave(rng, n, width, height, fineR)
            chB = octave(rng, n, width, height, fineR)
        } else { chR = null; chB = null }
        val lumaMix = 1f - 0.5f * c

        val detail = if (gp.smoothBoost > 0f) localDetail(r, g, b, width, height) else null

        for (i in 0 until n) {
            val l = luma(r[i], g[i], b[i])
            var a = grainDensity(l, gp.shadowBias)
            if (detail != null) a *= 1f + gp.smoothBoost * (0.5f - detail[i]).coerceIn(-0.5f, 0.5f)
            val amp = gp.amount * a
            val sh = luma[i] * lumaMix
            val cr = chR?.get(i) ?: 0f
            val cb = chB?.get(i) ?: 0f
            val cg = -(cr + cb) * 0.5f
            // Soft-light composite: grain modulates around the local tone (film texture), not a
            // flat additive offset (digital haze). See [softLightGrain].
            r[i] = softLightGrain(r[i], amp * (sh + cr * c))
            g[i] = softLightGrain(g[i], amp * (sh + cg * c))
            b[i] = softLightGrain(b[i], amp * (sh + cb * c))
        }
    }

    /**
     * Apply a signed grain value [d] to a channel value [x] with a soft-light-style modulation:
     * `x + d·(4x(1-x))`. The `4x(1-x)` envelope (0 at black/white, 1 at mid-grey) means grain
     * darkens shadows and lightens highlights *around the local tone* instead of adding a flat
     * offset — the difference between film-like texture and a washed-out digital haze. Clamped.
     */
    private fun softLightGrain(x: Float, d: Float): Float {
        // Envelope peaks at mid-grey but keeps a floor at the extremes, so highlights/shadows
        // still carry some grain (real film isn't perfectly clean there) — 0.3 at black/white,
        // 1.0 at mid-grey.
        val envelope = 0.3f + 0.7f * (4f * x * (1f - x))
        return (x + d * envelope).coerceIn(0f, 1f)
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
