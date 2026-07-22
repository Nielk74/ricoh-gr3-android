package com.ricohgr3.app.looks.emulation

/**
 * Low-memory, resolution-independent approximation of the stock/process image-structure MTF.
 *
 * The diffusion radius is expressed on the physical film plane. At each output size a separable
 * three-tap kernel is chosen with the corresponding variance, so a 720 px preview and 3000 px
 * export represent the same emulsion radius instead of applying the same number of output pixels.
 * Work is performed in exact linear light and a constant field remains exactly constant.
 */
object FilmOptics {

    fun applyDiffusion(
        r: FloatArray,
        g: FloatArray,
        b: FloatArray,
        width: Int,
        height: Int,
        params: ImageStructureParams,
        filmFormat: FilmFormat,
        longEdgePixels: Int = maxOf(width, height),
    ) {
        require(width >= 0 && height >= 0)
        require(longEdgePixels >= maxOf(width, height))
        require(r.size == width * height && g.size == r.size && b.size == r.size)
        if (!params.enabled || r.isEmpty()) return

        val sigmaPixels = sigmaPixels(
            diffusionMicrometres = params.diffusionMicrometres,
            longEdgePixels = longEdgePixels,
            filmFormat = filmFormat,
        )
        val sideWeight = kernelSideWeight(sigmaPixels)
        if (sideWeight <= 1e-7f) return
        val scratch = FloatArray(r.size)
        blurChannel(r, width, height, sideWeight, params.strength, scratch)
        blurChannel(g, width, height, sideWeight, params.strength, scratch)
        blurChannel(b, width, height, sideWeight, params.strength, scratch)
    }

    /** Physical diffusion radius converted to samples at the current output resolution. */
    fun sigmaPixels(
        diffusionMicrometres: Float,
        longEdgePixels: Int,
        filmFormat: FilmFormat,
    ): Float {
        require(diffusionMicrometres.isFinite() && diffusionMicrometres >= 0f)
        require(longEdgePixels >= 0)
        val longEdgeMillimetres = maxOf(
            filmFormat.widthMillimetres,
            filmFormat.heightMillimetres,
        )
        return diffusionMicrometres / (longEdgeMillimetres * 1000f) * longEdgePixels
    }

    /**
     * Side coefficient for `[w, 1-2w, w]`. Its discrete variance is `2w`; the cap keeps the
     * compact approximation positive and deliberately restrained at unusually large outputs.
     */
    fun kernelSideWeight(sigmaPixels: Float): Float =
        (sigmaPixels * sigmaPixels * 0.5f).coerceIn(0f, 0.245f)

    private fun blurChannel(
        channel: FloatArray,
        width: Int,
        height: Int,
        sideWeight: Float,
        strength: Float,
        scratch: FloatArray,
    ) {
        val centerWeight = 1f - 2f * sideWeight
        // Decode exactly once per sample. The earlier direct formulation decoded overlapping
        // neighbours repeatedly (12 extra EOTFs/pixel across RGB), which was needlessly costly
        // beside the grain/halation passes. `channel` is safe temporary linear storage: the
        // complete horizontal result is in `scratch` before vertical output starts.
        for (i in channel.indices) channel[i] = ColorMath.srgbToLinear(channel[i])
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val left = row + (x - 1).coerceAtLeast(0)
                val center = row + x
                val right = row + (x + 1).coerceAtMost(width - 1)
                scratch[center] =
                    channel[left] * sideWeight +
                    channel[center] * centerWeight +
                    channel[right] * sideWeight
            }
        }
        for (y in 0 until height) {
            val previousRow = (y - 1).coerceAtLeast(0) * width
            val row = y * width
            val nextRow = (y + 1).coerceAtMost(height - 1) * width
            for (x in 0 until width) {
                val blurred =
                    scratch[previousRow + x] * sideWeight +
                    scratch[row + x] * centerWeight +
                    scratch[nextRow + x] * sideWeight
                val source = channel[row + x]
                channel[row + x] = ColorMath.linearToSrgb(
                    source + (blurred - source) * strength,
                ).coerceIn(0f, 1f)
            }
        }
    }
}
