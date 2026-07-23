# Changelog

## v0.9.8 — 2026-07-23

- Grouped the viewer and local-lab tuning controls into **Develop** and **Export** tabs so only
  one section is visible at a time. The look strip, apply/reset, and save actions stay on screen;
  the local lab's rotation control lives in the Develop tab (and remains available without a look
  for rotated-only saves).

## v0.9.7 — 2026-07-23

- Rebuilt auto-import as a durable, drain-first workflow: every required full-size camera file is
  streamed to an app-private disk spool before any gallery save or film development begins. Partial
  downloads are atomic, completed work survives activity recreation, and failed/paused jobs can
  continue without repeating successful outputs.
- Added **Original + edited** output. JPEG+DNG pairs save both untouched originals while only the
  DNG is developed; edited-only imports skip a paired JPEG entirely. A JPEG-only exposure is still
  developed normally.
- Moved auto-import into a visible foreground service with CPU and camera-Wi-Fi locks, persistent
  notification progress, and pause/continue controls so a user-started import can keep running with
  the app in the background or the phone locked.
- Made Maximum auto-import development preserve the platform-decoded source dimensions. The film
  pipeline processes adaptive, overlap-padded vertical regions sequentially, sized from both live
  app-heap headroom and Android's device low-memory threshold, while retaining whole-frame Smart
  analysis, one shared skin proxy, spatial scale, top-connected sky semantics, and one continuous
  physical grain field.
- Expanded progress to show separate batch-download and output-completion tracks, active byte/file
  details, per-image region progress, the disk-backed queue, and the live safe memory budget.
- Added a persistent Grain on/off control for previews and edited exports. Turning it off removes
  only the physical grain layer; the selected stock's colour, tone, diffusion, halation, and Smart
  protections remain active, including across paused/resumed auto-imports.

## v0.9.6 — 2026-07-22

- Kept auto-import and batch-save camera fetches at full resolution while adding adaptive
  double buffering: the next full file downloads during the current save/develop only when both
  process-heap headroom and Android's low-memory state say it is safe.
- Added incremental response-byte reporting, separate camera-read and completion progress bars, live
  download/develop/save filenames, and an explicit full-size-source pipeline indicator.

## v0.9.5 — 2026-07-22

- Removed Portra's focus/detail grain mask. The physical 35 mm grain field is now applied without
  spatial content gating; only pixel luminance shapes its visibility, peaking in the midtones and
  rolling off toward black and paper white.
- Kept the accepted 35 mm grain scale as the sole format and removed alternative enlargement
  experiments.

## v0.9.4 — 2026-07-22

- Recalibrated Portra 400 and Portra 800 around the accepted 35 mm grain footprint: smooth and
  defocused tone carries clearer emulsion texture, focused detail is protected, and bright diffuse
  tone keeps texture without contaminating paper white.
- Added bounded Smart white-point recovery after the film shoulder so compressed highlights keep
  their spacing while credible white objects return toward white.
- Anchored the Portra channel shapes to conservatively digitized Kodak January 2025 Status-M
  characteristic curves and expanded the high-resolution review tooling and regression coverage.

## v0.9.3 — 2026-07-21

- Added 90°/180°/−90° rotation to the hidden **Local lab**, baked into both the preview and the
  edited save; a rotate-only save is offered when no film look is selected.

## v0.9.2 — 2026-07-21

- Added a hidden **Local lab** (triple-tap the "GR" wordmark on the connect screen) that develops
  any photo picked from the device with the same film looks, rendering intent, intensity, and
  edited-quality controls as the camera viewer, and saves the result to `Pictures/GR3`.

## v0.9.1 — 2026-07-21

- Kept GitHub update checks and other internet requests on Android's normal network while a camera
  Wi-Fi session is active; only camera API sockets are now routed through the internet-less AP.

## v0.9.0 — 2026-07-20

- Added a preset-first **Auto import** page that scans the connected camera and saves the whole
  roll with one original/film-look, rendering-intent, intensity, and edited-quality choice.
- Added **Save selected** to the library's batch panel, keeping the existing multi-select look
  workflow while baking the same settings into every selected copy.
- Added shared, item-by-item transfer progress with current filename, saved/failed/remaining
  counts, safe pause/continue, per-frame failure isolation, and retry of failed frames only.

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
