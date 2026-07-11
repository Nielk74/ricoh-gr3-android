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

- [~] **BLE → wake Wi-Fi**: WLAN Control service + SSID/passphrase/network-type UUIDs in
      `RicohGattProfile` (verified against `research/references/.../wlan_control_command/*`);
      credential **reads work**. **BLOCKED:** the `enableWifiAp()` network-type→AP *write* is
      rejected by shipping firmware (1.92 & 2.10) with GATT app error `0x80` — not the real
      trigger. Hardware-RE'd directly (`research/BLE_WIFI_WAKE_INVESTIGATION.md`, `research/tools/`).
      **Fallback shipped:** user turns Wi-Fi on at the camera, app auto-joins the AP. Revisit via
      btsnoop capture / APK decompile of the official app.
- [x] **Android Wi-Fi join**: `WifiApConnector` + `CameraWifiSession` (Idle→Joining→Connected→
      Lost/Failed state machine, generation-guarded); `CameraHttpClient.forNetwork()` binds OkHttp
      to the joined `Network`. JVM-tested; radio path needs on-device validation (API 29+).
- [x] HTTP client (OkHttp) rooted at `http://192.168.0.1/v1/` (`CameraHttpClient`, `NO_PROXY`, interface `CameraWifiController`)
- [x] `GET /v1/ping`, `GET /v1/props` — parsed into models (fixtures + tests)
- [x] **Live view**: MJPEG frame splitter (`MjpegFrameParser`) + `LiveViewScreen`/`LiveViewViewModel`
      (decoded frames + Wi-Fi shutter, Concept-A). End-to-end stream needs on-device validation.
- [x] **Settings control**: `PUT /v1/params/camera` (`CaptureParams`) — request shape tested; GR III enums
      (`sv`/`tv`/`av`/`xv`/`effect` incl. film sims) documented from `capture_ricoh_gr_iii.yaml`
- [x] **Capture over Wi-Fi**: `POST /v1/camera/shoot` (empty body per spec — verify on device)
- [x] **Photo browse**: `GET /v1/photos` list + `GET .../info` for EXIF (models + tests)
- [x] **Photo download**: JPEG + RAW (DNG) pull (`downloadPhoto`, size options); TODO: stream to disk vs. buffer
- [ ] `GET /v1/changes` (WebSocket) — react to camera-side setting changes
- [ ] Graceful reconnect / AP-drop handling; clear "camera Wi-Fi vs internet" UX
- [ ] **On-device validation** of the whole Wi-Fi plane (needs physical GR III)

## Phase 5 — Connection & session management  (M) — MVP FLOW WIRED

The **MVP happy path is wired end-to-end**: `ConnectScreen` (Concept-A stepper) drives
BLE pair → **user enables Wi-Fi on the camera** → app joins the camera AP → Library / Live
View entries; `MainViewModel` orchestrates the handoff over `CameraWifiSession`. (Auto BLE
wake was dropped — the camera rejects it; see Phase 4.) Falls back gracefully (BLE shutter
works without Wi-Fi; API<29 shows a "needs Android 10+" note). On-device validation against
a physical GR III is the remaining gate (no host radio).

- [x] Unified connection flow (disconnected → BLE → Wi-Fi active) surfaced in `ConnectScreen`
      over `CameraWifiSession.State` + `BleState` — the visible BLE→Wi-Fi state machine
- [ ] Full unified *state machine* class merging BLE + Wi-Fi (currently orchestrated in the VM)
- [ ] Remember paired camera (auto-reconnect on app open)
- [ ] Foreground service for long transfers / keep-alive
- [ ] Battery + storage indicators (from BLE Camera service)
- [ ] Handle multiple cameras / camera picker

## Phase 6 — UI / UX design  (L) — DIRECTION CHOSEN

Direction (chosen with user): **Concept A "Contact Sheet"** — mostly-white paper, warm
near-black ink, **Ricoh red** as the sole accent, mono metadata (camera-OSD / film-rebate).
**Library is the hero** (primary use = photo offload + edit); shooting is one tap away.
Two UX requirements: an **"edited" mark** on styled frames + **sticky last-used look**.
Concept artifacts approved; full-minimal Concept-A for single-photo viewer.

- [x] Design language + tokens: Compose theme wired in (`ui/theme/` — `Color.kt` Ricoh-red
      palette, `Type.kt` sans+mono roles, `Theme.kt` light/dark + `GrColors` extended tokens)
- [x] Navigation scaffold: `nav/` NavHost + routes (connect / gallery / viewer / live view / settings)
- [x] **Gallery / contact-sheet grid** (`gallery/GalleryScreen.kt`): 3-col contact sheet, thumbnail
      loader, multi-select (long-press), **edited mark** (red dot), RAW badge, sticky batch-apply bar
- [x] **Full-screen viewer** (`gallery/ViewerScreen.kt`): VIEW-sized load, look strip, press-and-hold
      before/after, applied-look tag + edited dot, film-rebate metadata, Reset/Apply (updates sticky)
- [x] Shared **edit core** in `GalleryViewModel`: per-frame `EditState` + sticky look (DataStore),
      one VM shared gallery↔viewer so a look applied in either shows instantly in the other
- [ ] Remaining Concept-A screens: connect polish, live view + shutter, settings
- [ ] Transfer progress UX; on-device thumbnail/photo load against a real GR III
- [ ] Live-view viewfinder UX (tap-to-focus, exposure controls, minimal chrome)
- [ ] Onboarding / camera-pairing flow (explain BLE→Wi-Fi handoff simply)
- [ ] Accessibility pass (contrast, touch targets, TalkBack)

## Phase 7 — Signature features  (L) — SPECCED → see `docs/PHASE7-LOOKS.md`

Full plan with real `/v1/params` `effect` enum values in **`docs/PHASE7-LOOKS.md`**.
Decision: **start on-camera, grow in-app**; Auto-Look kept; sticky look + edited mark.

- [ ] **7.1 On-camera looks** (ships first): push GR III `effect` before capture (`efc_posiFilm`,
      `efc_bleachBypass`, `efc_hardMonochrome`, `efc_HDRTone`, `col_vivid`…) via `/v1/params/camera`
- [~] **7.2 Look UX in library**: edited mark ✅, sticky default across frames ✅, batch apply ✅,
      before/after (press-and-hold) ✅, reset ✅ — all built in Phase 6d/6e. Preview tint is
      *indicative* (LookSwatch); true per-frame render awaits 7.3. On-device look-push wiring = 7.1.
- [ ] **7.3 In-app develop engine**: DNG decode + GPU pipeline + film-emulation LUTs (the big one)
- [ ] **7.4 Auto-Look**: heuristic scene→look (EXIF + thumbnail), opt-in; v2 tiny on-device model
- [ ] **7.5 Presets & sharing**: save/share edit stacks, apply on import (no cloud)

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
