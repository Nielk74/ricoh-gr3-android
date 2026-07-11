package com.ricohgr3.app.ui

import androidx.compose.ui.graphics.Color
import com.ricohgr3.app.looks.emulation.FilmLookCatalog

/**
 * A cheap two-stop swatch approximating each **film stock**, for the look picker chips and the
 * "before/after" placeholder. The colours come from each [FilmLookCatalog] entry's
 * `swatchTop`/`swatchBottom` (or the neutral Standard chip for `null`). Plain [Color] pairs, no
 * Android-radio deps, so they can be reasoned about without a device.
 */
object LookSwatch {

    /** [top] → [bottom] gradient stops that read as this stock at chip size. */
    data class Stops(val top: Color, val bottom: Color)

    /** Stops for a film-stock id, or the neutral Standard chip for `null`/unknown. */
    fun stopsFor(id: String?): Stops {
        val (top, bottom) = FilmLookCatalog.swatchFor(id)
        return Stops(Color(top.toInt()), Color(bottom.toInt()))
    }
}
