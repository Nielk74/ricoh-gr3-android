package com.ricohgr3.app.looks.emulation

import com.ricohgr3.app.looks.emulation.FilmLutFactory.Channel
import com.ricohgr3.app.looks.emulation.FilmLutFactory.Model
import com.ricohgr3.app.looks.emulation.FilmLutFactory.PrintStage
import com.ricohgr3.app.looks.emulation.FilmLutFactory.crossTalk

/**
 * A small, purposefully differentiated film catalog for GR III files.
 *
 * These looks are hand-authored, licence-clean print transforms rather than unidentified LUT
 * packs. Their static colour character is only half of the result: every entry also carries
 * [AdaptiveParams] and [SkinToneParams], so [DevelopPipeline] can protect highlights, low-key
 * intent, natural complexions, existing lighting colour, and high-ISO texture.
 *
 * The names describe aesthetic emulations, not lab-measured manufacturer profiles. Every entry
 * carries manufacturer/process provenance and plausible optical-density bounds so it is ready to
 * accept measured H-D data, but its built-in rendering coefficients remain visual fits.
 */
object FilmLookCatalog {
    data class Entry(val look: FilmLook, val model: Model)

    private fun entry(
        id: String,
        name: String,
        top: Long,
        bottom: Long,
        model: Model,
        stock: StockRenderParams = StockRenderParams(),
        adaptive: AdaptiveParams = AdaptiveParams(),
        splitTone: SplitTone = SplitTone.NONE,
        skinTone: SkinToneParams = SkinToneParams.NONE,
        foliageTone: FoliageToneParams = FoliageToneParams.NONE,
        skyTone: SkyToneParams = SkyToneParams.NONE,
        imageStructure: ImageStructureParams = imageStructure(id),
        halation: HalationParams = HalationParams.NONE,
        grain: GrainParams = GrainParams.NONE,
        whitePointRecovery: WhitePointRecoveryParams = WhitePointRecoveryParams.NONE,
    ): Entry {
        val profile = stockProfile(id)
        return Entry(
            look = FilmLook(
                id = id,
                displayName = name,
                lutAsset = null,
                colorBalance = profile.colorBalance,
                splitTone = splitTone,
                skinTone = skinTone,
                foliageTone = foliageTone,
                skyTone = skyTone,
                imageStructure = imageStructure,
                halation = halation,
                grain = grain,
                whitePointRecovery = whitePointRecovery,
                swatchTop = top,
                swatchBottom = bottom,
                stock = stock,
                adaptive = adaptive,
            ),
            model = model.copy(
                profile = profile,
                monochromeCapture = monochromeCapture(id),
            ),
        )
    }

    /**
     * Qualitative image-structure anchors from published stock MTF/sharpness families. These are
     * deliberately weak because the input JPEG/rendered DNG already contains lens, demosaic, and
     * camera-sharpening response. They are not lab-measured fits.
     */
    private fun imageStructure(id: String): ImageStructureParams = when (id) {
        "ektar100" -> ImageStructureParams(4.5f, 0.08f)
        "gold200" -> ImageStructureParams(7.0f, 0.12f)
        "portra400" -> ImageStructureParams(6.5f, 0.11f)
        "portra800" -> ImageStructureParams(8.2f, 0.14f)
        "superia400" -> ImageStructureParams(6.5f, 0.10f)
        "cinestill800t" -> ImageStructureParams(8.2f, 0.15f)
        "vision3_250d" -> ImageStructureParams(6.0f, 0.10f)
        "vision3_500t" -> ImageStructureParams(8.0f, 0.13f)
        "eterna" -> ImageStructureParams(8.0f, 0.14f)
        "trix400" -> ImageStructureParams(5.5f, 0.08f)
        "hp5" -> ImageStructureParams(7.0f, 0.10f)
        else -> ImageStructureParams.NONE
    }

    private fun grain(
        amount: Float,
        size: Float,
        clumping: Float,
        seed: Long,
        smoothAreaBoost: Float = 0f,
        detailSuppression: Float = 0f,
        highlightPersistence: Float = 0f,
    ) = GrainParams(
        amount = amount,
        size = size,
        shadowBias = 0.52f,
        chroma = 0.06f,
        clumping = clumping,
        seed = seed,
        smoothAreaBoost = smoothAreaBoost,
        detailSuppression = detailSuppression,
        highlightPersistence = highlightPersistence,
    )

    private fun skin(
        protection: Float,
        naturalness: Float,
        saturationCeiling: Float = 0.68f,
    ) = SkinToneParams(
        protection = protection,
        naturalness = naturalness,
        saturationCeiling = saturationCeiling,
    )

    private fun print(
        contrast: Float,
        toe: Float,
        shoulder: Float,
        exposureEv: Float = 0f,
        biasR: Float = 0f,
        biasG: Float = 0f,
        biasB: Float = 0f,
        blackPoint: Float = 0.0005f,
        paperWhite: Float = 0.992f,
    ) = PrintStage(
        contrast = contrast,
        toe = toe,
        shoulder = shoulder,
        exposureEv = exposureEv,
        biasR = biasR,
        biasG = biasG,
        biasB = biasB,
        blackPoint = blackPoint,
        paperWhite = paperWhite,
    )

    private val colorNegative = NegativeMaterial(
        baseFog = DensityTriplet.neutral(0.12f),
        dyeCapacity = DensityTriplet.neutral(2.65f),
    )
    private val cinemaNegative = NegativeMaterial(
        baseFog = DensityTriplet.neutral(0.15f),
        dyeCapacity = DensityTriplet.neutral(2.80f),
    )
    private val blackAndWhiteNegative = NegativeMaterial(
        baseFog = DensityTriplet.neutral(0.18f),
        dyeCapacity = DensityTriplet.neutral(2.20f),
    )

    private fun manufacturerAnchor(
        title: String,
        url: String? = null,
        revision: String? = null,
        note: String,
    ) = ProfileProvenance(
        basis = CalibrationBasis.MANUFACTURER_ANCHORED,
        sourceTitle = title,
        sourceUrl = url,
        sourceRevision = revision,
        notes = "$note Built-in coefficients are manufacturer-anchored visual fits, not " +
            "measurements made with this app's camera, process, or scanner.",
    )

    private fun stockProfile(id: String): FilmStockProfile = when (id) {
        "portra400" -> FilmStockProfile(
            stockId = id,
            materialName = "KODAK PROFESSIONAL PORTRA 400 Film",
            kind = FilmMaterialKind.COLOR_NEGATIVE,
            colorBalance = FilmColorBalance.DAYLIGHT,
            process = "C-41",
            negative = PortraSensitometry.PORTRA_400.negative,
            printOrScan = "Generic restrained color-negative print/scan positive",
            provenance = PortraSensitometry.PORTRA_400.provenance,
        )
        "portra800" -> FilmStockProfile(
            stockId = id,
            materialName = "KODAK PROFESSIONAL PORTRA 800 Film",
            kind = FilmMaterialKind.COLOR_NEGATIVE,
            colorBalance = FilmColorBalance.DAYLIGHT,
            process = "C-41",
            negative = PortraSensitometry.PORTRA_800.negative,
            printOrScan = "Generic restrained color-negative print/scan positive",
            provenance = PortraSensitometry.PORTRA_800.provenance,
        )
        "gold200" -> FilmStockProfile(
            stockId = id,
            materialName = "KODAK GOLD 200 Film",
            kind = FilmMaterialKind.COLOR_NEGATIVE,
            colorBalance = FilmColorBalance.DAYLIGHT,
            process = "C-41",
            negative = colorNegative,
            printOrScan = "Generic consumer color-negative print/scan positive",
            provenance = manufacturerAnchor(
                title = "KODAK GOLD 200 Film Technical Data E-7022",
                url = "https://kodakprofessional.com/sites/default/files/wysiwyg/pro/resources/" +
                    "E7022_Gold_200.pdf",
                note = "Published sensitometry, spectral, MTF, and granularity plots are " +
                    "qualitative anchors.",
            ),
        )
        "ektar100" -> FilmStockProfile(
            stockId = id,
            materialName = "KODAK PROFESSIONAL EKTAR 100 Film",
            kind = FilmMaterialKind.COLOR_NEGATIVE,
            colorBalance = FilmColorBalance.DAYLIGHT,
            process = "C-41",
            negative = colorNegative,
            printOrScan = "Generic high-saturation color-negative print/scan positive",
            provenance = manufacturerAnchor(
                title = "KODAK PROFESSIONAL EKTAR 100 Film Technical Data E-4046",
                url = "https://www.kodakprofessional.com/sites/default/files/wysiwyg/pro/" +
                    "resources/e4046_ektar_100.pdf",
                revision = "February 2016",
                note = "Published sensitometry, spectral, MTF, and granularity plots are " +
                    "qualitative anchors.",
            ),
        )
        "superia400" -> FilmStockProfile(
            stockId = id,
            materialName = "FUJICOLOR SUPERIA X-TRA 400",
            kind = FilmMaterialKind.COLOR_NEGATIVE,
            colorBalance = FilmColorBalance.DAYLIGHT,
            process = "CN-16 / C-41 compatible",
            negative = colorNegative,
            printOrScan = "Generic consumer color-negative print/scan positive",
            provenance = manufacturerAnchor(
                title = "FUJICOLOR SUPERIA X-TRA 400 Data Sheet",
                url = "https://asset.fujifilm.com/www/us/files/2025-06/" +
                    "8abba3dd9d004f44d1e9c7fdbdf5c520/" +
                    "films_superia-xtra400_datasheet_01.pdf",
                revision = "AF3-0217E",
                note = "Published characteristic, spectral-sensitivity, dye-density, MTF, and " +
                    "RMS granularity plots are qualitative anchors.",
            ),
        )
        "cinestill800t" -> FilmStockProfile(
            stockId = id,
            materialName = "CineStill 800T",
            kind = FilmMaterialKind.COLOR_NEGATIVE,
            colorBalance = FilmColorBalance.TUNGSTEN,
            process = "C-41, rem-jet-free motion-picture-derived stock",
            negative = colorNegative,
            printOrScan = "Generic C-41 scan positive",
            provenance = manufacturerAnchor(
                title = "CineStill 800T process guidance and KODAK VISION3 500T technical data",
                url = "https://cinestillfilm.com/blogs/news/cinestill-800t-in-your-toolbox",
                note = "The stock/process combination is treated separately from native ECN-2 " +
                    "VISION3 and remains an aesthetic interpretation.",
            ),
        )
        "vision3_250d" -> FilmStockProfile(
            stockId = id,
            materialName = "KODAK VISION3 250D Color Negative Film 5207/7207",
            kind = FilmMaterialKind.MOTION_PICTURE_COLOR_NEGATIVE,
            colorBalance = FilmColorBalance.DAYLIGHT,
            process = "ECN-2",
            negative = cinemaNegative,
            printOrScan = "Generic motion-picture print/scan positive",
            provenance = manufacturerAnchor(
                title = "KODAK VISION3 250D Color Negative Film Technical Information",
                url = "https://www.kodak.com/content/products-brochures/motion-picture/" +
                    "KODAK-VISION3-250D-5207-7207-technical-information.pdf",
                revision = "March 2026",
                note = "Published sensitometry, spectral, MTF, and granularity plots are " +
                    "qualitative anchors; printer-light calibration is not yet measured.",
            ),
        )
        "vision3_500t" -> FilmStockProfile(
            stockId = id,
            materialName = "KODAK VISION3 500T Color Negative Film 5219/7219",
            kind = FilmMaterialKind.MOTION_PICTURE_COLOR_NEGATIVE,
            colorBalance = FilmColorBalance.TUNGSTEN,
            process = "ECN-2, 3200 K",
            negative = cinemaNegative,
            printOrScan = "Generic motion-picture print/scan positive",
            provenance = manufacturerAnchor(
                title = "KODAK VISION3 500T Color Negative Film Technical Information",
                url = "https://www.kodak.com/content/products-brochures/motion-picture/" +
                    "KODAK-VISION3-5219-7219-technical-information.pdf",
                revision = "March 2026",
                note = "Published sensitometry, spectral, MTF, and density-dependent RMS " +
                    "granularity are qualitative anchors.",
            ),
        )
        "eterna" -> FilmStockProfile(
            stockId = id,
            materialName = "FUJIFILM ETERNA cinema-family aesthetic target",
            kind = FilmMaterialKind.MOTION_PICTURE_COLOR_NEGATIVE,
            colorBalance = FilmColorBalance.UNSPECIFIED,
            process = "Motion-picture color negative process",
            negative = cinemaNegative,
            printOrScan = "Generic low-contrast cinema print/scan positive",
            provenance = manufacturerAnchor(
                title = "FUJIFILM ETERNA family technical publications",
                note = "This is a family-level cinema aesthetic target rather than one selected " +
                    "emulsion, batch, printer-light setup, or print stock.",
            ),
        )
        "trix400" -> FilmStockProfile(
            stockId = id,
            materialName = "KODAK PROFESSIONAL TRI-X 400 Film / 400TX",
            kind = FilmMaterialKind.BLACK_AND_WHITE_NEGATIVE,
            colorBalance = FilmColorBalance.MONOCHROME,
            process = "Black-and-white negative",
            developer = "KODAK PROFESSIONAL D-76, 1:1 reference process",
            negative = blackAndWhiteNegative,
            printOrScan = "Neutral grade-2-like positive interpretation",
            provenance = manufacturerAnchor(
                title = "KODAK PROFESSIONAL TRI-X 320 and 400 Films Technical Data F-4017",
                url = "https://www.kodakprofessional.com/sites/default/files/wysiwyg/film/" +
                    "f4017_trix_320400.pdf",
                note = "Published spectral sensitivity and D-76 characteristic curves are " +
                    "qualitative anchors; development time/agitation are not simulated.",
            ),
        )
        "hp5" -> FilmStockProfile(
            stockId = id,
            materialName = "ILFORD HP5 PLUS",
            kind = FilmMaterialKind.BLACK_AND_WHITE_NEGATIVE,
            colorBalance = FilmColorBalance.MONOCHROME,
            process = "Black-and-white negative",
            developer = "ILFORD ID-11, 1+1 reference process",
            negative = blackAndWhiteNegative,
            printOrScan = "Neutral grade-2-like positive interpretation",
            provenance = manufacturerAnchor(
                title = "ILFORD HP5 PLUS Technical Information",
                url = "https://www.ilfordphoto.com/amfile/file/download/file/1903/product/691/",
                note = "Published spectral sensitivity and ID-11 characteristic curves are " +
                    "qualitative anchors; development time/agitation are not simulated.",
            ),
        )
        else -> error("No stock profile metadata for '$id'")
    }

    private fun monochromeCapture(id: String): MonochromeCaptureResponse? = when (id) {
        "trix400" -> MonochromeCaptureResponse(
            // Three-band camera-primary approximation to the broad 400TX panchromatic curve.
            redWeight = 0.29f,
            greenWeight = 0.58f,
            blueWeight = 0.13f,
            referenceIlluminant = "daylight",
            provenance = manufacturerAnchor(
                title = "KODAK TRI-X 320/400 spectral sensitivity, F-4017",
                url = "https://www.kodakprofessional.com/sites/default/files/wysiwyg/film/" +
                    "f4017_trix_320400.pdf",
                note = "RGB weights approximate the published spectral graph; they are not " +
                    "spectrometer-derived sensitivities.",
            ),
        )
        "hp5" -> MonochromeCaptureResponse(
            // Slightly greener three-band response than TRI-X, conservatively fitted by eye.
            redWeight = 0.24f,
            greenWeight = 0.64f,
            blueWeight = 0.12f,
            referenceIlluminant = "daylight",
            provenance = manufacturerAnchor(
                title = "ILFORD HP5 PLUS spectral sensitivity",
                url = "https://www.ilfordphoto.com/amfile/file/download/file/1903/product/691/",
                note = "RGB weights approximate the published spectral graph; they are not " +
                    "spectrometer-derived sensitivities.",
            ),
        )
        else -> null
    }

    val entries: List<Entry> = listOf(
        entry(
            id = "portra400",
            name = "Portra 400",
            top = 0xFFE8C7A9,
            bottom = 0xFF8C9A91,
            stock = StockRenderParams(lookStrength = 0.82f),
            adaptive = AdaptiveParams(
                highlightProtection = 1f,
                saturationGuard = 0.95f,
                grainTextureGuard = 0f,
            ),
            skinTone = skin(protection = 0.38f, naturalness = 0.45f),
            splitTone = SplitTone(
                shadowR = 0f, shadowG = 0.006f, shadowB = 0.016f,
                highR = 0.030f, highG = 0.017f, highB = 0f,
                amount = 0.78f,
            ),
            skyTone = SkyToneParams(
                cyanShift = 0.22f,
                saturationBoost = 0.20f,
            ),
            foliageTone = FoliageToneParams(
                cyanShift = 0.56f,
                saturationBoost = 0.20f,
            ),
            grain = grain(
                // Keep the smooth-tone result from the first 35 mm pass, but obtain it through
                // local visibility rather than a frame-wide amplitude lift: defocus/sky remains
                // clear while focused detail drops below the former calibration.
                amount = 0.060f,
                size = 2.15f,
                clumping = 0.16f,
                seed = 400,
                smoothAreaBoost = 0.35f,
                detailSuppression = 0.45f,
                highlightPersistence = 0.35f,
            ),
            whitePointRecovery = WhitePointRecoveryParams(amount = 0.78f),
            model = Model(
                r = Channel(
                    contrast = 0.23f, toe = 0.035f, shoulder = 0.82f, gain = 1.035f,
                    manufacturerCurveAnchor = PortraSensitometry.PORTRA_400.redAnchor(0.30f),
                ),
                g = Channel(
                    contrast = 0.24f, toe = 0.025f, shoulder = 0.78f, gain = 1.0f,
                    manufacturerCurveAnchor = PortraSensitometry.PORTRA_400.greenAnchor(0.30f),
                ),
                b = Channel(
                    contrast = 0.27f, toe = 0.012f, shoulder = 0.70f, gain = 0.97f,
                    manufacturerCurveAnchor = PortraSensitometry.PORTRA_400.blueAnchor(0.30f),
                ),
                crossTalk = crossTalk(0.025f, warm = 0.008f),
                print = print(
                    contrast = 0.92f,
                    toe = 0.10f,
                    shoulder = 0.44f,
                    exposureEv = 0.02f,
                ),
                saturation = 0.91f,
            ),
        ),
        entry(
            id = "portra800",
            name = "Portra 800",
            top = 0xFFE2B98F,
            bottom = 0xFF776F68,
            stock = StockRenderParams(lookStrength = 0.80f, grainScale = 0.92f),
            adaptive = AdaptiveParams(
                highlightProtection = 1f,
                saturationGuard = 0.95f,
                grainTextureGuard = 0f,
            ),
            skinTone = skin(protection = 0.40f, naturalness = 0.48f),
            splitTone = SplitTone(
                shadowR = 0.003f, shadowG = 0.006f, shadowB = 0.017f,
                highR = 0.035f, highG = 0.018f, highB = 0f,
                amount = 0.82f,
            ),
            skyTone = SkyToneParams(
                cyanShift = 0.25f,
                saturationBoost = 0.24f,
            ),
            foliageTone = FoliageToneParams(
                cyanShift = 0.62f,
                saturationBoost = 0.24f,
            ),
            grain = grain(
                amount = 0.092f,
                size = 2.55f,
                clumping = 0.25f,
                seed = 800,
                smoothAreaBoost = 0.36f,
                detailSuppression = 0.44f,
                highlightPersistence = 0.42f,
            ),
            whitePointRecovery = WhitePointRecoveryParams(amount = 0.82f),
            model = Model(
                r = Channel(
                    contrast = 0.28f, toe = 0.025f, shoulder = 0.78f, gain = 1.045f,
                    manufacturerCurveAnchor = PortraSensitometry.PORTRA_800.redAnchor(0.34f),
                ),
                g = Channel(
                    contrast = 0.29f, toe = 0.018f, shoulder = 0.74f, gain = 1.0f,
                    manufacturerCurveAnchor = PortraSensitometry.PORTRA_800.greenAnchor(0.34f),
                ),
                b = Channel(
                    contrast = 0.32f, toe = 0f, shoulder = 0.66f, gain = 0.955f,
                    manufacturerCurveAnchor = PortraSensitometry.PORTRA_800.blueAnchor(0.34f),
                ),
                crossTalk = crossTalk(0.028f, warm = 0.010f),
                print = print(
                    contrast = 0.94f,
                    toe = 0.11f,
                    shoulder = 0.40f,
                    exposureEv = 0.015f,
                ),
                saturation = 0.94f,
            ),
        ),
        entry(
            id = "gold200",
            name = "Gold 200",
            top = 0xFFF0C36F,
            bottom = 0xFF9B7248,
            stock = StockRenderParams(lookStrength = 0.78f),
            adaptive = AdaptiveParams(
                highlightProtection = 0.95f,
                saturationGuard = 0.9f,
            ),
            skinTone = skin(protection = 0.36f, naturalness = 0.46f),
            splitTone = SplitTone(
                shadowR = 0.018f, shadowG = 0.010f, shadowB = 0f,
                highR = 0.040f, highG = 0.027f, highB = 0f,
                amount = 0.82f,
            ),
            grain = grain(amount = 0.031f, size = 1.75f, clumping = 0.15f, seed = 200),
            model = Model(
                r = Channel(contrast = 0.31f, toe = 0.038f, shoulder = 0.70f, gain = 1.05f),
                g = Channel(contrast = 0.31f, toe = 0.026f, shoulder = 0.68f, gain = 1.005f),
                b = Channel(contrast = 0.34f, toe = 0.010f, shoulder = 0.62f, gain = 0.93f),
                crossTalk = crossTalk(0.032f, warm = 0.014f),
                print = print(
                    contrast = 0.98f,
                    toe = 0.08f,
                    shoulder = 0.34f,
                    exposureEv = 0.01f,
                ),
                saturation = 1.01f,
            ),
        ),
        entry(
            id = "ektar100",
            name = "Ektar 100",
            top = 0xFFE75B51,
            bottom = 0xFF246B70,
            stock = StockRenderParams(lookStrength = 0.84f),
            adaptive = AdaptiveParams(
                shadowProtection = 0.72f,
                highlightProtection = 1f,
                saturationGuard = 1f,
            ),
            skinTone = skin(
                protection = 0.58f,
                naturalness = 0.68f,
                saturationCeiling = 0.64f,
            ),
            grain = grain(amount = 0.018f, size = 1.25f, clumping = 0.06f, seed = 100),
            model = Model(
                r = Channel(contrast = 0.39f, toe = -0.012f, shoulder = 0.78f, gain = 1.015f),
                g = Channel(contrast = 0.38f, toe = -0.010f, shoulder = 0.80f, gain = 1.0f),
                b = Channel(contrast = 0.40f, toe = -0.014f, shoulder = 0.82f, gain = 1.0f),
                crossTalk = crossTalk(0.018f),
                print = print(
                    contrast = 1.06f,
                    toe = -0.02f,
                    shoulder = 0.32f,
                    exposureEv = -0.01f,
                    blackPoint = 0.0002f,
                    paperWhite = 0.996f,
                ),
                saturation = 1.14f,
            ),
        ),
        entry(
            id = "superia400",
            name = "Superia 400",
            top = 0xFF70B89B,
            bottom = 0xFF47778F,
            stock = StockRenderParams(lookStrength = 0.80f),
            adaptive = AdaptiveParams(
                highlightProtection = 0.9f,
                saturationGuard = 0.95f,
            ),
            skinTone = skin(protection = 0.48f, naturalness = 0.54f),
            splitTone = SplitTone(
                shadowR = 0f, shadowG = 0.012f, shadowB = 0.020f,
                highR = 0.004f, highG = 0.018f, highB = 0.008f,
                amount = 0.70f,
            ),
            grain = grain(amount = 0.034f, size = 1.75f, clumping = 0.16f, seed = 404),
            model = Model(
                r = Channel(contrast = 0.32f, toe = 0.005f, shoulder = 0.68f, gain = 0.985f),
                g = Channel(contrast = 0.31f, toe = 0.015f, shoulder = 0.72f, gain = 1.025f),
                b = Channel(contrast = 0.33f, toe = 0.010f, shoulder = 0.68f, gain = 1.005f),
                crossTalk = floatArrayOf(
                    0.955f, 0.050f, -0.005f,
                    0.018f, 0.964f, 0.018f,
                    0.002f, 0.045f, 0.953f,
                ),
                print = print(
                    contrast = 1.01f,
                    toe = 0.04f,
                    shoulder = 0.32f,
                ),
                saturation = 1.07f,
            ),
        ),
        entry(
            id = "cinestill800t",
            name = "CineStill 800T",
            top = 0xFFCF594B,
            bottom = 0xFF355F83,
            stock = StockRenderParams(lookStrength = 0.82f, grainScale = 0.88f),
            adaptive = AdaptiveParams(
                shadowProtection = 0.9f,
                highlightProtection = 1f,
                saturationGuard = 1f,
            ),
            skinTone = skin(protection = 0.48f, naturalness = 0.54f),
            splitTone = SplitTone(
                shadowR = 0f, shadowG = 0.012f, shadowB = 0.040f,
                highR = 0.026f, highG = 0.010f, highB = 0f,
                amount = 0.88f,
            ),
            halation = HalationParams(
                threshold = 0.78f,
                radius = 11,
                strength = 1.05f,
                tintR = 1f, tintG = 0.006f, tintB = 0.012f,
            ),
            grain = grain(amount = 0.046f, size = 2.05f, clumping = 0.25f, seed = 801),
            model = Model(
                r = Channel(contrast = 0.28f, toe = 0.014f, shoulder = 0.78f, gain = 0.94f),
                g = Channel(contrast = 0.27f, toe = 0.024f, shoulder = 0.82f, gain = 1.0f),
                b = Channel(contrast = 0.29f, toe = 0.035f, shoulder = 0.86f, gain = 1.07f),
                crossTalk = crossTalk(0.025f),
                print = print(
                    contrast = 0.96f,
                    toe = 0.09f,
                    shoulder = 0.42f,
                    exposureEv = 0.01f,
                ),
                saturation = 0.98f,
            ),
        ),
        entry(
            id = "vision3_250d",
            name = "Vision3 250D",
            top = 0xFFD8B486,
            bottom = 0xFF567A7A,
            stock = StockRenderParams(lookStrength = 0.79f),
            adaptive = AdaptiveParams(
                shadowProtection = 0.9f,
                highlightProtection = 1f,
                saturationGuard = 0.9f,
            ),
            skinTone = skin(protection = 0.38f, naturalness = 0.44f),
            splitTone = SplitTone(
                shadowR = 0f, shadowG = 0.010f, shadowB = 0.025f,
                highR = 0.026f, highG = 0.014f, highB = 0f,
                amount = 0.74f,
            ),
            halation = HalationParams(
                threshold = 0.77f,
                radius = 6,
                strength = 0.22f,
                tintR = 1f, tintG = 0.28f, tintB = 0.09f,
            ),
            grain = grain(amount = 0.024f, size = 1.55f, clumping = 0.10f, seed = 250),
            model = Model(
                r = Channel(contrast = 0.19f, toe = 0.035f, shoulder = 0.90f, gain = 1.018f),
                g = Channel(contrast = 0.19f, toe = 0.032f, shoulder = 0.90f, gain = 1.0f),
                b = Channel(contrast = 0.21f, toe = 0.025f, shoulder = 0.86f, gain = 0.978f),
                crossTalk = crossTalk(0.035f, warm = 0.006f),
                print = print(
                    contrast = 0.88f,
                    toe = 0.14f,
                    shoulder = 0.56f,
                    exposureEv = 0.03f,
                ),
                saturation = 0.94f,
            ),
        ),
        entry(
            id = "vision3_500t",
            name = "Vision3 500T",
            top = 0xFF4D7897,
            bottom = 0xFFC88759,
            stock = StockRenderParams(lookStrength = 0.81f, grainScale = 0.92f),
            adaptive = AdaptiveParams(
                shadowProtection = 0.92f,
                highlightProtection = 1f,
                saturationGuard = 0.95f,
            ),
            skinTone = skin(protection = 0.44f, naturalness = 0.50f),
            splitTone = SplitTone(
                shadowR = 0f, shadowG = 0.014f, shadowB = 0.036f,
                highR = 0.036f, highG = 0.017f, highB = 0f,
                amount = 0.82f,
            ),
            halation = HalationParams(
                threshold = 0.75f,
                radius = 7,
                strength = 0.30f,
                tintR = 1f, tintG = 0.14f, tintB = 0.035f,
            ),
            grain = grain(amount = 0.034f, size = 1.85f, clumping = 0.18f, seed = 500),
            model = Model(
                r = Channel(contrast = 0.20f, toe = 0.038f, shoulder = 0.90f, gain = 0.96f),
                g = Channel(contrast = 0.19f, toe = 0.035f, shoulder = 0.92f, gain = 1.0f),
                b = Channel(contrast = 0.21f, toe = 0.042f, shoulder = 0.88f, gain = 1.05f),
                crossTalk = crossTalk(0.036f, warm = 0.006f),
                print = print(
                    contrast = 0.90f,
                    toe = 0.15f,
                    shoulder = 0.55f,
                    exposureEv = 0.02f,
                ),
                saturation = 0.92f,
            ),
        ),
        entry(
            id = "eterna",
            name = "Eterna Cinema",
            top = 0xFFB9B9A9,
            bottom = 0xFF505D5C,
            stock = StockRenderParams(lookStrength = 0.78f),
            adaptive = AdaptiveParams(
                shadowProtection = 0.95f,
                highlightProtection = 1f,
                saturationGuard = 0.85f,
            ),
            skinTone = skin(protection = 0.34f, naturalness = 0.40f),
            splitTone = SplitTone(
                shadowR = 0f, shadowG = 0.008f, shadowB = 0.018f,
                highR = 0.018f, highG = 0.010f, highB = 0.003f,
                amount = 0.62f,
            ),
            halation = HalationParams(
                threshold = 0.79f,
                radius = 6,
                strength = 0.15f,
                tintR = 1f, tintG = 0.24f, tintB = 0.08f,
            ),
            grain = grain(amount = 0.025f, size = 1.65f, clumping = 0.10f, seed = 18),
            model = Model(
                r = Channel(contrast = 0.15f, toe = 0.050f, shoulder = 0.96f, gain = 1.005f),
                g = Channel(contrast = 0.15f, toe = 0.048f, shoulder = 0.98f, gain = 1.0f),
                b = Channel(contrast = 0.17f, toe = 0.045f, shoulder = 0.94f, gain = 0.99f),
                crossTalk = crossTalk(0.035f),
                print = print(
                    contrast = 0.82f,
                    toe = 0.19f,
                    shoulder = 0.65f,
                    exposureEv = 0.05f,
                    blackPoint = 0.001f,
                    paperWhite = 0.986f,
                ),
                saturation = 0.82f,
            ),
        ),
        entry(
            id = "trix400",
            name = "Tri-X 400",
            top = 0xFFE7E5DF,
            bottom = 0xFF171717,
            stock = StockRenderParams(lookStrength = 1f, grainScale = 0.92f),
            adaptive = AdaptiveParams(
                autoExposure = 0.68f,
                shadowProtection = 0.62f,
                highlightProtection = 0.86f,
                saturationGuard = 0f,
            ),
            grain = GrainParams(
                amount = 0.056f, size = 2.1f, shadowBias = 0.58f, chroma = 0f,
                clumping = 0.30f, seed = 320,
            ),
            model = Model(
                r = Channel(contrast = 0.46f, toe = -0.018f, shoulder = 0.72f),
                g = Channel(contrast = 0.46f, toe = -0.018f, shoulder = 0.72f),
                b = Channel(contrast = 0.46f, toe = -0.018f, shoulder = 0.72f),
                print = print(
                    contrast = 1.11f,
                    toe = -0.04f,
                    shoulder = 0.28f,
                    exposureEv = -0.02f,
                    blackPoint = 0.0002f,
                    paperWhite = 0.996f,
                ),
                saturation = 0f,
            ),
        ),
        entry(
            id = "hp5",
            name = "HP5 Plus",
            top = 0xFFE2DFD7,
            bottom = 0xFF363636,
            stock = StockRenderParams(lookStrength = 1f, grainScale = 0.90f),
            adaptive = AdaptiveParams(
                autoExposure = 0.72f,
                shadowProtection = 0.82f,
                highlightProtection = 1f,
                saturationGuard = 0f,
            ),
            grain = GrainParams(
                amount = 0.050f, size = 2.0f, shadowBias = 0.54f, chroma = 0f,
                clumping = 0.26f, seed = 405,
            ),
            model = Model(
                r = Channel(contrast = 0.30f, toe = 0.025f, shoulder = 0.86f),
                g = Channel(contrast = 0.30f, toe = 0.025f, shoulder = 0.86f),
                b = Channel(contrast = 0.30f, toe = 0.025f, shoulder = 0.86f),
                print = print(
                    contrast = 0.98f,
                    toe = 0.08f,
                    shoulder = 0.44f,
                    exposureEv = 0.02f,
                    blackPoint = 0.001f,
                    paperWhite = 0.988f,
                ),
                saturation = 0f,
            ),
        ),
    )

    private val legacyAliases = mapOf(
        "provia" to "vision3_250d",
        "velvia" to "ektar100",
        "astia" to "portra400",
        "classic_chrome" to "vision3_250d",
        "classic_neg" to "superia400",
        "nostalgic_neg" to "gold200",
        "pro_neg_hi" to "portra800",
        "pro_neg_std" to "portra400",
        "reala_ace" to "portra400",
        "bleach_bypass" to "trix400",
    )

    val ids: List<String> get() = entries.map { it.look.id }

    fun entryFor(id: String): Entry? {
        val canonical = legacyAliases[id] ?: id
        return entries.firstOrNull { it.look.id == canonical }
    }

    fun lookFor(id: String?): FilmLook? = id?.let { entryFor(it)?.look }

    fun displayNameFor(id: String?): String = lookFor(id)?.displayName ?: STANDARD_NAME

    fun swatchFor(id: String?): Pair<Long, Long> =
        lookFor(id)?.let { it.swatchTop to it.swatchBottom } ?: (STANDARD_TOP to STANDARD_BOTTOM)

    val pickerIds: List<String?> get() = listOf<String?>(null) + ids

    const val STANDARD_NAME = "Standard"
    const val STANDARD_TOP = 0xFFECEAE6L
    const val STANDARD_BOTTOM = 0xFFCFCCC6L
}
