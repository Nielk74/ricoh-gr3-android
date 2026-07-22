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
     *
     * [preGrade] (optional) is a mild base grade applied before the film look, used for a
     * platform-rendered DNG whose display rendition is flatter than a camera JPEG (see
     * `PhotoSave`). [iso] lets Smart intent avoid stacking excessive emulsion grain over sensor
     * noise.
     * [effectStrength] is the editor's 0–150% intensity control (`1f` = authored stock baseline).
     */
    fun render(
        src: Bitmap,
        look: FilmLook,
        lut: LutCube,
        preGrade: DevelopPipeline.PreGrade? = null,
        iso: Int? = null,
        effectStrength: Float = 1f,
        options: DevelopOptions = DevelopOptions(),
    ): Bitmap {
        val faceRegions = if (
            options.intent == RenderingIntent.SMART &&
            look.skinTone.enabled &&
            effectStrength > 0f
        ) {
            FaceRegionDetector.detect(src)
        } else {
            emptyList()
        }
        val pixels = renderPixels(
            src = src,
            look = look,
            lut = lut,
            preGrade = preGrade,
            iso = iso,
            effectStrength = effectStrength,
            options = options,
            faceRegions = faceRegions,
        )
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
        return out
    }

    /**
     * Render one decoded region and copy only its overlap-free core into [destination]. Keeping the
     * destination bitmap alive while discarding each region's float planes is what makes a
     * full-dimension export feasible on a small Android heap.
     */
    internal fun renderRegionInto(
        src: Bitmap,
        destination: Bitmap,
        sourceX: Int,
        sourceY: Int,
        coreWidth: Int,
        coreHeight: Int,
        destinationX: Int,
        destinationY: Int,
        look: FilmLook,
        lut: LutCube,
        preGrade: DevelopPipeline.PreGrade? = null,
        iso: Int? = null,
        effectStrength: Float = 1f,
        options: DevelopOptions = DevelopOptions(),
        faceRegions: List<FaceRegion> = emptyList(),
    ) {
        require(sourceX >= 0 && sourceY >= 0 && coreWidth > 0 && coreHeight > 0)
        require(sourceX + coreWidth <= src.width && sourceY + coreHeight <= src.height)
        require(destinationX >= 0 && destinationY >= 0)
        require(destinationX + coreWidth <= destination.width)
        require(destinationY + coreHeight <= destination.height)

        // Unlike a whole-frame render, a tile does not retain a second packed copy of every
        // decoded pixel through the float pipeline. Camera JPEG/DNG region sources are opaque, so
        // only the overlap-free core needs a packed ARGB array after development. This saves four
        // bytes per decoded tile pixel at the pipeline's halation peak.
        val planes = bitmapRgbPlanes(src)
        DevelopPipeline.apply(
            planes.r,
            planes.g,
            planes.b,
            src.width,
            src.height,
            look,
            lut,
            preGrade = preGrade,
            iso = iso,
            effectStrength = effectStrength,
            faceRegions = faceRegions,
            options = options,
        )
        val pixels = IntArray(coreWidth * coreHeight)
        var destinationIndex = 0
        for (y in sourceY until sourceY + coreHeight) {
            var sourceIndex = y * src.width + sourceX
            repeat(coreWidth) {
                val r = (planes.r[sourceIndex] * 255f + 0.5f).toInt().coerceIn(0, 255)
                val g = (planes.g[sourceIndex] * 255f + 0.5f).toInt().coerceIn(0, 255)
                val b = (planes.b[sourceIndex] * 255f + 0.5f).toInt().coerceIn(0, 255)
                pixels[destinationIndex++] =
                    0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
                sourceIndex++
            }
        }
        destination.setPixels(
            pixels,
            0,
            coreWidth,
            destinationX,
            destinationY,
            coreWidth,
            coreHeight,
        )
    }

    /** Analyze one small whole-frame proxy once, then reuse it for every full-resolution strip. */
    internal fun analyzeScene(
        src: Bitmap,
        preGrade: DevelopPipeline.PreGrade? = null,
    ): SceneProfile {
        val planes = bitmapPlanes(src)
        return DevelopPipeline.analyzeScene(
            planes.r,
            planes.g,
            planes.b,
            src.width,
            src.height,
            preGrade,
        )
    }

    internal fun detectFaces(src: Bitmap): List<FaceRegion> = FaceRegionDetector.detect(src)

    /**
     * Run the exact pre-stock stages on the whole-frame proxy and stop as soon as its skin mask is
     * available. The returned small mask is then reused by every full-resolution region.
     */
    internal fun captureSkinMask(
        src: Bitmap,
        look: FilmLook,
        lut: LutCube,
        preGrade: DevelopPipeline.PreGrade? = null,
        iso: Int? = null,
        effectStrength: Float = 1f,
        options: DevelopOptions = DevelopOptions(),
        faceRegions: List<FaceRegion>,
    ): SkinMask {
        val planes = bitmapRgbPlanes(src)
        var captured: SkinMask? = null
        val stop = SkinMaskCaptured()
        try {
            DevelopPipeline.apply(
                planes.r,
                planes.g,
                planes.b,
                src.width,
                src.height,
                look,
                lut,
                preGrade = preGrade,
                iso = iso,
                effectStrength = effectStrength,
                faceRegions = faceRegions,
                options = options.copy(
                    skinMaskMapping = null,
                    onSkinMaskReady = { mask ->
                        captured = mask
                        throw stop
                    },
                ),
            )
        } catch (failure: SkinMaskCaptured) {
            if (failure !== stop) throw failure
        }
        return captured ?: SkinMask(1, 1, floatArrayOf(0f), 0f)
    }

    private fun renderPixels(
        src: Bitmap,
        look: FilmLook,
        lut: LutCube,
        preGrade: DevelopPipeline.PreGrade?,
        iso: Int?,
        effectStrength: Float,
        options: DevelopOptions,
        faceRegions: List<FaceRegion>,
    ): IntArray {
        val planes = bitmapPlanes(src)
        DevelopPipeline.apply(
            planes.r,
            planes.g,
            planes.b,
            src.width,
            src.height,
            look,
            lut,
            preGrade = preGrade,
            iso = iso,
            effectStrength = effectStrength,
            faceRegions = faceRegions,
            options = options,
        )
        for (i in planes.pixels.indices) {
            val a = planes.pixels[i] and 0xFF000000.toInt()
            val r = (planes.r[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val g = (planes.g[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val b = (planes.b[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
            planes.pixels[i] = a or (r shl 16) or (g shl 8) or b
        }
        return planes.pixels
    }

    private fun bitmapPlanes(src: Bitmap): BitmapPlanes {
        val size = src.width * src.height
        val pixels = IntArray(size)
        src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
        val r = FloatArray(size)
        val g = FloatArray(size)
        val b = FloatArray(size)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            r[i] = ((pixel shr 16) and 0xFF) / 255f
            g[i] = ((pixel shr 8) and 0xFF) / 255f
            b[i] = (pixel and 0xFF) / 255f
        }
        return BitmapPlanes(pixels, r, g, b)
    }

    /** RGB-only extraction for opaque camera regions; the temporary packed read is not retained. */
    private fun bitmapRgbPlanes(src: Bitmap): RgbPlanes {
        val size = src.width * src.height
        val packed = IntArray(size)
        src.getPixels(packed, 0, src.width, 0, 0, src.width, src.height)
        val r = FloatArray(size)
        val g = FloatArray(size)
        val b = FloatArray(size)
        for (i in packed.indices) {
            val pixel = packed[i]
            r[i] = ((pixel shr 16) and 0xFF) / 255f
            g[i] = ((pixel shr 8) and 0xFF) / 255f
            b[i] = (pixel and 0xFF) / 255f
        }
        return RgbPlanes(r, g, b)
    }

    private data class BitmapPlanes(
        val pixels: IntArray,
        val r: FloatArray,
        val g: FloatArray,
        val b: FloatArray,
    )

    private data class RgbPlanes(
        val r: FloatArray,
        val g: FloatArray,
        val b: FloatArray,
    )

    private class SkinMaskCaptured : RuntimeException(null, null, false, false)
}
