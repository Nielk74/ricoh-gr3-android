package com.ricohgr3.app.looks.emulation

/**
 * A film-emulation look: a 3D LUT plus the parametric spatial/tonal layers that a pointwise
 * colour map can't express (split toning, halation, grain). Rendered on-device by
 * [DevelopEngine]. See `research/FILM_EMULATION.md` §4.
 *
 * @property id stable identifier (asset key / persisted value).
 * @property displayName UI label.
 * @property lutAsset path under `assets/` to the `.cube` file, or `null` for no colour LUT
 *   (identity — useful for looks defined purely by the parametric layers).
 * @property splitTone shadow/highlight tint, applied after the LUT.
 * @property halation red-orange highlight bloom; [Halation.NONE] to disable.
 * @property grain film grain; [Grain.NONE] to disable.
 */
data class FilmLook(
    val id: String,
    val displayName: String,
    val lutAsset: String?,
    val splitTone: SplitTone = SplitTone.NONE,
    val halation: HalationParams = HalationParams.NONE,
    val grain: GrainParams = GrainParams.NONE,
)

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
 * Halation: red-orange bloom bleeding out of bright edges (film's anti-halation layer
 * failing at highlights). [threshold] is the luminance above which pixels bloom; [radius]
 * the blur spread in pixels; [strength] the add-back intensity; [tint] the bloom colour.
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
 * Film grain: luminance-weighted, spatially-correlated monochrome noise. [amount] scales
 * amplitude; [size] blurs the noise field (larger = coarser grain); [shadowBias] makes
 * grain more visible in mid/shadow structure, mirroring real film. [seed] fixes the field
 * for deterministic (testable, non-flickering) output.
 */
data class GrainParams(
    val amount: Float,
    val size: Float,
    val shadowBias: Float,
    val seed: Long = 0L,
) {
    val enabled: Boolean get() = amount > 0f
    companion object {
        val NONE = GrainParams(amount = 0f, size = 1f, shadowBias = 0f)
    }
}
