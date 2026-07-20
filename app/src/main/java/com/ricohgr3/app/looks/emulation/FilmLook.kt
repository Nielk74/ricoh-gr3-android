package com.ricohgr3.app.looks.emulation

/**
 * A film-emulation look: a 3D LUT plus the parametric spatial/tonal layers that a pointwise
 * colour map can't express (selective skin/foliage/sky colour, split toning, halation, grain).
 * Rendered on-device by [DevelopEngine]. See `research/FILM_EMULATION.md` §4.
 *
 * @property id stable identifier (asset key / persisted value).
 * @property displayName UI label.
 * @property lutAsset path under `assets/` to the `.cube` file, or `null` for no colour LUT
 *   (identity — useful for looks defined purely by the parametric layers).
 * @property splitTone shadow/highlight tint, applied after the LUT.
 * @property skinTone connected-region skin isolation and natural colour protection.
 * @property foliageTone selective vegetation-green colour adjustment; disabled by default.
 * @property skyTone top-connected blue-sky colour adjustment; disabled by default.
 * @property halation stock-coloured highlight bloom; [HalationParams.NONE] to disable.
 * @property grain film grain; [GrainParams.NONE] to disable.
 * @property swatchTop/[swatchBottom] a representative 2-stop gradient (ARGB `0xFFrrggbb`) for
 *   the picker chip — an at-a-glance hint of the stock's colour. Plain `Long`s (not Android
 *   `Color`) so this stays JVM-testable and Android-free.
 * @property lutInputGamma exponent applied to each channel **before** sampling [lutAsset], and
 *   the LUT output is taken as-is (display-referred). `1.0` = feed sRGB directly, which is what
 *   the hand-authored shipped stocks use. The field remains for any future, licensed asset whose
 *   documented input transfer differs.
 * @property adaptive scene-aware input/output protection. [AdaptiveParams.NONE] makes the LUT
 *   literal, which is useful for identity transforms and tests; shipped stocks enable it.
 */
data class FilmLook(
    val id: String,
    val displayName: String,
    val lutAsset: String?,
    val splitTone: SplitTone = SplitTone.NONE,
    val skinTone: SkinToneParams = SkinToneParams.NONE,
    val foliageTone: FoliageToneParams = FoliageToneParams.NONE,
    val skyTone: SkyToneParams = SkyToneParams.NONE,
    val halation: HalationParams = HalationParams.NONE,
    val grain: GrainParams = GrainParams.NONE,
    val swatchTop: Long = 0xFFECEAE6,
    val swatchBottom: Long = 0xFFCFCCC6,
    val lutInputGamma: Float = 1f,
    val adaptive: AdaptiveParams = AdaptiveParams.NONE,
)

/**
 * A restrained colour move for vegetation-like yellow-green and green pixels. The soft
 * hue/chroma/luminance gate leaves skin, neutrals, blue-cyan objects, deep shadows, and pale
 * highlights alone; the transform then preserves luminance. This is intentionally a local
 * foliage-colour response rather than a global green-channel or hue rotation.
 *
 * @property cyanShift how far eligible greens rotate toward cyan-green (`0` = disabled,
 *   `1` = reach the bounded cyan-green target).
 * @property saturationBoost additional chroma inside the same foliage mask (`0.2` = up to 20%
 *   at calibrated layer strength), gamut-compressed around the original luminance.
 */
data class FoliageToneParams(
    val cyanShift: Float,
    val saturationBoost: Float = 0f,
) {
    val enabled: Boolean get() = cyanShift > 0f || saturationBoost > 0f

    companion object {
        val NONE = FoliageToneParams(cyanShift = 0f, saturationBoost = 0f)
    }
}

/**
 * Selective skin-colour handling applied after the stock LUT and split tone. Detection is
 * spatially coherent rather than a global hue key, and the correction preserves rendered
 * luminance and all local texture.
 *
 * @property protection how strongly an excessive stock hue/chroma move is pulled back toward
 *   the captured complexion. Beneficial desaturation is deliberately retained.
 * @property naturalness strength of the soft saturation ceiling for strongly coloured light.
 * @property saturationCeiling normal upper saturation for detected skin; deep shadows receive
 *   a small extra allowance so warm-lit and dark complexions are not forced toward beige.
 */
data class SkinToneParams(
    val protection: Float,
    val naturalness: Float,
    val saturationCeiling: Float = 0.68f,
) {
    val enabled: Boolean get() = protection > 0f || naturalness > 0f

    companion object {
        val NONE = SkinToneParams(
            protection = 0f,
            naturalness = 0f,
        )
    }
}

/**
 * A restrained colour move applied only to blue regions connected to the top edge of the frame.
 * This is intentionally not a global blue-channel or hue rotation: the connectivity gate keeps
 * blue clothes, glasses, signs, and interior objects out of a sky-specific stock response.
 *
 * @property cyanShift how far eligible blue sky moves toward cyan (`0` = disabled, values around
 *   `0.2` are deliberately subtle).
 * @property saturationBoost additional chroma inside the connected-sky mask, applied without
 *   changing sky luminance.
 */
data class SkyToneParams(
    val cyanShift: Float,
    val saturationBoost: Float = 0f,
) {
    val enabled: Boolean get() = cyanShift > 0f || saturationBoost > 0f

    companion object {
        val NONE = SkyToneParams(cyanShift = 0f, saturationBoost = 0f)
    }
}

/**
 * Split toning: tint shadows and highlights toward different colours, blended by a
 * luminance weight. A hallmark of both the teal/orange cinematic look and faded "retro"
 * stocks. Tints are RGB in `[0,1]`; [amount] in `[0,1]` scales the whole effect.
 */
data class SplitTone(
    val shadowR: Float, val shadowG: Float, val shadowB: Float,
    val highR: Float, val highG: Float, val highB: Float,
    val amount: Float,
) {
    companion object {
        val NONE = SplitTone(0f, 0f, 0f, 0f, 0f, 0f, amount = 0f)
    }
}

/**
 * Halation: red-orange light scattered through the emulsion around bright edges. [threshold]
 * is the luminance where the smooth source mask begins; [radius] is the spread in pixels;
 * [strength] scales the edge spill; [tint] controls its red-dominant spectral colour. The
 * pipeline subtracts the source core before compositing, so this is a surrounding fringe rather
 * than a red wash over the highlight itself.
 */
data class HalationParams(
    val threshold: Float,
    val radius: Int,
    val strength: Float,
    val tintR: Float, val tintG: Float, val tintB: Float,
) {
    val enabled: Boolean get() = strength > 0f && radius > 0
    companion object {
        val NONE = HalationParams(threshold = 1f, radius = 0, strength = 0f,
            tintR = 1f, tintG = 0.45f, tintB = 0.2f)
    }
}

/**
 * Film grain — a physically-motivated model, not uniform digital noise. See
 * `research/FILM_EMULATION.md` and [DevelopPipeline.applyGrain]. A deterministic, non-tiling
 * coordinate field is correlated only across immediate neighbours, then perturbs
 * log-odds/optical-density-like luminance. A bounded non-Gaussian crystal term makes faster
 * stocks irregular rather than digitally uniform. The response peaks in the midtones, preserves
 * black/white endpoints, and can be biased toward shadows.
 *
 * @property amount overall density-variation strength.
 * @property size local crystal correlation (larger = slightly broader neighbouring structure;
 *   never a scaled texture or low-frequency cloudy octave).
 * @property shadowBias shifts the midtone-peaked density response toward the shadows
 *   (0 = symmetric hump centred on mid-grey; 1 = peak pushed well into the shadows). Grain
 *   still falls off in both the deepest shadows and the brightest highlights.
 * @property chroma fraction of a tiny luminance-neutral neighbour variation. It stays coupled to
 *   the luma crystal so it cannot turn into independent RGB sensor-noise speckles. 0 = monochrome.
 * @property clumping non-Gaussian density irregularity (`0` = very clean/fine grain; values around
 *   `0.2` give a fast stock more occasional dense crystals). This never introduces a blurred or
 *   low-frequency grain layer.
 * @property seed fixes the field for deterministic (testable, non-flickering) output.
 */
data class GrainParams(
    val amount: Float,
    val size: Float,
    val shadowBias: Float,
    val chroma: Float = 0.1f,
    val clumping: Float = 0.12f,
    val seed: Long = 0L,
) {
    val enabled: Boolean get() = amount > 0f
    companion object {
        val NONE = GrainParams(amount = 0f, size = 1f, shadowBias = 0f)
    }
}
