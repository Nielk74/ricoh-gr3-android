# Changelog

## v0.8.1 — 2026-07-20

- Added a persisted **Edited export** quality selector with Compact (up to 1.5 MP / JPEG 92),
  High (up to 6 MP / JPEG 97), and Maximum (device-safe maximum / JPEG 100) choices.
- Kept High as the migration-safe default while allowing Maximum to remove the previous fixed
  6 MP cap; the heap-derived safety bound remains in place to avoid crashing lower-memory phones.
- Used the full selected pixel budget instead of falling to BitmapFactory's next lower
  power-of-two sampling tier.
- Made each edited-save confirmation disclose its actual pixel dimensions and JPEG quality.

## v0.8.0 — 2026-07-20

- Rebuilt film rendering around exact sRGB/linear-light colour math, explicit negative and print
  density, stock provenance, profile colour balance, physical-scale diffusion, immutable-source
  two-lobe halation, and resolution-independent film-plane grain.
- Added explicit **Stock** and **Smart** rendering contracts. Stock is the scene-invariant
  calibration surface; Smart adds bounded tone/subject protection and a subtle scene-guarded
  warmth bias for daylight-balanced colour stocks.
- Kept tungsten-balanced, monochrome, and unspecified-balance looks outside the new warmth bias,
  with high-chroma and gamut-edge guards for vivid colours.
- Made look intensity and rendering intent consistent across preview, edited save, sticky
  defaults, and multi-select batch application.
- Made edited-save resolution responsive to the Android heap, and made unsupported or undecodable
  edited DNG saves fail explicitly while preserving **Save original**.
- Added a manual in-app update screen and robust paginated GitHub release discovery.
- Expanded the reproducible film review lab, calibration metrics, research notes, and Android/JVM
  regression suites.
