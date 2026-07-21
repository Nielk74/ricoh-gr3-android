package com.ricohgr3.app.looks.emulation

/**
 * Graph-digitized PORTRA sensitometry from Kodak E-4050/E-4040, January 2025 revisions.
 *
 * Curves were sampled from the Status-M daylight characteristic plots at approximately 0.3–0.5
 * log-H intervals. Plot line width and manual coordinate reading imply roughly ±0.05 density-unit
 * uncertainty. They are deliberately labelled [CalibrationBasis.MANUFACTURER_DIGITIZED], never
 * laboratory measured. The absolute densities also replace the former shared neutral base/fog
 * metadata; rendering uses only their bounded relative shape through
 * [ManufacturerCharacteristicAnchor].
 */
internal object PortraSensitometry {
    data class CurveSet(
        val referenceLogExposure: Float,
        val negative: NegativeMaterial,
        val provenance: ProfileProvenance,
        val red: SampledDensityCurve,
        val green: SampledDensityCurve,
        val blue: SampledDensityCurve,
    ) {
        fun redAnchor(influence: Float) = anchor(red, influence)
        fun greenAnchor(influence: Float) = anchor(green, influence)
        fun blueAnchor(influence: Float) = anchor(blue, influence)

        private fun anchor(curve: SampledDensityCurve, influence: Float) =
            ManufacturerCharacteristicAnchor(
                curve = curve,
                referenceLogExposure = referenceLogExposure,
                influence = influence,
            )
    }

    val PORTRA_400 = curveSet(
        title = "KODAK PROFESSIONAL PORTRA 400 Film Technical Data E-4050",
        url = "https://www.kodakprofessional.com/sites/default/files/2025-07/e4050.pdf",
        revision = "January 2025",
        referenceLogExposure = -1.44f,
        red = listOf(
            -3.5f to 0.22f, -3.0f to 0.23f, -2.7f to 0.28f, -2.4f to 0.43f,
            -2.0f to 0.67f, -1.5f to 0.98f, -1.0f to 1.28f, -0.5f to 1.58f,
            0.0f to 1.84f, 0.55f to 2.02f,
        ),
        green = listOf(
            -3.5f to 0.67f, -3.0f to 0.68f, -2.7f to 0.73f, -2.4f to 0.88f,
            -2.0f to 1.10f, -1.5f to 1.40f, -1.0f to 1.70f, -0.5f to 1.98f,
            0.0f to 2.25f, 0.55f to 2.45f,
        ),
        blue = listOf(
            -3.5f to 0.85f, -3.0f to 0.86f, -2.7f to 0.93f, -2.4f to 1.10f,
            -2.0f to 1.34f, -1.5f to 1.66f, -1.0f to 1.96f, -0.5f to 2.26f,
            0.0f to 2.62f, 0.55f to 3.03f,
        ),
    )

    val PORTRA_800 = curveSet(
        title = "KODAK PROFESSIONAL PORTRA 800 Film Technical Data E-4040",
        url = "https://www.kodakprofessional.com/sites/default/files/2025-07/e4040.pdf",
        revision = "January 2025",
        referenceLogExposure = -1.74f,
        red = listOf(
            -3.5f to 0.28f, -3.0f to 0.30f, -2.7f to 0.36f, -2.4f to 0.50f,
            -2.0f to 0.75f, -1.5f to 1.05f, -1.0f to 1.35f, -0.5f to 1.64f,
            0.0f to 1.90f, 0.25f to 1.98f,
        ),
        green = listOf(
            -3.5f to 0.73f, -3.0f to 0.75f, -2.7f to 0.82f, -2.4f to 0.98f,
            -2.0f to 1.22f, -1.5f to 1.52f, -1.0f to 1.82f, -0.5f to 2.10f,
            0.0f to 2.38f, 0.25f to 2.47f,
        ),
        blue = listOf(
            -3.5f to 1.05f, -3.0f to 1.07f, -2.7f to 1.15f, -2.4f to 1.32f,
            -2.0f to 1.56f, -1.5f to 1.85f, -1.0f to 2.15f, -0.5f to 2.48f,
            0.0f to 2.83f, 0.25f to 2.95f,
        ),
    )

    private fun curveSet(
        title: String,
        url: String,
        revision: String,
        referenceLogExposure: Float,
        red: List<Pair<Float, Float>>,
        green: List<Pair<Float, Float>>,
        blue: List<Pair<Float, Float>>,
    ): CurveSet {
        val provenance = ProfileProvenance(
            basis = CalibrationBasis.MANUFACTURER_DIGITIZED,
            sourceTitle = title,
            sourceUrl = url,
            sourceRevision = revision,
            notes = "Status-M daylight characteristic curves digitized from the publication " +
                "with approximately ±0.05 density-unit reading uncertainty; relative shape is " +
                "blended conservatively for display-referred input.",
        )
        fun curve(points: List<Pair<Float, Float>>) = SampledDensityCurve(
            points = points.map { (logExposure, density) ->
                DensityCurvePoint(logExposure, density)
            },
            provenance = provenance,
        )
        val redCurve = curve(red)
        val greenCurve = curve(green)
        val blueCurve = curve(blue)
        return CurveSet(
            referenceLogExposure = referenceLogExposure,
            negative = NegativeMaterial(
                baseFog = DensityTriplet(
                    redCurve.points.first().density,
                    greenCurve.points.first().density,
                    blueCurve.points.first().density,
                ),
                dyeCapacity = DensityTriplet(
                    redCurve.points.last().density - redCurve.points.first().density,
                    greenCurve.points.last().density - greenCurve.points.first().density,
                    blueCurve.points.last().density - blueCurve.points.first().density,
                ),
            ),
            provenance = provenance,
            red = redCurve,
            green = greenCurve,
            blue = blueCurve,
        )
    }
}
