package com.ricohgr3.app.looks.emulation

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The pure-Kotlin film-develop pipeline. Operates on a planar RGB float buffer (values in
 * `[0,1]`, sRGB-encoded on input/output) of [width]×[height], with **no Android
 * dependencies**, so the entire look-rendering math is JVM-unit-testable — the device/GPU
 * `Bitmap` path (see the Android glue in `DevelopEngine`) merely marshals pixels in and out.
 *
 * Pipeline order: optional platform-DNG base → rendering intent/scene protection →
 * optional film-negative exposure and restrained product-side warmth
 * → adaptively blended negative/print LUT → luminance-neutral split-tone → connected-region skin
 * naturalisation → optional selective foliage and connected-sky colour → physical-scale
 * diffusion → two-lobe halation → ISO/scene-aware film-plane density grain.
 */
object DevelopPipeline {

    /**
     * A mild base grade applied **before** the film LUT, used to bring a platform-rendered DNG up
     * to the camera-JPEG-like tonal base the film models expect (see `PhotoSave`). Applied in
     * display space: an S-curve contrast around mid-grey plus a saturation scale.
     *
     * @property contrast S-curve strength around 0.5 (0 = none, ~0.2 gentle).
     * @property saturation display-space saturation scale (1 = unchanged).
     */
    data class PreGrade(val contrast: Float, val saturation: Float)

    /** `x^e` guarded for the `[0,1]` develop domain (negatives → 0). */
    private fun pow(x: Float, e: Float): Float = if (x <= 0f) 0f else x.toDouble().pow(e.toDouble()).toFloat()

    private fun srgbToLinear(c: Float): Float = ColorMath.srgbToLinear(c)
    private fun linearToSrgb(c: Float): Float = ColorMath.linearToSrgb(c)

    /** Perceptual/code-value weighting used only for masks and backwards-compatible look controls. */
    private fun luma(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

    /** Physical relative luminance used whenever light energy or exposure is changed. */
    private fun linearLuma(r: Float, g: Float, b: Float): Float =
        ColorMath.linearLuminance(r, g, b)

    /**
     * Render [look] over the RGB planes [r],[g],[b] in place. Each array is row-major,
     * length `width*height`, sRGB `[0,1]`. [lut] is the parsed colour table (identity if the
     * look has no `lutAsset`). [filmExposureEv] shifts exposure only at the negative input, before
     * the stock response; it is not a post-render brightness control. [faceRegions] are normalized
     * semantic gates from the platform face detector; an empty list safely disables selective
     * skin correction.
     */
    fun apply(
        r: FloatArray, g: FloatArray, b: FloatArray,
        width: Int, height: Int,
        look: FilmLook, lut: LutCube,
        preGrade: PreGrade? = null,
        iso: Int? = null,
        effectStrength: Float = 1f,
        filmExposureEv: Float = 0f,
        faceRegions: List<FaceRegion> = emptyList(),
        options: DevelopOptions = DevelopOptions(),
    ) {
        val lutSample = FloatArray(3)
        val skinOutput = FloatArray(3)
        val skinScratch = FloatArray(3)
        val colourScratch = FloatArray(3)
        val colourLab = FloatArray(3)
        val effect = effectStrength.coerceIn(0f, 1.5f)
        val negativeExposureGain =
            exp(0.69314718056f * filmExposureEv.coerceIn(-2f, 2f))

        // 0. Optional platform-DNG base grade (before analysis), kept close to neutral because
        // Android has already rendered sensor data into display RGB.
        if (preGrade != null) applyPreGrade(r, g, b, preGrade)

        // 1. Measure the actual frame and make small, bounded tonal/strength decisions. This is
        // what makes a look respond differently to backlight, high-key cloud, blue hour, and a
        // warm low-key interior while preserving the intent of each scene.
        val smart = options.intent == RenderingIntent.SMART
        val pleasingWarmthEligible =
            smart && look.colorBalance == FilmColorBalance.DAYLIGHT && effect > 0f
        val sceneProfile =
            if (smart && (look.adaptive.enabled || pleasingWarmthEligible)) {
                options.sceneProfile ?: SceneAnalyzer.analyze(r, g, b, width, height)
            } else {
                null
            }
        val baseAdjustment = if (smart && look.adaptive.enabled) {
            SceneAnalyzer.adjustment(
                requireNotNull(sceneProfile),
                look.adaptive,
                iso,
                look.stock,
            )
        } else {
            // The calibration path keeps the stock's authored mix and texture scale, but makes no
            // decision based on scene contents or camera ISO.
            SceneAdjustment.NONE.copy(
                lookStrength = look.stock.lookStrength,
                grainScale = look.stock.grainScale,
            )
        }
        // Tonal protection fades toward zero with the user's intensity, but is deliberately not
        // amplified beyond its authored value above 100%: "stronger film" should deepen the
        // stock character, not turn bounded auto-exposure into an HDR control.
        val protectionMix = effect.coerceAtMost(1f)
        val colourMix = (baseAdjustment.lookStrength * effect).coerceIn(0f, 1f)
        // Spatial/emulsion layers may grow a little beyond their authored baseline. The cap
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
        // Product-side warmth is intentionally outside the reproducible Stock contract. It uses a
        // tiny +6-mired Bradford adaptation for daylight-balanced colour stocks only, and fades
        // when reliable neutral samples say the scene already has a strong cast.
        if (pleasingWarmthEligible && sceneProfile != null) {
            val warmthMix =
                effect.coerceAtMost(1f) * pleasingWarmthScale(sceneProfile)
            if (warmthMix > 0f) {
                applyPleasingWarmth(r, g, b, warmthMix)
            }
        }
        if (smart && look.adaptive.enabled && protectionMix > 0f) {
            applySceneAdaptation(r, g, b, adjustment)
        }

        // 2. Detect coherent complexion regions from the scene-normalised source. The proxy mask
        // is intentionally computed before the stock colour transform, then reused during the
        // full-resolution LUT pass. This avoids globally keying every red/orange object.
        val skinMask = if (smart && look.skinTone.enabled && effect > 0f) {
            SkinTone.detect(
                r,
                g,
                b,
                width,
                height,
                faceRegions,
            ).takeIf { it.hasSkin }
        } else {
            null
        }
        val skinXMap = skinMask?.let { mask ->
            IntArray(width) { x ->
                (x.toLong() * mask.width / width).toInt().coerceIn(0, mask.width - 1)
            }
        }

        // 3. Apply the stock LUT and split tone, then naturalise only accepted skin regions. The
        // correction works around the rendered luminance, so it cannot flatten face lighting or
        // blur texture; halation and film-plane grain still happen afterwards.
        val g0 = look.lutInputGamma
        val st = look.splitTone
        val splitAmount = st.amount * layerMix
        val shadowTintLuma = luma(st.shadowR, st.shadowG, st.shadowB)
        val highTintLuma = luma(st.highR, st.highG, st.highB)
        for (y in 0 until height) {
            val row = y * width
            val skinRow = skinMask?.let { mask ->
                (y.toLong() * mask.height / height).toInt()
                    .coerceIn(0, mask.height - 1) * mask.width
            } ?: 0
            for (x in 0 until width) {
                val i = row + x
                val sourceR = r[i]
                val sourceG = g[i]
                val sourceB = b[i]
                // Exposure belongs before negative dye formation. Keep the adapted source
                // untouched for the final look blend and skin naturalisation: this makes the
                // bracket a film response, not destructive pre-brightening of the whole frame.
                val negativeR = negativeExposureInput(sourceR, negativeExposureGain)
                val negativeG = negativeExposureInput(sourceG, negativeExposureGain)
                val negativeB = negativeExposureInput(sourceB, negativeExposureGain)
                if (g0 == 1f) {
                    lut.sample(negativeR, negativeG, negativeB, lutSample)
                } else {
                    lut.sample(
                        pow(negativeR, g0),
                        pow(negativeG, g0),
                        pow(negativeB, g0),
                        lutSample,
                    )
                }
                var renderedR =
                    sourceR + (lutSample[0] - sourceR) * adjustment.lookStrength
                var renderedG =
                    sourceG + (lutSample[1] - sourceG) * adjustment.lookStrength
                var renderedB =
                    sourceB + (lutSample[2] - sourceB) * adjustment.lookStrength

                // Display-space, luminance-weighted and luminance-neutral split toning. Keeping
                // this in the same pass lets the skin correction evaluate the complete stock
                // colour rather than only the LUT.
                if (splitAmount > 0f) {
                    val originalY = linearLuma(renderedR, renderedG, renderedB)
                    ColorMath.srgbToOklab(renderedR, renderedG, renderedB, colourLab)
                    val l = colourLab[0].coerceIn(0f, 1f)
                    // Smoothly concentrate the tints near the ends; leave mid-grey mostly to the
                    // LUT.
                    val hw = l * l * splitAmount
                    val inv = 1f - l
                    val sw = inv * inv * splitAmount
                    renderedR = (renderedR +
                        (st.shadowR - shadowTintLuma) * sw +
                        (st.highR - highTintLuma) * hw).coerceIn(0f, 1f)
                    renderedG = (renderedG +
                        (st.shadowG - shadowTintLuma) * sw +
                        (st.highG - highTintLuma) * hw).coerceIn(0f, 1f)
                    renderedB = (renderedB +
                        (st.shadowB - shadowTintLuma) * sw +
                        (st.highB - highTintLuma) * hw).coerceIn(0f, 1f)
                    // "Neutral" now means constant physical relative luminance, not merely a
                    // weighted sum of gamma-encoded code values.
                    ColorMath.putAtLinearLuminance(
                        renderedR,
                        renderedG,
                        renderedB,
                        originalY,
                        colourScratch,
                    )
                    renderedR = colourScratch[0]
                    renderedG = colourScratch[1]
                    renderedB = colourScratch[2]
                }

                val maskWeight = if (skinMask != null && skinXMap != null) {
                    skinMask.weights[skinRow + skinXMap[x]]
                } else {
                    0f
                }
                if (maskWeight > 0f) {
                    SkinTone.naturalize(
                        sourceR = sourceR,
                        sourceG = sourceG,
                        sourceB = sourceB,
                        renderedR = renderedR,
                        renderedG = renderedG,
                        renderedB = renderedB,
                        maskWeight = maskWeight,
                        params = look.skinTone,
                        effectStrength = effect,
                        out = skinOutput,
                        scratch = skinScratch,
                    )
                    r[i] = skinOutput[0]
                    g[i] = skinOutput[1]
                    b[i] = skinOutput[2]
                } else {
                    r[i] = renderedR
                    g[i] = renderedG
                    b[i] = renderedB
                }
            }
        }

        // 4. Stock-specific vegetation colour. A soft hue/chroma/luminance gate rotates only
        // plausible yellow-green foliage toward cyan-green, with exact luminance restoration.
        val foliage = look.foliageTone
        if (smart && foliage.enabled) {
            applyFoliageCyanShift(
                r, g, b,
                cyanShift = foliage.cyanShift * layerMix,
                saturationBoost = foliage.saturationBoost * layerMix,
            )
        }

        // 5. Stock-specific sky colour. Connectivity to the top frame edge is the semantic gate:
        // this is not a global blue rotation and therefore leaves blue subjects/objects alone.
        val sky = look.skyTone
        if (smart && sky.enabled) {
            applySkyCyanShift(
                r, g, b, width, height,
                cyanShift = sky.cyanShift * layerMix,
                saturationBoost = sky.saturationBoost * layerMix,
            )
        }

        // 6. Image structure: a weak physical-scale diffusion/MTF response belongs underneath
        // halation and grain. It is stock-authored in both rendering intents.
        val imageStructure = look.imageStructure
        if (imageStructure.enabled) {
            FilmOptics.applyDiffusion(
                r,
                g,
                b,
                width,
                height,
                imageStructure.copy(
                    strength = (imageStructure.strength * layerMix).coerceIn(0f, 1f),
                ),
                options.filmFormat,
            )
        }

        // 7. Halation: scale radius with output dimensions so the small on-screen preview matches
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

        // 8. Film-plane grain (last). The field lives in physical film coordinates and each
        // output pixel analytically integrates its footprint, so preview/export resolution no
        // longer changes the apparent crystal size. Smart rendering may reduce the authored
        // amount for noisy/high-ISO scenes; Stock preserves the authored stock amount.
        val gr = look.grain
        if (gr.enabled) {
            val scaled = gr.copy(amount = gr.amount * adjustment.grainScale * layerMix)
            PhysicalFilmGrain.apply(
                r = r,
                g = g,
                b = b,
                width = width,
                height = height,
                params = scaled,
                renderSeed = options.renderSeed,
                filmPlane = PhysicalFilmGrain.FilmPlane(
                    longEdgeMillimeters = maxOf(
                        options.filmFormat.widthMillimetres,
                        options.filmFormat.heightMillimetres,
                    ),
                ),
            )
        }
    }

    /** Fade the optional warm style when credible neutral samples already show a strong cast. */
    internal fun pleasingWarmthScale(profile: SceneProfile): Float {
        val castStress =
            ((abs(profile.neutralWarmth) - 0.035f) / 0.16f).coerceIn(0f, 1f) *
                profile.neutralConfidence.coerceIn(0f, 1f)
        return (1f - castStress).coerceIn(0f, 1f)
    }

    /**
     * Apply a fixed +6-mired Bradford D65 adaptation in exact linear sRGB, blended by [amount].
     *
     * The app's source is already rendered RGB, so this is deliberately labelled a pleasing style
     * bias rather than illuminant recovery. The complete CAT/mix stays in linear light. When the
     * tiny move would cross the sRGB boundary, its amount is shortened along the original-to-CAT
     * vector; this leaves saturated boundary colours intact instead of clipping a channel or
     * collapsing chroma to force exact luminance.
     */
    internal fun applyPleasingWarmth(
        r: FloatArray,
        g: FloatArray,
        b: FloatArray,
        amount: Float,
    ) {
        val mix = amount.coerceIn(0f, 1f)
        if (mix == 0f) return
        for (i in r.indices) {
            // A pleasing neutral/skin bias should not repaint vivid object colours. Fade before
            // decoding so saturated cyan/yellow/primary boundaries remain effectively untouched.
            val codeChroma =
                maxOf(r[i], g[i], b[i]) - minOf(r[i], g[i], b[i])
            val localMix = mix * (1f - smoothstep(0.50f, 0.80f, codeChroma))
            if (localMix <= 0f) continue

            val lr = srgbToLinear(r[i])
            val lg = srgbToLinear(g[i])
            val lb = srgbToLinear(b[i])

            // Bradford D65 -> CIE daylight 6259 K (+6 reciprocal-megakelvin).
            val adaptedR =
                1.0078224f * lr + 0.01121765f * lg + 0.001989895f * lb
            val adaptedG =
                -0.00053236f * lr + 0.9976770f * lg + 0.00072567f * lb
            val adaptedB =
                -0.00091958f * lr - 0.00330192f * lg + 0.9633657f * lb

            val deltaR = adaptedR - lr
            val deltaG = adaptedG - lg
            val deltaB = adaptedB - lb
            var boundedMix = localMix

            // Intersect the requested CAT vector with the linear-sRGB cube. Reducing the shared
            // coefficient preserves the adaptation direction and can only make the effect
            // smaller. In particular, a saturated yellow/cyan stays saturated rather than being
            // pulled toward grey by an exact-luminance gamut compressor.
            if (deltaR > 0f) boundedMix = minOf(boundedMix, (1f - lr) / deltaR)
            else if (deltaR < 0f) boundedMix = minOf(boundedMix, -lr / deltaR)
            if (deltaG > 0f) boundedMix = minOf(boundedMix, (1f - lg) / deltaG)
            else if (deltaG < 0f) boundedMix = minOf(boundedMix, -lg / deltaG)
            if (deltaB > 0f) boundedMix = minOf(boundedMix, (1f - lb) / deltaB)
            else if (deltaB < 0f) boundedMix = minOf(boundedMix, -lb / deltaB)
            boundedMix = boundedMix.coerceIn(0f, localMix)

            r[i] = linearToSrgb(lr + deltaR * boundedMix).coerceIn(0f, 1f)
            g[i] = linearToSrgb(lg + deltaG * boundedMix).coerceIn(0f, 1f)
            b[i] = linearToSrgb(lb + deltaB * boundedMix).coerceIn(0f, 1f)
        }
    }

    /**
     * Shift an already-rendered source in bounded linear-light log-odds space before the negative
     * LUT. A literal multiply would clip every JPEG highlight before the film shoulder ever saw
     * it; this agrees closely with linear exposure in the shadows/midtones while retaining the
     * source's existing upper-tone separation for the stock response to compress.
     */
    private fun negativeExposureInput(channel: Float, gain: Float): Float {
        if (gain == 1f || channel <= 0f || channel >= 1f) return channel.coerceIn(0f, 1f)
        val linear = srgbToLinear(channel).coerceIn(0f, 1f)
        val exposed = (linear * gain) /
            (1f - linear + linear * gain).coerceAtLeast(1e-6f)
        return linearToSrgb(exposed)
    }

    /**
     * Apply the scene-level tonal decisions in linear light while preserving pixel chromaticity.
     * Exposure uses a bounded logit shift (black/white endpoints stay fixed); shadows and
     * highlights use smooth, monotonic curves; any out-of-gamut result is compressed toward its
     * target luminance instead of clipping channels independently.
     */
    fun applySceneAdaptation(
        r: FloatArray,
        g: FloatArray,
        b: FloatArray,
        adjustment: SceneAdjustment,
    ) {
        val exposureGain = exp(0.69314718056f * adjustment.exposureEv)
        for (i in r.indices) {
            val lr = srgbToLinear(r[i])
            val lg = srgbToLinear(g[i])
            val lb = srgbToLinear(b[i])
            val oldLuma = ColorMath.luminanceOfLinear(lr, lg, lb).coerceIn(0f, 1f)
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
            var rr = lr * scale
            var gg = lg * scale
            var bb = lb * scale
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
            r[i] = linearToSrgb(rr.coerceIn(0f, 1f)).coerceIn(0f, 1f)
            g[i] = linearToSrgb(gg.coerceIn(0f, 1f)).coerceIn(0f, 1f)
            b[i] = linearToSrgb(bb.coerceIn(0f, 1f)).coerceIn(0f, 1f)
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
     * Move vegetation-like greens slightly toward cyan-green without rotating every hue.
     *
     * The mask accepts a bounded yellow-green/green hue range with useful chroma and midtone
     * detail. Skin and warm objects have red as their dominant channel, existing cyan is outside
     * the hue window, and near-neutrals fail the saturation gate. Blue is raised in proportion to
     * its hue is rotated toward a bounded cyan-green target while retaining the original chroma;
     * a separate saturation control then expands that chroma. The result is gamut-compressed
     * around the original linear-light luminance, so foliage can read clearly cooler and richer without
     * changing scene exposure.
     */
    fun applyFoliageCyanShift(
        r: FloatArray,
        g: FloatArray,
        b: FloatArray,
        cyanShift: Float,
        saturationBoost: Float = 0f,
    ) {
        val hueAmount = cyanShift.coerceIn(0f, 1f)
        val saturationAmount = saturationBoost.coerceIn(0f, 0.50f)
        if (hueAmount <= 0f && saturationAmount <= 0f) return

        val shifted = FloatArray(3)
        for (i in r.indices) {
            val weight = foliageGreenLikelihood(r[i], g[i], b[i])
            if (weight <= 0f) continue

            val rr = r[i]
            val gg = g[i]
            val bb = b[i]
            val oldLuma = linearLuma(rr, gg, bb)
            if (oldLuma <= 0f) continue

            val max = maxOf(rr, gg, bb)
            val min = minOf(rr, gg, bb)
            val delta = max - min
            if (delta <= 0f || max <= 0f) continue
            val saturation = delta / max
            val hue = 60f * ((bb - rr) / delta + 2f)
            val hueMix = hueAmount * weight
            val shiftedHue = hue + (FOLIAGE_TARGET_HUE - hue) * hueMix
            hsvToRgb(shiftedHue, saturation, max, shifted)

            writeLumaMatchedChroma(
                r = r,
                g = g,
                b = b,
                index = i,
                targetR = shifted[0],
                targetG = shifted[1],
                targetB = shifted[2],
                outputLuma = oldLuma,
                chromaScale = 1f + saturationAmount * weight,
            )
        }
    }

    private const val FOLIAGE_TARGET_HUE = 160f

    /** Soft likelihood for useful vegetation colour (roughly HSV 52–176°). */
    private fun foliageGreenLikelihood(r: Float, g: Float, b: Float): Float {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        if (g < max || max < 0.08f || delta < 0.025f) return 0f
        val saturation = delta / max.coerceAtLeast(1e-5f)
        if (saturation < 0.05f) return 0f

        val hue = 60f * ((b - r) / delta + 2f)
        val hueWeight =
            smoothstep(52f, 70f, hue) * (1f - smoothstep(150f, 176f, hue))
        val saturationWeight = smoothstep(0.05f, 0.18f, saturation)
        val light = luma(r, g, b)
        val lightWeight =
            smoothstep(0.035f, 0.12f, light) * (1f - smoothstep(0.84f, 0.97f, light))
        val greenDominance = smoothstep(0.005f, 0.055f, g - maxOf(r, b))
        return (
            hueWeight * saturationWeight * lightWeight * greenDominance
            ).coerceIn(0f, 1f)
    }

    /**
     * Move blue sky slightly toward cyan without rotating blue everywhere in the image.
     *
     * A row-wise connected-component pass accepts only blue/cyan pixels whose region reaches the
     * top edge. This low-memory scan is deliberately semantic enough to reject isolated blue
     * clothing, glasses, signs, and interior objects, while avoiding a full-frame flood-fill
     * buffer on a 6 MP Android export. The hue move raises green only in eligible pixels; a
     * separate chroma expansion increases sky saturation. Both are gamut-compressed around the
     * original linear-light luminance so the operation changes sky colour rather than exposure.
     */
    fun applySkyCyanShift(
        r: FloatArray,
        g: FloatArray,
        b: FloatArray,
        width: Int,
        height: Int,
        cyanShift: Float,
        saturationBoost: Float = 0f,
    ) {
        val hueAmount = cyanShift.coerceIn(0f, 0.45f)
        val saturationAmount = saturationBoost.coerceIn(0f, 0.50f)
        if (
            (hueAmount <= 0f && saturationAmount <= 0f) ||
            width <= 0 ||
            height <= 0
        ) return

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
                    val weight = weights[column]
                    val hueMix = hueAmount * weight
                    val chromaScale = 1f + saturationAmount * weight
                    if (hueMix <= 0f && chromaScale <= 1f) continue
                    val rr = r[index]
                    val gg = g[index]
                    val bb = b[index]
                    val oldLuma = linearLuma(rr, gg, bb)
                    val blueGreenGap = (bb - gg).coerceAtLeast(0f)
                    if (oldLuma <= 0f) continue

                    // Raising G and slightly easing B rotates blue toward cyan. Chroma is then
                    // expanded around the old luminance, with a gamut limit instead of clipping.
                    val targetG = gg + blueGreenGap * hueMix
                    val targetB = bb - blueGreenGap * hueMix * 0.08f
                    writeLumaMatchedChroma(
                        r = r,
                        g = g,
                        b = b,
                        index = index,
                        targetR = rr,
                        targetG = targetG,
                        targetB = targetB,
                        outputLuma = oldLuma,
                        chromaScale = chromaScale,
                    )
                }
            }

            val swap = previousConnected
            previousConnected = currentConnected
            currentConnected = swap
        }
    }

    /**
     * Put [targetR]/[targetG]/[targetB]'s hue/chroma around [outputLuma], then expand chroma by
     * [chromaScale]. One shared gamut scale keeps every channel in range without clipping them
     * independently, so hue and exact linear-light luminance survive the saturation boost.
     */
    private fun writeLumaMatchedChroma(
        r: FloatArray,
        g: FloatArray,
        b: FloatArray,
        index: Int,
        targetR: Float,
        targetG: Float,
        targetB: Float,
        outputLuma: Float,
        chromaScale: Float,
    ) {
        val linearR = srgbToLinear(targetR)
        val linearG = srgbToLinear(targetG)
        val linearB = srgbToLinear(targetB)
        val targetLuma = ColorMath.luminanceOfLinear(linearR, linearG, linearB)
        val dr = linearR - targetLuma
        val dg = linearG - targetLuma
        val db = linearB - targetLuma
        var scale = chromaScale.coerceIn(0f, 1.60f)
        scale = minOf(
            scale,
            chromaLimit(outputLuma, dr),
            chromaLimit(outputLuma, dg),
            chromaLimit(outputLuma, db),
        )
        r[index] = linearToSrgb((outputLuma + dr * scale).coerceIn(0f, 1f))
        g[index] = linearToSrgb((outputLuma + dg * scale).coerceIn(0f, 1f))
        b[index] = linearToSrgb((outputLuma + db * scale).coerceIn(0f, 1f))
    }

    private fun chromaLimit(luma: Float, delta: Float): Float = when {
        delta > 0f -> (1f - luma) / delta
        delta < 0f -> luma / -delta
        else -> Float.POSITIVE_INFINITY
    }

    /** HSV-to-RGB conversion into caller-owned [out], avoiding per-pixel allocations. */
    private fun hsvToRgb(hue: Float, saturation: Float, value: Float, out: FloatArray) {
        val h = ((hue % 360f) + 360f) % 360f / 60f
        val chroma = value * saturation.coerceIn(0f, 1f)
        val x = chroma * (1f - abs(h % 2f - 1f))
        val m = value - chroma
        when (h.toInt().coerceIn(0, 5)) {
            0 -> { out[0] = chroma; out[1] = x; out[2] = 0f }
            1 -> { out[0] = x; out[1] = chroma; out[2] = 0f }
            2 -> { out[0] = 0f; out[1] = chroma; out[2] = x }
            3 -> { out[0] = 0f; out[1] = x; out[2] = chroma }
            4 -> { out[0] = x; out[1] = 0f; out[2] = chroma }
            else -> { out[0] = chroma; out[1] = 0f; out[2] = x }
        }
        out[0] += m
        out[1] += m
        out[2] += m
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
     * Edge-driven red-orange halation in linear light.
     *
     * A broad, predominantly red base-reflection lobe is followed by a tighter emulsion-scatter
     * lobe. Both source masks are derived from the same pre-halation pixels; the broad red halo
     * therefore cannot become a new highlight source for the tight lobe. The tight source is kept
     * as a 16-bit immutable mask while one float blur plane is reused for both lobes, avoiding two
     * full-resolution float masks.
     */
    fun applyHalation(
        r: FloatArray, g: FloatArray, b: FloatArray,
        width: Int, height: Int, h: HalationParams,
    ) {
        val n = width * height
        if (n == 0) return
        val mask = FloatArray(n)
        val tightCore = ShortArray(n)
        fillHalationMask(r, g, b, h, redSourceBias = 0.24f, out = mask)
        for (i in mask.indices) {
            tightCore[i] = (mask[i] * 65535f + 0.5f).toInt()
                .coerceIn(0, 65535)
                .toShort()
        }

        val broadRadius = (h.radius * 2.15f).roundToInt().coerceIn(h.radius + 1, 64)
        fillHalationMask(r, g, b, h, redSourceBias = 0.58f, out = mask)
        gaussianBlur(mask, width, height, broadRadius)
        compositeHalationPass(
            r, g, b, width, height, h,
            blurredMask = mask,
            fixedCore = null,
            strengthScale = 0.24f,
            redSourceBias = 0.58f,
            tintGScale = 0.30f,
            tintBScale = 0.08f,
        )

        for (i in mask.indices) {
            mask[i] = (tightCore[i].toInt() and 0xffff) / 65535f
        }
        gaussianBlur(mask, width, height, h.radius)
        compositeHalationPass(
            r, g, b, width, height, h,
            blurredMask = mask,
            fixedCore = tightCore,
            strengthScale = 0.78f,
            redSourceBias = 0.24f,
            tintGScale = 0.82f,
            tintBScale = 0.55f,
        )
    }

    private fun fillHalationMask(
        r: FloatArray,
        g: FloatArray,
        b: FloatArray,
        h: HalationParams,
        redSourceBias: Float,
        out: FloatArray,
    ) {
        for (i in out.indices) {
            val lr = srgbToLinear(r[i])
            val lg = srgbToLinear(g[i])
            val lb = srgbToLinear(b[i])
            val y = ColorMath.luminanceOfLinear(lr, lg, lb)
            // Longer wavelengths penetrate further into the emulsion/base. Biasing the broad
            // pass toward red lets a saturated practical light drive its own halo correctly.
            val source = y + (lr - y) * redSourceBias
            val t = ((source - h.threshold) / (1f - h.threshold + 1e-4f)).coerceIn(0f, 1f)
            out[i] = t * t * (3f - 2f * t)
        }
    }

    private fun compositeHalationPass(
        r: FloatArray,
        g: FloatArray,
        b: FloatArray,
        width: Int,
        height: Int,
        h: HalationParams,
        blurredMask: FloatArray,
        fixedCore: ShortArray?,
        strengthScale: Float,
        redSourceBias: Float,
        tintGScale: Float,
        tintBScale: Float,
    ) {
        val n = width * height
        for (i in 0 until n) {
            val rr = srgbToLinear(r[i])
            val gg = srgbToLinear(g[i])
            val bb = srgbToLinear(b[i])
            val sourceLinearLuma = ColorMath.luminanceOfLinear(rr, gg, bb)
            val core = if (fixedCore != null) {
                (fixedCore[i].toInt() and 0xffff) / 65535f
            } else {
                val sourceEnergy =
                    sourceLinearLuma + (rr - sourceLinearLuma) * redSourceBias
                val sourceT =
                    ((sourceEnergy - h.threshold) / (1f - h.threshold + 1e-4f))
                        .coerceIn(0f, 1f)
                sourceT * sourceT * (3f - 2f * sourceT)
            }
            val spill = (blurredMask[i] - core).coerceAtLeast(0f)
            val receiver = pow((1f - sourceLinearLuma).coerceIn(0f, 1f), 0.65f)
            val energy =
                (spill * h.strength * strengthScale * receiver).coerceIn(0f, 0.72f)
            if (energy <= 0f) continue

            r[i] = linearToSrgb(1f - (1f - rr) * (1f - energy * h.tintR))
            g[i] = linearToSrgb(1f - (1f - gg) * (1f - energy * h.tintG * tintGScale))
            b[i] = linearToSrgb(1f - (1f - bb) * (1f - energy * h.tintB * tintBScale))
        }
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
