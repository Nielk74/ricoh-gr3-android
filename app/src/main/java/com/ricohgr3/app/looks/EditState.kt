package com.ricohgr3.app.looks

/**
 * Which photos currently have a look applied, and which look.
 *
 * Drives the "edited mark" UX rule (PHASE7-LOOKS.md §7.2): any frame with a non-[STANDARD]
 * look shows a red dot on its thumbnail / in the viewer. Applying [CameraLook.STANDARD]
 * clears the mark — Standard is the as-shot baseline, i.e. "not edited".
 *
 * Immutable and pure Kotlin (no Android): every mutator returns a new [EditState], so it is
 * JVM-unit-testable and safe to expose as Compose state.
 *
 * @property applied photo id -> the look applied to it. Ids mapped to [CameraLook.STANDARD]
 *   are never stored (they are "not edited"), so the map only ever holds edited frames.
 */
data class EditState(
    val applied: Map<String, CameraLook> = emptyMap(),
) {
    /** True if [id] has a non-Standard look applied. */
    fun isEdited(id: String): Boolean = applied.containsKey(id)

    /** The look applied to [id], or [CameraLook.STANDARD] if none. */
    fun lookFor(id: String): CameraLook = applied[id] ?: CameraLook.STANDARD

    /**
     * Apply [look] to [id]. Applying [CameraLook.STANDARD] resets the frame (clears its
     * edited mark) rather than storing a Standard entry.
     */
    fun apply(id: String, look: CameraLook): EditState =
        if (look == CameraLook.STANDARD) reset(id)
        else copy(applied = applied + (id to look))

    /** Apply [look] to every id in [ids] (Standard resets each, as in [apply]). */
    fun applyAll(ids: Collection<String>, look: CameraLook): EditState =
        if (look == CameraLook.STANDARD) {
            copy(applied = applied - ids.toSet())
        } else {
            copy(applied = applied + ids.associateWith { look })
        }

    /** Clear any look on [id], returning it to the Standard (unedited) baseline. */
    fun reset(id: String): EditState =
        if (applied.containsKey(id)) copy(applied = applied - id) else this
}
