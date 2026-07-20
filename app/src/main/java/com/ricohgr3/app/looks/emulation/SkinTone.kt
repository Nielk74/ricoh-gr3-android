package com.ricohgr3.app.looks.emulation

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * A normalized face rectangle supplied by the platform detector. Coordinates use image space
 * (`0` = left/top, `1` = right/bottom), so the same regions work for preview and export sizes.
 */
data class FaceRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float = 1f,
) {
    internal fun weightAt(x: Float, y: Float): Float {
        val width = (right - left).coerceAtLeast(1e-4f)
        val height = (bottom - top).coerceAtLeast(1e-4f)
        val centerX = (left + right) * 0.5f
        // A detector box includes forehead/hair and ends close to the chin. A slightly lower,
        // softly feathered ellipse follows the useful facial-skin area without reaching clothing.
        val centerY = (top + bottom) * 0.5f + height * 0.025f
        // Detectors often crop a turned cheek or an ear tightly. The chromaticity stage remains
        // the final gate, so modest expansion recovers facial skin without turning this ellipse
        // into a rectangular background selection.
        val radiusX = width * 0.65f
        val radiusY = height * 0.60f
        val dx = (x - centerX) / radiusX
        val dy = (y - centerY) / radiusY
        val distance = sqrt(dx * dx + dy * dy)
        return ((1f - smoothstepValue(0.82f, 1.04f, distance)) *
            confidence.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }
}

/**
 * A compact, spatially coherent skin mask. Detection runs on a small proxy, so a full-resolution
 * Android export pays well under one megabyte for the mask rather than allocating another set of
 * image-sized planes.
 */
class SkinMask internal constructor(
    val width: Int,
    val height: Int,
    val weights: FloatArray,
    val coverage: Float,
) {
    val hasSkin: Boolean get() = coverage > 0.0001f

    /** Nearest proxy sample for a full-resolution coordinate. The proxy itself is edge-softened. */
    fun weightAt(x: Int, y: Int, fullWidth: Int, fullHeight: Int): Float {
        if (!hasSkin || fullWidth <= 0 || fullHeight <= 0) return 0f
        val maskX = (x.toLong() * width / fullWidth).toInt().coerceIn(0, width - 1)
        val maskY = (y.toLong() * height / fullHeight).toInt().coerceIn(0, height - 1)
        return weights[maskY * width + maskX]
    }
}

/**
 * Pure-Kotlin skin-region detection and naturalisation.
 *
 * Detection deliberately combines semantics, colour, and space:
 * - an on-device face detector establishes where complexion pixels are allowed;
 * - normalized RGB chromaticity handles pale, dark, cool-shade, and warm-lit complexions;
 * - narrow blue-content bounds reject yellow metal while a red-floor rejects red velvet;
 * - connected-component validation rejects isolated warm pixels inside the face bounds.
 *
 * This is not face recognition and it never smooths texture. It only supplies a soft colour mask
 * to the film pipeline, keeping pores, beard detail, focus, lighting, and grain untouched.
 */
object SkinTone {
    private const val DETECTION_LONG_EDGE = 360
    private const val SUPPORT_THRESHOLD = 0.055f
    private const val STRONG_THRESHOLD = 0.30f

    /**
     * Detect spatially coherent skin regions in sRGB [r]/[g]/[b].
     *
     * The source arrays are read only. The returned proxy mask is suitable for repeated sampling
     * during the LUT pass and is deterministic for preview/export parity.
     */
    fun detect(
        r: FloatArray,
        g: FloatArray,
        b: FloatArray,
        width: Int,
        height: Int,
        faceRegions: List<FaceRegion>,
    ): SkinMask {
        require(width >= 0 && height >= 0)
        require(r.size == g.size && g.size == b.size)
        require(r.size == width * height)
        if (r.isEmpty()) return SkinMask(1, 1, floatArrayOf(0f), 0f)
        // A colour key without a semantic face gate will inevitably mistake clothing, wood, and
        // décor for skin. If platform detection fails, leaving the stock untouched is safer.
        if (faceRegions.isEmpty()) return SkinMask(1, 1, floatArrayOf(0f), 0f)

        val stride = ceil(maxOf(width, height) / DETECTION_LONG_EDGE.toDouble())
            .toInt()
            .coerceAtLeast(1)
        val maskWidth = (width + stride - 1) / stride
        val maskHeight = (height + stride - 1) / stride
        val size = maskWidth * maskHeight
        val likelihood = FloatArray(size)

        for (maskY in 0 until maskHeight) {
            val sourceY = (maskY * stride + stride / 2).coerceAtMost(height - 1)
            val sourceRow = sourceY * width
            val maskRow = maskY * maskWidth
            for (maskX in 0 until maskWidth) {
                val sourceX = (maskX * stride + stride / 2).coerceAtMost(width - 1)
                val source = sourceRow + sourceX
                val normalizedX = (sourceX + 0.5f) / width
                val normalizedY = (sourceY + 0.5f) / height
                var faceWeight = 0f
                for (face in faceRegions) {
                    faceWeight = maxOf(
                        faceWeight,
                        face.weightAt(normalizedX, normalizedY),
                    )
                }
                likelihood[maskRow + maskX] =
                    pixelLikelihood(r[source], g[source], b[source]) * faceWeight
            }
        }

        val accepted = FloatArray(size)
        val visited = BooleanArray(size)
        val queue = IntArray(size)
        val minimumArea = maxOf(5, (size * 0.00035f).toInt())

        for (seed in 0 until size) {
            if (visited[seed] || likelihood[seed] <= SUPPORT_THRESHOLD) continue
            var head = 0
            var tail = 0
            queue[tail++] = seed
            visited[seed] = true
            var weightSum = 0f
            var strongPixels = 0
            var minX = maskWidth
            var maxX = 0
            var minY = maskHeight
            var maxY = 0

            while (head < tail) {
                val index = queue[head++]
                val x = index % maskWidth
                val y = index / maskWidth
                val weight = likelihood[index]
                weightSum += weight
                if (weight >= STRONG_THRESHOLD) strongPixels++
                minX = minOf(minX, x)
                maxX = maxOf(maxX, x)
                minY = minOf(minY, y)
                maxY = maxOf(maxY, y)

                val fromX = (x - 1).coerceAtLeast(0)
                val toX = (x + 1).coerceAtMost(maskWidth - 1)
                val fromY = (y - 1).coerceAtLeast(0)
                val toY = (y + 1).coerceAtMost(maskHeight - 1)
                for (nextY in fromY..toY) {
                    val row = nextY * maskWidth
                    for (nextX in fromX..toX) {
                        val next = row + nextX
                        if (!visited[next] && likelihood[next] > SUPPORT_THRESHOLD) {
                            visited[next] = true
                            queue[tail++] = next
                        }
                    }
                }
            }

            val area = tail
            val boxWidth = maxX - minX + 1
            val boxHeight = maxY - minY + 1
            val boxArea = boxWidth * boxHeight
            val areaFraction = area.toFloat() / size
            val meanWeight = weightSum / area.coerceAtLeast(1)
            val fill = area.toFloat() / boxArea.coerceAtLeast(1)
            val spansWidth = boxWidth.toFloat() / maskWidth
            val spansHeight = boxHeight.toFloat() / maskHeight
            val borderTouches =
                (if (minX == 0) 1 else 0) +
                    (if (maxX == maskWidth - 1) 1 else 0) +
                    (if (minY == 0) 1 else 0) +
                    (if (maxY == maskHeight - 1) 1 else 0)

            val enoughStrongPixels = strongPixels >= maxOf(3, (area * 0.035f).toInt())
            val frameSpanningSurface =
                (spansWidth > 0.84f && spansHeight > 0.30f) ||
                    (spansHeight > 0.88f && spansWidth > 0.30f) ||
                    borderTouches >= 3
            val acceptedComponent =
                area >= minimumArea &&
                    areaFraction < 0.34f &&
                    meanWeight > 0.13f &&
                    fill > 0.075f &&
                    enoughStrongPixels &&
                    !frameSpanningSurface

            if (acceptedComponent) {
                for (position in 0 until tail) {
                    val index = queue[position]
                    accepted[index] = smoothstep(
                        SUPPORT_THRESHOLD,
                        0.62f,
                        likelihood[index],
                    )
                }
            }
        }

        // One proxy pixel is roughly 2–9 output pixels. A small blur removes component stair-steps
        // without spilling far enough to tint hair, glasses, or a neighbouring background.
        val softened = boxBlur(accepted, maskWidth, maskHeight, radius = 1)
        var coverage = 0.0
        for (index in softened.indices) {
            softened[index] = maxOf(accepted[index], softened[index] * 0.82f)
                .coerceIn(0f, 1f)
            coverage += softened[index]
        }
        return SkinMask(
            width = maskWidth,
            height = maskHeight,
            weights = softened,
            coverage = (coverage / size).toFloat(),
        )
    }

    /**
     * Colour-only skin likelihood used as component input. It intentionally has two lobes:
     * warm/neutral skin and cool low-saturation skin under open shade.
     */
    fun pixelLikelihood(r: Float, g: Float, b: Float): Float {
        val rr = r.coerceIn(0f, 1f)
        val gg = g.coerceIn(0f, 1f)
        val bb = b.coerceIn(0f, 1f)
        val sum = rr + gg + bb
        if (sum < 0.025f) return 0f
        val rn = rr / sum
        val gn = gg / sum
        val bn = bb / sum
        val max = maxOf(rr, gg, bb)
        val min = minOf(rr, gg, bb)
        val saturation = if (max > 1e-5f) (max - min) / max else 0f
        val luminance = luma(rr, gg, bb)
        val luminanceWeight =
            smoothstep(0.035f, 0.11f, luminance) *
                (1f - smoothstep(0.93f, 0.995f, luminance))

        val warm = minOf(
            band(rn, 0.35f, 0.40f, 0.68f, 0.73f),
            band(gn, 0.17f, 0.21f, 0.38f, 0.42f),
            band(bn, 0.085f, 0.115f, 0.29f, 0.335f),
            band(saturation, 0.10f, 0.20f, 0.92f, 0.995f),
            smoothstep(0.018f, 0.10f, rn - gn),
            smoothstep(0.010f, 0.075f, gn - bn),
        )

        val cool = minOf(
            band(rn, 0.325f, 0.35f, 0.47f, 0.50f),
            band(gn, 0.235f, 0.275f, 0.37f, 0.405f),
            band(bn, 0.21f, 0.25f, 0.355f, 0.39f),
            band(saturation, 0.04f, 0.08f, 0.40f, 0.54f),
            smoothstep(0.006f, 0.035f, rn - maxOf(gn, bn)),
        )
        return (maxOf(warm, cool) * luminanceWeight).coerceIn(0f, 1f)
    }

    /**
     * Restore plausible skin colour after the stock transform while preserving rendered luma.
     *
     * [source] is the scene-adapted colour immediately before the stock transform. The source
     * chroma is first moved to the rendered luminance, so protection cannot flatten facial light
     * or undo the stock's tone curve. Excessive saturation is then compressed toward a generous
     * ceiling; naturally dark or strongly pigmented skin is not forced toward beige.
     */
    fun naturalize(
        sourceR: Float,
        sourceG: Float,
        sourceB: Float,
        renderedR: Float,
        renderedG: Float,
        renderedB: Float,
        maskWeight: Float,
        params: SkinToneParams,
        effectStrength: Float,
        out: FloatArray,
        scratch: FloatArray,
    ) {
        require(out.size >= 3 && scratch.size >= 3)
        val weight = maskWeight.coerceIn(0f, 1f) * effectStrength.coerceIn(0f, 1.25f)
        if (weight <= 0f || !params.enabled) {
            out[0] = renderedR
            out[1] = renderedG
            out[2] = renderedB
            return
        }

        val renderedLuma =
            ColorMath.linearLuminance(renderedR, renderedG, renderedB).coerceIn(0f, 1f)
        // Move the captured complexion to the stock-rendered exposure in exact linear light.
        // Scaling gamma-encoded RGB here used to change hue and could not truly preserve light.
        ColorMath.putAtLinearLuminance(
            sourceR,
            sourceG,
            sourceB,
            renderedLuma,
            scratch,
        )
        val referenceR = scratch[0]
        val referenceG = scratch[1]
        val referenceB = scratch[2]

        ColorMath.srgbToOklab(referenceR, referenceG, referenceB, scratch)
        val referenceA = scratch[1]
        val referenceBPerceptual = scratch[2]
        val sourceChroma = sqrt(
            referenceA * referenceA + referenceBPerceptual * referenceBPerceptual,
        )
        ColorMath.srgbToOklab(renderedR, renderedG, renderedB, scratch)
        val renderedA = scratch[1]
        val renderedBPerceptual = scratch[2]
        val renderedChroma = sqrt(
            renderedA * renderedA + renderedBPerceptual * renderedBPerceptual,
        )
        // Beneficial desaturation should survive. Stronger protection is reserved for a stock
        // that adds chroma or visibly pushes hue away from the captured complexion.
        val addedChroma =
            ((renderedChroma - sourceChroma - 0.005f) / 0.045f).coerceIn(0f, 1f)
        val hueRotation = chromaDirectionDistance(
            referenceA,
            referenceBPerceptual,
            renderedA,
            renderedBPerceptual,
        )
        val restoreBias = maxOf(0.08f, addedChroma, hueRotation)
        val protection = (params.protection * weight * restoreBias).coerceIn(0f, 0.90f)

        var rr = renderedR + (referenceR - renderedR) * protection
        var gg = renderedG + (referenceG - renderedG) * protection
        var bb = renderedB + (referenceB - renderedB) * protection
        ColorMath.putAtLinearLuminance(rr, gg, bb, renderedLuma, scratch)
        rr = scratch[0]
        gg = scratch[1]
        bb = scratch[2]

        val currentSaturation = saturation(rr, gg, bb)
        val shadow = 1f - smoothstep(
            ColorMath.srgbToLinear(0.08f),
            ColorMath.srgbToLinear(0.25f),
            renderedLuma,
        )
        val ceiling = (params.saturationCeiling + 0.07f * shadow).coerceAtMost(0.82f)
        if (currentSaturation > ceiling) {
            val targetScale = (ceiling / currentSaturation).coerceIn(0f, 1f)
            val chromaScale =
                1f - params.naturalness.coerceIn(0f, 1f) * weight * (1f - targetScale)
            val linearR = ColorMath.srgbToLinear(rr)
            val linearG = ColorMath.srgbToLinear(gg)
            val linearB = ColorMath.srgbToLinear(bb)
            rr = ColorMath.linearToSrgb(
                renderedLuma + (linearR - renderedLuma) * chromaScale,
            )
            gg = ColorMath.linearToSrgb(
                renderedLuma + (linearG - renderedLuma) * chromaScale,
            )
            bb = ColorMath.linearToSrgb(
                renderedLuma + (linearB - renderedLuma) * chromaScale,
            )
        }

        ColorMath.putAtLinearLuminance(rr, gg, bb, renderedLuma, out)
    }

    private fun saturation(r: Float, g: Float, b: Float): Float {
        val max = maxOf(r, g, b)
        return if (max > 1e-5f) (max - minOf(r, g, b)) / max else 0f
    }

    /**
     * Direction-only chroma difference. A stock may lower saturation beautifully while still
     * rotating a complexion toward green or magenta; separating direction from magnitude lets us
     * correct the latter without undoing the former.
     */
    private fun chromaDirectionDistance(
        referenceA: Float,
        referenceB: Float,
        renderedA: Float,
        renderedB: Float,
    ): Float {
        val referenceLength = sqrt(referenceA * referenceA + referenceB * referenceB)
        val renderedLength = sqrt(renderedA * renderedA + renderedB * renderedB)
        if (referenceLength < 0.004f || renderedLength < 0.004f) return 0f

        val da = referenceA / referenceLength - renderedA / renderedLength
        val db = referenceB / referenceLength - renderedB / renderedLength
        val distance = sqrt(da * da + db * db)
        val reliability =
            smoothstep(0.004f, 0.035f, referenceLength) *
                smoothstep(0.004f, 0.030f, renderedLength)
        return smoothstep(0.04f, 0.36f, distance) * reliability
    }

    private fun boxBlur(source: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius <= 0) return source.copyOf()
        val horizontal = FloatArray(source.size)
        val output = FloatArray(source.size)
        val diameter = radius * 2 + 1
        for (y in 0 until height) {
            val row = y * width
            var sum = 0f
            for (offset in -radius..radius) {
                sum += source[row + offset.coerceIn(0, width - 1)]
            }
            for (x in 0 until width) {
                horizontal[row + x] = sum / diameter
                val remove = (x - radius).coerceIn(0, width - 1)
                val add = (x + radius + 1).coerceIn(0, width - 1)
                sum += source[row + add] - source[row + remove]
            }
        }
        for (x in 0 until width) {
            var sum = 0f
            for (offset in -radius..radius) {
                sum += horizontal[offset.coerceIn(0, height - 1) * width + x]
            }
            for (y in 0 until height) {
                output[y * width + x] = sum / diameter
                val remove = (y - radius).coerceIn(0, height - 1)
                val add = (y + radius + 1).coerceIn(0, height - 1)
                sum += horizontal[add * width + x] - horizontal[remove * width + x]
            }
        }
        return output
    }

    private fun luma(r: Float, g: Float, b: Float): Float =
        0.2126f * r + 0.7152f * g + 0.0722f * b

    private fun band(
        value: Float,
        outerLow: Float,
        innerLow: Float,
        innerHigh: Float,
        outerHigh: Float,
    ): Float =
        smoothstep(outerLow, innerLow, value) *
            (1f - smoothstep(innerHigh, outerHigh, value))

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        return smoothstepValue(edge0, edge1, value)
    }
}

private fun smoothstepValue(edge0: Float, edge1: Float, value: Float): Float {
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
