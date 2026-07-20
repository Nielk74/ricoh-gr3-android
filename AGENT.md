# Film Lab visual-calibration workflow

Use this workflow whenever changing film colour, tone, skin handling, halation, bloom, grain, or
effect intensity. The work is not complete when tests pass or README thumbnails look plausible.
It is complete when the real pipeline has produced high-resolution examples, those examples have
been inspected at useful zoom levels, and the user has a working local review site on which to
make the final visual call.

## Non-negotiable principles

- Render through the same `SceneAnalyzer`, `FilmLookCatalog`, and `DevelopPipeline` used by the
  app. Never demonstrate a proposed look with browser CSS filters or a separate image recipe.
- Do not ask the user to judge colour science from README thumbnails or contact sheets. Use the
  3000 px review masters.
- Keep the comparison honest: same decoded source, crop, dimensions, colour space, and JPEG
  settings for every look being compared.
- Preserve a before-render before retuning. A new result without its baseline is not a useful
  comparison.
- Prefer semantic, bounded changes over global hue or saturation changes. Protect skin, neutrals,
  unrelated objects, deep black, and pale highlights as appropriate.
- Inspect the result yourself before serving it. Then keep the user in the loop; their visual
  verdict is the final calibration signal.
- Never commit `.references/`, `build/`, focused `/tmp` renders, browser-test dependencies, or
  exported review notes. These are private or generated.
- Do not push, tag, or publish unless the user explicitly requests it.

## Relevant source map

- `app/src/main/java/com/ricohgr3/app/looks/emulation/DevelopPipeline.kt` — processing order and
  spatial/selective effects.
- `app/src/main/java/com/ricohgr3/app/looks/emulation/FilmLook.kt` — look parameter models.
- `app/src/main/java/com/ricohgr3/app/looks/emulation/FilmLookCatalog.kt` — per-stock calibration.
- `app/src/main/java/com/ricohgr3/app/looks/emulation/SkinTone.kt` — complexion likelihood and
  correction.
- `app/src/test/java/com/ricohgr3/app/looks/emulation/` — colour-science regression tests.
- `tools/src/main/kotlin/com/ricohgr3/app/tools/ReviewSiteRenderer.kt` — high-resolution render
  assets and manifest.
- `tools/prepare-review-sources.sh` — JPEG/DNG orientation and display-sRGB normalization.
- `review-site/` — source HTML, CSS, and JavaScript for the review lab.
- `build/review-sources/` — ignored prepared 3000 px inputs.
- `build/film-review/` — ignored full review-site output.
- `docs/previews/` — tracked public README previews; regenerate after a look changes.

Edit `review-site/` or the renderer when improving the website. Never edit generated
`build/film-review/` files as the source of a fix.

## Processing invariants

Keep the photographic order:

```text
decoded scene / optical blur already in source
  → scene analysis and bounded tone protection
  → negative exposure and colour formation
  → print / scan response
  → split tone and selective colour
  → face-gated complexion handling
  → halation / bloom
  → film-plane grain
  → final output resize / sharpening / JPEG
```

Grain remains sharp over defocus and motion blur because it exists at the film plane. Halation
must be built from bright edges, not painted over every red or bright pixel. Selective colour
operations should retain original luminance and compress chroma into gamut with one shared scale
rather than clipping RGB channels independently.

## The calibration loop

### 1. Define one visual hypothesis

State the intended perceptual change before editing, for example:

- top-connected blue sky should be richer and slightly more cyan;
- vegetation should rotate toward cyan-green without moving skin or buildings;
- Portra grain should be more apparent in smooth shadows and midtones;
- CineStill highlight-edge spill should be redder without becoming a uniform bloom.

Change the smallest set of parameters or masks that can test that hypothesis. Avoid combining
unrelated colour, tone, grain, and layout experiments in one loop.

### 2. Select diagnostic scenes

Use two to four focused scenes while tuning:

- blue sky plus non-sky blue objects for sky selectivity;
- grass and trees plus skin, wood, stone, and neutral objects for foliage selectivity;
- multiple complexions and lighting conditions for portrait work;
- smooth defocus, useful shadow, midtone texture, and bright highlights for grain;
- hard practical lights against dark surroundings for halation;
- at least one matched JPEG/DNG pair when decoder behaviour could matter.

One flattering hero image is never enough. Keep the full scene set for the final regression render.

### 3. Preserve the baseline

Before changing code, retain the current relevant masters in a clearly named ignored or temporary
directory such as `/tmp/ricoh-film-before/`. Do not overwrite them during the next render. Compare
old look versus new look as well as original versus new look.

### 4. Implement with measurable protections

Add or update pure-Kotlin tests alongside the change. Tests should measure properties rather than
only assert that pixels differ:

- hue moves toward the intended target by a meaningful minimum;
- chroma increases or decreases by a meaningful minimum;
- Rec.709 luminance remains within a tight tolerance for colour-only changes;
- excluded colours and disconnected semantic regions remain stable;
- arrays stay finite and RGB remains in gamut;
- stronger stock/intensity settings remain ordered relative to weaker settings.

Run the fast gate during iteration:

```bash
./gradlew :tools:test --no-daemon
```

### 5. Render a focused high-resolution site

Prepare the complete source set once:

```bash
./gradlew :tools:prepareReviewSources --no-daemon
```

A focused input directory must contain `jpeg/` and/or `dng/` subdirectories with selected prepared
PNGs. Symlinks to `build/review-sources/` are fine. Render it to a distinct temporary output:

```bash
./gradlew :tools:renderReviewSite \
  -PreviewInput=/tmp/ricoh-film-focus-input \
  -PreviewOutput=/tmp/ricoh-film-focus-review \
  --no-daemon
```

`-PreviewInput=...` is Gradle's `-P` option followed by the `reviewInput` property; likewise for
`reviewOutput`. Supplying it skips full source preparation and makes a focused loop much faster.

Render the full private `.references/` set when the focused result is satisfactory:

```bash
./gradlew :tools:renderReviewSite --no-daemon
```

### 6. Inspect actual pixels

Inspect at least:

- Original, Compare, and Look modes;
- calibrated 100% and stronger 150% effect;
- Fit for the overall grade;
- 100% for local colour, edges, and skin;
- 200% for grain, halation, masking seams, and compression;
- JPEG and DNG versions when both exist;
- Skin mask for every portrait change.

Do not call a selective change successful merely because the global image feels different. Zoom
into the target and protected regions. Watch for neon foliage, cyan spill into neutral surfaces,
sky-mask holes, halos at mask boundaries, hue changes in faces, clipped chroma, repetitive grain,
and flat red highlight cores.

If the requested difference is still not noticeable, strengthen the targeted transform or improve
its likelihood mask. Do not compensate with global saturation.

### 7. Exercise the website

The generated page is part of the deliverable. Check it at a desktop viewport around 1600×1000
and a mobile viewport around 390×844. Browser automation may be installed under `/tmp`; do not
add scratch Node dependencies to the repository.

Verify:

- no console or page errors;
- every rendered scene and look is selectable;
- JPEG/DNG filtering works on desktop;
- 50%, 100%, and 150% intensity states select/blend the correct masters;
- Compare dragging works;
- Fit, 100%, and 200% zoom are real and pannable;
- Open image targets the current high-resolution master;
- the grain shortcut reaches 150% and 200%;
- available film-exposure brackets load the correct assets;
- Keep/Tweak/Reject notes persist and export;
- mobile scene/look strips remain usable;
- the document has no unintended horizontal overflow.

Take desktop and mobile screenshots for your own layout inspection, but judge image quality from
the masters rather than the scaled browser screenshot.

### 8. Serve it and invite a precise review

Serve the focused or full output on all interfaces:

```bash
python3 -m http.server 8765 \
  --bind 0.0.0.0 \
  --directory /tmp/ricoh-film-focus-review
```

Confirm both `http://127.0.0.1:8765/` and the machine's current LAN URL return HTTP 200. Keep the
server alive while awaiting feedback.

Give the user:

- the clickable LAN URL;
- the exact two or three scene names to inspect;
- the relevant look;
- whether to compare 100% against 150%;
- which regions deserve 100% or 200% zoom;
- a concise explanation of what changed and what was deliberately protected.

Then wait for the user's verdict. Do not hide uncertainty behind a long technical explanation.

### 9. Close the loop

After user acceptance, regenerate tracked previews and run the full gate:

```bash
./gradlew :tools:renderPreviews --no-daemon
./gradlew \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug \
  :tools:test \
  --no-daemon
git diff --check
```

Review `git status` and the complete diff. Expected tracked image changes belong in
`docs/previews/`; high-resolution review assets do not. Update `README.md` and relevant research
notes when the model or its guarantees changed.

If publication was requested, stage only the intentional source, tests, documentation, and tracked
previews. Commit tersely, push over the configured SSH remote, and verify that the remote branch
resolves to the new commit.

## Completion checklist

- The requested perceptual change is visible in more than one appropriate scene.
- Protected regions remain natural at 100% and 150%.
- JPEG/DNG and difficult scenes do not reveal a regression.
- Colour-only operations retain luminance and remain in gamut.
- Grain and halation survive 200% inspection without obvious synthetic artifacts.
- Pure colour-science tests and the full Android gate pass.
- The review site passes desktop and mobile interaction checks.
- The user has a working LAN URL and exact review instructions.
- Generated/private artifacts remain untracked.
