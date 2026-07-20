package com.ricohgr3.app.looks.emulation

import kotlin.math.exp
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The pure-Kotlin film-develop pipeline. Operates on a planar RGB float buffer (values in
 * `[0,1]`, sRGB-encoded on input/output) of [width]×[height], with **no Android
 * dependencies**, so the entire look-rendering math is JVM-unit-testable — the device/GPU
 * `Bitmap` path (see the Android glue in `DevelopEngine`) merely marshals pixels in and out.
 *
 * Pipeline order: optional RAW base → scene analysis/adaptation → adaptively blended stock LUT
 * → luminance-neutral split-tone → optional connected-sky colour → scaled halation →
 * ISO/scene-aware grain. Grain runs last in display space; halation is computed from a
 * linear-light highlight mask.
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
        iso: Int? = null,
        effectStrength: Float = 1f,
    ) {
        val n = width * height
        val tmp = FloatArray(3)
        val effect = effectStrength.coerceIn(0f, 1.5f)

        // 0. Optional RAW base grade (before analysis) — only for a genuinely flatter DNG render.
        if (preGrade != null) applyPreGrade(r, g, b, preGrade)

        // 1. Measure the actual frame and make small, bounded tonal/strength decisions. This is
        // what makes a look respond differently to backlight, high-key cloud, blue hour, and a
        // warm low-key interior while preserving the intent of each scene.
        val baseAdjustment = if (look.adaptive.enabled) {
            val profile = SceneAnalyzer.analyze(r, g, b, width, height)
            SceneAnalyzer.adjustment(profile, look.adaptive, iso)
        } else {
            SceneAdjustment.NONE
        }
        // Tonal protection fades toward zero with the user's intensity, but is deliberately not
        // amplified beyond its calibrated value above 100%: "stronger film" should deepen the
        // stock character, not turn bounded auto-exposure into an HDR control.
        val protectionMix = effect.coerceAtMost(1f)
        val colourMix = (baseAdjustment.lookStrength * effect).coerceIn(0f, 1f)
        // Spatial/emulsion layers may grow a little beyond their calibrated baseline. The cap
        // prevents a 150% slider setting from producing novelty-app grain or a red fog.
        val layerMix = (baseAdjustment.lookStrength * effect).coerceIn(0f, 1.25f)
        val adjustment = baseAdjustment.copy(
            exposureEv = baseAdjustment.exposureEv * protectionMix,
            shadowLift = baseAdjustment.shadowLift * protectionMix,
            highlightCompression = baseAdjustment.highlightCompression * protectionMix,
            contrast = baseAdjustment.contrast * protectionMix,
            saturation = 1f + (baseAdjustment.saturation - 1f) * protectionMix,
            lookStrength = colourMix,
        )
        if (look.adaptive.enabled && protectionMix > 0f) {
            applySceneAdaptation(r, g, b, adjustment)
        }

        // 2. Colour LUT (tone + colour response + cross-talk), blended over the scene-normalised
        // input rather than blindly replacing it. Skin-like hues get a small extra protection;
        // this is intentionally a soft colour safeguard, not a brittle face detector.
        val g0 = look.lutInputGamma
        for (i in 0 until n) {
            val rr = r[i]; val gg = g[i]; val bb = b[i]
            if (g0 == 1f) lut.sample(rr, gg, bb, tmp)
            else lut.sample(pow(rr, g0), pow(gg, g0), pow(bb, g0), tmp)
            val skin = if (adjustment.skinProtection > 0f) skinLikelihood(rr, gg, bb) else 0f
            val mix = adjustment.lookStrength * (1f - skin * adjustment.skinProtection)
            r[i] = rr + (tmp[0] - rr) * mix
            g[i] = gg + (tmp[1] - gg) * mix
            b[i] = bb + (tmp[2] - bb) * mix
        }

        // 3. Split toning (display space, luminance-weighted and luminance-neutral). The previous
        // additive implementation brightened an image while tinting it, which contributed to
        // clipped, washed-out filters.
        val st = look.splitTone
        if (st.amount > 0f) {
            val shadowTintLuma = luma(st.shadowR, st.shadowG, st.shadowB)
            val highTintLuma = luma(st.highR, st.highG, st.highB)
            val amount = st.amount * layerMix
            for (i in 0 until n) {
                val l = luma(r[i], g[i], b[i])
                // Smoothly concentrate the tints near the ends; leave mid-grey mostly to the LUT.
                val hw = l * l * amount
                val inv = 1f - l
                val sw = inv * inv * amount
                r[i] = (r[i] +
                    (st.shadowR - shadowTintLuma) * sw +
                    (st.highR - highTintLuma) * hw).coerceIn(0f, 1f)
                g[i] = (g[i] +
                    (st.shadowG - shadowTintLuma) * sw +
                    (st.highG - highTintLuma) * hw).coerceIn(0f, 1f)
                b[i] = (b[i] +
                    (st.shadowB - shadowTintLuma) * sw +
                    (st.highB - highTintLuma) * hw).coerceIn(0f, 1f)
            }
        }

        // 4. Stock-specific sky colour. Connectivity to the top frame edge is the semantic gate:
        // this is not a global blue rotation and therefore leaves blue subjects/objects alone.
        val sky = look.skyTone
        if (sky.enabled) {
            applySkyCyanShift(
                r, g, b, width, height,
                cyanShift = sky.cyanShift * layerMix,
            )
        }

        // 5. Halation: scale radius with output dimensions so the small on-screen preview matches
        // the exported image, and scale strength with scene brightness/highlight availability.
        val h = look.halation
        if (h.enabled) {
            val dimensionScale = maxOf(width, height) / 1600f
            val scaled = h.copy(
                radius = (h.radius * dimensionScale).roundToInt().coerceIn(1, 32),
                strength = h.strength * adjustment.halationScale * layerMix,
            )
            applyHalation(r, g, b, width, height, scaled)
        }

        // 6. Grain (display space, last). High ISO, low-key frames, and already busy/noisy files
        // automatically receive less added texture.
        val gr = look.grain
        if (gr.enabled) {
            val scaled = gr.copy(amount = gr.amount * adjustment.grainScale * layerMix)
            applyGrain(r, g, b, width, height, scaled)
        }
    }

    /**
     * Apply the scene-level tonal decisions while preserving pixel hue. Exposure uses a bounded
     * logit shift (black/white endpoints stay fixed); shadows and highlights use smooth,
     * monotonic curves; any out-of-gamut result is compressed toward its target luminance instead
     * of clipping channels independently.
     */
    fun applySceneAdaptation(
        r: FloatArray,
        g: FloatArray,
        b: FloatArray,
        adjustment: SceneAdjustment,
    ) {
        val exposureGain = exp(0.69314718056f * adjustment.exposureEv)
        for (i in r.indices) {
            val oldLuma = luma(r[i], g[i], b[i]).coerceIn(0f, 1f)
            var y = if (oldLuma <= 0f || oldLuma >= 1f) oldLuma
            else (oldLuma * exposureGain) / (1f - oldLuma + oldLuma * exposureGain)

            // Lift useful shadow separation without raising true black.
            if (adjustment.shadowLift > 0f) {
                val inv = 1f - y
                y += adjustment.shadowLift * 4f * y * inv * inv * inv
            }

            // Film-like shoulder: ease upper tones below the straight line, normalised to white.
            if (adjustment.highlightCompression > 0f && y > 0.55f) {
                val t = ((y - 0.55f) / 0.45f).coerceIn(0f, 1f)
                val a = 2.4f
                val rolled = (exp(a * t) - 1f) / (exp(a) - 1f)
                val target = 0.55f + 0.45f * rolled
                y += (target - y) * adjustment.highlightCompression
            }

            if (adjustment.contrast != 0f) {
                val smooth = y * y * (3f - 2f * y)
                y += (smooth - y) * adjustment.contrast
            }
            y = y.coerceIn(0f, 1f)

            val scale = if (oldLuma > 1e-5f) y / oldLuma else 0f
            var rr = r[i] * scale
            var gg = g[i] * scale
            var bb = b[i] * scale
            if (adjustment.saturation != 1f) {
                rr = y + (rr - y) * adjustment.saturation
                gg = y + (gg - y) * adjustment.saturation
                bb = y + (bb - y) * adjustment.saturation
            }

            // Hue-preserving gamut compression around the intended luma.
            val max = maxOf(rr, gg, bb)
            if (max > 1f && max > y) {
                val c = ((1f - y) / (max - y)).coerceIn(0f, 1f)
                rr = y + (rr - y) * c
                gg = y + (gg - y) * c
                bb = y + (bb - y) * c
            }
            val min = minOf(rr, gg, bb)
            if (min < 0f && min < y) {
                val c = (y / (y - min)).coerceIn(0f, 1f)
                rr = y + (rr - y) * c
                gg = y + (gg - y) * c
                bb = y + (bb - y) * c
            }
            r[i] = rr.coerceIn(0f, 1f)
            g[i] = gg.coerceIn(0f, 1f)
            b[i] = bb.coerceIn(0f, 1f)
        }
    }

    /** Soft HSV-ish likelihood for common skin hues; deliberately broad and never binary. */
    private fun skinLikelihood(r: Float, g: Float, b: Float): Float {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        if (delta < 0.035f || max < 0.16f || max > 0.98f) return 0f
        val saturation = delta / max.coerceAtLeast(1e-5f)
        if (saturation !in 0.08f..0.72f) return 0f

        val hue = when (max) {
            r -> 60f * ((g - b) / delta).let { if (it < 0f) it + 6f else it }
            g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        if (hue !in 3f..62f) return 0f
        val hueWeight = (1f - abs(hue - 28f) / 34f).coerceIn(0f, 1f)
        val satWeight = ((saturation - 0.08f) / 0.18f).coerceIn(0f, 1f) *
            ((0.72f - saturation) / 0.20f).coerceIn(0f, 1f)
        val lum = luma(r, g, b)
        val lumWeight = ((lum - 0.10f) / 0.18f).coerceIn(0f, 1f) *
            ((0.96f - lum) / 0.16f).coerceIn(0f, 1f)
        return hueWeight * satWeight * lumWeight
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
     * Move blue sky slightly toward cyan without rotating blue everywhere in the image.
     *
     * A row-wise connected-component pass accepts only blue/cyan pixels whose region reaches the
     * top edge. This low-memory scan is deliberately semantic enough to reject isolated blue
     * clothing, glasses, signs, and interior objects, while avoiding a full-frame flood-fill
     * buffer on a 6 MP Android export. The hue move raises green only in eligible pixels, then
     * restores the original luminance so the operation changes sky colour rather than exposure.
     */
    fun applySkyCyanShift(
        r: FloatArray,
        g: FloatArray,
        b: FloatArray,
        width: Int,
        height: Int,
        cyanShift: Float,
    ) {
        val amount = cyanShift.coerceIn(0f, 0.45f)
        if (amount <= 0f || width <= 0 || height <= 0) return

        var previousConnected = BooleanArray(width)
        var currentConnected = BooleanArray(width)
        val weights = FloatArray(width)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                weights[x] = skyBlueLikelihood(r[row + x], g[row + x], b[row + x])
                currentConnected[x] = false
            }

            var x = 0
            while (x < width) {
                while (x < width && weights[x] <= SKY_CONNECT_THRESHOLD) x++
                if (x >= width) break
                val start = x
                while (x < width && weights[x] > SKY_CONNECT_THRESHOLD) x++
                val end = x - 1

                var connected = y == 0
                if (!connected) {
                    val probeStart = (start - 1).coerceAtLeast(0)
                    val probeEnd = (end + 1).coerceAtMost(width - 1)
                    var probe = probeStart
                    while (probe <= probeEnd && !connected) {
                        connected = previousConnected[probe]
                        probe++
                    }
                }
                if (!connected) continue

                for (column in start..end) {
                    currentConnected[column] = true
                    val index = row + column
                    val mix = amount * weights[column]
                    if (mix <= 0f) continue
                    val rr = r[index]
                    val gg = g[index]
                    val bb = b[index]
                    val oldLuma = luma(rr, gg, bb)
                    val blueGreenGap = (bb - gg).coerceAtLeast(0f)
                    if (oldLuma <= 0f || blueGreenGap <= 0f) continue

                    // Raising G and slightly easing B rotates blue toward cyan. Luminance is put
                    // back exactly so a blue sky does not become a brighter cyan sky.
                    val targetG = gg + blueGreenGap * mix
                    val targetB = bb - blueGreenGap * mix * 0.08f
                    val targetLuma = luma(rr, targetG, targetB)
                    val scale = oldLuma / targetLuma.coerceAtLeast(1e-5f)
                    r[index] = (rr * scale).coerceIn(0f, 1f)
                    g[index] = (targetG * scale).coerceIn(0f, 1f)
                    b[index] = (targetB * scale).coerceIn(0f, 1f)
                }
            }

            val swap = previousConnected
            previousConnected = currentConnected
            currentConnected = swap
        }
    }

    private const val SKY_CONNECT_THRESHOLD = 0.03f

    /** Soft likelihood for photographic blue sky (roughly HSV 185–248°). */
    private fun skyBlueLikelihood(r: Float, g: Float, b: Float): Float {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        if (b < max || max < 0.10f || delta < 0.025f) return 0f
        val saturation = delta / max.coerceAtLeast(1e-5f)
        if (saturation < 0.06f) return 0f

        val hue = 60f * ((r - g) / delta + 4f)
        val hueWeight =
            smoothstep(184f, 200f, hue) * (1f - smoothstep(232f, 248f, hue))
        val saturationWeight = smoothstep(0.06f, 0.22f, saturation)
        val lightWeight = smoothstep(0.07f, 0.24f, luma(r, g, b))
        // Suppress purple/indigo objects while retaining cool dusk sky.
        val greenOverRed = smoothstep(-0.025f, 0.12f, g - r)
        return (hueWeight * saturationWeight * lightWeight * greenOverRed).coerceIn(0f, 1f)
    }

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Edge-driven red-orange halation in linear light. A smooth highlight core is blurred, then
     * the unblurred core is subtracted: uniform bright areas remain clean while only light that
     * spills *outside* a highlight survives. The spill is strongest on darker receiving pixels,
     * matching the visible red fringe around practical lights and hard sun reflections rather
     * than laying a red fog over the entire frame.
     */
    fun applyHalation(
        r: FloatArray, g: FloatArray, b: FloatArray,
        width: Int, height: Int, h: HalationParams,
    ) {
        val n = width * height
        val mask = FloatArray(n)
        for (i in 0 until n) {
            val lin = srgbToLinear(luma(r[i], g[i], b[i]))
            val t = ((lin - h.threshold) / (1f - h.threshold + 1e-4f)).coerceIn(0f, 1f)
            mask[i] = t * t * (3f - 2f * t)
        }
        gaussianBlur(mask, width, height, h.radius)
        for (i in 0 until n) {
            val sourceLuma = luma(r[i], g[i], b[i])
            val sourceLinearLuma = srgbToLinear(sourceLuma)
            val sourceT =
                ((sourceLinearLuma - h.threshold) / (1f - h.threshold + 1e-4f)).coerceIn(0f, 1f)
            val core = sourceT * sourceT * (3f - 2f * sourceT)
            val spill = (mask[i] - core).coerceAtLeast(0f)
            val receiver = pow((1f - sourceLinearLuma).coerceIn(0f, 1f), 0.65f)
            val energy = (spill * h.strength * receiver).coerceIn(0f, 0.72f)
            if (energy <= 0f) continue

            // Screen-composite light energy in linear space; red penetrates furthest, with only
            // restrained green/blue in the fringe.
            val rr = srgbToLinear(r[i])
            val gg = srgbToLinear(g[i])
            val bb = srgbToLinear(b[i])
            r[i] = linearToSrgb(1f - (1f - rr) * (1f - energy * h.tintR))
            g[i] = linearToSrgb(1f - (1f - gg) * (1f - energy * h.tintG * 0.82f))
            b[i] = linearToSrgb(1f - (1f - bb) * (1f - energy * h.tintB * 0.55f))
        }
    }

    /**
     * Non-tiling, high-frequency emulsion grain.
     *
     * A coordinate hash creates one deterministic density sample per output pixel. A compact 3×3
     * convolution correlates only immediate neighbours, producing small crystal-like structure
     * without the low-frequency clouds and repeating stamps of a texture plate. [GrainParams.size]
     * changes those local weights rather than scaling up a bitmap. Shadows receive only a slight
     * increase in local correlation; they never gain a separate blurry octave.
     *
     * The field is independent of image edges, focus, and motion blur. It is applied last and
     * perturbs log-odds/optical-density-like luminance through [compositeDensityGrain], preserving
     * black/white endpoints and hue. Smooth regions reveal the same sharp field more clearly simply
     * because they contain less competing scene detail.
     */
    fun applyGrain(
        r: FloatArray, g: FloatArray, b: FloatArray,
        width: Int, height: Int, gp: GrainParams,
    ) {
        val n = width * height
        if (n == 0) return
        val seed = gp.seed.toInt()
        val resolutionScale = sqrt((maxOf(width, height) / 1600f).coerceIn(0.25f, 4f))
        val baseStructure =
            ((gp.size * resolutionScale - 1.05f) / 1.90f).coerceIn(0f, 1f)

        // Quantised shadow variants avoid a square root for every pixel. Every kernel sums to
        // one and is variance-normalised to the same 0.42 standard deviation, so "larger grain"
        // changes crystal structure rather than quietly changing exposure or overall strength.
        val centerWeights = FloatArray(GRAIN_SHADOW_LEVELS)
        val axialWeights = FloatArray(GRAIN_SHADOW_LEVELS)
        val diagonalWeights = FloatArray(GRAIN_SHADOW_LEVELS)
        for (level in 0 until GRAIN_SHADOW_LEVELS) {
            val shadow = level.toFloat() / (GRAIN_SHADOW_LEVELS - 1)
            val structure = (baseStructure + 0.10f * shadow).coerceIn(0f, 1f)
            val axial = 0.025f + (0.095f - 0.025f) * structure
            val diagonal = 0.030f * structure
            val center = 1f - 4f * axial - 4f * diagonal
            val variance = (center * center + 4f * axial * axial +
                4f * diagonal * diagonal) / 3f
            val normaliser = 0.42f / sqrt(variance.coerceAtLeast(1e-6f))
            centerWeights[level] = center * normaliser
            axialWeights[level] = axial * normaliser
            diagonalWeights[level] = diagonal * normaliser
        }

        var above = grainHashRow(width, -1, seed)
        var current = grainHashRow(width, 0, seed)
        var below = grainHashRow(width, 1, seed)
        var index = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val oldLuma = luma(r[index], g[index], b[index]).coerceIn(0f, 1f)
                val shadowT = ((0.42f - oldLuma) / 0.38f).coerceIn(0f, 1f)
                val shadow = shadowT * shadowT * (3f - 2f * shadowT)
                val level = (shadow * (GRAIN_SHADOW_LEVELS - 1)).roundToInt()
                val centerWeight = centerWeights[level]
                val axialWeight = axialWeights[level]
                val diagonalWeight = diagonalWeights[level]

                val center = current[x + 1]
                val axial = above[x + 1] + below[x + 1] + current[x] + current[x + 2]
                val diagonal = above[x] + above[x + 2] + below[x] + below[x + 2]
                val grain = center * centerWeight + axial * axialWeight +
                    diagonal * diagonalWeight

                // Chroma remains tightly coupled to the luma crystal. Neighbour taps provide a
                // very small channel difference without independent RGB "sensor noise".
                val chromaR = grain * 0.84f + current[x] * 0.16f
                val chromaB = grain * 0.84f + below[x + 2] * 0.16f
                compositeDensityGrain(r, g, b, index, grain, chromaR, chromaB, gp)
                index++
            }
            val oldAbove = above
            above = current
            current = below
            below = oldAbove
            fillGrainHashRow(below, y + 2, seed)
        }
    }

    private const val GRAIN_SHADOW_LEVELS = 9

    private fun grainHashRow(width: Int, y: Int, seed: Int): FloatArray =
        FloatArray(width + 2).also { fillGrainHashRow(it, y, seed) }

    private fun fillGrainHashRow(row: FloatArray, y: Int, seed: Int) {
        for (index in row.indices) row[index] = grainHash(index - 1, y, seed)
    }

    /** Stable, non-periodic coordinate hash mapped to `[-1,1]`. */
    private fun grainHash(x: Int, y: Int, seed: Int): Float {
        var hash = seed xor (x * 521_288_629) xor (y * 1_597_334_677)
        hash = (hash xor (hash ushr 16)) * -2_048_144_789
        hash = (hash xor (hash ushr 13)) * -1_028_477_387
        hash = hash xor (hash ushr 16)
        return (((hash ushr 8) and 0x00FF_FFFF) / 8_388_607.5f) - 1f
    }

    /**
     * Perturb luminance in log-odds/optical-density-like space, then put that luminance back under
     * the original hue. The chroma component is constructed to have zero Rec.709 luminance.
     */
    private fun compositeDensityGrain(
        r: FloatArray,
        g: FloatArray,
        b: FloatArray,
        index: Int,
        grain: Float,
        chromaR: Float,
        chromaB: Float,
        gp: GrainParams,
    ) {
        val oldLuma = luma(r[index], g[index], b[index]).coerceIn(0f, 1f)
        if (oldLuma <= 0f || oldLuma >= 1f) return
        val density = grainDensity(oldLuma, gp.shadowBias)
        val y = oldLuma.coerceIn(0.0005f, 0.9995f)
        val logOdds = kotlin.math.ln(y / (1f - y))
        val shifted = logOdds + grain * gp.amount * density * 2.65f
        val newLuma = (1f / (1f + exp(-shifted))).coerceIn(0f, 1f)
        val scale = newLuma / oldLuma.coerceAtLeast(1e-5f)
        var rr = r[index] * scale
        var gg = g[index] * scale
        var bb = b[index] * scale

        val chromaAmount = gp.amount * density * gp.chroma.coerceIn(0f, 1f) * 0.32f
        if (chromaAmount > 0f) {
            // Solve G so the chroma vector has exactly zero Rec.709 luminance.
            val chromaG = -(0.2126f * chromaR + 0.0722f * chromaB) / 0.7152f
            val envelope = 4f * newLuma * (1f - newLuma)
            rr += chromaR * chromaAmount * envelope
            gg += chromaG * chromaAmount * envelope
            bb += chromaB * chromaAmount * envelope
        }

        // Hue-preserving gamut compression around the new luminance.
        val max = maxOf(rr, gg, bb)
        if (max > 1f && max > newLuma) {
            val mix = ((1f - newLuma) / (max - newLuma)).coerceIn(0f, 1f)
            rr = newLuma + (rr - newLuma) * mix
            gg = newLuma + (gg - newLuma) * mix
            bb = newLuma + (bb - newLuma) * mix
        }
        val min = minOf(rr, gg, bb)
        if (min < 0f && min < newLuma) {
            val mix = (newLuma / (newLuma - min)).coerceIn(0f, 1f)
            rr = newLuma + (rr - newLuma) * mix
            gg = newLuma + (gg - newLuma) * mix
            bb = newLuma + (bb - newLuma) * mix
        }
        r[index] = rr.coerceIn(0f, 1f)
        g[index] = gg.coerceIn(0f, 1f)
        b[index] = bb.coerceIn(0f, 1f)
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
     * Separable box-approximated Gaussian blur of a single-channel [plane] in place, using
     * three box passes (a standard fast Gaussian approximation). [radius] in pixels; a
     * radius of 0 is a no-op.
     */
    fun gaussianBlur(plane: FloatArray, width: Int, height: Int, radius: Int) {
        if (radius <= 0) return
        val scratch = FloatArray(plane.size)
        repeat(3) {
            boxBlurH(plane, scratch, width, height, radius)
            boxBlurV(scratch, plane, width, height, radius)
        }
    }

    private fun boxBlurH(src: FloatArray, out: FloatArray, w: Int, h: Int, radius: Int) {
        val norm = 1f / (2 * radius + 1)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            for (k in -radius..radius) sum += src[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                out[row + x] = sum * norm
                val remove = (x - radius).coerceIn(0, w - 1)
                val add = (x + radius + 1).coerceIn(0, w - 1)
                sum += src[row + add] - src[row + remove]
            }
        }
    }

    private fun boxBlurV(src: FloatArray, out: FloatArray, w: Int, h: Int, radius: Int) {
        val norm = 1f / (2 * radius + 1)
        for (x in 0 until w) {
            var sum = 0f
            for (k in -radius..radius) sum += src[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = sum * norm
                val remove = (y - radius).coerceIn(0, h - 1)
                val add = (y + radius + 1).coerceIn(0, h - 1)
                sum += src[add * w + x] - src[remove * w + x]
            }
        }
    }
}
