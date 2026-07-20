# Portra grain calibration — July 2026

This note records the evidence and repeatable calibration used for the app's Portra 400 and
Portra 800 grain. The public photographs were inspected and measured only as temporary local
references; none of their image data is redistributed with the project.

## 1. Manufacturer anchor

Kodak's Print Grain Index is perceptual rather than a direct pixel-noise measurement. Kodak says
25 is approximately the visible threshold and a four-point change is a just-noticeable
difference for 90% of observers.

| 35mm / 135 stock | 4×6 in | 8×10 in | 16×20 in |
|---|---:|---:|---:|
| [Portra 400, E-4050](https://www.kodakprofessional.com/sites/default/files/2025-07/e4050.pdf) | 37 | 59 | 89 |
| [Portra 800, E-4040](https://imaging.kodakalaris.com/sites/default/files/files/products/e4040_portra_800.pdf) | 48 | 70 | 99 |

The 11-point separation is about 2.75 perceptual steps at every listed enlargement. Therefore the
app must make both 35mm stocks visibly textured at normal export size, with Portra 800
unambiguously stronger than Portra 400. The same Kodak tables also show why a 6×6 Portra 800
reference cannot be used as a direct 35mm amplitude target: larger negatives receive materially
lower grain-index values at the same print size.

## 2. Real-scan reference set

Several scanner pipelines were used so that one scanner's sharpening or noise would not be
mistaken for emulsion grain:

- [Portra 400, 35mm, Epson V800 portrait scan](https://www.flickr.com/photos/32681588@N03/16807983382/)
- [Portra 400, 35mm, Noritsu LS-1100](https://www.flickr.com/photos/hamishgill/33117002952/)
- [Portra 400, 35mm, Noritsu high-resolution scan](https://www.flickr.com/photos/201275399@N05/54859806596/)
- [Portra 800, 35mm, Plustek 8200i/SilverFast](https://www.flickr.com/photos/198584526@N08/54637163449/)
- [Portra 800, 6×7, Frontier SP-2500](https://www.flickr.com/photos/trentdavis/18047613278/)
- [Controlled Portra 400/800 35mm crops from the same Noritsu HS-1800 workflow](https://www.photrio.com/forum/threads/samples-of-portra-800-in-35mm-and-120.203536/)

The controlled thread is useful but also contains an important warning: its very enlarged crops
may include Noritsu scanner noise. It is treated as an upper bound, not ground truth.

## 3. Measurement method

1. Normalize each scan and app render to a 3000-pixel long edge, matching the Film Lab export.
2. Choose smooth sky, skin, painted wall, defocus, and low-mid shadow regions. Avoid hard edges
   and obvious scene texture.
3. Measure display-luminance residual after a two-pixel Gaussian detrend. The reported value is
   residual standard deviation in 8-bit code values.
4. Also inspect one-pixel residual correlation, fourth moment, and chroma residuals. These reject
   a solution that reaches the target merely with isolated RGB specks or large density clouds.
5. Compare ranges, not a single exact number: the film format, exposure, scanner optics,
   scanner sharpening, negative inversion, and JPEG encode all alter the result.

Selected measurements at the matched output size:

| Sample / region | Mean display luma | High-pass σ / 255 |
|---|---:|---:|
| Real Portra 400, Noritsu, painted wall | 0.545 | 7.22 |
| Real Portra 400, V800, well-exposed skin | 0.537 | 2.14 |
| Real Portra 400, heavily lifted thin-negative shadow | 0.474 | 12.38 |
| Real Portra 800, Plustek, low-mid wall/shadow | 0.231 | 3.67 |
| Real Portra 800, 6×7 Frontier, bright sky | 0.927 | 0.97 |
| Previous app Portra 400, blue sky, 100% | 0.448 | 1.40 |
| Previous app Portra 800, blue sky, 100% | 0.437 | 1.80 |
| Pre-physical-field app Portra 400, blue sky, 100% / 150% | 0.448 / 0.453 | 3.19 / 4.91 |
| Pre-physical-field app Portra 800, blue sky, 100% / 150% | 0.438 / 0.442 | 4.24 / 6.34 |

The old app values sat below even the soft V800 reference and contradicted Kodak's visible-grain
index. The table records why the catalog amplitudes were raised; it is not a current lab
measurement of `PhysicalFilmGrain`. Scanner processing varies too much to turn these display-code
residuals into emulsion ground truth.

## 4. Model decision

- Raise density amplitude from `0.028 → 0.058` for Portra 400 and `0.040 → 0.088` for
  Portra 800.
- Keep the catalog ordering and authored size/clumping anchors (`1.65`/`0.13` and
  `1.95`/`0.22`), but interpret size on a 36 mm film plane rather than in output pixels. These
  numbers are visual calibrations, not measured crystal diameters.
- Keep chroma restrained and tied to the same luminance crystal. Scanner references show more
  colour variation, but its magnitude is too scanner-dependent to copy safely.
- Preserve the tone-dependent density model: useful shadows and midtones reveal grain; true
  printed black and bright highlights mask it. The 6×7 bright-sky reference supports the
  highlight roll-off, while the lifted 35mm shadow demonstrates why underexposure must increase
  irregularity without adding a uniform grey noise overlay.
- Continue applying grain after colour, physical-scale diffusion, semantic colour handling, and
  halation. Smooth/defocused regions reveal the same film-plane field because less scene detail
  competes with it.

`DevelopPipelineTest.fasterColourStocksCarryLargerMoreVisibleGrain` guards the catalog ordering.
`PhysicalFilmGrainTest` guards stable per-photo identity, different fields across photos, zero
mean/endpoints, 720-vs-3000 footprint consistency, midtone response, tightly correlated colour,
non-repetition, absence of low-frequency clouds, and crop anchoring. Absolute amplitude still
requires the controlled acquisition and holdout process in
[`FILM_FIDELITY_CALIBRATION.md`](FILM_FIDELITY_CALIBRATION.md).
