package com.ricohgr3.app.looks.emulation

/**
 * A tileable, monochrome **film-grain plate** — a real scanned-style grain texture sampled and
 * composited over the image, rather than grain synthesised per pixel. This is how production
 * film-emulation tools get convincing grain: a fixed grain "plate" is overlaid (soft-light),
 * tiled to cover the frame. It looks like film because the plate *is* film-grain structure, not
 * band-limited noise re-derived every render.
 *
 * The plate is loaded from a PNG under `assets/grain/` (Android) or the same file on disk (JVM
 * preview).
 * Values are the grain deviation around mid-grey, pre-centred to roughly `[-1, 1]` (0 = no
 * deviation), row-major, [size]×[size]. Pure data — no Android deps, so both the on-device
 * engine and the JVM preview renderer share the exact same grain.
 *
 * @property size edge length in pixels (square, tileable).
 * @property data length `size*size`, grain deviation ~`[-1,1]`, mean ~0.
 */
class GrainTexture(val size: Int, val data: FloatArray) {
    init {
        require(size >= 2) { "grain texture size must be >= 2, was $size" }
        require(data.size == size * size) { "grain data ${data.size} != ${size * size}" }
    }

    /** Sample the plate at integer pixel ([x],[y]) with wrap-around tiling. */
    fun at(x: Int, y: Int): Float {
        val xs = ((x % size) + size) % size
        val ys = ((y % size) + size) % size
        return data[ys * size + xs]
    }

    companion object {
        /**
         * Build a [GrainTexture] from an 8-bit grayscale plate. [gray] is row-major luminance in
         * `[0,255]` (mid-grey 128 = zero deviation); it is centred and scaled to ~`[-1,1]`.
         */
        fun fromGray(size: Int, gray: IntArray): GrainTexture {
            val data = FloatArray(size * size)
            for (i in data.indices) data[i] = (gray[i] - 128) / 100f // ~±1.3 at the extremes
            return GrainTexture(size, data)
        }
    }
}
