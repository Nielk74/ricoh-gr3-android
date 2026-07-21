package com.ricohgr3.app.looks.emulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmStockProfileTest {
    private val measured = ProfileProvenance(
        basis = CalibrationBasis.LAB_MEASURED,
        sourceTitle = "Unit-test sensitometry",
        sourceRevision = "fixture-1",
        notes = "Synthetic monotonic samples used to verify the import path.",
    )

    private fun sample(lut: LutCube, r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val out = FloatArray(3)
        lut.sample(r, g, b, out)
        return Triple(out[0], out[1], out[2])
    }

    @Test fun opticalDensityIsNegativeLog10Transmittance() {
        val density = OpticalDensity.of(2f)
        assertEquals(0.01f, density.transmittance(), 0.000001f)
        assertEquals(2f, OpticalDensity.fromTransmittance(0.01f).value, 0.00001f)
    }

    @Test fun sampledCurveInterpolatesAndClampsInLogExposure() {
        val curve = SampledDensityCurve(
            points = listOf(
                DensityCurvePoint(-3f, 0.12f),
                DensityCurvePoint(-1f, 0.72f),
                DensityCurvePoint(0f, 2.12f),
            ),
            provenance = measured,
        )
        assertEquals(0.12f, curve.densityAt(-4f).value, 0.0001f)
        assertEquals(0.42f, curve.densityAt(-2f).value, 0.0001f)
        assertEquals(2.12f, curve.densityAt(1f).value, 0.0001f)
    }

    @Test fun sampledCurveRejectsOutOfOrderOrNonMonotonicData() {
        assertThrows(IllegalArgumentException::class.java) {
            SampledDensityCurve(
                listOf(
                    DensityCurvePoint(-2f, 0.2f),
                    DensityCurvePoint(-1f, 0.1f),
                ),
                measured,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SampledDensityCurve(
                listOf(
                    DensityCurvePoint(-1f, 0.1f),
                    DensityCurvePoint(-1f, 0.2f),
                ),
                measured,
            )
        }
    }

    @Test fun csvImportsThreeNegativeLayersAndIndependentPrintCurve() {
        val csv = """
            stage,channel,log_exposure,density
            negative,r,-3,0.12
            negative,r,0,2.20
            negative,g,-3,0.12
            negative,g,0,2.10
            negative,b,-3,0.12
            negative,b,0,2.00
            print,neutral,-3,0.05
            print,neutral,0,1.80
        """.trimIndent()
        val curves = FilmDensityProfileCsv.parse(csv, measured)
        assertEquals(2.20f, curves.negativeR.points.last().density, 0.0001f)
        assertEquals(2.10f, curves.negativeG.points.last().density, 0.0001f)
        assertEquals(2.00f, curves.negativeB.points.last().density, 0.0001f)
        assertNotNull(curves.print)
    }

    @Test fun csvRequiresLabMeasuredProvenanceAndAllNegativeLayers() {
        val incomplete = """
            stage,channel,log_exposure,density
            negative,r,-3,0.12
            negative,r,0,2.20
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) {
            FilmDensityProfileCsv.parse(incomplete, measured)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FilmDensityProfileCsv.parse(
                incomplete,
                measured.copy(basis = CalibrationBasis.MANUFACTURER_ANCHORED),
            )
        }
    }

    @Test fun measuredNegativeAndPrintCurvesBuildAMonotonicPositive() {
        val csv = """
            stage,channel,log_exposure,density
            negative,r,-3,0.10
            negative,r,-1,0.80
            negative,r,0,2.30
            negative,g,-3,0.10
            negative,g,-1,0.80
            negative,g,0,2.30
            negative,b,-3,0.10
            negative,b,-1,0.80
            negative,b,0,2.30
            print,neutral,-3,0.06
            print,neutral,-1,0.55
            print,neutral,0,1.70
        """.trimIndent()
        val curves = FilmDensityProfileCsv.parse(csv, measured)
        val model = FilmLutFactory.withMeasuredCurves(
            model = FilmLutFactory.Model(),
            curves = curves,
            printLogExposureOffset = 0.2f,
        )
        val lut = FilmLutFactory.build(model)
        val dark = sample(lut, 0.1f, 0.1f, 0.1f).first
        val middle = sample(lut, 0.5f, 0.5f, 0.5f).first
        val light = sample(lut, 0.9f, 0.9f, 0.9f).first
        assertTrue("measured positive must remain ordered: $dark, $middle, $light", dark < middle)
        assertTrue("measured positive must remain ordered: $dark, $middle, $light", middle < light)
    }

    @Test fun measuredCurvesMustFitDeclaredMaterialDensityRange() {
        val tooDense = """
            stage,channel,log_exposure,density
            negative,r,-3,0.10
            negative,r,0,7.00
            negative,g,-3,0.10
            negative,g,0,7.00
            negative,b,-3,0.10
            negative,b,0,7.00
        """.trimIndent()
        val curves = FilmDensityProfileCsv.parse(tooDense, measured)
        assertThrows(IllegalArgumentException::class.java) {
            FilmLutFactory.withMeasuredCurves(FilmLutFactory.Model(), curves)
        }
    }

    @Test fun catalogProfilesDeclareTheirActualCalibrationBasisAndNeverClaimLabMeasurement() {
        for (entry in FilmLookCatalog.entries) {
            val profile = entry.model.profile
            assertEquals(entry.look.id, profile.stockId)
            val expected = if (entry.look.id == "portra400" || entry.look.id == "portra800") {
                CalibrationBasis.MANUFACTURER_DIGITIZED
            } else {
                CalibrationBasis.MANUFACTURER_ANCHORED
            }
            assertEquals("${entry.look.id} calibration basis", expected, profile.provenance.basis)
            assertTrue(
                "${entry.look.id} must not claim an unperformed lab calibration",
                profile.provenance.basis != CalibrationBasis.LAB_MEASURED,
            )
            assertTrue(profile.process.isNotBlank())
            assertTrue(profile.printOrScan.isNotBlank())
            assertTrue(profile.negative.dyeCapacity.r > 0f)
        }
    }

    @Test fun manufacturerCharacteristicAnchorPreservesEndpointsReferenceGreyAndOrdering() {
        val anchor = PortraSensitometry.PORTRA_400.redAnchor(influence = 1f)
        val samples = (0..100).map { anchor.normalizedDensity(it / 100f) }

        assertEquals(0f, samples.first(), 0f)
        assertEquals(1f, samples.last(), 0f)
        assertEquals(0.18f, anchor.normalizedDensity(0.18f), 0.0001f)
        assertTrue(
            "digitized characteristic response must remain monotonic",
            samples.zipWithNext().all { (first, second) -> second >= first },
        )
    }

    @Test fun portraProfilesCarryDistinctJanuary2025StatusMCurvesAndAbsoluteDensities() {
        val p400 = PortraSensitometry.PORTRA_400
        val p800 = PortraSensitometry.PORTRA_800

        assertEquals(CalibrationBasis.MANUFACTURER_DIGITIZED, p400.provenance.basis)
        assertEquals(CalibrationBasis.MANUFACTURER_DIGITIZED, p800.provenance.basis)
        assertEquals("January 2025", p400.provenance.sourceRevision)
        assertEquals("January 2025", p800.provenance.sourceRevision)
        assertTrue(p400.provenance.sourceUrl!!.endsWith("e4050.pdf"))
        assertTrue(p800.provenance.sourceUrl!!.endsWith("e4040.pdf"))
        assertTrue("PORTRA 800 blue base/fog is higher in the published plot",
            p800.negative.baseFog.b > p400.negative.baseFog.b)
        assertTrue("the two graph shapes must not collapse to one response",
            kotlin.math.abs(
                p800.blueAnchor(1f).normalizedDensity(0.50f) -
                    p400.blueAnchor(1f).normalizedDensity(0.50f),
            ) > 0.004f)
    }

    @Test fun triXAndHp5UseDistinctPanchromaticCaptureResponses() {
        val triX = FilmLookCatalog.entryFor("trix400")!!.model
        val hp5 = FilmLookCatalog.entryFor("hp5")!!.model
        assertNotNull(triX.monochromeCapture)
        assertNotNull(hp5.monochromeCapture)
        assertTrue(triX.profile.developer!!.contains("D-76"))
        assertTrue(hp5.profile.developer!!.contains("ID-11"))

        val triXRed = triX.monochromeCapture!!.exposure(1f, 0f, 0f)
        val hp5Red = hp5.monochromeCapture!!.exposure(1f, 0f, 0f)
        assertTrue("TRI-X and HP5 red capture must differ", triXRed > hp5Red)

        val triXLut = FilmLutFactory.build(triX)
        val hp5Lut = FilmLutFactory.build(hp5)
        val triXRendered = sample(triXLut, 0.85f, 0.20f, 0.12f)
        val hp5Rendered = sample(hp5Lut, 0.85f, 0.20f, 0.12f)
        assertEquals(triXRendered.first, triXRendered.second, 0.001f)
        assertEquals(hp5Rendered.first, hp5Rendered.second, 0.001f)
        assertTrue(
            "stock-specific capture must visibly separate a warm patch",
            kotlin.math.abs(triXRendered.first - hp5Rendered.first) > 0.01f,
        )
    }
}
