# Film fidelity calibration contract

This document separates what the software can compute from what must be measured. It prevents an
aesthetic fit, a manufacturer graph, a scanner JPEG, and a traceable stock/process measurement
from being described as equivalent evidence.

The built-in looks are currently **manufacturer-anchored visual fits**. They use the published
shape and ordering of characteristic, spectral-sensitivity, dye-density, MTF, and granularity
data, but they have not been fitted to negatives exposed, processed, and measured for this app.
Their `CalibrationBasis` must remain `MANUFACTURER_ANCHORED` until the acceptance gates below are
met.

## 1. Define the target before measuring

A film emulation is not just a stock name. A calibration target is the complete chain:

```text
camera spectral response + capture rendering
  → film stock, format, batch, exposure and illuminant
  → developer/process chemistry, time and temperature
  → enlarger/print stock or scanner/camera-scan settings
  → viewing/output colour space and luminance
```

At minimum, each measured profile records:

- stock, format, emulsion/batch, rated and actual exposure index;
- process (for example C-41 or ECN-2), chemistry, lab/developer, time, temperature, and date;
- illuminant spectral power distribution or identified calibrated source;
- camera/lens and all capture-rendering settings;
- scanner/enlarger/print material, optics, resolution, sharpening, noise reduction, inversion,
  colour management, and output encoding;
- instrument model, calibration date, measurement geometry, observer/illuminant, and raw files;
- train/validation split identifiers and a checksum of every source table.

A change in process or scan chain is a new target, not an invisible update to the old one.

## 2. Required capture set

Use at least three independently processed rolls/batches when practical. Randomize exposure order
and reserve one complete roll or session as a holdout.

1. **Sensitometry:** calibrated neutral-step exposures spanning toe through shoulder, in 1/3-stop
   or finer increments. Read processed base/fog and each colour-layer density with a calibrated
   densitometer. Repeat under the stock's reference illuminant and the app's common daylight,
   tungsten, and representative LED sources.
2. **Colour response:** a spectrally characterized chart plus additional saturated pigments,
   foliage, sky, neutrals, and memory colours. Capture an exposure bracket around the rated
   exposure. Include multiple real complexions under daylight, tungsten, mixed, and high-CRI LED.
3. **Image structure:** slanted edges and/or sinusoidal targets for MTF, uniform midtone patches
   for granularity, and point/highlight targets at several exposures for a radial halation profile.
4. **Real scenes:** low-key, high-key, backlit, mixed-light, foliage/sky, night practicals, and
   portraits. These audit semantic guards and failure modes; they do not replace instrument data.
5. **Digital control:** capture the same target and illuminant through the GR III JPEG path and,
   separately, through a controlled scene-linear RAW developer. A platform-rendered Android DNG
   is recorded as its own device-specific input class.

## 3. Data interchange already implemented

Measured negative and optional print H-D curves use:

```csv
stage,channel,log_exposure,density
negative,r,-3.0,0.18
negative,r,-2.8,0.19
negative,g,-3.0,0.17
negative,g,-2.8,0.18
negative,b,-3.0,0.20
negative,b,-2.8,0.21
print,neutral,-3.0,0.08
print,neutral,-2.8,0.09
```

`FilmDensityProfileCsv` rejects missing channels, non-finite values, non-increasing exposure
samples, non-monotonic density, and any import not explicitly marked `LAB_MEASURED`.
`FilmLutFactory.withMeasuredCurves` also checks that every negative curve lies inside the
profile's stated base/fog and dye-capacity range.

Spectral sensitivity, dye spectra, MTF, granularity, and halation profiles do not yet have an
import format. Until those are implemented and measured, their built-in coefficients remain
qualitative manufacturer anchors.

## 4. Fit in independent stages

Do not fit the whole look to a small set of attractive photographs. Fit and validate each
computation against the evidence that identifies it:

1. fit base/fog, speed offsets, toe, straight-line slope, shoulder, and layer capacities from
   negative D-logE measurements;
2. fit inter-layer/spectral colour response from chart patches across exposure and illuminant;
3. fit print density/reflectance or the explicitly identified scanner transform separately;
4. fit B&W capture response from the measured spectral curve and camera sensitivities, not
   Rec.709 display luma;
5. fit MTF before grain, then grain power spectrum/distribution after removing deterministic
   scene structure;
6. fit broad and tight halation radial profiles from clipped-point/edge targets;
7. tune Smart guards only on the training scenes, then freeze them before holdout review.

Stock mode is the calibration surface: it must be scene-invariant. Smart mode is assessed
separately as a bounded rendering aid and must never change Stock coefficients.

## 5. Validation metrics and gates

Report the whole distribution and the named worst patches/scenes, not only an average:

| Component | Primary evidence | Report |
| --- | --- | --- |
| Tone / sensitometry | held-out D-logE steps | density RMSE, toe/shoulder error, speed offset, monotonicity |
| Colour | held-out spectral/chart patches | ΔE00 or documented perceptual ΔE, hue/chroma/luminance error by exposure and illuminant |
| Neutrality | grey scale | RGB/OKLab bias and white-balance drift |
| B&W response | coloured spectral patches | relative rendered density and filter response |
| MTF | slanted edge / sine target | MTF50, MTF10, overshoot and directional consistency |
| Grain | uniform patches | radial PSD, variance by density, RGB correlation, skew/kurtosis, crop/resolution consistency |
| Halation | point/edge target | radial energy by channel, broad/tight radius, core contamination |
| Rendering | frozen real scenes | clipped/crushed fraction, temporal/seed stability, preview/export parity |

Promotion to `LAB_MEASURED` requires:

- traceable raw tables and metadata committed or archived with checksums;
- no train/holdout leakage;
- all monotonicity, finite-value, endpoint, gamut, memory, and deterministic-render tests passing;
- preset numeric tolerances written before the final holdout run;
- a visual review at fit, 100%, and 200% on calibrated displays;
- an explicit profile version and a statement of the exact target chain.

There is intentionally no universal “looks right” threshold here. Numeric tolerances must be
chosen from repeatability measurements of the actual instruments and process before fitting, then
recorded with the profile.

## 6. Current software guarantees

The current implementation provides the foundations needed for that calibration:

- exact sRGB transfer functions, linear-light luminance, and OKLab perceptual decisions;
- `FilmFidelityEvaluator` for matched mean/p95/max OKLab distance, signed component bias, and
  linear-luminance RMSE;
- explicit optical density/transmittance/reflectance and validated sampled H-D curves;
- distinct B&W capture responses and profile provenance;
- explicit daylight/tungsten/monochrome/unspecified profile balance, with Smart-only pleasing
  warmth and gamut-edge fading kept separate from the Stock calibration surface;
- `.cube` input-domain support;
- resolution-invariant scene sampling and matched-resolution decisions for equivalent JPEG input;
- physical-scale qualitative diffusion, immutable-source two-lobe halation, and analytically
  integrated film-plane grain with a stable per-photo seed;
- an explicit Stock/Smart contract and a 3000 px multi-scene review lab.

It does **not** yet provide a scene-linear GR III RAW developer, a spectrometer-derived camera/film
spectral model, measured stock/process/scan profiles, measured MTF/halation imports, high-bit-depth
output, or on-device performance certification. Those are the next fidelity boundary, not values
that should be guessed in code.

## Primary references

- [KODAK VISION3 500T 5219/7219 technical information](https://www.kodak.com/content/products-brochures/motion-picture/KODAK-VISION3-5219-7219-technical-information.pdf)
- [KODAK VISION3 250D 5207/7207 technical information](https://www.kodak.com/content/products-brochures/motion-picture/KODAK-VISION3-250D-5207-7207-technical-information.pdf)
- [KODAK PROFESSIONAL PORTRA 400 technical data E-4050](https://www.kodakprofessional.com/sites/default/files/2025-07/e4050.pdf)
- [KODAK PROFESSIONAL PORTRA 800 technical data E-4040](https://www.kodakprofessional.com/sites/default/files/wysiwyg/pro/resources/e4040_portra_800.pdf)
- [KODAK PROFESSIONAL EKTAR 100 technical data E-4046](https://www.kodakprofessional.com/sites/default/files/wysiwyg/pro/resources/e4046_ektar_100.pdf)
- [KODAK GOLD 200 technical data E-7022](https://www.kodakprofessional.com/sites/default/files/wysiwyg/pro/resources/E7022_Gold_200.pdf)
- [FUJICOLOR SUPERIA X-TRA 400 data sheet AF3-0217E](https://asset.fujifilm.com/www/us/files/2025-06/8abba3dd9d004f44d1e9c7fdbdf5c520/films_superia-xtra400_datasheet_01.pdf)
- [CineStill 800T process and halation guidance](https://cinestillfilm.com/blogs/news/cinestill-800t-in-your-toolbox)
- [KODAK PROFESSIONAL TRI-X 320/400 technical data F-4017](https://www.kodakprofessional.com/sites/default/files/wysiwyg/film/f4017_trix_320400.pdf)
- [ILFORD HP5 PLUS technical information](https://www.ilfordphoto.com/amfile/file/download/file/1903/product/691/)
- [Newson et al., Film Grain Rendering, Resolution-Free from Capture to Display](https://www.ipol.im/pub/art/2017/192/)
