package com.ricohgr3.app.tools

import com.ricohgr3.app.looks.emulation.FaceRegion
import java.io.File

/**
 * macOS review-site counterpart to the app's bundled on-device detector.
 *
 * Apple Vision is used only by the local calibration renderer; it does not ship in the Android
 * app. Other operating systems fail closed with no face regions, matching the production safety
 * rule that a missing detector must never become a global warm-colour key.
 */
internal fun detectFaceRegions(
    repo: File,
    images: List<File>,
): Map<String, List<FaceRegion>> {
    if (images.isEmpty()) return emptyMap()
    if (!System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
        println("Face audit: Apple Vision unavailable on this OS; selective skin is disabled")
        return emptyMap()
    }
    val script = File(repo, "tools/detect-faces.swift")
    if (!script.isFile) {
        println("Face audit: helper not found at $script; selective skin is disabled")
        return emptyMap()
    }

    val command = ArrayList<String>(images.size + 3).apply {
        add("xcrun")
        add("swift")
        add(script.absolutePath)
        images.forEach { add(it.absolutePath) }
    }
    val process = ProcessBuilder(command)
        .directory(repo)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
    val output = process.inputStream.bufferedReader().readLines()
    val exit = process.waitFor()
    if (exit != 0) {
        println("Face audit: Apple Vision helper exited $exit; selective skin is disabled")
        return emptyMap()
    }

    val result = HashMap<String, List<FaceRegion>>(images.size)
    for (line in output) {
        val separator = line.indexOf('\t')
        if (separator < 0) continue
        val index = line.substring(0, separator).toIntOrNull() ?: continue
        val file = images.getOrNull(index) ?: continue
        val encoded = line.substring(separator + 1)
        val regions = if (encoded.isBlank()) {
            emptyList()
        } else {
            encoded.split(';').mapNotNull { item ->
                val values = item.split(',').mapNotNull(String::toFloatOrNull)
                if (values.size != 5) {
                    null
                } else {
                    FaceRegion(
                        left = values[0].coerceIn(0f, 1f),
                        top = values[1].coerceIn(0f, 1f),
                        right = values[2].coerceIn(0f, 1f),
                        bottom = values[3].coerceIn(0f, 1f),
                        confidence = values[4].coerceIn(0f, 1f),
                    )
                }
            }
        }
        result[file.canonicalPath] = regions
    }
    for (file in images) result.putIfAbsent(file.canonicalPath, emptyList())
    return result
}
