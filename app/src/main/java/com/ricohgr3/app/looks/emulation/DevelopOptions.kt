package com.ricohgr3.app.looks.emulation

/**
 * Separates the reproducible photographic stock response from optional content-aware product
 * safeguards.
 *
 * [STOCK] is the calibration path: identical input values receive identical stock rendering
 * regardless of surrounding scene semantics. [SMART] retains the app's conservative scene
 * normalization, face-gated complexion protection, semantic foliage/sky handling, and restrained
 * scene-guarded warmth for explicitly daylight-balanced colour profiles.
 */
enum class RenderingIntent {
    STOCK,
    SMART,
}

/** Physical capture aperture represented by the emulation's spatial layers. */
data class FilmFormat(
    val widthMillimetres: Float,
    val heightMillimetres: Float,
) {
    init {
        require(widthMillimetres.isFinite() && widthMillimetres > 0f)
        require(heightMillimetres.isFinite() && heightMillimetres > 0f)
    }

    companion object {
        /** GR III field-of-view rendered as the familiar 36×24 mm still-film format. */
        val FULL_FRAME_35MM = FilmFormat(36f, 24f)
    }
}

/**
 * Per-render context that is deliberately not part of a stock definition.
 *
 * @property intent fixed stock response or optional content-aware rendering.
 * @property renderSeed stable identity of the photograph. Stock seed and render seed are combined,
 *   so repeated renders of one photograph match while different photographs cannot share a grain
 *   field.
 * @property filmFormat physical frame used to make grain and diffusion resolution-independent.
 * @property sceneProfile optional canonical profile calculated once and reused across preview and
 *   export. If absent, the pipeline calculates the same canonical profile from its input.
 * @property filmPlane optional crop mapping into the photograph's full physical film frame. A
 *   tiled export supplies origins here so adjacent regions sample one continuous grain field.
 * @property spatialLongEdgePixels optional full-frame long-edge length. A tiled export supplies
 *   it so diffusion and halation retain their whole-image physical scale even when a region does
 *   not itself span the photograph's long edge.
 * @property grainEnabled whether to render the stock's physical grain layer. Disabling it leaves
 *   the authored colour, tone, diffusion, halation, and Smart safeguards unchanged.
 * @property skinMaskMapping optional whole-frame skin proxy positioned over this render region.
 *   Tiled exports use it so a face is classified once rather than independently at every join.
 * @property onSkinMaskReady internal analysis hook invoked immediately after the pre-stock skin
 *   proxy is available. Production preview analysis uses it to capture that proxy once.
 */
data class DevelopOptions(
    val intent: RenderingIntent = RenderingIntent.SMART,
    val renderSeed: Long = 0L,
    val filmFormat: FilmFormat = FilmFormat.FULL_FRAME_35MM,
    val sceneProfile: SceneProfile? = null,
    val filmPlane: PhysicalFilmGrain.FilmPlane? = null,
    val spatialLongEdgePixels: Int? = null,
    val grainEnabled: Boolean = true,
    val skinMaskMapping: SkinMaskMapping? = null,
    val onSkinMaskReady: ((SkinMask?) -> Unit)? = null,
)

/** Stable FNV-1a identity hash; unlike String.hashCode it supplies a useful 64-bit grain seed. */
fun stableRenderSeed(identity: String): Long {
    var hash = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis as a signed Long.
    for (byte in identity.encodeToByteArray()) {
        hash = hash xor (byte.toLong() and 0xffL)
        hash *= 0x100000001b3L
    }
    return hash
}
