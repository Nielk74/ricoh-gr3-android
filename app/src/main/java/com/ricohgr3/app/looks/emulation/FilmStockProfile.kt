package com.ricohgr3.app.looks.emulation

import kotlin.math.log10
import kotlin.math.pow

/**
 * How strongly a stock profile is tied to physical measurements.
 *
 * [MANUFACTURER_ANCHORED] means the material/process choice and plausible density limits are
 * informed by a manufacturer's technical publication, but the actual rendering coefficients
 * have not been fitted to this application's own sensitometry. It must not be presented as a
 * measured match. [LAB_MEASURED] is reserved for a profile backed by traceable local measurements.
 */
enum class CalibrationBasis {
    NEUTRAL_REFERENCE,
    MANUFACTURER_ANCHORED,
    LAB_MEASURED,
}

data class ProfileProvenance(
    val basis: CalibrationBasis,
    val sourceTitle: String,
    val sourceUrl: String? = null,
    val sourceRevision: String? = null,
    val notes: String,
)

/**
 * Absolute photographic optical density, `D = -log10(T)`, where `T` is transmittance (or,
 * for a reflective print, relative reflectance). This is deliberately distinct from normalized
 * curve controls and display RGB values.
 */
class OpticalDensity private constructor(val value: Float) {
    init {
        require(value.isFinite() && value >= 0f) { "Optical density must be finite and >= 0" }
    }

    fun transmittance(): Float = 10f.pow(-value)

    override fun equals(other: Any?): Boolean =
        other is OpticalDensity && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "OpticalDensity($value)"

    companion object {
        fun of(value: Float): OpticalDensity = OpticalDensity(value)

        fun fromTransmittance(transmittance: Float): OpticalDensity {
            require(transmittance.isFinite() && transmittance > 0f && transmittance <= 1f) {
                "Transmittance must be finite and in (0, 1]"
            }
            return OpticalDensity(-log10(transmittance))
        }
    }
}

data class DensityTriplet(
    val r: Float,
    val g: Float,
    val b: Float,
) {
    init {
        require(listOf(r, g, b).all { it.isFinite() && it >= 0f }) {
            "Density values must be finite and >= 0"
        }
    }

    companion object {
        fun neutral(value: Float) = DensityTriplet(value, value, value)
    }
}

/**
 * Physical bounds for the three image-forming layers of a processed negative.
 *
 * [baseFog] is the density of the processed base plus fog at no image exposure. [dyeCapacity] is
 * the additional image-forming density above base/fog. Equal channel capacities preserve the
 * legacy neutral/cross-talk behaviour; future measured profiles may provide independent values.
 */
data class NegativeMaterial(
    val baseFog: DensityTriplet,
    val dyeCapacity: DensityTriplet,
) {
    init {
        require(listOf(dyeCapacity.r, dyeCapacity.g, dyeCapacity.b).all { it > 0f }) {
            "Every negative layer needs positive dye-density capacity"
        }
    }

    companion object {
        val NEUTRAL = NegativeMaterial(
            baseFog = DensityTriplet.neutral(0.10f),
            dyeCapacity = DensityTriplet.neutral(2.60f),
        )
    }
}

enum class FilmMaterialKind {
    COLOR_NEGATIVE,
    MOTION_PICTURE_COLOR_NEGATIVE,
    BLACK_AND_WHITE_NEGATIVE,
    NEUTRAL_REFERENCE,
}

/** Authored capture illuminant class, kept explicit for product-side white-balance decisions. */
enum class FilmColorBalance {
    DAYLIGHT,
    TUNGSTEN,
    MONOCHROME,
    UNSPECIFIED,
}

/**
 * Traceable material/process metadata carried with the computational model.
 *
 * The catalog's built-in profiles are manufacturer-anchored starting points. Their density
 * limits are plausible working values and the legacy curve controls remain aesthetic fits until
 * a [CalibrationBasis.LAB_MEASURED] profile is imported.
 */
data class FilmStockProfile(
    val stockId: String,
    val materialName: String,
    val kind: FilmMaterialKind,
    val colorBalance: FilmColorBalance,
    val process: String,
    val developer: String? = null,
    val negative: NegativeMaterial = NegativeMaterial.NEUTRAL,
    val printOrScan: String,
    val provenance: ProfileProvenance,
) {
    companion object {
        val NEUTRAL = FilmStockProfile(
            stockId = "neutral",
            materialName = "Neutral mathematical reference",
            kind = FilmMaterialKind.NEUTRAL_REFERENCE,
            colorBalance = FilmColorBalance.UNSPECIFIED,
            process = "none",
            negative = NegativeMaterial.NEUTRAL,
            printOrScan = "identity positive",
            provenance = ProfileProvenance(
                basis = CalibrationBasis.NEUTRAL_REFERENCE,
                sourceTitle = "Internal identity transform",
                notes = "Mathematical round-trip used by tests; not a photographic material.",
            ),
        )
    }
}

/**
 * A conservative three-band approximation of a monochrome emulsion's panchromatic sensitivity.
 *
 * It maps *linear* camera RGB exposure into one film exposure before the H-D curve. The weights
 * are a camera-primary approximation to a published spectral-sensitivity graph, not a replacement
 * for integrating a measured scene spectrum against an emulsion sensitivity curve.
 */
data class MonochromeCaptureResponse(
    val redWeight: Float,
    val greenWeight: Float,
    val blueWeight: Float,
    val referenceIlluminant: String,
    val filter: String = "none",
    val provenance: ProfileProvenance,
) {
    init {
        require(listOf(redWeight, greenWeight, blueWeight).all { it.isFinite() && it >= 0f }) {
            "Monochrome capture weights must be finite and >= 0"
        }
        require(redWeight + greenWeight + blueWeight > 0f) {
            "Monochrome capture weights cannot all be zero"
        }
    }

    fun exposure(linearR: Float, linearG: Float, linearB: Float): Float {
        val total = redWeight + greenWeight + blueWeight
        return (
            linearR * redWeight +
                linearG * greenWeight +
                linearB * blueWeight
            ) / total
    }
}

data class DensityCurvePoint(
    val logExposure: Float,
    val density: Float,
)

/**
 * Sampled monotonic sensitometric (H-D / D-logE) curve in absolute optical density.
 *
 * Samples are linearly interpolated in log10 exposure and clamped outside the measured domain.
 * This intentionally does not extrapolate unmeasured toe or shoulder behaviour.
 */
data class SampledDensityCurve(
    val points: List<DensityCurvePoint>,
    val provenance: ProfileProvenance,
) {
    init {
        require(points.size >= 2) { "A density curve needs at least two points" }
        points.forEach {
            require(it.logExposure.isFinite() && it.density.isFinite()) {
                "Density curve samples must be finite"
            }
            require(it.density in 0f..8f) { "Density ${it.density} is outside the supported [0,8] range" }
        }
        points.zipWithNext().forEach { (a, b) ->
            require(b.logExposure > a.logExposure) {
                "log_exposure samples must be strictly increasing"
            }
            require(b.density >= a.density) {
                "density samples must be monotonic non-decreasing"
            }
        }
    }

    fun densityAt(logExposure: Float): OpticalDensity {
        require(logExposure.isFinite()) { "log exposure must be finite" }
        if (logExposure <= points.first().logExposure) return OpticalDensity.of(points.first().density)
        if (logExposure >= points.last().logExposure) return OpticalDensity.of(points.last().density)

        val upper = points.binarySearch { it.logExposure.compareTo(logExposure) }
            .let { if (it >= 0) it else -it - 1 }
        val a = points[upper - 1]
        val b = points[upper]
        val t = (logExposure - a.logExposure) / (b.logExposure - a.logExposure)
        return OpticalDensity.of(a.density + (b.density - a.density) * t)
    }
}

/**
 * Calibration interchange for a measured profile.
 *
 * CSV columns are:
 *
 * `stage,channel,log_exposure,density`
 *
 * Negative channels must be `r`, `g`, and `b`; the optional print curve uses `neutral`.
 * Blank lines and lines beginning with `#` are ignored. Units are log10 relative exposure and
 * absolute optical density. Imported data is validated by [SampledDensityCurve].
 */
object FilmDensityProfileCsv {
    data class ImportedCurves(
        val negativeR: SampledDensityCurve,
        val negativeG: SampledDensityCurve,
        val negativeB: SampledDensityCurve,
        val print: SampledDensityCurve?,
    )

    fun parse(
        text: String,
        provenance: ProfileProvenance,
    ): ImportedCurves {
        require(provenance.basis == CalibrationBasis.LAB_MEASURED) {
            "Imported measurement CSV must use LAB_MEASURED provenance"
        }
        val rows = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        require(rows.isNotEmpty()) { "Density profile CSV is empty" }
        require(rows.first().lowercase().replace(" ", "") ==
            "stage,channel,log_exposure,density") {
            "Expected CSV header: stage,channel,log_exposure,density"
        }

        val grouped = linkedMapOf<Pair<String, String>, MutableList<DensityCurvePoint>>()
        rows.drop(1).forEachIndexed { index, row ->
            val cells = row.split(',').map { it.trim() }
            require(cells.size == 4) { "CSV row ${index + 2} must have exactly four columns" }
            val stage = cells[0].lowercase()
            val channel = cells[1].lowercase()
            require(stage == "negative" || stage == "print") {
                "CSV row ${index + 2}: stage must be negative or print"
            }
            require(
                (stage == "negative" && channel in setOf("r", "g", "b")) ||
                    (stage == "print" && channel == "neutral"),
            ) {
                "CSV row ${index + 2}: invalid channel '$channel' for $stage"
            }
            val logExposure = cells[2].toFloatOrNull()
                ?: throw IllegalArgumentException("CSV row ${index + 2}: invalid log_exposure")
            val density = cells[3].toFloatOrNull()
                ?: throw IllegalArgumentException("CSV row ${index + 2}: invalid density")
            grouped.getOrPut(stage to channel) { mutableListOf() }
                .add(DensityCurvePoint(logExposure, density))
        }

        fun required(stage: String, channel: String): SampledDensityCurve {
            val samples = grouped[stage to channel]
                ?: throw IllegalArgumentException("Missing $stage/$channel density curve")
            return SampledDensityCurve(samples, provenance)
        }

        val printSamples = grouped["print" to "neutral"]
        return ImportedCurves(
            negativeR = required("negative", "r"),
            negativeG = required("negative", "g"),
            negativeB = required("negative", "b"),
            print = printSamples?.let { SampledDensityCurve(it, provenance) },
        )
    }
}
