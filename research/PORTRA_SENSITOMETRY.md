# Portra 400/800 sensitometry and white-point calibration — July 2026

This note records the manufacturer evidence used by the Portra colour/tone models and the narrow
way it enters a display-referred Android pipeline. It must not be read as a claim that the app has
measured Kodak negative, C-41 chemistry, a scanner, or a print stock.

## Manufacturer evidence

The sources are Kodak's January 2025 technical publications:

- [PORTRA 400 E-4050](https://www.kodakprofessional.com/sites/default/files/2025-07/e4050.pdf),
  Status-M daylight characteristic curves, Log H reference −1.44;
- [PORTRA 800 E-4040](https://www.kodakprofessional.com/sites/default/files/2025-07/e4040.pdf),
  EI 800 Status-M daylight characteristic curves, Log H reference −1.74.

Red-, green-, and blue-record curves were manually sampled at roughly 0.3–0.5 Log-H intervals.
Line width and plot-coordinate reading support only about ±0.05 density-unit precision. The full
sample lists live in `PortraSensitometry.kt`; these endpoint summaries are useful audit anchors:

| Stock | Record | first sampled D | last sampled D | sampled capacity |
|---|---|---:|---:|---:|
| Portra 400 | red | 0.22 | 2.02 | 1.80 |
| Portra 400 | green | 0.67 | 2.45 | 1.78 |
| Portra 400 | blue | 0.85 | 3.03 | 2.18 |
| Portra 800 | red | 0.28 | 1.98 | 1.70 |
| Portra 800 | green | 0.73 | 2.47 | 1.74 |
| Portra 800 | blue | 1.05 | 2.95 | 1.90 |

The unequal absolute record densities are expected negative-material metadata, not an RGB colour
cast to apply directly to a positive image.

## Bounded use in the app

The app normally receives an already rendered camera JPEG or platform-rendered DNG, not calibrated
scene-linear exposure. Applying absolute Log-H literally would double-render the camera tone curve.
`ManufacturerCharacteristicAnchor` therefore:

1. aligns the publication's Log-H reference to 18% input exposure;
2. normalizes the sampled density response to exact black and white endpoints;
3. power-aligns the curve so 18% grey remains 18%;
4. blends only 30% of the curve shape for Portra 400 and 34% for Portra 800 with the existing
   display-safe fallback response.

This is stronger evidence than a stock-name aesthetic fit, so the two profiles are labelled
`MANUFACTURER_DIGITIZED`. It remains weaker than traceable local sensitometry and is deliberately
separate from the `LAB_MEASURED` CSV import.

## White after shoulder compression

The negative/positive shoulder remains responsible for highlight spacing. Smart rendering then
measures the already-rendered result and, only when the source has a credible bright anchor,
monotonically expands luminance above an encoded 0.70 pivot toward the source/target white point.
The operation runs in linear light, preserves chromaticity through common-scale gamut compression,
does not invent white in a low-key scene, and keeps display white exact. Portra highlight split
toning fades back to neutral near white so the recovered endpoint does not become cream.

In the July 2026 3000-pixel diagnostic pass, the bright DNG portrait's approximate linear p99 was
0.800 in the archived Portra 400 result and 0.933 after recovery; the source anchor was 0.849.
The low-key theatre frame did not qualify for a global expansion, while its small clipped practical
light retained white through the endpoint-preserving path. These scene figures validate the guard;
they are not stock sensitometry.

## Remaining boundary

A manufacturer graph does not identify camera spectral response, illuminant spectra, processing
chemistry, internegative/print stock, scanner matrices, or sharpening. A measured Portra match
still requires the controlled acquisition, train/holdout split, and tolerances in
[`FILM_FIDELITY_CALIBRATION.md`](FILM_FIDELITY_CALIBRATION.md).
