# Film Emulation — science, libraries, and the plan for this app

Research backing the "much better filters" work. Grounded in how real film emulation is
done in colour-grading tools and the constraints of this Android app (min SDK 26,
compile SDK 34, Jetpack Compose, no RenderScript).

---

## Current implementation (adaptive pipeline, July 2026)

The early fixed-LUT experiments documented later in this file are historical. The shipped path is
now designed around the fact that both camera JPEGs and Android-rendered DNGs are already
display-referred photographs:

1. `SceneAnalyzer` samples up to roughly 50k pixels and measures robust luminance percentiles,
   clipped/crushed area, colourfulness, low-chroma warm/cool bias, and micro-contrast.
2. It derives bounded decisions for exposure, shadow separation, highlight shoulder, saturation,
   LUT mix, halation, and grain. It deliberately preserves night, backlight, tungsten, and
   blue-hour intent; this is input normalisation, not aggressive auto-enhance/auto-WB.
3. `FilmLutFactory` builds licence-clean 33³ stock transforms as an explicit two-stage system:
   per-channel negative dye density in bounded log-exposure space, density-layer coupling, then
   an independent positive print/scan characteristic with its own contrast, toe, shoulder,
   print-light balance, black point, and paper white. Divergent channel curves create
   exposure-dependent colour crossover instead of a constant tint.
4. The stock transform is blended rather than blindly replacing every pixel. Existing strong
   casts reduce added stock colour. For colour stocks, a bundled on-device face detector first
   establishes a semantic region; normalized-RGB chromaticity then trims hair, glasses, beard,
   clothing, and background. The accepted complexion is corrected after LUT + split tone around
   the rendered luminance, preserving face lighting and texture. If face detection fails, the
   correction fails closed instead of becoming a global warm-colour key.
5. Split toning is luminance-neutral. Portra's blue-to-cyan response is limited to blue regions
   connected to the top frame edge, so it changes sky rather than clothing/signage. A separate
   hue/chroma/luminance gate moves vegetation-range yellow-greens slightly toward cyan-green while
   excluding skin, neutrals, existing cyan, deep shadows, and pale highlights. Both selective
   transforms restore the original luminance. Halation is a linear-light, edge-only spill whose
   radius follows output size; the bright source core is subtracted so flat highlights do not
   turn red. CineStill uses a high-threshold red fringe while the quieter cinema stocks retain
   warmer, weaker spill.
6. Grain perturbs optical-density-like luminance from a deterministic, non-tiling coordinate
   field correlated only across immediate neighbours. A bounded zero-mean cubic crystal term
   gives fast stocks heavier, irregular density tails without a blurred low-frequency layer. It
   is sharp on the film plane, weighted toward shadows and midtones, resolution-scaled, and
   gently reduced when the digital source is already high-ISO/noisy. Low-key frames are not
   globally suppressed; only printed black and bright highlights strongly mask grain.
7. DNG and wide-gamut inputs are explicitly converted to sRGB before the sRGB-authored pipeline.
8. A persisted 50–150% stock-strength control scales colour and emulsion layers. Tonal scene
   protection fades below 100% but is not amplified above it, preventing a stronger look from
   becoming aggressive auto-HDR.
9. The review lab renders true −1/0/+1-stop negative-input brackets for selected stocks. Because
   the source is already a rendered JPEG or DNG preview, the bracket uses a bounded linear-light
   log-odds shift: it behaves like exposure through the shadows and midtones while retaining
   existing highlight separation for the stock shoulder instead of clipping before the LUT.

The calibration loop is reproducible: `./gradlew :tools:renderReferences` renders all local
`.references` JPEG/PNG scenes and writes contact sheets plus exact scene decisions under
`build/reference-renders/`. Pure colour-science tests also run without an Android SDK via
`./gradlew :tools:test`.

The current curated set is Portra 400/800, Gold 200, Ektar 100, Superia 400, CineStill 800T,
Vision3 250D/500T, Eterna Cinema, Tri-X 400, and HP5 Plus. Names describe aesthetic emulations,
not measured manufacturer profiles. No third-party film LUT is required at runtime.

---

## 1. Why the original "filters" were weak

Two things exist today and neither actually *renders* a look onto a captured photo:

1. **Camera-side `effect` enums** (`CameraLook.kt`) — `col_vivid`, `efc_posiFilm`, … are
   pushed to the camera via `PUT /v1/params/camera` *before capture*. Authentic, but:
   only ~11 fixed looks, only applies to *future* frames, nothing you can do to an
   already-captured JPEG, and no on-device preview of the real result.
2. **`LookSwatch.kt`** — a two-stop gradient per look. Purely indicative chips; not the
   image. The "edited" download path has no real pixels to export.

So "better filters" = **a real client-side develop engine** that renders a look onto a
captured bitmap, on-device, with quality comparable to Lightroom/Resolve film LUTs.

---

## 2. How film emulation actually works (the science)

A convincing film look is *not* one operation. It is a stack, applied in a defined order.
Every serious tool (DaVinci Resolve film-emulation powergrades, FilmBox, RawTherapee /
darktable film simulations, Fuji's in-camera Film Simulation) is some subset of:

1. **Input linearisation / working space.** Do the colour math in a linear-ish space, not
   raw sRGB gamma, or hue shifts and blends look wrong. At minimum, decode sRGB → linear
   before tone/colour ops, re-encode after.

2. **Tone curve (the biggest single contributor).** Film's characteristic S-curve: a toe
   that lifts and compresses shadows, a shoulder that rolls off highlights instead of
   clipping. This is what gives "film" its gentle contrast. Implemented as a 1D curve per
   channel (or on luma). Kodak vs Fuji differ mostly here + in colour.

3. **Colour response / channel cross-talk.** Film dyes are not clean RGB. Emulation shifts
   hues per region (e.g. greens toward yellow, skies toward cyan), changes saturation
   non-uniformly (Velvia: high sat; Portra: restrained, warm skin), and does *channel
   mixing* (a bit of red leaks into green, etc.). This is why a 3D LUT is the right tool —
   it captures arbitrary colour-to-colour maps a per-channel curve can't.

4. **Split toning.** Different tint in shadows vs highlights (classic: teal shadows, warm
   highlights; or the faded "retro" warm shadow). Cheap, huge stylistic payoff.

5. **Halation.** Red-orange light scattered through the emulsion around bright edges.
   Implemented as: build a smooth linear-light highlight mask → blur it → subtract the source
   core → weight receiving darker pixels → red-dominant screen composite. Subtracting the core
   is essential: a uniform bright field receives no red fog and the highlight itself stays clean.

6. **Grain.** `logit(I_out) = logit(I) + A(I)·G` (see §2.6 for the shipped model). `G` is
   non-tiling, locally correlated density grain; `A(I)` is a shadow-biased, **midtone-peaked**
   response. Useful shadows and midtones show the structure most clearly, while printed black and
   bright highlights mask it. The field is correlated (not per-pixel white noise), varies in
   size/clumping, and is correlated-but-distinct across R/G/B (identical mono = flat; independent
   RGB = electronic).

7. **Optional: vignette, soft bloom, slight desaturation of extremes, black/white point.**

**Order matters:** linearise → tone → colour(LUT) → split-tone → selective complexion colour →
selective foliage colour → selective sky colour → halation → (re-encode) → grain → vignette.
Grain and vignette go last, in display space.

### The 3D LUT is the workhorse
A **3D LUT** partitions RGB space into a grid (typically 17³, 33³, or 64³); each vertex
holds an output RGB. At runtime you find the cube a pixel falls in and **trilinear-
interpolate** the 8 corners. One lookup captures tone + colour + cross-talk together — which
is exactly why the industry ships film looks as `.cube` files. Split-tone, face-gated complexion
colour, selective foliage/connected-sky colour, halation, and grain are layered *around* the LUT
because they're spatial or parametric and don't belong in a pointwise colour map.

**File formats:** `.cube` (Adobe/Resolve text format — easiest to parse), `.3dl`, and
**HaldCLUT** (a PNG that is the identity LUT laid out as an image; apply any edit to the
Hald PNG in an editor and it *becomes* a LUT). HaldCLUT is attractive here because it ships
as a plain PNG asset and there are large free, permissively-usable collections.

### 2.6 Grain model (shipped, `DevelopPipeline.applyGrain`)

Realistic film grain, `logit(I_out) = logit(I) + A(I)·G`, checked against the film-grain
guideline:

- **`G` — the grain field (shipped approach): a coordinate-hash density field.** The retired
  512px texture plate produced repeating, low-frequency camouflage blotches at 100% zoom.
  `DevelopPipeline.applyGrain` now hashes absolute output coordinates and performs a compact 3×3
  convolution. Correlation never extends beyond immediate neighbours, so crystal size can change
  without scaled textures, blurry octaves, tiling, or density clouds. The kernel is
  variance-normalized to zero mean and stable strength. Tiny channel differences come from
  neighbouring taps of the same luma crystal, keeping RGB correlated rather than electronic.
- **Fast-stock clumping**: a bounded zero-mean cubic term changes the density distribution rather
  than overlaying a second noise map. Portra 800, CineStill 800T, Tri-X, and HP5 therefore gain
  occasional denser crystals, especially in shadows, while fine stocks stay closer to a clean
  Gaussian field. A high-frequency amplitude jitter breaks uniformity without adding clouds.
- **`A(I)` — the strength**: a **midtone-peaked hump** (`grainDensity`) that falls off in both the
  deepest shadows *and* the brightest highlights (real silver-grain density), biasable toward the
  shadows by `shadowBias`. Measured: peak ~0.95 at luma 0.25–0.5, ~0.54 at black, ~0.04 near white.
- **Detail/sharpness**: grain strength is applied **independently of image sharpness** after lens
  blur, motion blur, colour, and halation. Smooth/defocused regions reveal the same sharp
  film-plane field more clearly because less scene detail competes with it; no edge/detail mask is
  used.
- **Blend**: grain perturbs log-odds/optical-density-like luminance, then rescales RGB by the new
  luminance to preserve hue. Unlike flat addition, this keeps black/white endpoints fixed and does
  not wash the frame toward grey. The tiny chroma vector is constructed to be Rec.709
  luminance-neutral; `chroma` stays low because strong colour speckle reads as electronic.
- **Order/perf**: grain is **last**, in display space, before JPEG compression. Runs off the main
  thread; peak memory bounded (≤ ~4 float buffers over the ~6 MP edit image) to respect the
  no-crash / no-OOM rule.

Per-stock density amount ranges from 0.018 (Ektar 100, finest) to 0.088 (Portra 800, the most
pronounced colour stock in the calibrated set); the edited JPEG export uses quality 97 so the
final encode does not immediately erase the fine structure. The cross-scanner evidence,
matched-output measurements, and Portra 400/800 decision are recorded in
[`PORTRA_GRAIN_CALIBRATION.md`](PORTRA_GRAIN_CALIBRATION.md).

---

## 3. Libraries / assets surveyed

| Source | What it gives | License / usability |
|---|---|---|
| **G'MIC / "film emulation" HaldCLUTs** (Pat David / rawtherapee set) | ~300 film-stock HaldCLUT PNGs (Fuji, Kodak, Polaroid, Agfa…) generated from real stock scans | Public-domain / CC0-ish — the community set is widely redistributed. **Verify each file's license before shipping.** |
| **RawTherapee / darktable film-simulation packs** | Same Hald PNG collection, documented | GPL tooling, CC0 luts |
| **Adobe `.cube` film LUTs (free packs)** | `.cube` 3D LUTs | Mixed — many "free" packs are *not* redistributable. Avoid bundling unless CC0/MIT. |
| **Android `RuntimeShader` / AGSL** | GPU shader path for the LUT + halation + grain | Platform API, **SDK 33+ only** — needs a fallback |
| **`ScriptIntrinsic3DLUT`** (RenderScript) | Ready-made trilinear 3D LUT on GPU | **Deprecated (API 31), being removed — do not use** |
| **OpenGL ES 2/3** | Portable GPU path (sampler for LUT, fragment shader for grain/halation) | Works from API 26; more boilerplate |
| **Pure Kotlin CPU** | Trilinear LUT + curves on an `IntArray` of pixels | Works everywhere; slow on full-res but fine as fallback / for export off the UI thread |

### Rendering-path decision for THIS app (min SDK 26)
- **AGSL (`RuntimeShader` + `RenderEffect`)** is the cleanest but is **API 33+**. Great for
  the live *preview* on new devices.
- **CPU trilinear-interpolated LUT** in pure Kotlin is the **portable baseline** — runs on
  all supported devices, is unit-testable on the JVM (no device needed, matching this repo's
  testing constraint), and is correct for the **export/download** path where latency is fine.
- **Plan:** ship the **CPU engine first** (correct, testable, powers edited-export), add an
  **AGSL fast-path for preview** behind an SDK-33 check later. Avoid RenderScript entirely.

The shipped looks are licence-clean procedural 33³ LUTs generated from the negative/print models
in `FilmLookCatalog`, plus per-look spatial metadata (grain, halation, split tone, skin, foliage,
and sky handling). `FilmLookLoader` can still prefer a future licensed `.cube` asset when one has
a documented input transform and redistribution permission.

---

## 4. Proposed engine architecture

```
looks/emulation/
  LutCube.kt          # parse .cube -> FloatArray(size^3 * 3) + size; trilinear sample()
  FilmLook.kt         # stock + skin/foliage/sky/split-tone/halation/grain parameters
  FilmLookCatalog.kt  # curated authored stock models
  FaceRegionDetector  # bundled on-device ML Kit semantic face bounds
  SkinTone.kt         # proxy chromaticity mask + luminance-preserving correction
  DevelopPipeline.kt  # scene -> negative/print LUT -> split -> skin -> foliage -> sky -> halation -> grain
  DevelopEngine.kt    # Android Bitmap/face-detection glue over the pure pipeline
  (later) LutShader.kt / AgslDevelop.kt  # SDK33+ preview fast-path
```

- **Pure-Kotlin core** (`LutCube`, curve math, `SkinTone`, grain field, split-tone) has **no Android
  deps** → JVM unit tests, consistent with the repo's "BLE/device can't run in CI, so keep
  logic pure" rule.
- `DevelopEngine.applyCpu` operates on a pixel `IntArray` off the main thread (coroutine),
  returns a new `Bitmap` → feeds the **edited-download/export** path directly.
- Swatch chips (`LookSwatch`) get replaced/augmented by rendering each LUT against a small
  fixed reference thumbnail so the picker shows the *real* look, not a gradient.

### Look set (shipped — emulation names only)
Curated, not 300, and every entry named after a real stock (no invented "Retro Fade" labels).
The shipped set (`FilmLookCatalog`): **Portra 400**, **Portra 800**, **Gold 200**, **Ektar 100**,
**Superia 400**, **CineStill 800T** (strong red halation), **Vision3 250D**,
**Vision3 500T**, **Eterna Cinema**, **Tri-X 400**, and **HP5 Plus**. Each combines a procedural
film-density model with parametric grain, halation, split-tone, and selective-colour layers.

---

## 4a. Historical experiment: third-party Fujifilm `.cube` film sims (retired)

The procedural LUTs (§4b) were still not good enough ("very bad"). A previous build used **real
Fujifilm film-simulation `.cube` 3D LUTs** from
[`abpy/FujifilmCameraProfiles`](https://github.com/abpy/FujifilmCameraProfiles) (32³, sRGB
variants), bundled under `app/src/main/assets/luts/`. The 11 sims: **Provia, Velvia, Astia,
Classic Chrome, Classic Neg, Nostalgic Neg, Pro Neg Hi, Pro Neg Std, Eterna, Reala Ace, Bleach
Bypass**. `FilmLookCatalog` points each entry at its `.cube` via `lutAsset`; the procedural
`Model` is kept only as a fallback if an asset fails to load.

**Input domain — the key gotcha.** These LUTs were authored for a *linear-ish camera-profile*
input and bake their own tone curve. Feeding raw sRGB washes mid-grey to ~0.70; a full sRGB→linear
(`x^2.4`) crushes it to ~0.19. Empirically, pre-warping the sRGB input by **`x^1.6`** lands
mid-grey at ~0.43 (photographic) — so `FilmLook.lutInputGamma = 1.6` for the Fuji sims and the
pipeline raises each channel to that power *before* sampling the LUT, taking the LUT output as
display-referred (no re-encode). Procedural LUTs keep `lutInputGamma = 1` (plain sRGB→sRGB).
Verified by `FujiLutVerify` (mid-grey band, differentiation, bleach-bypass desaturation).

**Why retired.** The source repo has no licence, the input-domain gamma was guessed, and one fixed
transform could not handle the supplied backlit/high-key/blue-hour/mixed-light scenes. The current
adaptive, hand-authored catalog avoids that runtime/licensing dependency.

## 4b. The active procedural colour engine — negative → print/scan

The first `FilmLutFactory` applied a gentle mid-grey S-curve plus per-channel `gain*gamma`
**in display (sRGB-gamma) space**, with gains ~1.03 — barely perceptible. Colour math in gamma
space also compresses every operation. The current factory instead builds each 33³ transform as a
restrained two-stage photographic system for already-rendered camera files:

- **Negative dye formation:** sRGB is decoded to linear exposure. Each layer has independent
  speed, straight-line slope, toe, and shoulder in bounded log-exposure space.
- **Density-layer coupling:** a 3×3 `Model.crossTalk` matrix operates on the formed dye densities.
- **Positive print/scan:** `PrintStage` supplies a second contrast/toe/shoulder characteristic,
  print-light exposure and channel balance, black point, and paper white.
- **Scanner colour:** saturation is applied around Rec.709 luma after the positive is encoded.

The neutral negative plus neutral print is tested as an identity round-trip. Each stock then
separately calibrates both stages: Portra and Vision3 use softer print shoulders, Eterna a low
contrast wide-latitude print, and Ektar/Tri-X a harder print. Divergent layer curves create a
stock's shadow/highlight colour **crossover** (for example, CineStill's cooler shadows relative to
its upper tones) rather than applying one constant tint.

The grades are intentionally **strong and clearly film** (the "much better filters" ask), with
regression tests for the paired stage, neutral identity, crossover, monotonic exposure brackets,
and retained highlight separation.

### DNG (RAW) develop → JPEG
`PhotoSave.saveEdited` now develops **DNG** originals too (previously they were saved untouched):
the DNG is decoded via the platform `ImageDecoder` DNG path (`decodeRawBounded`, API 28+,
downsampled to `MAX_EDIT_PIXELS`), given a mild **RAW base grade** (`DevelopPipeline.PreGrade`:
contrast + slight saturation — RAW previews decode flatter than the camera JPEG the models were
tuned against), then run through the film look. The result is **always saved as JPEG** (a
developed rendition is a finished image, not sensor data). If a device/firmware DNG can't be
decoded (API < 28, or the platform rejects it), the untouched original is saved — the action
never fails or crashes.

### Dead-end "reverse engineering" leads
Two repos were suggested as RE targets and **neither is usable**: `gitlab.com/antoineklein2000/film`
and `github.com/Nielk74/film` are both **scraped static copies of the commercial Color.io web app**
(minified JS + a `ubitmap.wasm` blob). No readable algorithm, no film-stock definitions, and the
only `luts/*.json` present are (a) broken 404 HTML placeholders and (b) technical colour-space
transforms (LogC4→Rec709, ACEScct→DWG), **not** film-stock emulations — plus it's third-party
copyrighted code. They did *confirm* the pipeline shape (tone/density → LUT → halation →
diffusion → grain), which matches §2. Our LUTs are built from documented film colour science
instead, license-clean.

---

## 5. Open decisions before coding
- **LUT asset sourcing & licence.** Prefer CC0/public-domain HaldCLUT stock sims (convert to
  `.cube` at build time), or hand-authored `.cube` files we can licence-clear. Must confirm
  redistribution rights per file before bundling.
- **Preview cost.** CPU full-res is fine for export; for live grid preview use downscaled
  thumbnails (already small) or the AGSL path on SDK 33+.
- **Relationship to camera `effect` enums.** Keep both: camera-side looks for authentic
  in-camera JPEGs (unchanged), client-side film emulation for developing/exporting any
  captured frame. UI should make the distinction legible.

### UI: film stocks ARE the picker (not the camera effect enum) — done
The gallery/viewer look picker (`LookStrip`) now lists the **film stocks directly** — Standard +
the 11 `FilmLookCatalog` stocks — so the user sees and taps "Portra 400", "CineStill 800T", etc.
The whole edit layer (`EditState`, `StickyLookStore`/`LookPreferenceCodec`, `GalleryViewModel`,
`ViewerScreen`, `PhotoSave.saveEdited`) is keyed on the **film-stock id (`String?`; null =
Standard)**. `CameraLook` (the camera's `col_vivid`/`efc_*` enum) is now used **only** by the
live-capture screen (pre-capture control over Wi-Fi); the old `CameraLookMapping` (camera-look →
film-look translation) is deleted. Each `FilmLook` carries a 2-stop `swatchTop`/`swatchBottom`
for its picker chip.

---

## Sources
- [darktable — LUT 3D module](https://docs.darktable.org/usermanual/4.0/en/module-reference/processing-modules/lut-3d/)
- [Largest collection of HaldCLUT/LUT film simulations](https://marcrphoto.wordpress.com/the-largest-collection-of-film-simulation-haldclut-luts-brought-together/)
- [Film emulation — Wikipedia](https://en.wikipedia.org/wiki/Film_emulation)
- [How the Film Look Really Works — a colorist's guide](https://pixeltoolspost.com/blogs/resolve/film-emulation-explained)
- [Android — Migrate from RenderScript](https://developer.android.com/guide/topics/renderscript/migrate)
- [Android — RenderEffect](https://developer.android.com/reference/android/graphics/RenderEffect)
- [AGSL: Made in the Shade(r) — Chet Haase](https://medium.com/androiddevelopers/agsl-made-in-the-shade-r-7d06d14fe02a)
- [ScriptIntrinsic3DLUT (deprecated) — trilinear 3D LUT reference](https://developer.android.com/reference/android/renderscript/ScriptIntrinsic3DLUT)
- [NVIDIA GPU Gems 2, Ch.24 — Using lookup tables to accelerate colour transforms](https://developer.nvidia.com/gpugems/gpugems2/part-iii-high-quality-rendering/chapter-24-using-lookup-tables-accelerate-color)
- [Film Grain Synthesis for AV1 (Norkin & Birkbeck)](https://norkin.org/pdf/DCC_2018_AV1_film_grain.pdf)
- [Film Grain Rendering and Parameter Estimation — ACM TOG](https://dl.acm.org/doi/10.1145/3592127)
