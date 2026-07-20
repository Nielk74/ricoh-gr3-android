package com.ricohgr3.app.tools

import com.ricohgr3.app.looks.emulation.DevelopPipeline
import com.ricohgr3.app.looks.emulation.FilmLookCatalog
import com.ricohgr3.app.looks.emulation.SceneAnalyzer
import com.ricohgr3.app.looks.emulation.SkinMask
import com.ricohgr3.app.looks.emulation.SkinTone
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Calibration utility that visualises the exact skin mask used after each look's scene
 * adaptation. Accepted regions are shown in cyan over a monochrome source, making spill onto
 * hair, clothing, wood, gold, or red backgrounds immediately visible at 100% zoom.
 *
 * Args: `<repoRoot> <preparedSources> <outputDir>`.
 */
fun main(args: Array<String>) {
    require(args.size == 3) { "usage: <repoRoot> <preparedSources> <outputDir>" }
    val repo = File(args[0])
    val inputRoot = resolveSkinPath(repo, args[1])
    val outputRoot = resolveSkinPath(repo, args[2]).apply {
        if (exists()) check(deleteRecursively()) { "could not clear $this" }
        mkdirs()
    }
    val auditLooks = listOf("portra400", "ektar100")
        .map { id -> FilmLookCatalog.entryFor(id)!!.look }

    val sources = listOf("jpeg", "dng").flatMap { kind ->
        File(inputRoot, kind).listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
            .sortedBy { it.name }
            .map { kind to it }
    }
    require(sources.isNotEmpty()) { "no prepared PNG sources in $inputRoot" }
    val faceRegionsByPath = detectFaceRegions(repo, sources.map { it.second })

    for ((kind, file) in sources) {
        val decoded = ImageIO.read(file) ?: error("could not decode $file")
        val source = toRgb(decoded)
        val planes = rgbPlanes(source)
        val faceRegions = faceRegionsByPath[file.canonicalPath].orEmpty()
        for (look in auditLooks) {
            val mask = skinMaskFor(source, planes, look.id, faceRegions)
            val output = maskOverlay(source, mask)
            val name = "${kind}-${file.nameWithoutExtension}-${look.id}.jpg"
            writeJpg(output, File(outputRoot, name), quality = 0.95f)
            output.flush()
            println(
                "$kind/${file.nameWithoutExtension} ${look.displayName}: " +
                    "faces=${faceRegions.size} " +
                    "coverage=${"%.3f".format(mask.coverage * 100f)}%",
            )
        }
        decoded.flush()
        if (source !== decoded) source.flush()
    }
    println("Done: ${sources.size * auditLooks.size} mask overlays in $outputRoot")
}

internal data class RgbPlanes(
    val r: FloatArray,
    val g: FloatArray,
    val b: FloatArray,
)

internal fun rgbPlanes(image: BufferedImage): RgbPlanes {
    val size = image.width * image.height
    val r = FloatArray(size)
    val g = FloatArray(size)
    val b = FloatArray(size)
    var index = 0
    for (y in 0 until image.height) {
        for (x in 0 until image.width) {
            val pixel = image.getRGB(x, y)
            r[index] = ((pixel shr 16) and 0xFF) / 255f
            g[index] = ((pixel shr 8) and 0xFF) / 255f
            b[index] = (pixel and 0xFF) / 255f
            index++
        }
    }
    return RgbPlanes(r, g, b)
}

internal fun skinMaskFor(
    source: BufferedImage,
    sourcePlanes: RgbPlanes = rgbPlanes(source),
    lookId: String = "portra400",
    faceRegions: List<com.ricohgr3.app.looks.emulation.FaceRegion>,
): SkinMask {
    val look = FilmLookCatalog.entryFor(lookId)?.look
        ?: error("unknown skin-mask audit look: $lookId")
    val r = sourcePlanes.r.copyOf()
    val g = sourcePlanes.g.copyOf()
    val b = sourcePlanes.b.copyOf()
    val profile = SceneAnalyzer.analyze(r, g, b, source.width, source.height)
    val adjustment = SceneAnalyzer.adjustment(profile, look.adaptive)
    DevelopPipeline.applySceneAdaptation(r, g, b, adjustment)
    return SkinTone.detect(
        r,
        g,
        b,
        source.width,
        source.height,
        faceRegions,
    )
}

internal fun maskOverlay(source: BufferedImage, mask: SkinMask): BufferedImage {
    val output = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until source.height) {
        for (x in 0 until source.width) {
            val pixel = source.getRGB(x, y)
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            val luma = (0.2126f * red + 0.7152f * green + 0.0722f * blue)
            val weight = mask.weightAt(x, y, source.width, source.height)
            val base = luma * (0.72f - 0.22f * weight)
            val outRed = (base * (1f - weight) + 25f * weight)
                .toInt()
                .coerceIn(0, 255)
            val outGreen = (base * (1f - weight) + 238f * weight)
                .toInt()
                .coerceIn(0, 255)
            val outBlue = (base * (1f - weight) + 215f * weight)
                .toInt()
                .coerceIn(0, 255)
            output.setRGB(x, y, (outRed shl 16) or (outGreen shl 8) or outBlue)
        }
    }
    return output
}

private fun resolveSkinPath(repo: File, path: String): File =
    File(path).let { if (it.isAbsolute) it else File(repo, path) }
