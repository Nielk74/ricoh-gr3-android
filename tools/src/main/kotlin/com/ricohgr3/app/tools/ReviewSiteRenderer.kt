package com.ricohgr3.app.tools

import com.ricohgr3.app.looks.emulation.FilmLookCatalog
import com.ricohgr3.app.looks.emulation.SceneAnalyzer
import com.ricohgr3.app.looks.emulation.SceneProfile
import java.awt.image.BufferedImage
import java.io.File
import java.time.Instant
import java.util.Locale
import javax.imageio.ImageIO

/**
 * Generates the high-resolution assets and manifest consumed by `review-site/`.
 *
 * Unlike the compact calibration contact sheets, this renderer keeps a 3000 px long edge and
 * writes every look separately. The website can therefore compare identical pixels, zoom to
 * 100/200%, and open any developed image directly without upscaling a thumbnail.
 *
 * Args: `<repoRoot> <preparedSources> <siteTemplate> <outputDir>`.
 */
fun reviewSiteMain(args: Array<String>) {
    require(args.size == 4) {
        "usage: <repoRoot> <preparedSources> <siteTemplate> <outputDir>"
    }
    val repo = File(args[0])
    val inputRoot = resolve(repo, args[1])
    val templateRoot = resolve(repo, args[2])
    val outputRoot = resolve(repo, args[3]).apply { mkdirs() }
    val assetsRoot = File(outputRoot, "assets/scenes").apply { mkdirs() }

    require(templateRoot.isDirectory) { "review site template not found: $templateRoot" }
    copyTemplate(templateRoot, outputRoot)

    val sources = listOf("jpeg", "dng").flatMap { kind ->
        File(inputRoot, kind).listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
            .sortedBy { it.name }
            .map { Source(kind, it) }
    }
    require(sources.isNotEmpty()) { "no prepared review sources in $inputRoot" }
    val faceRegionsByPath = detectFaceRegions(repo, sources.map { it.file })

    val expectedSceneIds =
        sources.mapTo(mutableSetOf()) { "${it.kind}-${it.file.nameWithoutExtension.lowercase()}" }
    assetsRoot.listFiles()
        .orEmpty()
        .filter { it.isDirectory && it.name !in expectedSceneIds }
        .forEach { stale -> check(stale.deleteRecursively()) { "could not remove $stale" } }

    val lutDir = File(repo, "app/src/main/assets/luts")
    val sceneJson = ArrayList<String>(sources.size)

    sources.forEachIndexed { index, source ->
        val sceneId = "${source.kind}-${source.file.nameWithoutExtension.lowercase()}"
        val sceneDir = File(assetsRoot, sceneId).apply { mkdirs() }
        val decoded = ImageIO.read(source.file) ?: error("could not decode ${source.file}")
        val work = toRgb(decoded)
        val faceRegions = faceRegionsByPath[source.file.canonicalPath].orEmpty()
        println(
            "[${index + 1}/${sources.size}] ${source.kind.uppercase()} ${source.file.name} · " +
                "${faceRegions.size} face${if (faceRegions.size == 1) "" else "s"}",
        )

        writeJpg(work, File(sceneDir, "original.jpg"), quality = 0.96f)
        val thumbWidth = minOf(480, work.width)
        val thumb = if (work.width > thumbWidth) scaleTo(work, thumbWidth) else work
        writeJpg(thumb, File(sceneDir, "thumb.jpg"), quality = 0.86f)
        if (thumb !== work) thumb.flush()

        val skinMask = skinMaskFor(
            source = work,
            lookId = "portra400",
            faceRegions = faceRegions,
        )
        val skinMaskImage = maskOverlay(work, skinMask)
        writeJpg(skinMaskImage, File(sceneDir, "skin-mask.jpg"), quality = 0.94f)
        skinMaskImage.flush()

        val analysisImage = if (work.width > 960) scaleTo(work, 960) else work
        val profile = profileOf(analysisImage)
        if (analysisImage !== work) analysisImage.flush()

        val perLook = ArrayList<String>(FilmLookCatalog.entries.size)
        FilmLookCatalog.entries.forEach { entry ->
            val look = entry.look
            val adjustment = SceneAnalyzer.adjustment(profile, look.adaptive)
            val lut = loadLut(look, lutDir)
            val output = develop(
                work,
                look,
                lut,
                effectStrength = 1f,
                faceRegions = faceRegions,
            )
            val filename = "${look.id}.jpg"
            writeJpg(output, File(sceneDir, filename), quality = 0.94f)
            output.flush()
            val strongOutput = develop(
                work,
                look,
                lut,
                effectStrength = 1.5f,
                faceRegions = faceRegions,
            )
            val strongFilename = "${look.id}-150.jpg"
            writeJpg(strongOutput, File(sceneDir, strongFilename), quality = 0.94f)
            strongOutput.flush()
            perLook += """
                "${look.id}":{
                  "src":"assets/scenes/$sceneId/$filename",
                  "strongSrc":"assets/scenes/$sceneId/$strongFilename",
                  "ev":${adjustment.exposureEv.json()},
                  "shadows":${adjustment.shadowLift.json()},
                  "highlights":${adjustment.highlightCompression.json()},
                  "mix":${adjustment.lookStrength.json()},
                  "saturation":${adjustment.saturation.json()},
                  "grain":${adjustment.grainScale.json()},
                  "halation":${(
                      if (look.halation.enabled) {
                          look.halation.strength * adjustment.halationScale
                      } else {
                          0f
                      }
                  ).json()}
                }
            """.trimIndent().replace("\n", "")
            println("    ${look.displayName} · 100/150%")
        }

        sceneJson += """
            {
              "id":"$sceneId",
              "name":"${source.file.nameWithoutExtension.jsonEscape()}",
              "sourceType":"${source.kind.uppercase()}",
              "width":${work.width},
              "height":${work.height},
              "faces":${faceRegions.size},
              "thumbnail":"assets/scenes/$sceneId/thumb.jpg",
              "original":"assets/scenes/$sceneId/original.jpg",
              "skinMask":"assets/scenes/$sceneId/skin-mask.jpg",
              "profile":{
                "p01":${profile.p01.json()},
                "p10":${profile.p10.json()},
                "p50":${profile.p50.json()},
                "p90":${profile.p90.json()},
                "p99":${profile.p99.json()},
                "clipped":${profile.clippedHighlights.json()},
                "crushed":${profile.crushedShadows.json()},
                "saturation":${profile.meanSaturation.json()},
                "warmth":${profile.neutralWarmth.json()},
                "tone":"${profile.toneLabel()}",
                "contrast":"${if (profile.highContrast) "High contrast" else "Balanced contrast"}"
              },
              "looks":{${perLook.joinToString(",")}}
            }
        """.trimIndent()

        decoded.flush()
        if (work !== decoded) work.flush()
    }

    val looksJson = FilmLookCatalog.entries.joinToString(",") { entry ->
        val look = entry.look
        """
            {
              "id":"${look.id}",
              "name":"${look.displayName.jsonEscape()}",
              "description":"${descriptionFor(look.id).jsonEscape()}",
              "swatchTop":"${look.swatchTop.cssHex()}",
              "swatchBottom":"${look.swatchBottom.cssHex()}"
            }
        """.trimIndent()
    }
    val manifest = """
        {
          "generatedAt":"${Instant.now()}",
          "renderLongEdge":3000,
          "looks":[$looksJson],
          "scenes":[${sceneJson.joinToString(",")}]
        }
    """.trimIndent()
    File(outputRoot, "manifest.json").writeText(manifest)
    println("Done: ${sources.size} high-resolution scenes in ${outputRoot.relativeTo(repo)}")
}

/** Separate entry name avoids colliding with the preview/contact-sheet top-level main methods. */
fun main(args: Array<String>) = reviewSiteMain(args)

private data class Source(val kind: String, val file: File)

private fun resolve(repo: File, path: String): File =
    File(path).let { if (it.isAbsolute) it else File(repo, path) }

private fun copyTemplate(templateRoot: File, outputRoot: File) {
    templateRoot.walkTopDown()
        .filter { it.isFile }
        .forEach { source ->
            val relative = source.relativeTo(templateRoot)
            val destination = File(outputRoot, relative.path)
            destination.parentFile?.mkdirs()
            source.copyTo(destination, overwrite = true)
        }
}

private fun profileOf(image: BufferedImage): SceneProfile {
    val width = image.width
    val height = image.height
    val size = width * height
    val r = FloatArray(size)
    val g = FloatArray(size)
    val b = FloatArray(size)
    var index = 0
    for (y in 0 until height) for (x in 0 until width) {
        val pixel = image.getRGB(x, y)
        r[index] = ((pixel shr 16) and 0xFF) / 255f
        g[index] = ((pixel shr 8) and 0xFF) / 255f
        b[index] = (pixel and 0xFF) / 255f
        index++
    }
    return SceneAnalyzer.analyze(r, g, b, width, height)
}

private fun SceneProfile.toneLabel(): String = when {
    lowKey -> "Low key"
    highKey -> "High key"
    else -> "Balanced light"
}

private fun descriptionFor(id: String): String = when (id) {
    "portra400" -> "Soft contrast · warm skin · cyan-biased skies"
    "portra800" -> "Low-light warmth · cyan-biased skies · visible grain"
    "gold200" -> "Golden highlights · nostalgic warmth"
    "ektar100" -> "Clean grain · vivid color · crisp contrast"
    "superia400" -> "Cool greens · lively color · candid character"
    "cinestill800t" -> "Tungsten blue · red halation · night color"
    "vision3_250d" -> "Daylight cinema · gentle shoulder · natural skin"
    "vision3_500t" -> "Cool shadows · warm light · cinematic night"
    "eterna" -> "Muted palette · soft contrast · wide latitude"
    "trix400" -> "Punchy monochrome · classic reportage grain"
    "hp5" -> "Open monochrome · softer tonal scale"
    else -> "Adaptive film emulation"
}

private fun Long.cssHex(): String = "#%06X".format(Locale.US, this and 0xFFFFFF)
private fun Float.json(): String = "%.4f".format(Locale.US, this)
private fun String.jsonEscape(): String =
    replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
