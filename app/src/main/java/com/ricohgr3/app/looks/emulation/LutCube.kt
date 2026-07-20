package com.ricohgr3.app.looks.emulation

/**
 * A 3D colour lookup table (`.cube`), the workhorse of the film-emulation engine.
 *
 * The cube partitions the RGB unit space into a [size]³ grid; each vertex stores an output
 * RGB triple. At runtime [sample] finds the cube a colour falls in and **trilinearly
 * interpolates** the 8 surrounding vertices — this captures tone, colour response, and
 * channel cross-talk in one pass, which per-channel curves cannot. See
 * `research/FILM_EMULATION.md` §2.
 *
 * Pure Kotlin, no Android deps → JVM-unit-testable (consistent with this repo: device/GPU
 * paths can't run in CI, so the colour math stays pure and verifiable).
 *
 * @property size grid resolution per axis (e.g. 17, 33, 64).
 * @property data flat vertex array, length `size*size*size*3`, laid out with **R fastest**
 *   then G then B (the `.cube` convention), values in `[0,1]`.
 * @property domainMin per-channel input represented by the first cube vertex.
 * @property domainMax per-channel input represented by the last cube vertex.
 */
class LutCube(
    val size: Int,
    val data: FloatArray,
    val domainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
    val domainMax: FloatArray = floatArrayOf(1f, 1f, 1f),
) {
    init {
        require(size >= 2) { "LUT size must be >= 2, was $size" }
        val expected = size * size * size * 3
        require(data.size == expected) {
            "LUT data length ${data.size} != expected $expected for size $size"
        }
        require(domainMin.size == 3 && domainMax.size == 3) {
            "LUT domains must contain exactly three channels"
        }
        for (channel in 0..2) {
            require(
                domainMin[channel].isFinite() &&
                    domainMax[channel].isFinite() &&
                    domainMax[channel] > domainMin[channel],
            ) {
                "Invalid LUT domain for channel $channel: " +
                    "${domainMin[channel]}..${domainMax[channel]}"
            }
        }
    }

    /** Index of the first component of vertex (r,g,b) in [data]. R varies fastest. */
    private fun vertexBase(r: Int, g: Int, b: Int): Int =
        ((b * size + g) * size + r) * 3

    /**
     * Trilinearly sample the table at ([rIn],[gIn],[bIn]). Each input is mapped through its
     * declared `DOMAIN_MIN`/`DOMAIN_MAX` and then clamped to the cube extent. The result is
     * written into [out] as `[r,g,b]`; [out] must have length >= 3.
     */
    fun sample(rIn: Float, gIn: Float, bIn: Float, out: FloatArray) {
        val max = size - 1
        fun coordinate(value: Float, channel: Int): Float =
            (
                (value - domainMin[channel]) /
                    (domainMax[channel] - domainMin[channel])
                ).coerceIn(0f, 1f) * max
        val r = coordinate(rIn, 0)
        val g = coordinate(gIn, 1)
        val b = coordinate(bIn, 2)

        val r0 = r.toInt().coerceAtMost(max - 1).coerceAtLeast(0)
        val g0 = g.toInt().coerceAtMost(max - 1).coerceAtLeast(0)
        val b0 = b.toInt().coerceAtMost(max - 1).coerceAtLeast(0)
        val r1 = r0 + 1
        val g1 = g0 + 1
        val b1 = b0 + 1

        val fr = r - r0
        val fg = g - g0
        val fb = b - b0

        var or = 0f; var og = 0f; var ob = 0f
        // Accumulate the 8 corners weighted by their fractional overlap.
        for (bi in 0..1) {
            val bIdx = if (bi == 0) b0 else b1
            val wb = if (bi == 0) 1f - fb else fb
            for (gi in 0..1) {
                val gIdx = if (gi == 0) g0 else g1
                val wg = if (gi == 0) 1f - fg else fg
                for (ri in 0..1) {
                    val rIdx = if (ri == 0) r0 else r1
                    val wr = if (ri == 0) 1f - fr else fr
                    val w = wr * wg * wb
                    if (w == 0f) continue
                    val base = vertexBase(rIdx, gIdx, bIdx)
                    or += data[base] * w
                    og += data[base + 1] * w
                    ob += data[base + 2] * w
                }
            }
        }
        out[0] = or
        out[1] = og
        out[2] = ob
    }

    companion object {
        /**
         * Parse an Adobe/Resolve `.cube` file body. Recognises `LUT_3D_SIZE`,
         * `DOMAIN_MIN`, and `DOMAIN_MAX`; skips comments (`#`) and `TITLE`; and reads `size³`
         * whitespace-separated RGB triples. `LUT_1D_SIZE` is rejected (this engine is 3D-only).
         *
         * @throws IllegalArgumentException on malformed input.
         */
        fun parse(text: String): LutCube {
            var size = -1
            val values = ArrayList<Float>()
            var domainMin = floatArrayOf(0f, 0f, 0f)
            var domainMax = floatArrayOf(1f, 1f, 1f)
            fun parseDomain(line: String, name: String): FloatArray {
                val parts = line.split(Regex("\\s+"))
                require(parts.size == 4) { "$name requires exactly three values" }
                return floatArrayOf(
                    parts[1].toFloat(),
                    parts[2].toFloat(),
                    parts[3].toFloat(),
                )
            }
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                when {
                    line.startsWith("LUT_3D_SIZE", ignoreCase = true) -> {
                        size = line.split(Regex("\\s+"))[1].toInt()
                    }
                    line.startsWith("LUT_1D_SIZE", ignoreCase = true) ->
                        throw IllegalArgumentException("1D LUTs are not supported")
                    line.startsWith("TITLE", ignoreCase = true) -> {}
                    line.startsWith("DOMAIN_MIN", ignoreCase = true) ->
                        domainMin = parseDomain(line, "DOMAIN_MIN")
                    line.startsWith("DOMAIN_MAX", ignoreCase = true) ->
                        domainMax = parseDomain(line, "DOMAIN_MAX")
                    line.startsWith("LUT_", ignoreCase = true) -> {}
                    else -> {
                        val parts = line.split(Regex("\\s+"))
                        if (parts.size == 3) {
                            values.add(parts[0].toFloat())
                            values.add(parts[1].toFloat())
                            values.add(parts[2].toFloat())
                        }
                    }
                }
            }
            require(size >= 2) { "Missing or invalid LUT_3D_SIZE" }
            val expected = size * size * size * 3
            require(values.size == expected) {
                "Expected $expected values for size $size, got ${values.size}"
            }
            return LutCube(size, values.toFloatArray(), domainMin, domainMax)
        }

        /** The identity LUT of the given [size] (output == input); a no-op passthrough. */
        fun identity(size: Int): LutCube {
            val max = (size - 1).toFloat()
            val data = FloatArray(size * size * size * 3)
            var i = 0
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                data[i++] = r / max
                data[i++] = g / max
                data[i++] = b / max
            }
            return LutCube(size, data)
        }
    }
}
