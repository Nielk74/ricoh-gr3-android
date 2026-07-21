package com.ricohgr3.app.looks.emulation

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10

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
 *  2. **Negative optical density** — each R/G/B layer has its own speed, straight-line slope, toe
 *     and shoulder. The result is represented as actual optical density `D = -log10(T)`, including
 *     base/fog and image-forming dye. A validated sampled H-D curve may replace the fallback.
 *  3. **Inter-layer coupling** — a 3×3 matrix mixes image-forming dye optical densities.
 *  4. **Print / scan response** — the negative transmittance is printed through a separate
 *     positive characteristic. A sampled print D-logE curve may replace the fallback.
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
     * [measuredCurve], when present, replaces these fallback controls with absolute optical
     * density sampled against log10 exposure; [measuredLogExposureOffset] aligns relative camera
     * exposure with that curve's calibration axis. [manufacturerCurveAnchor] instead contributes
     * only a bounded graph-digitized shape suitable for already-rendered RGB. Defaults are neutral
     * so `Model()` is an exact round-trip.
     */
    data class Channel(
        val contrast: Float = 0f,
        val toe: Float = 0f,
        val shoulder: Float = 0f,
        val gain: Float = 1f,
        val measuredCurve: SampledDensityCurve? = null,
        val measuredLogExposureOffset: Float = 0f,
        val manufacturerCurveAnchor: ManufacturerCharacteristicAnchor? = null,
    )

    /**
     * Positive print / scanner response applied after negative dye formation.
     *
     * [contrast] controls the print straight-line slope. [toe] opens useful shadow separation;
     * [shoulder] controls paper-highlight compression. [exposureEv] and per-channel [biasR],
     * [biasG], [biasB] are print-light/scanner balance in stops. [blackPoint] and [paperWhite]
     * are linear-light output endpoints. [measuredCurve], when present, is the positive
     * material's D-logE response; negative transmittance plus [measuredLogExposureOffset] becomes
     * its print exposure.
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
        val measuredCurve: SampledDensityCurve? = null,
        val measuredLogExposureOffset: Float = 0f,
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
     * @property profile material, process, density bounds, and calibration provenance.
     * @property monochromeCapture stock-specific panchromatic three-band capture approximation;
     *   null for color stocks.
     */
    data class Model(
        val r: Channel = Channel(),
        val g: Channel = Channel(),
        val b: Channel = Channel(),
        val crossTalk: FloatArray = IDENTITY_3X3,
        val print: PrintStage = PrintStage(),
        val saturation: Float = 1f,
        val profile: FilmStockProfile = FilmStockProfile.NEUTRAL,
        val monochromeCapture: MonochromeCaptureResponse? = null,
    ) {
        // Value semantics not needed (models are static catalog data); silence the array warning.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private val IDENTITY_3X3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private const val LN_2 = 0.69314718056f
    private const val DENSITY_EPSILON = 0.000001f

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
    private fun normalizedFallbackDensity(exposure: Float, channel: Channel): Float {
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

    private fun negativeOpticalDensity(
        exposure: Float,
        channel: Channel,
        baseFog: Float,
        dyeCapacity: Float,
    ): OpticalDensity {
        channel.measuredCurve?.let { curve ->
            val logExposure =
                log10(exposure.coerceAtLeast(DENSITY_EPSILON)) +
                    channel.measuredLogExposureOffset
            return curve.densityAt(logExposure)
        }
        var normalized = normalizedFallbackDensity(exposure, channel)
        channel.manufacturerCurveAnchor?.let { anchor ->
            val digitized = anchor.normalizedDensity(exposure)
            normalized += (digitized - normalized) * anchor.influence
        }
        return OpticalDensity.of(baseFog + normalized * dyeCapacity)
    }

    /**
     * Convert coupled negative optical density into linear positive-print light.
     *
     * In the measured branch, negative transmittance determines log print exposure, the print
     * H-D curve forms paper density, and `10^-D` returns relative reflectance. The fallback branch
     * recovers normalized image-forming density from negative transmittance and retains the
     * catalog's prior visual response.
     */
    private fun printPositive(
        negativeDensity: OpticalDensity,
        baseFog: Float,
        dyeCapacity: Float,
        stage: PrintStage,
        channelBiasEv: Float,
    ): Float {
        val negativeTransmittance = negativeDensity.transmittance()
        stage.measuredCurve?.let { curve ->
            val printLightEv = stage.exposureEv + channelBiasEv
            val logPrintExposure =
                log10(negativeTransmittance) +
                    stage.measuredLogExposureOffset +
                    printLightEv * LOG10_2
            val paperDensity = curve.densityAt(logPrintExposure)
            val rawReflectance = paperDensity.transmittance()
            val minReflectance = curve.densityAt(curve.points.last().logExposure).transmittance()
            val maxReflectance = curve.densityAt(curve.points.first().logExposure).transmittance()
            val normalized = (
                (rawReflectance - minReflectance) /
                    (maxReflectance - minReflectance).coerceAtLeast(DENSITY_EPSILON)
                ).coerceIn(0f, 1f)
            val black = stage.blackPoint.coerceIn(0f, 0.2f)
            val white = stage.paperWhite.coerceIn(black + 0.1f, 1f)
            return black + normalized * (white - black)
        }

        // Recover D from T explicitly; do not treat a bounded display value as "density".
        val recoveredDensity = OpticalDensity.fromTransmittance(negativeTransmittance).value
        val density = ((recoveredDensity - baseFog) / dyeCapacity)
            .coerceIn(0f, 1f)
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
        val reflectance = black + positive * (white - black)
        // Represent the positive as print/scanner optical density before returning light.
        return OpticalDensity.fromTransmittance(
            reflectance.coerceIn(DENSITY_EPSILON, 1f),
        ).transmittance()
    }

    private fun luma(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

    /** Build a [size]³ LUT realising [model]. */
    fun build(model: Model, size: Int = 33): LutCube {
        val max = (size - 1).toFloat()
        val data = FloatArray(size * size * size * 3)
        val m = model.crossTalk
        require(m.size == 9) { "crossTalk must contain a row-major 3x3 matrix" }
        val negative = model.profile.negative
        var i = 0
        for (bi in 0 until size) for (gi in 0 until size) for (ri in 0 until size) {
            // 1. Decode display RGB to linear exposure. A monochrome stock forms one exposure
            // through its own panchromatic response rather than Rec.709 display luma.
            val linearR = ColorMath.srgbToLinear(ri / max)
            val linearG = ColorMath.srgbToLinear(gi / max)
            val linearB = ColorMath.srgbToLinear(bi / max)
            val mono = model.monochromeCapture?.exposure(linearR, linearG, linearB)
            val exposureR = mono ?: linearR
            val exposureG = mono ?: linearG
            val exposureB = mono ?: linearB

            // 2. Form absolute negative density D = base/fog + image dye density.
            val rd = negativeOpticalDensity(
                exposureR, model.r, negative.baseFog.r, negative.dyeCapacity.r,
            )
            val gd = negativeOpticalDensity(
                exposureG, model.g, negative.baseFog.g, negative.dyeCapacity.g,
            )
            val bd = negativeOpticalDensity(
                exposureB, model.b, negative.baseFog.b, negative.dyeCapacity.b,
            )

            // 3. Couple only image-forming dye density; base/fog is a separate material term.
            val rdDye = rd.value - negative.baseFog.r
            val gdDye = gd.value - negative.baseFog.g
            val bdDye = bd.value - negative.baseFog.b
            val mrDye = (m[0] * rdDye + m[1] * gdDye + m[2] * bdDye)
                .coerceIn(0f, negative.dyeCapacity.r)
            val mgDye = (m[3] * rdDye + m[4] * gdDye + m[5] * bdDye)
                .coerceIn(0f, negative.dyeCapacity.g)
            val mbDye = (m[6] * rdDye + m[7] * gdDye + m[8] * bdDye)
                .coerceIn(0f, negative.dyeCapacity.b)
            val mr = OpticalDensity.of(negative.baseFog.r + mrDye)
            val mg = OpticalDensity.of(negative.baseFog.g + mgDye)
            val mb = OpticalDensity.of(negative.baseFog.b + mbDye)

            // 4. Print/scan negative transmittance into a positive, then display-encode.
            var r = ColorMath.linearToSrgb(
                printPositive(
                    mr, negative.baseFog.r, negative.dyeCapacity.r,
                    model.print, model.print.biasR,
                ),
            )
            var g = ColorMath.linearToSrgb(
                printPositive(
                    mg, negative.baseFog.g, negative.dyeCapacity.g,
                    model.print, model.print.biasG,
                ),
            )
            var b = ColorMath.linearToSrgb(
                printPositive(
                    mb, negative.baseFog.b, negative.dyeCapacity.b,
                    model.print, model.print.biasB,
                ),
            )

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

    /**
     * Apply validated measured curves without replacing the stock's spatial layers or metadata.
     * Callers should also replace [Model.profile] with traceable [CalibrationBasis.LAB_MEASURED]
     * material metadata whose base/fog and dye capacities match the imported curves.
     */
    fun withMeasuredCurves(
        model: Model,
        curves: FilmDensityProfileCsv.ImportedCurves,
        negativeLogExposureOffset: Float = 0f,
        printLogExposureOffset: Float = 0f,
    ): Model {
        require(negativeLogExposureOffset.isFinite() && printLogExposureOffset.isFinite()) {
            "Measured log-exposure offsets must be finite"
        }
        val negative = model.profile.negative
        fun validateRange(
            name: String,
            curve: SampledDensityCurve,
            baseFog: Float,
            dyeCapacity: Float,
        ) {
            val min = curve.points.first().density
            val max = curve.points.last().density
            require(min >= baseFog - 0.0001f && max <= baseFog + dyeCapacity + 0.0001f) {
                "$name curve density [$min,$max] does not fit material range " +
                    "[$baseFog,${baseFog + dyeCapacity}]"
            }
        }
        validateRange("negative/r", curves.negativeR, negative.baseFog.r, negative.dyeCapacity.r)
        validateRange("negative/g", curves.negativeG, negative.baseFog.g, negative.dyeCapacity.g)
        validateRange("negative/b", curves.negativeB, negative.baseFog.b, negative.dyeCapacity.b)

        return model.copy(
            r = model.r.copy(
                measuredCurve = curves.negativeR,
                measuredLogExposureOffset = negativeLogExposureOffset,
            ),
            g = model.g.copy(
                measuredCurve = curves.negativeG,
                measuredLogExposureOffset = negativeLogExposureOffset,
            ),
            b = model.b.copy(
                measuredCurve = curves.negativeB,
                measuredLogExposureOffset = negativeLogExposureOffset,
            ),
            print = model.print.copy(
                measuredCurve = curves.print,
                measuredLogExposureOffset = printLogExposureOffset,
            ),
        )
    }

    private const val LOG10_2 = 0.30102999566f
}
