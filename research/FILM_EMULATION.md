# Film Emulation — science, libraries, and the plan for this app

Research backing the "much better filters" work. Grounded in how real film emulation is
done in colour-grading tools and the constraints of this Android app (min SDK 26,
compile SDK 34, Jetpack Compose, no RenderScript).

---

## Current implementation (Stock/Smart pipeline, July 2026)

The early fixed-LUT experiments documented later in this file are historical. The shipped path is
now designed around the fact that both camera JPEGs and Android-rendered DNGs are already
display-referred photographs:

1. `SceneAnalyzer` samples the same 224×224 normalized positions at every render resolution and
   measures 4096-bin **linear-light** luminance percentiles, clipped/crushed area, colourfulness,
   low-chroma warm/cool bias, and micro-contrast at a canonical 720 px scene scale. A 720 px
   preview and 3000 px export therefore make the same scene decision.
2. The UI exposes two explicit rendering contracts. **Stock** applies only authored,
   scene-invariant stock and layer values. **Smart** additionally derives bounded exposure,
   shadow, shoulder, saturation, skin/foliage/sky, halation, and high-ISO grain protection. It
   also gives explicitly daylight-balanced colour profiles a restrained +6-mired Bradford warmth
   bias before negative exposure. The bias stays close to linear-light luminance, fades across
   high-chroma pixels and at the sRGB gamut boundary instead of collapsing saturated colours, and
   fades when credible neutral samples indicate that the source already has a strong cast.
   Tungsten, monochrome, and unspecified-balance profiles are excluded; Stock remains bit-for-bit
   outside this product preference. Preview and export carry the same persisted intent and stable
   per-photo seed. For
   JPEGs each size independently evaluates the canonical analyzer, whose resolution-stability is
   covered by 720/960/3000 px tests; the review renderer can additionally inject one precomputed
   profile.
3. `FilmLutFactory` builds licence-clean 33³ transforms as an explicit negative → positive
   system. Exposure forms absolute optical density `D = -log10(T)`, including base/fog and dye
   capacity; density-layer coupling is followed by negative transmittance, a separate print
   density/reflectance stage, and display encoding. Validated monotonic sampled D-logE curves can
   replace the fallback fits through the documented
   `stage,channel,log_exposure,density` interchange.
4. Every catalog entry carries its material, process, print/scan assumption, sources, and explicit
   calibration basis. Most are `MANUFACTURER_ANCHORED`. Portra 400/800 are
   `MANUFACTURER_DIGITIZED`: bounded channel-curve anchors come from manually sampled January
   2025 Kodak Status-M graphs with stated ±0.05 density-unit uncertainty. Neither basis is a
   measurement of this camera/stock/process/scan chain; `LAB_MEASURED` is reserved for traceable
   imported data.
5. Tri-X and HP5 no longer share Rec.709 display luma. Each forms one monochrome exposure through
   its own conservative panchromatic RGB response before the H-D curve. The three-band weights
   approximate published spectral graphs and remain explicitly labelled as such.
6. Exact IEC sRGB transfer functions and linear Rec.709 luminance are shared by exposure,
   density, split-tone, selective-colour, skin, diffusion, halation, and grain operations.
   Perceptual complexion hue/chroma decisions use OKLab; common-scale gamut compression avoids
   independent channel clipping. `.cube` `DOMAIN_MIN`/`DOMAIN_MAX` values are parsed and applied.
7. The stock transform is blended rather than blindly replacing every pixel. In Smart mode a
   bundled on-device face detector establishes a semantic region; chromaticity then trims hair,
   glasses, beard, clothing, and background. Complexion correction works around the rendered
   linear luminance and fails closed if no accepted region is found.
8. A weak physical-scale emulsion/scan diffusion layer precedes halation and grain. Halation uses
   a broad red base-reflection lobe plus a tighter emulsion-scatter lobe; both masks are derived
   from immutable pre-halation highlights and subtract the source core, preventing red fog over
   flat highlights and recursive halo growth.
9. `PhysicalFilmGrain` defines one deterministic, infinite crystal field in physical film
   coordinates on the calibrated 35 mm frame. A local Smart visibility map raises the same field
   in defocus and continuous tone while restraining it over focused source detail; it replaces
   Portra's former frame-global texture suppression. Bright diffuse tones retain a bounded
   stock-specific part of the density response while exact black and paper white remain fixed.
   Each output pixel analytically integrates its film-plane footprint, so a preview and a
   downsampled export see the same field, crystal scale, and variance. Grain is zero-mean,
   midtone-peaked density variation with restrained correlated colour and stock-specific
   clumping; Smart may still reduce it for a known high-ISO source.
10. Android DNG input is platform-rendered, bounded, and converted to sRGB before this
    display-referred pipeline. It is **not** a scene-linear RAW develop and can vary by device;
    the UI labels that preview/export limitation rather than claiming JPEG/DNG parity.
11. A persisted 50–150% strength control scales colour and emulsion layers. Tonal scene
    protection fades below 100% but is not amplified above it. The review lab also renders real
    −1/0/+1-stop negative-input brackets using a bounded linear-light shift that preserves an
    already-rendered source's highlight separation.

The calibration loop is reproducible: `./gradlew :tools:renderReferences` renders all local
`.references` JPEG/PNG scenes and writes contact sheets plus exact scene decisions under
`build/reference-renders/`. Pure colour-science tests also run without an Android SDK via
`./gradlew :tools:test`.

The current curated set is Portra 400/800, Gold 200, Ektar 100, Superia 400, CineStill 800T,
Vision3 250D/500T, Eterna Cinema, Tri-X 400, and HP5 Plus. Names describe aesthetic emulations,
not manufacturer-certified matches. See
[`FILM_FIDELITY_CALIBRATION.md`](FILM_FIDELITY_CALIBRATION.md) for the measurement contract
required to promote a profile from manufacturer-anchored to lab-measured.

---

## 1. Why the original "filters" were weak

At the original baseline, two mechanisms existed and neither actually *rendered* a look onto a
captured photo:

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

5. **Image structure and halation.** A restrained, physical-scale diffusion/MTF response softens
   image detail beneath later emulsion texture. Halation is red-orange light scattered through
   the emulsion/base around bright edges. The shipped approximation separates a broad red lobe
   from a tighter orange lobe, subtracts the immutable source core, weights darker receiving
   pixels, and composites in linear light.

6. **Grain.** `logit(I_out) = logit(I) + A(I)·G` (see §2.6). `G` is a stochastic film-plane
   crystal field integrated over the output pixel footprint; `A(I)` is a shadow-biased,
   **midtone-peaked** density response. Useful shadows and midtones show the structure most
   clearly, while printed black and bright highlights mask it. The field is correlated-but-
   distinct across R/G/B (identical mono = flat; independent RGB = electronic).

7. **Optional: vignette, soft bloom, slight desaturation of extremes, black/white point.**

**Order matters:** input/render contract → tone protection → negative/positive colour LUT →
split-tone → selective complexion/foliage/sky colour → credible-white re-anchoring →
physical-scale diffusion → two-lobe halation → film-plane grain → output encoding.

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

### 2.6 Grain model (shipped, `PhysicalFilmGrain`)

The model follows the resolution-free stochastic-film approach described by Newson et al.:
simulate the material in physical coordinates, then integrate the same realization over each
output pixel rather than redrawing output-resolution noise.

- **Film coordinates:** an infinite hashed lattice supplies zero-mean crystal impulses without a
  texture tile or practical repeat boundary. Catalog size units map to micrometres on a 36 mm
  film plane.
- **Analytic footprint integration:** compact tent basis functions are integrated exactly over
  every output pixel. Tests compare a literal 720 px render with an area-downsampled 3000 px
  render of the same field and require correlation above 0.97 with matched visible variance.
- **Fast-stock clumping:** an orthogonal zero-mean cubic component changes impulse tails before
  footprint integration. It produces occasional denser crystals without a separate low-frequency
  cloud layer or a mean-density shift.
- **Tone response:** `densityResponse` peaks in useful low-mid/mid tones and rolls off at black and
  white. Portra retains a bounded fraction through bright diffuse tone so compression does not
  make highlights unnaturally texture-free. The scalar field perturbs luminance in bounded
  linear-light log-odds space, preserving exact endpoints and avoiding a grey veil.
- **Local visibility:** Smart Portra rendering derives a small immutable-source detail map before
  applying grain. Smooth/defocused tone gains visibility while focused edges and texture suppress
  it locally; one sharp subject can no longer reduce grain across the whole frame.
- **Format scale:** the default 36×24 mm mapping is the calibrated 35 mm result used by the app
  and review lab.
- **Colour structure:** a small secondary field creates tightly correlated, luminance-neutral RGB
  differences. It is deliberately weak because scanner noise and sharpening are not emulsion.
- **Identity:** a stable photo identifier is mixed with the stock seed. The same frame is stable
  across preview/export and sessions; two photographs no longer receive the same grain.
- **Order/performance:** grain is the final image-forming layer before JPEG encoding and runs off
  the main thread. The implementation allocates axis kernels rather than a full-frame grain plate.

Per-stock density amount ranges from 0.018 (Ektar 100, finest) to 0.092 (Portra 800, the most
pronounced colour stock in the authored set). The edited JPEG selector makes the final encode
explicit: Compact uses quality 92, High uses 97, and Maximum uses 100 to retain the most fine
structure the Android encoder allows. The cross-scanner evidence,
matched-output measurements, and Portra 400/800 decision are recorded in
[`PORTRA_GRAIN_CALIBRATION.md`](PORTRA_GRAIN_CALIBRATION.md).
The January 2025 graph samples, display-input alignment, uncertainty, and white-recovery boundary
are recorded separately in [`PORTRA_SENSITOMETRY.md`](PORTRA_SENSITOMETRY.md).

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
in `FilmLookCatalog`, plus per-look spatial metadata (diffusion, grain, halation, split tone,
skin, foliage, and sky handling). `FilmLookLoader` can still prefer a future licensed `.cube`
asset when one has
a documented input transform and redistribution permission.

---

## 4. Engine architecture

```
looks/emulation/
  ColorMath.kt          # exact sRGB, linear luminance, OKLab, gamut-safe luminance placement
  DevelopOptions.kt     # Stock/Smart intent, stable seed, film format, optional reusable profile
  FilmStockProfile.kt   # optical density, provenance, D-logE CSV, B&W capture response
  FilmLutFactory.kt     # negative density -> transmittance -> print density/reflectance
  LutCube.kt            # .cube parser, domains, trilinear sampling
  SceneAnalyzer.kt      # resolution-invariant scene statistics and Smart safeguards
  SkinTone.kt           # proxy chromaticity mask + luminance-preserving correction
  FilmOptics.kt         # physical-scale qualitative diffusion/MTF layer
  PhysicalFilmGrain.kt  # analytic film-plane crystal integration
  FilmFidelityMetrics.kt # held-out OKLab and linear-luminance comparison metrics
  DevelopPipeline.kt    # the ordered pure-Kotlin render
  DevelopEngine.kt      # Android Bitmap/face-detection glue
  (later) LutShader.kt / AgslDevelop.kt  # SDK33+ preview fast-path
```

- **Pure-Kotlin core** (`ColorMath`, density/LUT math, scene analysis, `SkinTone`, optics,
  halation, grain, and fidelity metrics) has **no Android deps** → JVM unit tests, consistent
  with the repo's "BLE/device can't run in CI, so keep logic pure" rule.
- `DevelopEngine.render` operates on a pixel `IntArray` off the main thread (coroutine),
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
- **Published Portra shape:** manually digitized red/green/blue Status-M curves are normalized to
  the display-input domain, aligned to preserve 18% grey, and blended conservatively with the
  fallback channel response. The graph points and uncertainty remain explicit manufacturer
  evidence, not claimed local sensitometry.
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

### DNG (platform RAW rendition) → JPEG
`PhotoSave.saveEdited` now develops **DNG** originals too (previously they were saved untouched):
the DNG is decoded via the platform `ImageDecoder` path (`decodeRawBounded`, API 28+), downsampled
to the selected Compact/High cap or Maximum's device-heap safety ceiling, given a mild **RAW base
grade** (`DevelopPipeline.PreGrade`: contrast + slight saturation — RAW previews decode flatter
than the camera JPEG the models were tuned against), then run through the film look. The result is
**always saved as JPEG** (a developed rendition is a finished image, not sensor data). Edited DNG
save is unavailable below API 28. If a newer platform rejects a particular DNG, the app reports
that failure and asks the user to choose **Save original**; it never silently returns untouched
sensor data from an edited-save action. This is not demosaic/white-balance from scene-linear
sensor samples: Android owns the DNG rendition and may produce a different base on different
devices. The viewer labels this limitation. Preview and export use the same downstream
Stock/Smart contract, but their pixels are not guaranteed to match because the camera preview and
device RAW renderer are different input renderings.

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

## 5. Settled decisions and remaining work
- **Transform assets and licence.** The active negative/print transforms are hand-authored in this
  repository and require no unidentified third-party LUT pack. Future measured or imported
  profiles still need explicit provenance and redistribution rights before bundling.
- **Preview cost.** The portable CPU path is the shipped preview/export baseline and uses
  downscaled viewer input plus heap-bounded edited output. An AGSL fast path on API 33+ remains a
  performance enhancement, not a different renderer contract.
- **Relationship to camera `effect` enums.** Camera-side looks remain capture controls for
  authentic in-camera JPEGs; client-side film stocks develop transferred frames. The UI and model
  keep these separate.

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
- [Kodak Vision3 500T technical information — sensitometry, spectral, MTF, granularity](https://www.kodak.com/content/products-brochures/motion-picture/KODAK-VISION3-5219-7219-technical-information.pdf)
- [Kodak Portra 400 technical data E-4050](https://www.kodakprofessional.com/sites/default/files/2025-07/e4050.pdf)
- [Kodak Gold 200 technical data E-7022](https://www.kodakprofessional.com/sites/default/files/wysiwyg/pro/resources/E7022_Gold_200.pdf)
- [Apple iPhone User Guide — Apply Photographic Styles](https://support.apple.com/en-ie/guide/iphone/iph629d2cd37/ios)
- [Afifi et al. — When Color Constancy Goes Wrong](https://openaccess.thecvf.com/content_CVPR_2019/papers/Afifi_When_Color_Constancy_Goes_Wrong_Correcting_Improperly_White-Balanced_Images_CVPR_2019_paper.pdf)
- [Newson et al. — Film Grain Rendering, Resolution-Free from Capture to Display](https://www.ipol.im/pub/art/2017/192/)
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
