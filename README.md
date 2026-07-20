# Ricoh GR III — Android Companion App

[![Build](https://github.com/Nielk74/ricoh-gr3-android/actions/workflows/build.yml/badge.svg)](https://github.com/Nielk74/ricoh-gr3-android/actions/workflows/build.yml)

An Android app to connect to and control the **Ricoh GR III / GR IIIx** (griii) camera
wirelessly: remote shutter, live view, settings control, and photo transfer.

> **Status:** BLE remote-shutter proof of concept working. See [`ROADMAP.md`](ROADMAP.md) for
> the full living plan and [`research/FEASIBILITY.md`](research/FEASIBILITY.md) for the protocol analysis.

## TL;DR — Is this feasible?

**Yes.** The GR III exposes two wireless control planes that are well understood by the
community:

- **Wi-Fi** — the camera runs an HTTP REST API at `http://192.168.0.1/v1/` (capture,
  live view MJPEG stream, settings, photo listing/download). Fully documented via
  reverse engineering.
- **Bluetooth Low Energy (BLE)** — GATT services for remote shutter, exposure settings,
  GPS geotagging, battery, and **waking up Wi-Fi remotely**. Exact service/characteristic
  UUIDs are known.

No official Ricoh SDK is required (though one exists for Pentax bodies). Everything can be
built on standard Android APIs: `HttpURLConnection`/OkHttp + `android.bluetooth.le`.

See [`research/FEASIBILITY.md`](research/FEASIBILITY.md) for the full analysis, and
[`research/references/`](research/references/) for cloned protocol specs.

## Film emulations

The app develops captured photos on-device through a scene-adaptive film engine: robust exposure
analysis, a hand-authored 3D stock transform, luminance-neutral split tone, edge-only halation, and
ISO-aware optical-density grain. A bundled on-device face detector gates a chromaticity mask so
complexions can stay natural without muting red velvet, wood, gold, clothing, hair, glasses, or
beard detail. It protects highlight latitude, low-key intent, and existing lighting colour instead
of applying one fixed LUT strength to every frame. A 50–150% control lets the stock character grow
without turning scene protection into an HDR effect; preview and export use the same setting. The
previews below are rendered
by CI from **one neutral, unedited GR III sample** through the **exact same pipeline** the app
ships, so they're an honest preview of each look — see
[`docs/FILM_PREVIEWS.md`](docs/FILM_PREVIEWS.md) for how they're generated.

| Standard (as shot) | Portra 400 | Portra 800 | Gold 200 |
|:--:|:--:|:--:|:--:|
| ![Standard](docs/previews/standard.jpg) | ![Portra 400](docs/previews/portra400.jpg) | ![Portra 800](docs/previews/portra800.jpg) | ![Gold 200](docs/previews/gold200.jpg) |
| **Ektar 100** | **Superia 400** | **CineStill 800T** | **Vision3 250D** |
| ![Ektar 100](docs/previews/ektar100.jpg) | ![Superia 400](docs/previews/superia400.jpg) | ![CineStill 800T](docs/previews/cinestill800t.jpg) | ![Vision3 250D](docs/previews/vision3_250d.jpg) |
| **Vision3 500T** | **Eterna Cinema** | **Tri-X 400** | **HP5 Plus** |
| ![Vision3 500T](docs/previews/vision3_500t.jpg) | ![Eterna Cinema](docs/previews/eterna.jpg) | ![Tri-X 400](docs/previews/trix400.jpg) | ![HP5 Plus](docs/previews/hp5.jpg) |

<sub>The stock names describe aesthetic emulations, not measured manufacturer profiles. Colour
transforms and grain are generated in-repo; no third-party film LUT is required at runtime.
Sample photo © its author (RICOH GR III sample gallery).</sub>

## Recommended architecture

```
BLE (always-on, low power)          Wi-Fi (on-demand, high bandwidth)
────────────────────────            ─────────────────────────────────
• Discover & pair camera            • Live view (MJPEG stream)
• Remote shutter + AF               • Full settings control
• Read battery / status             • Browse & download photos (JPEG/RAW)
• Geotagging (push phone GPS)       • Firmware-level camera props
• Wake Wi-Fi / hand off  ──────────▶
```

BLE for lightweight, always-connected control; use it to trigger the camera's Wi-Fi AP
when you need live view or bulk transfer, then talk HTTP.

## Tech stack (proposed)

- Kotlin + Jetpack Compose
- OkHttp for the `/v1/` REST API and MJPEG live view
- `android.bluetooth.le` (Nordic BLE library optional) for the GATT layer
- Min SDK 26+ (camera officially supports Android 13 via Image Sync)
