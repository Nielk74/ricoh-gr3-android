package com.ricohgr3.app.looks

import com.ricohgr3.app.wifi.CaptureParams

/**
 * The GR III / GR IIIx "looks" (Custom Image / effect presets), mapped to the exact
 * `effect` enum values the camera's Wi-Fi `/v1/params/camera` endpoint accepts.
 *
 * Source of truth: `docs/PHASE7-LOOKS.md` (grounded in the real camera enum definitions
 * in `research/references/ricoh-wireless-protocol/definitions/capture_ricoh_gr_iii.yaml`).
 *
 * [effect] is the literal `val` string sent to the camera, or `null` for [STANDARD],
 * which is the as-shot baseline (unset — no `effect` field is sent). These strings hit
 * real hardware, so they must match the spec exactly.
 *
 * Pure Kotlin: no Android dependencies, so the mapping is JVM-unit-testable.
 */
enum class CameraLook(val displayName: String, val effect: String?) {
    STANDARD("Standard", null),
    VIVID("Vivid", "col_vivid"),
    POSITIVE_FILM("Positive Film", "efc_posiFilm"),
    BLEACH_BYPASS("Bleach Bypass", "efc_bleachBypass"),
    RETRO("Retro", "efc_retro"),
    HDR_TONE("HDR Tone", "efc_HDRTone"),
    MONOCHROME("Monochrome", "efc_monochrome"),
    SOFT_MONOCHROME("Soft Monochrome", "efc_softMonochrome"),
    HARD_MONOCHROME("Hard Monochrome", "efc_hardMonochrome"),
    HIGH_CONTRAST("B&W (Hi-Contrast)", "efc_highContrast"),
    CUSTOM1("Custom 1", "col_custom1"),
    CUSTOM2("Custom 2", "col_custom2");

    /**
     * The `effect` string for `PUT /v1/params/camera`, or `null` for [STANDARD] (unset).
     * Alias for [effect]; provided as an explicit intent-revealing helper at call sites.
     */
    fun toEffectParam(): String? = effect

    /**
     * A [CaptureParams] carrying only this look's `effect`. For [STANDARD] the `effect`
     * field stays null, so it is dropped from the form body (leaving the camera baseline).
     */
    fun toCaptureParams(): CaptureParams = CaptureParams(effect = effect)

    companion object {
        /**
         * Resolve a camera `effect` string (as returned by `/v1/props`) back to a look.
         * `null` or unknown values map to [STANDARD].
         */
        fun fromEffect(effect: String?): CameraLook =
            entries.firstOrNull { it.effect == effect } ?: STANDARD
    }
}
