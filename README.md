# Ricoh GR III Android Companion

[![Build](https://github.com/Nielk74/ricoh-gr3-android/actions/workflows/build.yml/badge.svg)](https://github.com/Nielk74/ricoh-gr3-android/actions/workflows/build.yml)
[![Latest release](https://img.shields.io/github/v/release/Nielk74/ricoh-gr3-android?display_name=tag)](https://github.com/Nielk74/ricoh-gr3-android/releases/latest)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)

A native Kotlin companion for the **Ricoh GR III and GR IIIx**: Bluetooth remote control,
Wi-Fi live view and photo transfer, plus a scene-aware, on-device film lab for JPEG and DNG
captures.

The app is independent and unofficial. It uses the camera's community-documented BLE GATT and
local HTTP interfaces; it does not require Ricoh's Image Sync app or a cloud account.

> **Current release: v0.7.0.** The app, protocol clients, colour-science core, update path, and
> automated tests are implemented. Real-camera radio behaviour still needs validation across GR
> III/IIIx firmware and Android vendors; see [Current limitations](#current-limitations).

## What it does

| Area | Current capability |
| --- | --- |
| Bluetooth | Scan, connect, read camera identity and WLAN credentials, cache credentials in private app storage, fire the shutter, and expose basic camera state. |
| Wi-Fi | Join the camera AP on Android 10+, bind traffic to its internet-less network, show MJPEG live view, fire the Wi-Fi shutter with retry, and read camera properties. |
| Library | Browse a three-column camera contact sheet, inspect metadata, distinguish RAW files, select batches, download JPEG/DNG originals, and mark edited frames. |
| Viewer | Render the real developed preview, press and hold for before/after, choose a sticky look, adjust effect from 50–150%, reset, and save original or edited copies to `Pictures/GR3`. |
| Film Lab | Eleven hand-authored film/cinema looks with adaptive tone, negative-to-print colour, natural skin isolation, Portra sky and foliage handling, stock-coloured halation, and density grain. |
| Updates | Check GitHub Releases at most once every 24 hours, offer newer stable builds, verify the published APK SHA-256, and hand installation to Android. |

## Connection model

The GR III exposes two useful wireless planes, but the camera runs them **one at a time**:

```text
Bluetooth mode                         Wi-Fi mode
─────────────────────────────          ─────────────────────────────────
Scan and pair                          Turn Wi-Fi on from the camera body
Remote shutter + basic control         App joins the camera access point
Read/cache Wi-Fi credentials           Live view, gallery, transfer, shutter
Low power; no live view                High bandwidth; Android 10+ required
                 Change mode = disconnect the active transport
```

The reliable workflow is:

1. Pair over Bluetooth once if the app still needs the camera's SSID and passphrase.
2. Return to the connection chooser; the credentials remain cached locally.
3. Enable Wi-Fi on the camera body and choose **Wi-Fi** in the app.
4. Accept Android's network prompt, then open **Library** or **Live View**.

The reverse-engineered BLE write that should wake the camera's AP is rejected by shipping GR III
firmware, so the app does not pretend that hand-off is automatic. You currently enable Wi-Fi on
the camera yourself. The investigation is documented in
[`research/BLE_WIFI_WAKE_INVESTIGATION.md`](research/BLE_WIFI_WAKE_INVESTIGATION.md).

## Film Lab

The Film Lab is not a fixed colour overlay. It measures the actual frame and adjusts bounded
parts of each stock response for high-key, backlit, low-key, mixed-light, and high-ISO scenes.
Preview and export share the same pure-Kotlin processing core.

### Included looks

- Portra 400 and Portra 800
- Gold 200 and Ektar 100
- Superia 400
- CineStill 800T
- Vision3 250D and Vision3 500T
- Eterna Cinema
- Tri-X 400 and HP5 Plus

The processing order is deliberate:

```text
JPEG or rendered DNG
  → scene analysis and bounded tone protection
  → optional film-negative exposure bracket
  → per-channel negative density
  → positive print / scanner response
  → luminance-neutral split tone
  → connected, face-gated skin naturalisation
  → selective Portra foliage and top-connected sky colour
  → stock-coloured halation
  → film-plane density grain
  → high-quality JPEG export
```

Important details:

- **Effect intensity:** 50–150% changes the stock character and emulsion layers. Tonal
  protection is not over-amplified, so 150% does not become an HDR effect.
- **Natural portraits:** an on-device face detector gates a chromaticity mask. Complexions are
  protected without globally desaturating red fabric, wood, hair, glasses, beard detail, or warm
  light.
- **Portra colour:** only eligible, top-connected blue sky moves toward cyan. Vegetation-range
  yellow-greens also receive a smaller cyan-green move; skin, neutrals, deep shadows, pale
  highlights, and existing cyan are excluded. Both transforms preserve luminance.
- **Halation:** generated from highlight-edge spill in linear light rather than a uniform bloom.
  CineStill 800T uses the strong red fringe associated with its rem-jet-free character; cinema
  stocks remain warmer and more restrained.
- **Grain:** applied after tone, blur, and halation on the film plane. A non-repeating
  density-domain field stays sharp over blurred detail, peaks through shadows/midtones, rolls off
  near black and bright highlights, and uses larger, more irregular crystals for faster stocks.
  Edited JPEGs export at quality 97 to retain that texture.
- **No mystery LUT pack:** the negative, print, colour-coupling, grain, and spatial models are
  authored in this repository. Stock names describe aesthetic targets, not manufacturer-certified
  colourimetry.

See the methodology in [`research/FILM_EMULATION.md`](research/FILM_EMULATION.md), the Portra
grain measurements in
[`research/PORTRA_GRAIN_CALIBRATION.md`](research/PORTRA_GRAIN_CALIBRATION.md), and the reproducible
preview workflow in [`docs/FILM_PREVIEWS.md`](docs/FILM_PREVIEWS.md).

### Generated previews

These are generated from one neutral GR III sample through the exact pipeline used by the app.

| Standard | Portra 400 | Portra 800 | Gold 200 |
|:--:|:--:|:--:|:--:|
| ![Standard](docs/previews/standard.jpg) | ![Portra 400](docs/previews/portra400.jpg) | ![Portra 800](docs/previews/portra800.jpg) | ![Gold 200](docs/previews/gold200.jpg) |
| **Ektar 100** | **Superia 400** | **CineStill 800T** | **Vision3 250D** |
| ![Ektar 100](docs/previews/ektar100.jpg) | ![Superia 400](docs/previews/superia400.jpg) | ![CineStill 800T](docs/previews/cinestill800t.jpg) | ![Vision3 250D](docs/previews/vision3_250d.jpg) |
| **Vision3 500T** | **Eterna Cinema** | **Tri-X 400** | **HP5 Plus** |
| ![Vision3 500T](docs/previews/vision3_500t.jpg) | ![Eterna Cinema](docs/previews/eterna.jpg) | ![Tri-X 400](docs/previews/trix400.jpg) | ![HP5 Plus](docs/previews/hp5.jpg) |

<sub>Sample photo copyright remains with its author (RICOH GR III sample gallery). It is bundled
only to produce comparable look previews.</sub>

## Install

Download the APK from [the latest GitHub Release](https://github.com/Nielk74/ricoh-gr3-android/releases/latest),
open it on the Android device, and allow installation from that source when Android asks.

- **Android 8.0 / API 26 or newer** is supported for Bluetooth control.
- **Android 10 / API 29 or newer** is required for joining and routing traffic over the camera's
  Wi-Fi access point.
- Android 8–9 asks for legacy storage permission when saving to the shared gallery. Android 10+
  uses scoped storage.

After installation, the app checks only this repository's public GitHub Releases feed. A visible
banner appears when a newer stable APK exists. Download is always user-initiated, its SHA-256 is
verified when the release publishes one, and Android still requires confirmation before
installation. Updates must be signed with the same key as the installed app.

## Use the app

### Bluetooth remote

1. Enable Bluetooth on the camera and phone.
2. Open the app, grant the requested nearby-device permission, and choose **Bluetooth**.
3. Scan, select the GR III/IIIx, and connect.
4. Use the remote shutter. The app also caches Wi-Fi credentials when the firmware exposes them.

On Android 8–11, the platform requires location permission for BLE scanning. The app does not use
that permission to track or upload location.

### Wi-Fi library and live view

1. Enable Wi-Fi from the camera menu.
2. Choose **Wi-Fi** in the app.
3. Join with the remembered credentials, or use the currently connected camera network.
4. Open **Library** for contact-sheet browsing and transfers, or **Live View** for the MJPEG
   viewfinder and Wi-Fi shutter.
5. Open a frame, choose a film look, hold the image to compare with the original, set 50–150%
   intensity, and save either the untouched original or a developed copy.

Original JPEG/DNG downloads are preserved byte-for-byte. Developed output is a new JPEG and never
overwrites the camera original.

## Build and verify

Requirements:

- JDK 17
- Android SDK Platform 34 and matching build tools
- macOS, Linux, or Windows with the checked-in Gradle wrapper

Build a debug APK:

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Run the same main verification used by CI:

```bash
./gradlew assembleDebug lint test
./gradlew :tools:renderPreviews
```

The colour-science module can also be tested without an Android SDK:

```bash
./gradlew :tools:test
```

### High-resolution film review

The repository includes a modern local review lab for calibration, not just tiny README
thumbnails:

```bash
./gradlew :tools:renderReviewSite
python3 -m http.server 8765 --directory build/film-review
```

Open `http://localhost:8765`. It renders every supplied JPEG/DNG scene at a 3000 px long edge and
provides:

- original, developed, split-compare, and skin-mask views;
- fit, 100%, and 200% zoom with synchronized panning;
- the real 50–150% effect range and a dedicated grain-inspection shortcut;
- real −1/0/+1 film-negative exposure renders for selected stocks;
- per-scene Keep/Tweak/Reject notes with export.

Place private calibration captures in `.references/`; generated review files remain under the
ignored `build/` directory.

## Architecture

```text
app/src/main/java/com/ricohgr3/app/
├── ble/                 GATT profile, scanner, connection and shutter controller
├── wifi/                AP join, network-bound HTTP, MJPEG and camera REST client
├── data/                Camera photo repository, models, persistence and MediaStore export
├── gallery/             Contact sheet, viewer, edit state and save/develop flows
├── liveview/            MJPEG viewfinder state and Wi-Fi shutter
├── looks/emulation/     Scene analysis, stock models, skin, tone, halation and grain
├── update/              Release discovery, semantic versions, APK download and checksum
├── ui/update/           In-app update banner and progress UI
└── nav/                 Compose navigation and transport flow

tools/                   JVM preview, calibration and review-site renderers
review-site/             Responsive high-resolution comparison interface
research/                Protocol and colour-science investigations
docs/                    Product plans, look design and generated preview documentation
```

Core technology:

- Kotlin 2 and Jetpack Compose / Material 3
- OkHttp for the local camera API, MJPEG transport, and release downloads
- Android BLE APIs and `WifiNetworkSpecifier`
- Kotlin Serialization and DataStore
- ML Kit face detection, bundled for on-device skin isolation
- Pure-Kotlin, Android-free colour math shared with the JVM render/test tools
- Min SDK 26; compile and target SDK 34

## Testing boundary

Automated coverage includes:

- BLE payload and controller-state tests;
- camera HTTP models, fixtures, and MockWebServer integration;
- MJPEG frame parsing;
- gallery/edit persistence and viewer save logic;
- scene adaptation, LUT interpolation, negative/print behaviour, skin masks, selective sky and
  foliage transforms, halation, and deterministic grain statistics;
- semantic-version selection, draft/prerelease filtering, and update download checks;
- reproducible README and 3000 px review renders.

CI runs `assembleDebug`, Android lint, the test suites, and film-preview generation on every
push and pull request. These checks prove the software paths; they cannot prove BLE/Wi-Fi radio
behaviour on every physical camera and phone.

## Privacy and security

- Photos are downloaded from the camera's local access point and developed on the phone.
- The app does not upload photos or edit data to a cloud service.
- Camera credentials are stored in private app DataStore.
- The update checker contacts GitHub's public Releases API only, no more than once per 24 hours.
- Release APKs are checksum-verified when possible and still subject to Android's same-signing-key
  and user-confirmation rules.

## Current limitations

- The wireless protocol is unofficial and reverse-engineered; a camera firmware update can change
  behaviour.
- Bluetooth and Wi-Fi control are mutually exclusive on the camera.
- BLE credentials can be read, but the known remote Wi-Fi wake write is rejected by tested GR III
  firmware. Enable Wi-Fi on the body.
- Wi-Fi joining and camera HTTP behaviour still need physical validation across GR III/IIIx
  firmware versions and Android OEM networking stacks.
- Live view does not yet provide tap-to-focus or the complete exposure-control surface.
- CPU film development is the portable baseline and can take time on older phones. A future AGSL
  path can accelerate preview.
- Full-resolution development is bounded to roughly 6 MP to avoid exhausting a mobile heap. The
  original 24 MP JPEG/DNG remains available and can be saved untouched.
- DNG rendering uses Android's platform `ImageDecoder` on API 28+ and therefore varies by device.
  If a device cannot render a DNG, the app preserves the original instead of producing a broken
  edit.
- The Settings route, onboarding polish, and instrumented on-device end-to-end suite are not yet
  complete.

## Documentation

| Document | Purpose |
| --- | --- |
| [`ROADMAP.md`](ROADMAP.md) | Living implementation plan and remaining hardware/product work |
| [`research/FEASIBILITY.md`](research/FEASIBILITY.md) | GR III wireless protocol feasibility |
| [`research/BLE_WIFI_WAKE_INVESTIGATION.md`](research/BLE_WIFI_WAKE_INVESTIGATION.md) | Why automatic BLE-to-Wi-Fi wake is not shipped |
| [`docs/PHASE7-LOOKS.md`](docs/PHASE7-LOOKS.md) | Film Lab product and processing design |
| [`research/FILM_EMULATION.md`](research/FILM_EMULATION.md) | Colour-science model and implementation |
| [`research/PORTRA_GRAIN_CALIBRATION.md`](research/PORTRA_GRAIN_CALIBRATION.md) | Portra 400/800 grain evidence and targets |
| [`docs/FILM_PREVIEWS.md`](docs/FILM_PREVIEWS.md) | Reproducible preview and review-lab workflow |
| [`RELEASING.md`](RELEASING.md) | Signing, versioning, checksums, tags, and in-app updates |

## Releases

Pushing a `v*` tag runs the release workflow. The tag becomes `versionName`; GitHub Actions builds
the APK and AAB, signs them when release secrets are configured, writes the APK SHA-256, and
publishes all three files to GitHub Releases. See [`RELEASING.md`](RELEASING.md) before cutting a
tag, especially because future Android updates must use the same signing key.
