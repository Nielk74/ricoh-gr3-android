package com.ricohgr3.app.looks.emulation

import android.graphics.Bitmap

/**
 * Android glue over the pure [DevelopPipeline]: marshals a [Bitmap] into planar RGB float
 * buffers, runs the film-develop math, and returns a new [Bitmap]. All the colour science
 * lives in [DevelopPipeline] (JVM-testable); this file only touches pixels.
 *
 * CPU path — correct on every supported device (min SDK 26) and fine for the export/download
 * latency budget. A GPU/AGSL preview fast-path (SDK 33+) can be added later without changing
 * this API. See `research/FILM_EMULATION.md` §3.
 */
object DevelopEngine {

    /**
     * Render [look] onto [src] (using its parsed [lut]) and return a new ARGB_8888 bitmap.
     * [src] is not modified. Runs synchronously on the calling thread — call it off the main
     * thread (e.g. `withContext(Dispatchers.Default)`).
     */
    fun render(src: Bitmap, look: FilmLook, lut: LutCube): Bitmap {
        val w = src.width
        val h = src.height
        val n = w * h
        val pixels = IntArray(n)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val rr = FloatArray(n)
        val gg = FloatArray(n)
        val bb = FloatArray(n)
        for (i in 0 until n) {
            val p = pixels[i]
            rr[i] = ((p shr 16) and 0xFF) / 255f
            gg[i] = ((p shr 8) and 0xFF) / 255f
            bb[i] = (p and 0xFF) / 255f
        }

        DevelopPipeline.apply(rr, gg, bb, w, h, look, lut)

        for (i in 0 until n) {
            val a = pixels[i] and 0xFF000000.toInt()
            val r = (rr[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val g = (gg[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val b = (bb[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
            pixels[i] = a or (r shl 16) or (g shl 8) or b
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}
