package com.ricohgr3.app.looks.emulation

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a [FilmLookCatalog.Entry] into a render-ready ([FilmLook], [LutCube]) pair.
 *
 * Prefers a licensed film-stock `.cube` from `assets/` when [FilmLook.lutAsset] is set;
 * otherwise synthesises the LUT from the entry's [FilmLutFactory.Model]. Parsed LUTs are
 * cached by look id — a 33³ LUT is ~36 k floats, cheap to keep resident, and building/parsing
 * once avoids per-render cost. See `research/FILM_EMULATION.md` §4.
 */
class FilmLookLoader(private val appContext: Context) {

    private val cache = ConcurrentHashMap<String, LutCube>()

    /** The rendered ([FilmLook], [LutCube]) for [id], or null if unknown. */
    fun resolve(id: String): Pair<FilmLook, LutCube>? {
        val entry = FilmLookCatalog.entryFor(id) ?: return null
        val lut = cache.getOrPut(id) { loadLut(entry) }
        return entry.look to lut
    }

    /** Grain plate, decoded from assets once and cached. Null if the asset is missing/unreadable. */
    @Volatile private var grainTexture: GrainTexture? = null
    @Volatile private var grainLoaded = false

    /**
     * The shared film-grain plate (`assets/grain/grain_35mm.png`), or null if it can't be loaded
     * (in which case the pipeline falls back to synthesised grain). Decoded once and cached.
     */
    fun grainTexture(): GrainTexture? {
        if (grainLoaded) return grainTexture
        synchronized(this) {
            if (grainLoaded) return grainTexture
            grainTexture = runCatching {
                appContext.assets.open(GRAIN_ASSET).use { input ->
                    val bmp = android.graphics.BitmapFactory.decodeStream(input)
                        ?: return@runCatching null
                    val s = minOf(bmp.width, bmp.height)
                    val px = IntArray(s * s)
                    bmp.getPixels(px, 0, s, 0, 0, s, s)
                    bmp.recycle()
                    // Green channel as luminance (plate is grayscale).
                    val gray = IntArray(s * s) { (px[it] shr 8) and 0xFF }
                    GrainTexture.fromGray(s, gray)
                }
            }.getOrNull()
            grainLoaded = true
        }
        return grainTexture
    }

    private fun loadLut(entry: FilmLookCatalog.Entry): LutCube {
        val asset = entry.look.lutAsset
        if (asset != null) {
            runCatching {
                appContext.assets.open(asset).bufferedReader().use { LutCube.parse(it.readText()) }
            }.onSuccess { return it }
            // Fall through to procedural on any asset/parse failure — never crash a render.
        }
        return FilmLutFactory.build(entry.model)
    }

    private companion object {
        const val GRAIN_ASSET = "grain/grain_35mm.png"
    }
}
