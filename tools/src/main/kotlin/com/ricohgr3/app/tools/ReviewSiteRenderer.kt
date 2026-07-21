package com.ricohgr3.app.tools

import com.ricohgr3.app.looks.emulation.DevelopOptions
import com.ricohgr3.app.looks.emulation.FilmLookCatalog
import com.ricohgr3.app.looks.emulation.SceneAnalyzer
import com.ricohgr3.app.looks.emulation.SceneProfile
import com.ricohgr3.app.looks.emulation.stableRenderSeed
import java.awt.image.BufferedImage
import java.io.File
import java.time.Instant
import java.util.Locale
import javax.imageio.ImageIO

private val EXPOSURE_BRACKET_LOOKS =
    setOf("portra400", "portra800", "cinestill800t", "vision3_250d")

/**
 * Generates the high-resolution assets and manifest consumed by `review-site/`.
 *
 * Unlike the compact calibration contact sheets, this renderer keeps a 3000 px long edge and
 * writes every look separately. The website can therefore compare identical pixels, zoom to
 * 100/200%, and open any developed image directly without upscaling a thumbnail.
 *
 * Args: `<repoRoot> <preparedSources> <siteTemplate> <outputDir>` followed by optional
 * `--baseline=<review-root>` and `--looks=<comma-separated-stock-ids>` arguments.
 */
fun reviewSiteMain(args: Array<String>) {
    require(args.size >= 4) {
        "usage: <repoRoot> <preparedSources> <siteTemplate> <outputDir> " +
            "[--baseline=<review-root>] [--looks=<ids>]"
    }
    val repo = File(args[0])
    val inputRoot = resolve(repo, args[1])
    val templateRoot = resolve(repo, args[2])
    val outputRoot = resolve(repo, args[3]).apply { mkdirs() }
    val assetsRoot = File(outputRoot, "assets/scenes").apply { mkdirs() }
    val baselineRoot = args.drop(4)
        .firstOrNull { it.startsWith("--baseline=") }
        ?.substringAfter('=')
        ?.let { resolve(repo, it) }
        ?.also { require(it.isDirectory) { "baseline review not found: $it" } }
    val selectedLookIds = args.drop(4)
        .firstOrNull { it.startsWith("--looks=") }
        ?.substringAfter('=')
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
    val reviewLooks = reviewLooks(selectedLookIds, baselineRoot != null)

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

        // Analyse the exact work buffer once, then pass this same immutable decision input to every
        // render. A former 960 px proxy made the manifest describe different microcontrast/grain
        // decisions from the 3000 px output.
        val profile = profileOf(work)
        val developOptions = DevelopOptions(
            sceneProfile = profile,
            renderSeed = stableRenderSeed("${source.kind}:${source.file.name}"),
        )

        val perLook = ArrayList<String>(reviewLooks.size)
        reviewLooks.forEach { reviewLook ->
            val entry = reviewLook.entry
            val look = entry.look
            val adjustment = SceneAnalyzer.adjustment(profile, look.adaptive, stock = look.stock)
            val filename = "${reviewLook.id}.jpg"
            val strongFilename = "${reviewLook.id}-150.jpg"
            val lut = if (reviewLook.baselineLookId == null) loadLut(look, lutDir) else null
            if (reviewLook.baselineLookId != null) {
                val baselineScene = File(checkNotNull(baselineRoot), "assets/scenes/$sceneId")
                copyBaseline(
                    source = File(baselineScene, "${reviewLook.baselineLookId}.jpg"),
                    destination = File(sceneDir, filename),
                )
                copyBaseline(
                    source = File(baselineScene, "${reviewLook.baselineLookId}-150.jpg"),
                    destination = File(sceneDir, strongFilename),
                )
            } else {
                val output = develop(
                    work,
                    look,
                    checkNotNull(lut),
                    effectStrength = 1f,
                    faceRegions = faceRegions,
                    options = developOptions,
                )
                writeJpg(output, File(sceneDir, filename), quality = 0.97f)
                output.flush()
                val strongOutput = develop(
                    work,
                    look,
                    checkNotNull(lut),
                    effectStrength = 1.5f,
                    faceRegions = faceRegions,
                    options = developOptions,
                )
                writeJpg(strongOutput, File(sceneDir, strongFilename), quality = 0.97f)
                strongOutput.flush()
            }

            val exposureMasters = if (reviewLook.id in EXPOSURE_BRACKET_LOOKS) {
                fun bracket(ev: Int, suffix: String, strength: Float): String {
                    val strengthSuffix = if (strength > 1f) "-150" else ""
                    val bracketFilename = "${reviewLook.id}-$suffix$strengthSuffix.jpg"
                    val bracketOutput = develop(
                        work,
                        look,
                        checkNotNull(lut),
                        effectStrength = strength,
                        filmExposureEv = ev.toFloat(),
                        faceRegions = faceRegions,
                        options = developOptions,
                    )
                    writeJpg(bracketOutput, File(sceneDir, bracketFilename), quality = 0.97f)
                    bracketOutput.flush()
                    return "assets/scenes/$sceneId/$bracketFilename"
                }
                val under = bracket(ev = -1, suffix = "evm1", strength = 1f)
                val underStrong = bracket(ev = -1, suffix = "evm1", strength = 1.5f)
                val over = bracket(ev = 1, suffix = "evp1", strength = 1f)
                val overStrong = bracket(ev = 1, suffix = "evp1", strength = 1.5f)
                """
                    {
                      "-1":{"src":"$under","strongSrc":"$underStrong"},
                      "0":{
                        "src":"assets/scenes/$sceneId/$filename",
                        "strongSrc":"assets/scenes/$sceneId/$strongFilename"
                      },
                      "1":{"src":"$over","strongSrc":"$overStrong"}
                    }
                """.trimIndent().replace("\n", "")
            } else {
                "null"
            }
            perLook += """
                "${reviewLook.id}":{
                  "src":"assets/scenes/$sceneId/$filename",
                  "strongSrc":"assets/scenes/$sceneId/$strongFilename",
                  "exposureMasters":$exposureMasters,
                  "ev":${adjustment.exposureEv.json()},
                  "shadows":${adjustment.shadowLift.json()},
                  "highlights":${adjustment.highlightCompression.json()},
                  "contrast":${adjustment.contrast.json()},
                  "mix":${adjustment.lookStrength.json()},
                  "saturation":${adjustment.saturation.json()},
                  "grain":${adjustment.grainScale.json()},
                  "halationScale":${adjustment.halationScale.json()},
                  "halation":${(
                      if (look.halation.enabled) {
                          look.halation.strength * adjustment.halationScale
                      } else {
                          0f
                      }
                  ).json()}
                }
            """.trimIndent().replace("\n", "")
            println(
                "    ${reviewLook.name} · 100/150%" +
                    if (reviewLook.id in EXPOSURE_BRACKET_LOOKS) " · -1/0/+1 EV" else "",
            )
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
                "meanLuma":${profile.meanLuma.json()},
                "dynamicRange":${profile.dynamicRange.json()},
                "clipped":${profile.clippedHighlights.json()},
                "crushed":${profile.crushedShadows.json()},
                "saturation":${profile.meanSaturation.json()},
                "warmth":${profile.neutralWarmth.json()},
                "neutralConfidence":${profile.neutralConfidence.json()},
                "microContrast":${profile.microContrast.json()},
                "tone":"${profile.toneLabel()}",
                "contrast":"${if (profile.highContrast) "High contrast" else "Balanced contrast"}"
              },
              "looks":{${perLook.joinToString(",")}}
            }
        """.trimIndent()

        decoded.flush()
        if (work !== decoded) work.flush()
    }

    val looksJson = reviewLooks.joinToString(",") { reviewLook ->
        val look = reviewLook.entry.look
        """
            {
              "id":"${reviewLook.id}",
              "name":"${reviewLook.name.jsonEscape()}",
              "description":"${reviewLook.description.jsonEscape()}",
              "swatchTop":"${look.swatchTop.cssHex()}",
              "swatchBottom":"${look.swatchBottom.cssHex()}"
            }
        """.trimIndent()
    }
    val manifest = """
        {
          "generatedAt":"${Instant.now()}",
          "renderLongEdge":3000,
          "analysisBasis":"render-source",
          "adjustmentBasis":"100-percent-base",
          "luminanceEncoding":"linear-sRGB",
          "exposureBracketLooks":[
            ${EXPOSURE_BRACKET_LOOKS.joinToString(",") { "\"$it\"" }}
          ],
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

private data class ReviewLook(
    val id: String,
    val name: String,
    val description: String,
    val entry: FilmLookCatalog.Entry,
    val baselineLookId: String? = null,
)

private fun reviewLooks(selectedIds: Set<String>?, includeBaseline: Boolean): List<ReviewLook> {
    val entries = FilmLookCatalog.entries.filter { selectedIds == null || it.look.id in selectedIds }
    if (selectedIds != null) {
        val missing = selectedIds - entries.mapTo(mutableSetOf()) { it.look.id }
        require(missing.isEmpty()) { "unknown review look ids: ${missing.sorted().joinToString()}" }
    }
    return entries.flatMap { entry ->
        val look = entry.look
        buildList {
            if (includeBaseline && look.id in setOf("portra400", "portra800")) {
                add(
                    ReviewLook(
                        id = "${look.id}_before",
                        name = "${look.displayName} · Before",
                        description = "Archived pre-calibration render · 35mm grain",
                        entry = entry,
                        baselineLookId = look.id,
                    ),
                )
            }
            add(
                ReviewLook(
                    id = look.id,
                    name = if (look.id in setOf("portra400", "portra800")) {
                        "${look.displayName} · 35mm"
                    } else {
                        look.displayName
                    },
                    description = descriptionFor(look.id),
                    entry = entry,
                ),
            )
        }
    }
}

private fun copyBaseline(source: File, destination: File) {
    require(source.isFile) { "baseline master not found: $source" }
    source.copyTo(destination, overwrite = true)
}

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
    "portra400" -> "Soft contrast · warm skin · cyan sky and foliage"
    "portra800" -> "Low-light warmth · cyan sky and foliage · visible grain"
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
