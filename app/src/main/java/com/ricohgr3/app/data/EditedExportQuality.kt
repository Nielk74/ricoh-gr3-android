package com.ricohgr3.app.data

/**
 * User-visible quality presets for a developed (edited) JPEG.
 *
 * Resolution and JPEG encoding are intentionally one choice: both affect the visible detail and
 * file size, and neither should be an invisible implementation decision. [MAXIMUM] removes the
 * normal resolution cap, but the export path still applies its device-heap safety ceiling.
 */
enum class EditedExportQuality(
    val displayName: String,
    val jpegQuality: Int,
    internal val pixelCap: Int,
) {
    /** Small sharing copy: one quarter of the High pixel count and a lighter JPEG encode. */
    COMPACT(displayName = "Compact", jpegQuality = 92, pixelCap = 1_500_000),

    /** Former default, retained as an explicit faster 6 MP choice. */
    HIGH(displayName = "High", jpegQuality = 97, pixelCap = 6_000_000),

    /** Full-size for disk-backed auto-import; highest heap-safe resolution on direct save paths. */
    MAXIMUM(displayName = "Maximum", jpegQuality = 100, pixelCap = Int.MAX_VALUE),
}
