package com.ricohgr3.app.tools

import com.ricohgr3.app.looks.emulation.DevelopPipeline
import com.ricohgr3.app.looks.emulation.FilmLookCatalog
import com.ricohgr3.app.looks.emulation.SceneAnalyzer
import com.ricohgr3.app.looks.emulation.SceneProfile
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Local calibration loop for the user's real GR III JPEG set.
 *
 * It renders a compact contact sheet per source plus a text report of the measured scene and
 * adaptive decisions. Outputs live under `build/` and are intentionally not product assets.
 * This makes tone/colour iteration reproducible instead of tuning one coefficient against one
 * flattering sample.
 *
 * Args: `<repoRoot> <inputDirRel> <outDirRel>`.
 */
fun main(args: Array<String>) {
    require(args.size == 3) { "usage: <repoRoot> <inputDirRel> <outDirRel>" }
    val repo = File(args[0])
    fun resolve(path: String): File = File(path).let { if (it.isAbsolute) it else File(repo, path) }
    val inputDir = resolve(args[1])
    val outDir = resolve(args[2]).apply { mkdirs() }
    val lutDir = File(repo, "app/src/main/assets/luts")

    val selectedIds = listOf(
        "portra400",
        "gold200",
        "ektar100",
        "cinestill800t",
        "vision3_250d",
        "vision3_500t",
        "eterna",
    )
    val sources = inputDir.listFiles()
        .orEmpty()
        .filter {
            it.isFile &&
                it.extension.lowercase() in setOf("jpg", "jpeg", "png") &&
                !it.name.startsWith("Screenshot_", ignoreCase = true)
        }
        .sortedBy { it.name }
    require(sources.isNotEmpty()) { "no JPEG/PNG references in $inputDir" }
    val expectedSheets = sources.mapTo(mutableSetOf()) { "${it.nameWithoutExtension}.png" }
    outDir.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension.equals("png", ignoreCase = true) && it.name !in expectedSheets }
        .forEach { stale ->
            check(stale.delete()) { "could not remove stale sheet $stale" }
        }

    val report = StringBuilder()
    report.appendLine("GR III adaptive-film calibration")
    report.appendLine("sources=${sources.size} looks=${selectedIds.joinToString()}")
    report.appendLine()

    for (source in sources) {
        val decoded = ImageIO.read(source) ?: continue
        // Small enough for a fast all-scenes loop; large enough to see highlight transitions,
        // skin colour, and grain character in the generated contact sheet.
        val work = if (decoded.width > 960) scaleTo(decoded, 960) else toRgb(decoded)
        val profile = profileOf(work)
        report.appendLine(source.name)
        report.appendLine(profile.summary())

        val panels = ArrayList<Pair<String, BufferedImage>>()
        panels += "Original" to work
        for (id in selectedIds) {
            val entry = FilmLookCatalog.entryFor(id) ?: continue
            val adjustment = SceneAnalyzer.adjustment(profile, entry.look.adaptive)
            report.appendLine(
                "  ${entry.look.displayName.padEnd(15)} " +
                    "ev=${adjustment.exposureEv.fmt()} sh=${adjustment.shadowLift.fmt()} " +
                    "hi=${adjustment.highlightCompression.fmt()} mix=${adjustment.lookStrength.fmt()} " +
                    "sat=${adjustment.saturation.fmt()} grain=${adjustment.grainScale.fmt()}",
            )
            val lut = loadLut(entry.look, lutDir)
            panels += entry.look.displayName to develop(work, entry.look, lut)
        }
        report.appendLine()

        val sheet = contactSheet(panels)
        ImageIO.write(sheet, "png", File(outDir, "${source.nameWithoutExtension}.png"))
        // Panels other than `work` are standalone rendered buffers and can be flushed eagerly.
        panels.drop(1).forEach { it.second.flush() }
        sheet.flush()
        decoded.flush()
        if (work !== decoded) work.flush()
        println("  ${source.name} -> ${source.nameWithoutExtension}.png")
    }

    File(outDir, "scene-report.txt").writeText(report.toString())
    println("Done: ${sources.size} scene sheets + scene-report.txt in ${outDir.relativeTo(repo)}")
}

private fun profileOf(image: BufferedImage): SceneProfile {
    val w = image.width
    val h = image.height
    val n = w * h
    val r = FloatArray(n)
    val g = FloatArray(n)
    val b = FloatArray(n)
    var i = 0
    for (y in 0 until h) for (x in 0 until w) {
        val p = image.getRGB(x, y)
        r[i] = ((p shr 16) and 0xFF) / 255f
        g[i] = ((p shr 8) and 0xFF) / 255f
        b[i] = (p and 0xFF) / 255f
        i++
    }
    return SceneAnalyzer.analyze(r, g, b, w, h)
}

private fun contactSheet(panels: List<Pair<String, BufferedImage>>): BufferedImage {
    val columns = 4
    val rows = (panels.size + columns - 1) / columns
    val panelW = 360
    val imageH = 240
    val labelH = 34
    val gap = 8
    val sheet = BufferedImage(
        columns * panelW + (columns + 1) * gap,
        rows * (imageH + labelH) + (rows + 1) * gap,
        BufferedImage.TYPE_INT_RGB,
    )
    val gfx = sheet.createGraphics()
    gfx.color = Color(24, 24, 23)
    gfx.fillRect(0, 0, sheet.width, sheet.height)
    gfx.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    gfx.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    gfx.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    gfx.font = Font("SansSerif", Font.PLAIN, 16)

    panels.forEachIndexed { index, (label, image) ->
        val col = index % columns
        val row = index / columns
        val left = gap + col * (panelW + gap)
        val top = gap + row * (imageH + labelH + gap)
        gfx.color = Color(8, 8, 8)
        gfx.fillRect(left, top, panelW, imageH)

        val scale = minOf(panelW.toDouble() / image.width, imageH.toDouble() / image.height)
        val drawW = (image.width * scale).toInt().coerceAtLeast(1)
        val drawH = (image.height * scale).toInt().coerceAtLeast(1)
        val x = left + (panelW - drawW) / 2
        val y = top + (imageH - drawH) / 2
        gfx.drawImage(image, x, y, drawW, drawH, null)

        gfx.color = Color(232, 230, 224)
        gfx.drawString(label, left + 4, top + imageH + 23)
    }
    gfx.dispose()
    return sheet
}

private fun SceneProfile.summary(): String =
    "  p01=${p01.fmt()} p10=${p10.fmt()} p50=${p50.fmt()} p90=${p90.fmt()} " +
        "p99=${p99.fmt()} range=${dynamicRange.fmt()} sat=${meanSaturation.fmt()} " +
        "clip=${(100f * clippedHighlights).fmt()}% black=${(100f * crushedShadows).fmt()}% " +
        "warm=${neutralWarmth.fmt()} neutral=${neutralConfidence.fmt()} micro=${microContrast.fmt()}"

private fun Float.fmt(): String = "%.3f".format(java.util.Locale.US, this)
