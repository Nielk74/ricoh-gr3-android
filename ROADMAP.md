# Ricoh GR3 Android App — Roadmap

Living plan. Keep this updated as work progresses. Checkboxes: `[x]` done, `[~]` in
progress, `[ ]` todo. Details for undecided items (esp. UI) are deferred until we reach them.

Legend for effort: S = small, M = medium, L = large.

---

## Phase 0 — Research & feasibility ✅ DONE

- [x] Init git repo + private GitHub repo (`Nielk74/ricoh-gr3-android`)
- [x] Deep research on GR III wireless control → **feasible**
- [x] Document findings (`research/FEASIBILITY.md`) + clone protocol specs (`research/references/`)
- [x] Save project facts to memory

## Phase 1 — Project scaffold + BLE shutter PoC ✅ DONE

- [x] Kotlin + Jetpack Compose project (min SDK 26, compile SDK 34, Gradle wrapper)
- [x] `RicohGattProfile` — reverse-engineered GATT UUIDs
- [x] `CameraBleManager` — scan / connect / read device info / fire shutter
- [x] Compose UI (`CameraScreen`) + runtime BLE permissions
- [x] Verified: `./gradlew assembleDebug` produces valid installable APK
- [x] `CLAUDE.md` project docs
- [ ] **On-device validation** — fire the shutter on a real GR III (needs hardware; manual)

---

## Phase 2 — CI / CD  (M) ✅ MOSTLY DONE

Goal: every push is built & tested; tags cut a GitHub Release with an APK.

- [x] GitHub Actions: `build.yml` — on push/PR: set up JDK 17 + Android SDK, `./gradlew assembleDebug lint test`
- [x] Gradle build cache + dependency caching in CI (setup-java `cache: gradle`)
- [x] Unit tests (JVM) + Android lint in `build.yml`; reports uploaded as artifacts (`if: always()`)
- [x] `release.yml` — on tag `v*`: build **release APK + AAB**, sign it, create GitHub Release, attach artifacts
  - [x] Signing: config reads keystore from GitHub secrets (`KEYSTORE_B64`, `KEY_ALIAS`, `KEY_PASSWORD`, `STORE_PASSWORD`); see `RELEASING.md`
  - [x] Wire optional signing config into `app/build.gradle.kts` (falls back to unsigned locally when secrets absent)
- [x] Version bumping strategy (versionName from tag, versionCode from run number, via env vars)
- [x] Status badge in README
- [ ] (later) instrumented tests on emulator via `reactivecircus/android-emulator-runner`
- [ ] (optional) Dependabot / renovate for dependency updates
- [ ] **Verify CI on GitHub** — first real push/tag run (needs the repo secrets configured for release)

## Phase 3 — Testing foundation  (M) — IN PROGRESS

- [x] Unit tests for BLE payload encoding (`RicohGattProfile.shutterPayload`, shutter byte array) — pure logic, no device
- [x] Fake/mocked controller so ViewModel + UI are testable without a radio (`FakeCameraController`)
  - [x] Extract a `CameraController` interface (BLE impl + fake impl)
- [x] Controller/state-transition tests (scan → discover → connect → shutter count)
- [ ] Compose UI tests (screens render per connection state)
- [ ] HTTP layer tests against a mock web server (`MockWebServer`) using recorded `/v1/` responses — *deferred to Phase 4*
- [ ] Test the `/v1/` response parsers with fixtures captured from the reference specs — *deferred to Phase 4*

## Phase 4 — Wi-Fi layer (HTTP `/v1/` API)  (L) — DATA LAYER DONE

Goal: live view + settings + photo transfer over the camera's Wi-Fi AP.
Transport/data layer built & unit-tested (23 tests green, MockWebServer + fixtures);
grounded in the cloned OpenAPI spec + community repos. UI wiring is Phase 6.

- [ ] **BLE → wake Wi-Fi**: read WLAN SSID/passphrase/channel via BLE WLAN Control chars; enable camera AP
- [~] **Android Wi-Fi join**: `WifiApConnector` (`WifiNetworkSpecifier` + `bindProcessToNetwork` + teardown)
      scaffolded & documented; needs on-device validation (API 29+; no host radio)
- [x] HTTP client (OkHttp) rooted at `http://192.168.0.1/v1/` (`CameraHttpClient`, `NO_PROXY`, interface `CameraWifiController`)
- [x] `GET /v1/ping`, `GET /v1/props` — parsed into models (fixtures + tests)
- [~] **Live view**: MJPEG frame splitter (`MjpegFrameParser`, SOI/EOI marker-based) built & tested;
      Compose rendering + end-to-end stream = Phase 6
- [x] **Settings control**: `PUT /v1/params/camera` (`CaptureParams`) — request shape tested; GR III enums
      (`sv`/`tv`/`av`/`xv`/`effect` incl. film sims) documented from `capture_ricoh_gr_iii.yaml`
- [x] **Capture over Wi-Fi**: `POST /v1/camera/shoot` (empty body per spec — verify on device)
- [x] **Photo browse**: `GET /v1/photos` list + `GET .../info` for EXIF (models + tests)
- [x] **Photo download**: JPEG + RAW (DNG) pull (`downloadPhoto`, size options); TODO: stream to disk vs. buffer
- [ ] `GET /v1/changes` (WebSocket) — react to camera-side setting changes
- [ ] Graceful reconnect / AP-drop handling; clear "camera Wi-Fi vs internet" UX
- [ ] **On-device validation** of the whole Wi-Fi plane (needs physical GR III)

## Phase 5 — Connection & session management  (M)

- [ ] Unified connection state machine (disconnected → BLE → Wi-Fi active) across BLE + Wi-Fi
- [ ] Remember paired camera (auto-reconnect on app open)
- [ ] Foreground service for long transfers / keep-alive
- [ ] Battery + storage indicators (from BLE Camera service)
- [ ] Handle multiple cameras / camera picker

## Phase 6 — UI / UX design  (L) — DETAILS TBD WITH USER

Direction (given): **modern, very minimalist, mostly white**, UI/UX-skill driven.
Concrete layouts/flows to be decided together when we get here.

- [ ] Design language: mostly-white minimal theme, light/dark, typography, spacing system
      (use design/dataviz + artifact-design skills; prototype screens as artifacts for sign-off)
- [ ] Core screens: connect, live view + shutter, gallery, photo detail, settings
- [ ] Live-view viewfinder UX (tap-to-focus, exposure controls, minimal chrome)
- [ ] Gallery grid + full-screen viewer, transfer progress
- [ ] Onboarding / camera-pairing flow (explain BLE→Wi-Fi handoff simply)
- [ ] Accessibility pass (contrast, touch targets, TalkBack)

## Phase 7 — Signature features  (L) — SCOPE TBD WITH USER

Ideas the user floated (to refine later):

- [ ] **Film emulations** — replicate/extend GR III Custom Image looks (posi film, bleach bypass,
      hard/soft monochrome, HDR tone…) as post-process filters on transferred JPEG/RAW
- [ ] **Auto filter** — auto-select/apply a look (heuristic or on-device ML) per scene
- [ ] In-app RAW (DNG) develop pipeline (exposure, WB, tone, grain)
- [ ] Preset system: save/share custom looks; apply on import
- [ ] Batch processing / export
- [ ] Optional: apply look as camera-side Custom Image setting before capture (via `/v1/params`)

## Phase 8 — Polish & release  (M)

- [ ] App icon + branding finalized
- [ ] Crash reporting / analytics (privacy-respecting, opt-in)
- [ ] Play Store listing assets (if publishing) or F-Droid/GitHub-release distribution
- [ ] Privacy policy (no cloud dependency = strong story)
- [ ] Performance pass (live-view latency, image decode, memory)
- [ ] Localization scaffold

---

## Cross-cutting / tech debt

- [x] Extract `CameraController` interface (decouples UI from BLE/Wi-Fi transports) — enables Phase 3 tests
- [x] Dependency version catalog (`gradle/libs.versions.toml`)
- [ ] Error/telemetry model shared across BLE + HTTP
- [ ] Consider Nordic BLE library if raw GATT proves fragile across OEMs
- [ ] Structured logging (behind a debug flag)

## Known risks (from FEASIBILITY.md §5)

- Reverse-engineered protocol is unofficial → may shift with camera firmware
- Android Wi-Fi AP handoff UX is the hardest engineering piece (`WifiNetworkSpecifier`)
- BLE bonding quirks vary by Android OEM
- On-device testing requires physical GR III (no CI coverage for real radio)
