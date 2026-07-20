# Phase 7 — Signature Features: Looks, Auto-Look & Develop

The one distinctive thing this app does that the official Ricoh app doesn't: make the
GR III's *looks* fast, intuitive, and eventually unlimited. This is the plan, grounded
in the real Wi-Fi `/v1/params/camera` enums (source:
`research/references/ricoh-wireless-protocol/definitions/capture_ricoh_gr_iii.yaml`).

Design language: **Concept A ("Contact Sheet")** — mostly-white paper, Ricoh red as the
sole accent, mono metadata. See `app/src/main/java/com/ricohgr3/app/ui/theme/`.

---

## Decisions locked with the user

- **Start on-camera, grow in-app.** v1 pushes the camera's own Custom Image / effect
  before capture (authentic, zero processing). The in-app RAW develop engine comes later.
- **Library is the hero.** Looks live in the library/edit flow, not buried in settings.
- **Intuitive editing is a hard requirement:**
  - **Edited mark** — any frame with a look applied shows a small red dot / glyph on its
    thumbnail (contact-sheet grid + viewer), so "what have I touched?" is answerable at a glance.
  - **Sticky default** — the *last-used look* is pre-selected for the next frame, so styling
    a roll is one tap per frame (or "Apply to all new").
  - **Auto-look** — an optional smart pick per scene (kept from Concept B).

---

## The looks (real GR III `effect` enum → UI)

`PUT /v1/params/camera` with `effect=<val>`. These are the exact values the camera accepts:

| UI name (Custom Image) | `effect` value        | Notes |
|------------------------|-----------------------|-------|
| Standard               | *(unset / default)*   | as-shot baseline |
| Vivid                  | `col_vivid`           | punchy color |
| Positive Film          | `efc_posiFilm`        | the signature GR slide-film look |
| Bleach Bypass          | `efc_bleachBypass`    | desaturated, silvery |
| Retro                  | `efc_retro`           | faded warm |
| HDR Tone               | `efc_HDRTone`         | tone-mapped |
| Monochrome             | `efc_monochrome`      | B&W |
| Soft Monochrome        | `efc_softMonochrome`  | low-contrast B&W |
| Hard Monochrome        | `efc_hardMonochrome`  | high-contrast B&W |
| B&W (Hi-Contrast)      | `efc_highContrast`    | punchy B&W |
| Custom 1 / Custom 2    | `col_custom1` / `col_custom2` | user's in-camera custom slots |

Related params we already model in `wifi/RicohApiModels.kt` (`CaptureParams`) and can pair
with a look: `sv` (ISO), `tv` (shutter), `av` (aperture), `xv` (exposure comp),
`WBMode` (white balance), `meteringMode`, `focusMode`, `shootMode`.

> **Important caveat to verify on-device:** `effect` is a *capture-time* camera setting.
> Pushing it changes how the **next** JPEG is developed in-camera. It does **not**
> re-develop already-captured frames. That's why v1's "apply a look" for existing shots is
> really "apply to the next captures"; true per-existing-frame looks need the in-app engine
> (Phase 7.3). The UI copy must not promise retroactive on-camera looks.

---

## Phase 7.1 — On-camera looks (ships first)  (M)

Goal: pick a look in the app → camera shoots that way. Authentic, tiny build.

- [ ] `LookRepository`: the table above as a typed enum (`CameraLook`) → `effect` value +
      display name + a swatch gradient (for the picker) + a cheap on-device preview LUT
      (approximate, for the thumbnail — not a promise of exact output).
- [ ] Wire `CameraWifiController.setCameraParams(effect = …)` to a look picker in the
      **Camera/live-view** flow (the one place a look genuinely affects capture).
- [ ] **Sticky selection**: persist last-used look (DataStore); pre-select it next launch.
- [ ] Confirm-on-camera feedback: read back `/v1/props` `effect` and show the active look
      as a mono chip in the viewfinder.
- [ ] On-device verification against a real GR III (values are from the spec, not yet
      hardware-confirmed).

## Phase 7.2 — Look UX in the library  (M)

Goal: the "intuitive editing" requirements, made concrete.

- [ ] **Edited mark**: contact-sheet thumbnails + viewer show a small red dot when a look is
      applied (in-app look for transferred frames; distinct glyph for "shot with effect X").
- [ ] **Sticky default across frames**: styling frame N pre-selects the same look on N+1.
- [ ] **Batch apply**: "Apply to all new" / multi-select apply.
- [ ] **Before/after** toggle in the viewer (press-and-hold to see the original).
- [ ] **Reset**: one tap back to Standard; edited mark clears.

## Phase 7.3 — In-app develop engine (the big one)  (L)

Goal: unlimited, shareable looks applied to transferred **JPEG and RAW (DNG)** — no longer
limited to the camera's built-in set. This is where "film emulations" become truly ours.
Research + design: **`research/FILM_EMULATION.md`**.

- [x] **CPU adaptive film engine** — pure-Kotlin, JVM-tested, in `looks/emulation/`:
      `SceneAnalyzer` (robust percentiles/cast/texture → bounded per-frame decisions),
      `FilmLutFactory` (display-referred print curve + linear dye cross-talk), `DevelopPipeline`
      (adaptive tone/mix + skin protection → luminance-neutral split-tone → connected-sky colour
      → stock-coloured edge-only halation → non-tiling ISO-aware density grain), and
      `DevelopEngine` (Bitmap glue). The 11-stock catalog includes
      Portra 400/800, Gold, Ektar, Superia, CineStill/Vision3, Eterna, Tri-X, and HP5.
      Preview and edited export use the same render.
- [x] **Stock intensity** — a persisted 50–150% control scales LUT, split tone, grain, and
      halation while bounded scene protection stops at its calibrated 100% value. Per-frame,
      sticky-next-frame, batch-apply, preview, and edited export all carry the same strength.
- [x] **High-resolution review lab** — `:tools:renderReviewSite` generates 3000 px original,
      100%, and 150% masters for every local JPEG/DNG example; the browser UI provides split
      compare, 100/200% inspection, intensity interpolation, and exportable review notes.
- [x] **Licence-clean runtime assets** — active stock transforms are authored in-repo; no
      unidentified third-party `.cube` or third-party grain scan is required.
- [x] **Multi-scene calibration loop** — `:tools:renderReferences` produces contact sheets plus
      exact scene decisions from ignored local GR III references; `:tools:test` runs the pure
      colour-science suite even on a host without the Android SDK.
- [ ] **AGSL/`RuntimeShader` preview fast-path** (API 33+) for live grid/viewer preview;
      keep the CPU engine as the universal + export path (min SDK 26). No RenderScript.
- [ ] DNG decode (transferred via `downloadPhoto(... raw)`), demosaic to a working buffer.
- [ ] Non-destructive edit stack per photo; render on export.

## Phase 7.4 — Auto-Look  (M) — kept from Concept B

Goal: the app suggests a look per scene. Opt-in, never automatic without consent.

- [ ] v1 heuristic: from EXIF + a downscaled thumbnail — scene brightness, saturation,
      face presence, indoor/outdoor → map to a look (e.g. flat overcast → Positive Film;
      harsh midday street → Hard Monochrome). Fully on-device, explainable.
- [ ] Surface as a dashed "Auto ✦" card (as in the concept); one tap to accept/override.
- [ ] v2 (optional): tiny on-device classifier (TFLite) trained on look/scene pairs.

## Phase 7.5 — Presets & sharing  (M)

- [ ] Save an edit stack (7.3) as a named preset.
- [ ] Export/import presets as a small shareable file (no cloud; privacy story intact).
- [ ] Apply a preset on import / batch.

---

## Build order & dependencies

```
7.1 on-camera looks ─┬─▶ 7.2 library look UX ─┬─▶ 7.4 Auto-Look
 (needs Wi-Fi + live  │   (needs gallery UI,   │   (needs EXIF + thumbs)
  view UI, Phase 6)   │    Phase 6)            │
                      └─▶ 7.3 develop engine ──┴─▶ 7.5 presets
                          (independent heavy R&D; can start in parallel)
```

7.1/7.2/7.4 depend on **Phase 6** screens (viewfinder + gallery) existing. 7.3 is
self-contained image-processing R&D and can be prototyped independently.

## Open questions for later

- Exact on-device look previews: approximate LUTs are fine for the picker, but do we want
  the thumbnail to match the camera's actual JPEG rendering? (Would need captured samples.)
- Do we let users push a look as a **Custom Image slot** (`col_custom1/2`) so it persists on
  the camera body? The `effect` enum exposes those slots.
- RAW develop is a large surface — worth confirming appetite before investing in 7.3.
