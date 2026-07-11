package com.ricohgr3.app.looks.emulation

/**
 * The curated v1 film-emulation look set and their LUTs. Each look pairs a procedural colour
 * [FilmLutFactory.Model] (the pointwise tone/colour response, baked to a [LutCube]) with the
 * parametric spatial layers (split-tone, halation, grain) that make it read as film.
 *
 * A tight, hand-tuned set beats 300 auto-generated stocks — these are chosen to span the
 * range a GR III shooter actually wants (see `research/FILM_EMULATION.md` §4). When licensed
 * film-stock `.cube` assets are added, set [FilmLook.lutAsset] and the catalog loader prefers
 * the asset over the procedural model.
 *
 * Pure Kotlin (models + params only); asset loading lives in the Android-side loader.
 */
object FilmLookCatalog {

    /** A look plus the colour model used to synthesise its LUT when no asset is provided. */
    data class Entry(val look: FilmLook, val model: FilmLutFactory.Model)

    val entries: List<Entry> = listOf(
        // Warm, restrained colour negative — flattering skin, gentle contrast.
        Entry(
            FilmLook(
                id = "portra400", displayName = "Portra 400", lutAsset = null,
                splitTone = SplitTone(
                    shadowR = 0.02f, shadowG = 0.01f, shadowB = 0.04f,
                    highR = 0.05f, highG = 0.03f, highB = 0f, amount = 0.5f,
                ),
                grain = GrainParams(amount = 0.035f, size = 2f, shadowBias = 0.6f, seed = 400),
            ),
            FilmLutFactory.Model(
                contrast = 0.18f, lift = 0.02f,
                gainR = 1.03f, gainG = 1.0f, gainB = 0.97f,
                gammaR = 1.05f, gammaB = 0.95f, saturation = 0.92f,
            ),
        ),
        // Consumer gold — warm, punchy, nostalgic.
        Entry(
            FilmLook(
                id = "gold200", displayName = "Gold 200", lutAsset = null,
                splitTone = SplitTone(
                    shadowR = 0.03f, shadowG = 0.015f, shadowB = 0f,
                    highR = 0.06f, highG = 0.04f, highB = 0f, amount = 0.6f,
                ),
                grain = GrainParams(amount = 0.045f, size = 2f, shadowBias = 0.5f, seed = 200),
            ),
            FilmLutFactory.Model(
                contrast = 0.22f, gainR = 1.06f, gainB = 0.92f,
                gammaB = 0.9f, saturation = 1.05f,
            ),
        ),
        // Vivid, saturated slide film.
        Entry(
            FilmLook(
                id = "velvia", displayName = "Velvia", lutAsset = null,
                grain = GrainParams(amount = 0.025f, size = 1.5f, shadowBias = 0.7f, seed = 50),
            ),
            FilmLutFactory.Model(
                contrast = 0.30f, gainG = 1.02f,
                gammaB = 1.05f, saturation = 1.35f,
            ),
        ),
        // Tungsten-balanced cinema stock — cyan cast + strong red halation.
        Entry(
            FilmLook(
                id = "cinestill800t", displayName = "CineStill 800T", lutAsset = null,
                splitTone = SplitTone(
                    shadowR = 0f, shadowG = 0.02f, shadowB = 0.06f,
                    highR = 0.03f, highG = 0.02f, highB = 0f, amount = 0.55f,
                ),
                halation = HalationParams(
                    threshold = 0.72f, radius = 8, strength = 0.55f,
                    tintR = 1f, tintG = 0.35f, tintB = 0.12f,
                ),
                grain = GrainParams(amount = 0.06f, size = 2.5f, shadowBias = 0.5f, seed = 800),
            ),
            FilmLutFactory.Model(
                contrast = 0.24f, gainB = 1.08f, gammaR = 0.95f, saturation = 1.0f,
            ),
        ),
        // Faded, warm, low-contrast retro.
        Entry(
            FilmLook(
                id = "retrofade", displayName = "Retro Fade", lutAsset = null,
                splitTone = SplitTone(
                    shadowR = 0.06f, shadowG = 0.04f, shadowB = 0.02f,
                    highR = 0.04f, highG = 0.03f, highB = 0f, amount = 0.7f,
                ),
                grain = GrainParams(amount = 0.05f, size = 2.5f, shadowBias = 0.4f, seed = 1),
            ),
            FilmLutFactory.Model(
                contrast = 0.10f, lift = 0.08f,
                gainR = 1.04f, gainB = 0.9f, saturation = 0.8f,
            ),
        ),
        // Classic B&W — Tri-X-like, gritty.
        Entry(
            FilmLook(
                id = "trix400", displayName = "Tri-X 400", lutAsset = null,
                grain = GrainParams(amount = 0.075f, size = 2.5f, shadowBias = 0.5f, seed = 320),
            ),
            FilmLutFactory.Model(contrast = 0.30f, saturation = 0f),
        ),
    )

    /** Look ids in catalog order. */
    val ids: List<String> get() = entries.map { it.look.id }

    fun entryFor(id: String): Entry? = entries.firstOrNull { it.look.id == id }
}
