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
 * The names describe aesthetic emulations, not measured manufacturer profiles.
 */
object FilmLookCatalog {
    data class Entry(val look: FilmLook, val model: Model)

    private fun entry(
        id: String,
        name: String,
        top: Long,
        bottom: Long,
        model: Model,
        adaptive: AdaptiveParams = AdaptiveParams(),
        splitTone: SplitTone = SplitTone.NONE,
        skinTone: SkinToneParams = SkinToneParams.NONE,
        foliageTone: FoliageToneParams = FoliageToneParams.NONE,
        skyTone: SkyToneParams = SkyToneParams.NONE,
        halation: HalationParams = HalationParams.NONE,
        grain: GrainParams = GrainParams.NONE,
    ) = Entry(
        look = FilmLook(
            id = id,
            displayName = name,
            lutAsset = null,
            splitTone = splitTone,
            skinTone = skinTone,
            foliageTone = foliageTone,
            skyTone = skyTone,
            halation = halation,
            grain = grain,
            swatchTop = top,
            swatchBottom = bottom,
            adaptive = adaptive,
        ),
        model = model,
    )

    private fun grain(
        amount: Float,
        size: Float,
        clumping: Float,
        seed: Long,
    ) = GrainParams(
        amount = amount,
        size = size,
        shadowBias = 0.52f,
        chroma = 0.06f,
        clumping = clumping,
        seed = seed,
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

    val entries: List<Entry> = listOf(
        entry(
            id = "portra400",
            name = "Portra 400",
            top = 0xFFE8C7A9,
            bottom = 0xFF8C9A91,
            adaptive = AdaptiveParams(
                lookStrength = 0.82f,
                highlightProtection = 1f,
                saturationGuard = 0.95f,
            ),
            skinTone = skin(protection = 0.38f, naturalness = 0.45f),
            splitTone = SplitTone(
                shadowR = 0f, shadowG = 0.006f, shadowB = 0.016f,
                highR = 0.030f, highG = 0.017f, highB = 0f,
                amount = 0.78f,
            ),
            skyTone = SkyToneParams(cyanShift = 0.22f),
            foliageTone = FoliageToneParams(cyanShift = 0.14f),
            grain = grain(amount = 0.058f, size = 1.65f, clumping = 0.13f, seed = 400),
            model = Model(
                r = Channel(contrast = 0.23f, toe = 0.035f, shoulder = 0.82f, gain = 1.035f),
                g = Channel(contrast = 0.24f, toe = 0.025f, shoulder = 0.78f, gain = 1.0f),
                b = Channel(contrast = 0.27f, toe = 0.012f, shoulder = 0.70f, gain = 0.97f),
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
            adaptive = AdaptiveParams(
                lookStrength = 0.80f,
                highlightProtection = 1f,
                saturationGuard = 0.95f,
                grainScale = 0.92f,
            ),
            skinTone = skin(protection = 0.40f, naturalness = 0.48f),
            splitTone = SplitTone(
                shadowR = 0.003f, shadowG = 0.006f, shadowB = 0.017f,
                highR = 0.035f, highG = 0.018f, highB = 0f,
                amount = 0.82f,
            ),
            skyTone = SkyToneParams(cyanShift = 0.25f),
            foliageTone = FoliageToneParams(cyanShift = 0.16f),
            grain = grain(amount = 0.088f, size = 1.95f, clumping = 0.22f, seed = 800),
            model = Model(
                r = Channel(contrast = 0.28f, toe = 0.025f, shoulder = 0.78f, gain = 1.045f),
                g = Channel(contrast = 0.29f, toe = 0.018f, shoulder = 0.74f, gain = 1.0f),
                b = Channel(contrast = 0.32f, toe = 0f, shoulder = 0.66f, gain = 0.955f),
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
            adaptive = AdaptiveParams(
                lookStrength = 0.78f,
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
            adaptive = AdaptiveParams(
                lookStrength = 0.84f,
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
            adaptive = AdaptiveParams(
                lookStrength = 0.80f,
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
            adaptive = AdaptiveParams(
                lookStrength = 0.82f,
                shadowProtection = 0.9f,
                highlightProtection = 1f,
                saturationGuard = 1f,
                grainScale = 0.88f,
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
            adaptive = AdaptiveParams(
                lookStrength = 0.79f,
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
            adaptive = AdaptiveParams(
                lookStrength = 0.81f,
                shadowProtection = 0.92f,
                highlightProtection = 1f,
                saturationGuard = 0.95f,
                grainScale = 0.92f,
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
            adaptive = AdaptiveParams(
                lookStrength = 0.78f,
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
            adaptive = AdaptiveParams(
                lookStrength = 1f,
                autoExposure = 0.68f,
                shadowProtection = 0.62f,
                highlightProtection = 0.86f,
                saturationGuard = 0f,
                grainScale = 0.92f,
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
            adaptive = AdaptiveParams(
                lookStrength = 1f,
                autoExposure = 0.72f,
                shadowProtection = 0.82f,
                highlightProtection = 1f,
                saturationGuard = 0f,
                grainScale = 0.90f,
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
