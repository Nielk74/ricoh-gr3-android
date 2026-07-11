package com.ricohgr3.app.looks.emulation

import com.ricohgr3.app.looks.emulation.FilmLutFactory.Channel
import com.ricohgr3.app.looks.emulation.FilmLutFactory.Model
import com.ricohgr3.app.looks.emulation.FilmLutFactory.crossTalk

/**
 * The film-simulation set and their LUTs. These are the **Fujifilm film simulations** rendered
 * from real `.cube` 3D LUTs bundled under `assets/luts/` (from `abpy/FujifilmCameraProfiles`),
 * which give a genuine, high-quality colour response the earlier procedural LUTs couldn't match.
 *
 * Each entry points at its `.cube` via [FilmLook.lutAsset] (the loader prefers the asset and only
 * falls back to the procedural [Model] if the asset is missing/unparseable). The Fuji LUTs were
 * authored for a linear-ish camera-profile input and bake their own tone curve, so they set
 * [FilmLook.lutInputGamma] ≈ 1.6 — the pipeline pre-warps the sRGB input by that exponent so
 * mid-grey lands correctly (feeding raw sRGB washes mids; full linearisation crushes them).
 *
 * The spatial layers (grain, and halation where a stock warrants it) are layered around the LUT
 * as before. Pure Kotlin (params only); asset loading lives in the Android-side loader.
 */
object FilmLookCatalog {

    /** A look plus the colour model used to synthesise its LUT **only if the asset is missing**. */
    data class Entry(val look: FilmLook, val model: Model)

    /** Gamma the Fuji `.cube` LUTs expect on their (sRGB) input — see class kdoc. */
    private const val FUJI_INPUT_GAMMA = 1.6f

    /** Neutral fallback model (used only if a `.cube` asset fails to load). */
    private val fallbackColour = Model(
        r = Channel(contrast = 0.35f, shoulder = 0.6f),
        g = Channel(contrast = 0.35f, shoulder = 0.6f),
        b = Channel(contrast = 0.35f, shoulder = 0.6f),
        crossTalk = crossTalk(0.02f),
        saturation = 1.0f,
    )
    private val fallbackMono = Model(
        r = Channel(contrast = 0.45f, shoulder = 0.55f),
        g = Channel(contrast = 0.45f, shoulder = 0.55f),
        b = Channel(contrast = 0.45f, shoulder = 0.55f),
        saturation = 0f,
    )

    /** Build a Fuji-sim entry: asset-backed LUT + linear-input gamma + per-sim grain/swatch. */
    private fun fuji(
        id: String, name: String,
        swatchTop: Long, swatchBottom: Long,
        grain: GrainParams,
        halation: HalationParams = HalationParams.NONE,
        splitTone: SplitTone = SplitTone.NONE,
        model: Model = fallbackColour,
    ) = Entry(
        FilmLook(
            id = id, displayName = name, lutAsset = "luts/$id.cube",
            splitTone = splitTone, halation = halation, grain = grain,
            swatchTop = swatchTop, swatchBottom = swatchBottom,
            lutInputGamma = FUJI_INPUT_GAMMA,
        ),
        model,
    )

    val entries: List<Entry> = listOf(
        // Provia — the standard: neutral, balanced, faithful colour. Fine grain.
        fuji("provia", "Provia",
            swatchTop = 0xFFC9D2DA, swatchBottom = 0xFF6E7A86,
            grain = GrainParams(amount = 0.035f, size = 1.8f, shadowBias = 0.6f, seed = 10)),
        // Velvia — vivid, high-saturation, punchy landscape slide.
        fuji("velvia", "Velvia",
            swatchTop = 0xFFE0483A, swatchBottom = 0xFF1F6E4A,
            grain = GrainParams(amount = 0.03f, size = 1.6f, shadowBias = 0.65f, seed = 11)),
        // Astia — soft, gentle contrast, flattering skin.
        fuji("astia", "Astia",
            swatchTop = 0xFFE9CDBE, swatchBottom = 0xFF9E8478,
            grain = GrainParams(amount = 0.035f, size = 1.8f, shadowBias = 0.55f, seed = 12)),
        // Classic Chrome — muted, documentary, slightly desaturated with deep tones.
        fuji("classic_chrome", "Classic Chrome",
            swatchTop = 0xFFB7B0A2, swatchBottom = 0xFF56514A,
            grain = GrainParams(amount = 0.045f, size = 2f, shadowBias = 0.55f, seed = 13)),
        // Classic Neg — colour-negative character: crunchy, teal-shadowed, amber highlights.
        fuji("classic_neg", "Classic Neg",
            swatchTop = 0xFFD9A15A, swatchBottom = 0xFF3E5E5A,
            grain = GrainParams(amount = 0.05f, size = 2f, shadowBias = 0.5f, seed = 14)),
        // Nostalgic Neg — warm, amber, faded-album highlights with rich shadows.
        fuji("nostalgic_neg", "Nostalgic Neg",
            swatchTop = 0xFFD9B37A, swatchBottom = 0xFF6E5238,
            grain = GrainParams(amount = 0.045f, size = 2f, shadowBias = 0.5f, seed = 15)),
        // Pro Neg Hi — portrait negative, a touch more contrast.
        fuji("pro_neg_hi", "Pro Neg Hi",
            swatchTop = 0xFFDDC3B0, swatchBottom = 0xFF7A6656,
            grain = GrainParams(amount = 0.04f, size = 1.9f, shadowBias = 0.55f, seed = 16)),
        // Pro Neg Std — soft, low-contrast studio portrait negative.
        fuji("pro_neg_std", "Pro Neg Std",
            swatchTop = 0xFFE2D0C2, swatchBottom = 0xFF8C7A6C,
            grain = GrainParams(amount = 0.035f, size = 1.9f, shadowBias = 0.5f, seed = 17)),
        // Eterna — cinema stock: low saturation, gentle contrast, filmic. Soft halation.
        fuji("eterna", "Eterna",
            swatchTop = 0xFFB9BCB4, swatchBottom = 0xFF5E635E,
            grain = GrainParams(amount = 0.045f, size = 2.1f, shadowBias = 0.55f, seed = 18),
            halation = HalationParams(threshold = 0.8f, radius = 6, strength = 0.25f,
                tintR = 1f, tintG = 0.45f, tintB = 0.2f)),
        // Reala Ace — faithful colour with soft tonality; a modern balanced neg.
        fuji("reala_ace", "Reala Ace",
            swatchTop = 0xFFCFC9BE, swatchBottom = 0xFF6E6A60,
            grain = GrainParams(amount = 0.035f, size = 1.8f, shadowBias = 0.55f, seed = 19)),
        // Bleach Bypass — desaturated, high-contrast, silvery.
        fuji("bleach_bypass", "Bleach Bypass",
            swatchTop = 0xFFCDCFC9, swatchBottom = 0xFF3A3E3E,
            grain = GrainParams(amount = 0.055f, size = 2.1f, shadowBias = 0.5f, seed = 20),
            model = fallbackMono),
    )

    /** Look ids in catalog order. */
    val ids: List<String> get() = entries.map { it.look.id }

    fun entryFor(id: String): Entry? = entries.firstOrNull { it.look.id == id }

    fun lookFor(id: String?): FilmLook? = id?.let { entryFor(it)?.look }

    /** Display name for a stock id, or "Standard" for null/unknown (the as-shot baseline). */
    fun displayNameFor(id: String?): String = lookFor(id)?.displayName ?: STANDARD_NAME

    /** The [FilmLook.swatchTop]/[FilmLook.swatchBottom] for [id], or the neutral Standard chip. */
    fun swatchFor(id: String?): Pair<Long, Long> =
        lookFor(id)?.let { it.swatchTop to it.swatchBottom } ?: (STANDARD_TOP to STANDARD_BOTTOM)

    /**
     * The picker's ordered list of selectable stock ids, with `null` first for **Standard**
     * (the as-shot baseline / "no film look"). The gallery and viewer look strips iterate this
     * so the film stocks are what the user actually sees and taps.
     */
    val pickerIds: List<String?> get() = listOf<String?>(null) + ids

    const val STANDARD_NAME = "Standard"
    const val STANDARD_TOP = 0xFFECEAE6L
    const val STANDARD_BOTTOM = 0xFFCFCCC6L
}
