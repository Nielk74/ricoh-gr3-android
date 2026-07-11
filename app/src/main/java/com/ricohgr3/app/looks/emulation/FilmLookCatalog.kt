package com.ricohgr3.app.looks.emulation

import com.ricohgr3.app.looks.emulation.FilmLutFactory.Channel
import com.ricohgr3.app.looks.emulation.FilmLutFactory.Model
import com.ricohgr3.app.looks.emulation.FilmLutFactory.crossTalk

/**
 * The curated film-stock emulation set and their LUTs. Every entry is named after a real
 * photographic stock — this is a deliberately tight, hand-tuned catalog (a good ~11 beats 300
 * auto-generated stocks), chosen to span what a GR III shooter actually reaches for: warm
 * colour negatives, a punchy slide, two tungsten cine stocks with halation, and two classic
 * B&W films.
 *
 * Each look pairs a film-density [Model] (baked to a [LutCube] by [FilmLutFactory]) with the
 * parametric spatial layers (split-tone, halation, grain) that make it read as film. The grades
 * are intentionally **strong and clearly film** — the previous set was too subtle.
 *
 * When licensed film-stock `.cube` assets are added, set [FilmLook.lutAsset] and the loader
 * prefers the asset over the procedural model.
 *
 * Pure Kotlin (models + params only); asset loading lives in the Android-side loader.
 */
object FilmLookCatalog {

    /** A look plus the colour model used to synthesise its LUT when no asset is provided. */
    data class Entry(val look: FilmLook, val model: Model)

    val entries: List<Entry> = listOf(
        // ── Kodak Portra 400 ─────────────────────────────────────────────────────────────
        // The reference pro colour negative: warm, restrained saturation, luminous skin,
        // creamy highlight roll-off, gently warm highlights over cool-neutral shadows.
        Entry(
            FilmLook(
                id = "portra400", displayName = "Portra 400", lutAsset = null,
                splitTone = SplitTone(
                    shadowR = 0f, shadowG = 0.01f, shadowB = 0.03f,
                    highR = 0.05f, highG = 0.03f, highB = 0f, amount = 0.45f,
                ),
                grain = GrainParams(amount = 0.045f, size = 2f, shadowBias = 0.6f, seed = 400),
            ),
            Model(
                r = Channel(contrast = 0.34f, toe = 0.04f, shoulder = 0.7f, gain = 1.06f),
                g = Channel(contrast = 0.36f, toe = 0.02f, shoulder = 0.65f, gain = 1.0f),
                b = Channel(contrast = 0.4f, toe = -0.02f, shoulder = 0.55f, gain = 0.94f),
                crossTalk = crossTalk(0.03f, warm = 0.015f),
                saturation = 0.92f,
            ),
        ),
        // ── Kodak Portra 800 ─────────────────────────────────────────────────────────────
        // Faster Portra: warmer and a touch more contrast/saturation, denser shadows, more
        // grain. The low-light wedding stock.
        Entry(
            FilmLook(
                id = "portra800", displayName = "Portra 800", lutAsset = null,
                splitTone = SplitTone(
                    shadowR = 0.01f, shadowG = 0.01f, shadowB = 0.02f,
                    highR = 0.06f, highG = 0.035f, highB = 0f, amount = 0.5f,
                ),
                grain = GrainParams(amount = 0.07f, size = 2.2f, shadowBias = 0.6f, seed = 800),
            ),
            Model(
                r = Channel(contrast = 0.4f, toe = 0.02f, shoulder = 0.65f, gain = 1.08f),
                g = Channel(contrast = 0.42f, toe = 0f, shoulder = 0.6f, gain = 1.0f),
                b = Channel(contrast = 0.46f, toe = -0.04f, shoulder = 0.5f, gain = 0.9f),
                crossTalk = crossTalk(0.035f, warm = 0.02f),
                saturation = 0.98f,
            ),
        ),
        // ── Kodak Gold 200 ───────────────────────────────────────────────────────────────
        // Consumer gold: warm, sunny, nostalgic. Yellow-forward highlights, punchy but not
        // vivid, slightly lifted warm shadows.
        Entry(
            FilmLook(
                id = "gold200", displayName = "Gold 200", lutAsset = null,
                splitTone = SplitTone(
                    shadowR = 0.03f, shadowG = 0.02f, shadowB = 0f,
                    highR = 0.07f, highG = 0.05f, highB = 0f, amount = 0.6f,
                ),
                grain = GrainParams(amount = 0.05f, size = 2f, shadowBias = 0.5f, seed = 200),
            ),
            Model(
                r = Channel(contrast = 0.44f, toe = 0.06f, shoulder = 0.55f, gain = 1.1f),
                g = Channel(contrast = 0.44f, toe = 0.03f, shoulder = 0.5f, gain = 1.0f),
                b = Channel(contrast = 0.5f, toe = 0f, shoulder = 0.45f, gain = 0.85f),
                crossTalk = crossTalk(0.04f, warm = 0.03f),
                saturation = 1.06f,
            ),
        ),
        // ── Kodak Ektar 100 ──────────────────────────────────────────────────────────────
        // The most saturated colour negative: vivid, high-contrast, clean, near-slide punch
        // but with negative latitude. Cool-clean whites, deep reds.
        Entry(
            FilmLook(
                id = "ektar100", displayName = "Ektar 100", lutAsset = null,
                grain = GrainParams(amount = 0.025f, size = 1.5f, shadowBias = 0.6f, seed = 100),
            ),
            Model(
                // Stronger shoulder so the vivid grade rolls highlights off instead of clipping.
                r = Channel(contrast = 0.46f, toe = -0.03f, shoulder = 0.85f, gain = 1.03f),
                g = Channel(contrast = 0.46f, toe = -0.03f, shoulder = 0.85f, gain = 1.0f),
                b = Channel(contrast = 0.48f, toe = -0.04f, shoulder = 0.85f, gain = 0.99f),
                crossTalk = crossTalk(0.02f),
                saturation = 1.2f,
            ),
        ),
        // ── Fujifilm Superia 400 ─────────────────────────────────────────────────────────
        // Classic Fuji consumer neg: green-leaning, cool, punchy, that recognisable Fuji
        // shift in foliage and skies.
        Entry(
            FilmLook(
                id = "superia400", displayName = "Superia 400", lutAsset = null,
                splitTone = SplitTone(
                    shadowR = 0f, shadowG = 0.02f, shadowB = 0.03f,
                    highR = 0f, highG = 0.03f, highB = 0.01f, amount = 0.5f,
                ),
                grain = GrainParams(amount = 0.055f, size = 2f, shadowBias = 0.55f, seed = 401),
            ),
            Model(
                r = Channel(contrast = 0.46f, toe = 0f, shoulder = 0.55f, gain = 0.97f),
                g = Channel(contrast = 0.46f, toe = 0.01f, shoulder = 0.55f, gain = 1.05f),
                b = Channel(contrast = 0.48f, toe = 0f, shoulder = 0.5f, gain = 0.99f),
                // Leak green into red/blue → the Fuji "third colour layer" foliage/sky shift.
                crossTalk = floatArrayOf(
                    0.95f, 0.06f, -0.01f,
                    0.02f, 0.96f, 0.02f,
                    0f, 0.06f, 0.94f,
                ),
                saturation = 1.12f,
            ),
        ),
        // ── Fujifilm Pro 400H ────────────────────────────────────────────────────────────
        // Discontinued fashion favourite: soft, airy, minty-cool with pastel highlights and
        // gentle contrast. The bright-and-clean wedding look.
        Entry(
            FilmLook(
                id = "pro400h", displayName = "Pro 400H", lutAsset = null,
                splitTone = SplitTone(
                    shadowR = 0f, shadowG = 0.02f, shadowB = 0.03f,
                    highR = 0.01f, highG = 0.04f, highB = 0.02f, amount = 0.55f,
                ),
                grain = GrainParams(amount = 0.04f, size = 2f, shadowBias = 0.5f, seed = 402),
            ),
            Model(
                r = Channel(contrast = 0.3f, toe = 0.05f, shoulder = 0.75f, gain = 0.99f),
                g = Channel(contrast = 0.3f, toe = 0.05f, shoulder = 0.72f, gain = 1.03f),
                b = Channel(contrast = 0.32f, toe = 0.04f, shoulder = 0.7f, gain = 1.0f),
                crossTalk = crossTalk(0.03f),
                saturation = 0.9f,
            ),
        ),
        // ── CineStill 800T ───────────────────────────────────────────────────────────────
        // Tungsten-balanced cine stock shot daylight: strong cyan/teal cast, and the signature
        // red halation glowing off every highlight (its anti-halation layer is removed).
        Entry(
            FilmLook(
                id = "cinestill800t", displayName = "CineStill 800T", lutAsset = null,
                splitTone = SplitTone(
                    shadowR = 0f, shadowG = 0.02f, shadowB = 0.07f,
                    highR = 0.02f, highG = 0.02f, highB = 0.01f, amount = 0.6f,
                ),
                halation = HalationParams(
                    threshold = 0.68f, radius = 9, strength = 0.6f,
                    tintR = 1f, tintG = 0.3f, tintB = 0.1f,
                ),
                grain = GrainParams(amount = 0.07f, size = 2.5f, shadowBias = 0.5f, seed = 800),
            ),
            Model(
                r = Channel(contrast = 0.44f, toe = 0f, shoulder = 0.55f, gain = 0.9f),
                g = Channel(contrast = 0.44f, toe = 0.02f, shoulder = 0.55f, gain = 1.0f),
                b = Channel(contrast = 0.46f, toe = 0.05f, shoulder = 0.6f, gain = 1.12f),
                crossTalk = crossTalk(0.03f),
                saturation = 1.02f,
            ),
        ),
        // ── CineStill 400D ───────────────────────────────────────────────────────────────
        // Daylight cine stock: cleaner and warmer than 800T, holds skin well, softer halation.
        // A modern, filmic "daylight" look.
        Entry(
            FilmLook(
                id = "cinestill400d", displayName = "CineStill 400D", lutAsset = null,
                splitTone = SplitTone(
                    shadowR = 0.01f, shadowG = 0.01f, shadowB = 0.03f,
                    highR = 0.04f, highG = 0.03f, highB = 0f, amount = 0.5f,
                ),
                halation = HalationParams(
                    threshold = 0.75f, radius = 7, strength = 0.4f,
                    tintR = 1f, tintG = 0.35f, tintB = 0.14f,
                ),
                grain = GrainParams(amount = 0.045f, size = 2f, shadowBias = 0.55f, seed = 401),
            ),
            Model(
                r = Channel(contrast = 0.4f, toe = 0.02f, shoulder = 0.6f, gain = 1.03f),
                g = Channel(contrast = 0.4f, toe = 0.02f, shoulder = 0.6f, gain = 1.0f),
                b = Channel(contrast = 0.42f, toe = 0f, shoulder = 0.55f, gain = 0.95f),
                crossTalk = crossTalk(0.03f, warm = 0.01f),
                saturation = 1.05f,
            ),
        ),
        // ── Kodak Vision3 500T ───────────────────────────────────────────────────────────
        // The motion-picture negative behind the modern cinema look: wide latitude, gentle
        // toe, cool tungsten shadows, warm highlights — the "teal & orange" base.
        Entry(
            FilmLook(
                id = "vision3_500t", displayName = "Vision3 500T", lutAsset = null,
                splitTone = SplitTone(
                    shadowR = 0f, shadowG = 0.02f, shadowB = 0.06f,
                    highR = 0.06f, highG = 0.03f, highB = 0f, amount = 0.6f,
                ),
                halation = HalationParams(
                    threshold = 0.78f, radius = 6, strength = 0.3f,
                    tintR = 1f, tintG = 0.4f, tintB = 0.18f,
                ),
                grain = GrainParams(amount = 0.05f, size = 2.2f, shadowBias = 0.55f, seed = 500),
            ),
            Model(
                r = Channel(contrast = 0.36f, toe = 0.05f, shoulder = 0.75f, gain = 0.94f),
                g = Channel(contrast = 0.36f, toe = 0.04f, shoulder = 0.72f, gain = 1.0f),
                b = Channel(contrast = 0.38f, toe = 0.06f, shoulder = 0.7f, gain = 1.08f),
                crossTalk = crossTalk(0.04f, warm = 0.01f),
                saturation = 1.0f,
            ),
        ),
        // ── Kodak Tri-X 400 ──────────────────────────────────────────────────────────────
        // The definitive B&W reportage stock: gritty, contrasty, deep blacks, pronounced grain.
        Entry(
            FilmLook(
                id = "trix400", displayName = "Tri-X 400", lutAsset = null,
                grain = GrainParams(amount = 0.09f, size = 2.5f, shadowBias = 0.55f, seed = 320),
            ),
            Model(
                r = Channel(contrast = 0.55f, toe = -0.05f, shoulder = 0.5f),
                g = Channel(contrast = 0.55f, toe = -0.05f, shoulder = 0.5f),
                b = Channel(contrast = 0.55f, toe = -0.05f, shoulder = 0.5f),
                saturation = 0f,
            ),
        ),
        // ── Ilford HP5 Plus 400 ──────────────────────────────────────────────────────────
        // The gentler classic B&W: softer contrast than Tri-X, longer grey scale, forgiving
        // highlights, still that organic grain.
        Entry(
            FilmLook(
                id = "hp5", displayName = "HP5 Plus", lutAsset = null,
                grain = GrainParams(amount = 0.075f, size = 2.4f, shadowBias = 0.5f, seed = 405),
            ),
            Model(
                r = Channel(contrast = 0.4f, toe = 0.04f, shoulder = 0.7f),
                g = Channel(contrast = 0.4f, toe = 0.04f, shoulder = 0.7f),
                b = Channel(contrast = 0.4f, toe = 0.04f, shoulder = 0.7f),
                saturation = 0f,
            ),
        ),
    )

    /** Look ids in catalog order. */
    val ids: List<String> get() = entries.map { it.look.id }

    fun entryFor(id: String): Entry? = entries.firstOrNull { it.look.id == id }
}
