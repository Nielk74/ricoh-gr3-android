package com.ricohgr3.app.tools

import com.ricohgr3.app.looks.emulation.DevelopPipeline
import com.ricohgr3.app.looks.emulation.FilmLook
import com.ricohgr3.app.looks.emulation.FilmLookCatalog
import com.ricohgr3.app.looks.emulation.FilmLutFactory
import com.ricohgr3.app.looks.emulation.LutCube
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * CI/desktop preview renderer for the film-emulation catalog. Runs the **real**
 * [DevelopPipeline] (the same pure-Kotlin colour science the app ships) over a sample photo,
 * once per stock, and writes a labelled PNG per stock into the output dir — so the README can
 * show an honest, reproducible preview of every emulation without a device or emulator.
 *
 * Deliberately JVM-only: it decodes with `javax.imageio` and marshals pixels through
 * [BufferedImage], mirroring what the Android `DevelopEngine` does with `Bitmap`. Because the
 * develop core has no Android deps, the pixels produced here match the on-device export.
 *
 * Args: `<repoRoot> <sampleRelPath> <lutAssetDir> <outDirRel>`.
 */
fun main(args: Array<String>) {
    require(args.size == 4) { "usage: <repoRoot> <sampleRelPath> <lutAssetDir> <outDirRel>" }
    val repo = File(args[0])
    val sample = File(repo, args[1])
    val lutDir = File(repo, args[2])
    val outDir = File(repo, args[3]).apply { mkdirs() }

    require(sample.isFile) { "sample not found: $sample" }
    val src = ImageIO.read(sample) ?: error("could not decode $sample")
    println("Loaded ${sample.name}: ${src.width}x${src.height}")

    // Bound the preview so each thumbnail is small and fast for the README table. Keep aspect.
    // JPEG (not PNG) keeps the committed files ~30–50 KB each instead of >1 MB.
    val maxW = 560
    val work = if (src.width > maxW) scaleTo(src, maxW) else toRgb(src)

    // Standard (as-shot) baseline first.
    writeJpg(work, File(outDir, "standard.jpg"))
    println("  standard -> standard.jpg")

    for (entry in FilmLookCatalog.entries) {
        val look = entry.look
        val lut = loadLut(look, lutDir)
        val out = develop(work, look, lut)
        val name = look.id + ".jpg"
        writeJpg(out, File(outDir, name))
        println("  ${look.displayName} -> $name")
    }
    println("Done: ${FilmLookCatalog.entries.size + 1} previews in ${outDir.relativeTo(repo)}")
}

/** Prefer the `.cube` asset (as the app does); fall back to the procedural model. */
private fun loadLut(look: FilmLook, lutDir: File): LutCube {
    val asset = look.lutAsset
    if (asset != null) {
        // asset paths are like "luts/provia.cube" -> file lutDir/provia.cube
        val file = File(lutDir, File(asset).name)
        if (file.isFile) return LutCube.parse(file.readText())
    }
    return FilmLutFactory.build(FilmLookCatalog.entryFor(look.id)!!.model)
}

/** Run the real develop pipeline on a copy of [src]; returns a new image. */
private fun develop(src: BufferedImage, look: FilmLook, lut: LutCube): BufferedImage {
    val w = src.width; val h = src.height; val n = w * h
    val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
    var i = 0
    for (y in 0 until h) for (x in 0 until w) {
        val p = src.getRGB(x, y)
        r[i] = ((p shr 16) and 0xFF) / 255f
        g[i] = ((p shr 8) and 0xFF) / 255f
        b[i] = (p and 0xFF) / 255f
        i++
    }
    DevelopPipeline.apply(r, g, b, w, h, look, lut)
    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    i = 0
    for (y in 0 until h) for (x in 0 until w) {
        val rr = (r[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
        val gg = (g[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
        val bb = (b[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
        out.setRGB(x, y, (rr shl 16) or (gg shl 8) or bb)
        i++
    }
    return out
}

private fun toRgb(src: BufferedImage): BufferedImage {
    if (src.type == BufferedImage.TYPE_INT_RGB) return src
    val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
    val gfx = out.createGraphics()
    gfx.color = Color.WHITE
    gfx.fillRect(0, 0, out.width, out.height)
    gfx.drawImage(src, 0, 0, null)
    gfx.dispose()
    return out
}

private fun scaleTo(src: BufferedImage, targetW: Int): BufferedImage {
    val h = (src.height.toLong() * targetW / src.width).toInt().coerceAtLeast(1)
    val out = BufferedImage(targetW, h, BufferedImage.TYPE_INT_RGB)
    val gfx = out.createGraphics()
    gfx.setRenderingHint(
        java.awt.RenderingHints.KEY_INTERPOLATION,
        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
    )
    gfx.drawImage(src, 0, 0, targetW, h, null)
    gfx.dispose()
    return out
}

private fun writeJpg(img: BufferedImage, file: File, quality: Float = 0.85f) {
    file.parentFile?.mkdirs()
    val writer = ImageIO.getImageWritersByFormatName("jpg").next()
    val param = writer.defaultWriteParam.apply {
        compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
        compressionQuality = quality
    }
    file.outputStream().use { os ->
        javax.imageio.ImageIO.createImageOutputStream(os).use { ios ->
            writer.output = ios
            writer.write(null, javax.imageio.IIOImage(img, null, null), param)
        }
    }
    writer.dispose()
}
