package com.ricohgr3.app.looks

import com.ricohgr3.app.looks.emulation.RenderingIntent

/**
 * Which photos currently have a film look applied, and which look.
 *
 * A look is identified by its **film-stock id** (`FilmLookCatalog` ids like `"portra400"`), or
 * `null` for the as-shot baseline ("Standard" — not edited). Applying `null` clears the mark.
 *
 * Drives the "edited mark" UX rule (PHASE7-LOOKS.md §7.2): any frame with a non-null look shows
 * a red dot on its thumbnail / in the viewer.
 *
 * Immutable and pure Kotlin (no Android): every mutator returns a new [EditState], so it is
 * JVM-unit-testable and safe to expose as Compose state.
 *
 * @property applied photo id -> the film-stock id applied to it. Ids mapped to Standard (`null`)
 *   are never stored (they are "not edited"), so the map only ever holds edited frames.
 */
data class EditState(
    val applied: Map<String, String> = emptyMap(),
    /** Per-frame effect multiplier (`1f` = authored stock baseline, clamped to 0.5–1.5). */
    val intensities: Map<String, Float> = emptyMap(),
    /** Per-frame renderer contract. Missing entries migrate safely to the protected Smart path. */
    val renderingIntents: Map<String, RenderingIntent> = emptyMap(),
) {
    /** True if [id] has a (non-Standard) film look applied. */
    fun isEdited(id: String): Boolean = applied.containsKey(id)

    /** The film-stock id applied to [id], or `null` (Standard) if none. */
    fun lookFor(id: String): String? = applied[id]

    /** Effect multiplier for [id], defaulting to the authored 100% when absent/unedited. */
    fun intensityFor(id: String): Float = intensities[id]?.coerceIn(0.5f, 1.5f) ?: 1f

    /** Renderer contract for [id], defaulting to Smart for edits made by older app versions. */
    fun renderingIntentFor(id: String): RenderingIntent =
        renderingIntents[id] ?: RenderingIntent.SMART

    /**
     * Apply film-stock [look] to [id]. Applying `null` (Standard) resets the frame (clears its
     * edited mark) rather than storing an entry.
     */
    fun apply(
        id: String,
        look: String?,
        intensity: Float = 1f,
        renderingIntent: RenderingIntent = RenderingIntent.SMART,
    ): EditState =
        if (look == null) reset(id)
        else copy(
            applied = applied + (id to look),
            intensities = intensities + (id to intensity.coerceIn(0.5f, 1.5f)),
            renderingIntents = renderingIntents + (id to renderingIntent),
        )

    /** Apply [look] to every id in [ids] (`null` resets each, as in [apply]). */
    fun applyAll(
        ids: Collection<String>,
        look: String?,
        intensity: Float = 1f,
        renderingIntent: RenderingIntent = RenderingIntent.SMART,
    ): EditState =
        if (look == null) {
            copy(
                applied = applied - ids.toSet(),
                intensities = intensities - ids.toSet(),
                renderingIntents = renderingIntents - ids.toSet(),
            )
        } else {
            copy(
                applied = applied + ids.associateWith { look },
                intensities = intensities + ids.associateWith { intensity.coerceIn(0.5f, 1.5f) },
                renderingIntents = renderingIntents + ids.associateWith { renderingIntent },
            )
        }

    /** Clear any look on [id], returning it to the Standard (unedited) baseline. */
    fun reset(id: String): EditState =
        if (
            applied.containsKey(id) ||
            intensities.containsKey(id) ||
            renderingIntents.containsKey(id)
        ) {
            copy(
                applied = applied - id,
                intensities = intensities - id,
                renderingIntents = renderingIntents - id,
            )
        } else {
            this
        }
}
