package com.ricohgr3.app.looks.emulation

import com.ricohgr3.app.looks.CameraLook

/**
 * Maps each camera-side [CameraLook] (the in-camera `effect` enum) to the client-side film
 * emulation used when *developing/exporting* an already-captured frame. This lets the edited
 * export render a real film develop ([DevelopEngine]) instead of a flat indicative tint, while
 * the camera keeps producing its own authentic JPEGs for future captures.
 *
 * A `null` mapping means "no client-side film equivalent" — the export should fall back to the
 * honest gradient-tint preview (or the untouched original for Standard). See
 * `research/FILM_EMULATION.md` §5.
 */
object CameraLookMapping {

    /** The film-emulation look id for [look], or null to use the non-film fallback. */
    fun filmLookId(look: CameraLook): String? = when (look) {
        CameraLook.STANDARD -> null
        CameraLook.VIVID -> "velvia"
        CameraLook.POSITIVE_FILM -> "portra400"
        CameraLook.RETRO -> "retrofade"
        CameraLook.BLEACH_BYPASS -> "cinestill800t"
        CameraLook.MONOCHROME,
        CameraLook.SOFT_MONOCHROME,
        CameraLook.HARD_MONOCHROME,
        CameraLook.HIGH_CONTRAST -> "trix400"
        CameraLook.HDR_TONE,
        CameraLook.CUSTOM1,
        CameraLook.CUSTOM2 -> null
    }
}
