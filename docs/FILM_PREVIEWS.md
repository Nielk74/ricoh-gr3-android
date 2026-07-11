# Film-emulation previews

The README's film-emulation grid is generated automatically from a single neutral GR III photo,
run through the **same pure-Kotlin develop pipeline the app ships** (`DevelopPipeline` +
`FilmLookCatalog`). No device or emulator is involved — the colour-science core has no Android
dependencies, so a plain JVM tool decodes the JPEG, applies each look, and writes the thumbnails.

## Regenerate locally

```bash
./gradlew :tools:renderPreviews
```

This reads:
- the sample photo `docs/preview-src/griii-sample.jpg`,
- the film-sim LUTs in `app/src/main/assets/luts/`,

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

## Licensing / attribution

- **Sample photo** — a `RICOH GR III` sample-gallery image (downscaled). Copyright remains its
  author's; it is included here purely to illustrate the looks. If redistribution is ever a
  concern (e.g. a public release), swap `docs/preview-src/griii-sample.jpg` for an image you own
  or a CC0 photo and re-run the renderer — nothing else changes.
- **Film-simulation LUTs** — `.cube` files from
  [`abpy/FujifilmCameraProfiles`](https://github.com/abpy/FujifilmCameraProfiles), derived from
  Adobe camera-matching profiles; the source repo carries no explicit licence. See
  `research/FILM_EMULATION.md` §4a for the full note. Same caveat applies before any public
  distribution.
