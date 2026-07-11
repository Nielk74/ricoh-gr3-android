# Film Emulation — science, libraries, and the plan for this app

Research backing the "much better filters" work. Grounded in how real film emulation is
done in colour-grading tools and the constraints of this Android app (min SDK 26,
compile SDK 34, Jetpack Compose, no RenderScript).

---

## 1. Why the current "filters" are weak

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

5. **Halation.** Red-orange bloom bleeding out of bright edges into darker surroundings —
   film's anti-halation layer failing at highlights. Implemented as: threshold the
   highlights → Gaussian blur → tint red/orange → screen/add back. Luminance-gated.

6. **Grain.** `I_out = I + A(I)·G` (see §2.6 for the shipped model). `G` is spatially-correlated,
   multi-scale, correlated-RGB grain; `A(I)` is a **midtone-peaked** density response. Real film
   grain is *strongest in the midtones* (falling off in both deep shadows and highlights),
   correlated (not per-pixel white noise), varies in size/clumping, and is correlated-but-distinct
   across R/G/B (identical mono = flat; independent RGB = electronic).

7. **Optional: vignette, soft bloom, slight desaturation of extremes, black/white point.**

**Order matters:** linearise → tone → colour(LUT) → split-tone → halation → (re-encode) →
grain → vignette. Grain and vignette go last, in display space.

### The 3D LUT is the workhorse
A **3D LUT** partitions RGB space into a grid (typically 17³, 33³, or 64³); each vertex
holds an output RGB. At runtime you find the cube a pixel falls in and **trilinear-
interpolate** the 8 corners. One lookup captures tone + colour + cross-talk together — which
is exactly why the industry ships film looks as `.cube` files. Steps 4–7 (split-tone,
halation, grain) are layered *around* the LUT because they're spatial or parametric and
don't belong in a pointwise colour map.

**File formats:** `.cube` (Adobe/Resolve text format — easiest to parse), `.3dl`, and
**HaldCLUT** (a PNG that is the identity LUT laid out as an image; apply any edit to the
Hald PNG in an editor and it *becomes* a LUT). HaldCLUT is attractive here because it ships
as a plain PNG asset and there are large free, permissively-usable collections.

### 2.6 Grain model (shipped, `DevelopPipeline.applyGrain`)

Realistic film grain, `I_out = I + A(I)·G`, checked against the film-grain guideline:

- **`G` — the grain field**: a shared **luma** octave (fine) plus a **coarser** octave summed in
  for *clumping and size variety* (not one uniform speckle size). On top, a small **per-channel
  chroma** component (independent R and B octaves; G = −(R+B)/2) so R/G/B are
  **correlated-but-distinct** — identical mono grain looks flat, fully-independent RGB looks
  electronic. Each octave is blurred for spatial correlation then **renormalised** (blurring
  otherwise collapses amplitude — the old bug that made grain invisible). `chroma=0` ⇒ pure mono.
- **`A(I)` — the strength**: a **midtone-peaked hump** (`grainDensity`) that falls off in both the
  deepest shadows *and* the brightest highlights (real silver-grain density), biasable toward the
  shadows by `shadowBias`. Measured: peak ~0.95 at luma 0.25–0.5, ~0.54 at black, ~0.04 near white.
- **Detail/sharpness**: grain strength is applied **independently of image sharpness**; a bounded
  **secondary** `smoothBoost` makes grain read slightly *more visible* in smooth/defocused regions
  (`|luma − blur(luma)|`), never proportional to blur.
- **Order/perf**: grain is **last**, in display space, before JPEG compression. Runs off the main
  thread; peak memory bounded (≤ ~4 float buffers over the ~6 MP edit image) to respect the
  no-crash / no-OOM rule.

Per-stock midtone amplitude ranges ~3.9/255 (Velvia, finest) → ~7.2/255 (Bleach Bypass, grittiest).

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

We store looks as **`.cube` 3D LUTs in assets** (small: 33³ ≈ 36k floats) parsed into a flat
float array, plus per-look parametric metadata (grain amount, halation strength, split-tone
tints) in a small manifest. That keeps looks data-driven — adding a stock is dropping in a
`.cube` + a manifest row, no code.

---

## 4. Proposed engine architecture

```
looks/emulation/
  LutCube.kt          # parse .cube -> FloatArray(size^3 * 3) + size; trilinear sample()
  FilmLook.kt         # data: id, name, lut asset, toneCurve?, splitTone, halation, grain
  FilmLookCatalog.kt  # loads manifest (JSON in assets) -> List<FilmLook>
  DevelopEngine.kt    # applyCpu(bitmap, look): linearise -> LUT -> splitTone -> halation
                      #                          -> encode -> grain -> vignette
  Grain.kt            # correlated luminance-weighted noise field
  Halation.kt         # highlight threshold -> blur -> tint -> screen
  (later) LutShader.kt / AgslDevelop.kt  # SDK33+ preview fast-path
```

- **Pure-Kotlin core** (`LutCube`, curve math, grain field, split-tone) has **no Android
  deps** → JVM unit tests, consistent with the repo's "BLE/device can't run in CI, so keep
  logic pure" rule.
- `DevelopEngine.applyCpu` operates on a pixel `IntArray` off the main thread (coroutine),
  returns a new `Bitmap` → feeds the **edited-download/export** path directly.
- Swatch chips (`LookSwatch`) get replaced/augmented by rendering each LUT against a small
  fixed reference thumbnail so the picker shows the *real* look, not a gradient.

### Look set (shipped — emulation names only)
Curated, not 300, and every entry named after a real stock (no invented "Retro Fade" labels).
The shipped set (`FilmLookCatalog`): **Portra 400**, **Portra 800**, **Gold 200**, **Ektar 100**,
**Superia 400**, **Pro 400H**, **CineStill 800T** (strong red halation), **CineStill 400D**,
**Vision3 500T**, **Tri-X 400**, **HP5 Plus**. Each = a procedural film-density model +
parametric grain/halation/split-tone.

---

## 4a. Shipped LUTs: real Fujifilm `.cube` film sims (current)

The procedural LUTs (§4b) were still not good enough ("very bad"). The shipped set now uses **real
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

**Licensing note.** The source repo has **no LICENSE file** and the LUTs are derived from Adobe's
camera-matching profiles — redistribution rights are unclear. Bundling them is a deliberate,
user-accepted risk for this app; revisit before any public/store release.

## 4b. The (fallback) procedural colour engine — why the first LUTs were too subtle

The first `FilmLutFactory` applied a gentle mid-grey S-curve plus per-channel `gain*gamma`
**in display (sRGB-gamma) space**, with gains ~1.03 — barely perceptible. Colour math in gamma
space also compresses every operation. The remake (`FilmLutFactory.kt`) fixes both:

- **All tone/colour math in scene-linear light** (sRGB→linear in, linear→sRGB out). The same
  parameter reads far stronger, and film's characteristic curve / dye coupling are only
  physically meaningful in linear.
- **Per-channel characteristic ("density") curves** with *independent* contrast, toe, and
  shoulder (`FilmLutFactory.Channel`). Divergent per-channel curves are what create a stock's
  shadow/highlight colour **crossover** (CineStill's cyan shadows, Portra's warm highlights).
- **A 3×3 dye cross-talk matrix** (`Model.crossTalk`) mixing channels the way real film dye
  layers couple — colour rotations a per-channel curve can't do (Fuji's foliage/sky shift).
- **Saturation** around Rec.709 luma (0 = mono for B&W stocks).

The grades are intentionally **strong and clearly film** (the "much better filters" ask), with
regression tests (`LutCubeTest.strongGradeVisiblyShiftsMidtones`, `channelsCanDivergeForColourCrossover`)
that fail if a look ever goes back to being a whisper.

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
