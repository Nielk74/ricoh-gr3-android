package com.ricohgr3.app.ui

import androidx.compose.ui.graphics.Color
import com.ricohgr3.app.looks.CameraLook

/**
 * A cheap two-stop swatch approximating each [CameraLook], for the look picker chips and the
 * "before/after" placeholder. These are *indicative* tints — not the camera's real JPEG
 * rendering (that needs captured samples or the Phase 7.3 develop engine). Kept as plain
 * [Color] pairs, no Android deps, so they can be reasoned about without a device.
 */
object LookSwatch {

    /** [top] → [bottom] gradient stops that read as this look at chip size. */
    data class Stops(val top: Color, val bottom: Color)

    fun stopsFor(look: CameraLook): Stops = when (look) {
        CameraLook.STANDARD -> Stops(Color(0xFFECEAE6), Color(0xFFCFCCC6))
        CameraLook.VIVID -> Stops(Color(0xFFFF7A00), Color(0xFFC81E5A))
        CameraLook.POSITIVE_FILM -> Stops(Color(0xFF3E8E7E), Color(0xFFE8B23A))
        CameraLook.BLEACH_BYPASS -> Stops(Color(0xFFCDCFC9), Color(0xFF7C8080))
        CameraLook.RETRO -> Stops(Color(0xFFD8B98A), Color(0xFF9C6B3F))
        CameraLook.HDR_TONE -> Stops(Color(0xFF6DA9D6), Color(0xFFE0C15A))
        CameraLook.MONOCHROME -> Stops(Color(0xFFDDDDDD), Color(0xFF3A3A3A))
        CameraLook.SOFT_MONOCHROME -> Stops(Color(0xFFE4E2DE), Color(0xFF6E6C68))
        CameraLook.HARD_MONOCHROME -> Stops(Color(0xFFF2F2F2), Color(0xFF141414))
        CameraLook.HIGH_CONTRAST -> Stops(Color(0xFFFFFFFF), Color(0xFF000000))
        CameraLook.CUSTOM1 -> Stops(Color(0xFF8E7BE0), Color(0xFF3A2E7A))
        CameraLook.CUSTOM2 -> Stops(Color(0xFF6BB6A0), Color(0xFF2E6A5A))
    }
}
