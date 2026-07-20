# Film-emulation previews

The README's film-emulation grid is generated automatically from a single neutral GR III photo,
run through the **same pure-Kotlin develop pipeline the app ships** (`DevelopPipeline` +
`SceneAnalyzer` + `FilmLookCatalog`). No device or emulator is involved — the colour-science core
has no Android dependencies, so a plain JVM tool decodes the JPEG, analyses the scene, applies
each adaptive look, and writes the thumbnails.

## Regenerate locally

```bash
./gradlew :tools:renderPreviews
```

This reads:
- the sample photo `docs/preview-src/griii-sample.jpg`,
- the hand-authored negative/print stock models in `FilmLookCatalog`,
- the analytic, non-tiling density-grain model in `DevelopPipeline`,

and writes one thumbnail per look to `docs/previews/*.jpg` (Standard + every stock in
`FilmLookCatalog`). The renderer lives in `tools/` (`:tools`, a JVM-only Gradle module).

## In CI

The **Build** workflow runs `:tools:renderPreviews` on every push/PR and uploads
`docs/previews/` as the **`film-previews`** artifact, so each build carries an up-to-date preview
even before the committed copies are refreshed. The committed `docs/previews/*.jpg` are what the
README embeds; regenerate and commit them whenever the looks change (the CI artifact makes drift
easy to spot).

## Adding / changing a look

Because previews are derived from the catalog, adding a stock or retuning one needs no preview
edits — just run `renderPreviews` and commit the regenerated `docs/previews/`, then add the new
cell to the README grid.

## Multi-scene calibration

One pleasant hero image is not enough to tune an adaptive look. For local work, put owned GR III
JPEGs/PNGs in the ignored `.references/` folder and run:

```bash
./gradlew :tools:renderReferences
```

This writes contact sheets and `scene-report.txt` under the ignored
`build/reference-renders/`. The report records tonal percentiles and the exact exposure,
highlight, look-mix, saturation, and grain decisions for every scene. DNGs can be rendered to
temporary PNG previews by the host OS and passed with `-PreferenceInput=/path/to/previews`.

## High-resolution interactive review

For pixel-level comparison of the JPEG and DNG camera examples, build the local review lab:

```bash
./gradlew :tools:renderReviewSite
python3 -m http.server 8765 --directory build/film-review
```

Open `http://localhost:8765`. The site keeps every scene at a 3000 px long edge, offers
original/developed split comparison plus 100% and 200% zoom, and records local Keep/Tweak/Reject
notes for export. The Skin mask view shows the face-detector-gated chromaticity mask in cyan, so
spill onto clothing, décor, hair, glasses, and beard can be audited at full resolution. Its
50–150% intensity slider blends between original, a real calibrated 100% master, and a real 150%
master. The Android preview/export path evaluates the same strength directly; 100% and 150%
therefore match the site exactly. For Portra 400, CineStill 800T, and Vision3 250D, the
**Film exposure** buttons load real −1/0/+1-stop renders made before negative dye formation; they
are not post-render brightness changes. **Inspect grain** jumps directly to the developed image at
150% effect and 200% zoom; the same control remains visible in the mobile layout.

## Licensing / attribution

- **Sample photo** — a `RICOH GR III` sample-gallery image (downscaled). Copyright remains its
  author's; it is included here purely to illustrate the looks. If redistribution is ever a
  concern (e.g. a public release), swap `docs/preview-src/griii-sample.jpg` for an image you own
  or a CC0 photo and re-run the renderer — nothing else changes.
- **Stock transforms and grain** — hand-authored/analytic code in this repository. Stock names are
  descriptive aesthetic targets, not claims of measured manufacturer colourimetry.
