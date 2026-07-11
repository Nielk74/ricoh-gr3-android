# CLAUDE.md — Ricoh GR3 Android app

Android companion app to control the **Ricoh GR III / GR IIIx** wirelessly.
See `research/FEASIBILITY.md` for the protocol analysis this app is built on, and
`ROADMAP.md` for the living plan (keep it updated as work progresses).

## Build

```bash
./gradlew :app:assembleDebug     # debug APK -> app/build/outputs/apk/debug/
./gradlew :app:installDebug      # install on a connected device/emulator
```

Requires the Android SDK (`local.properties` → `sdk.dir`). JDK 17. Compile SDK 34, min SDK 26.

## Layout

- `app/src/main/java/com/ricohgr3/app/`
  - `MainActivity.kt` — entry point, runtime BLE permission handling.
  - `MainViewModel.kt` — owns `CameraBleManager`, exposes state to Compose.
  - `ble/RicohGattProfile.kt` — reverse-engineered GATT UUIDs (shutter = Operation Request).
  - `ble/CameraBleManager.kt` — scan / connect / read device info / fire shutter.
  - `ble/BleState.kt` — immutable UI state model.
  - `ui/CameraScreen.kt` — Compose UI.

## Current scope (PoC)

BLE only: discover camera → connect → read model/firmware/serial → remote shutter (with/without AF).
The shutter writes `[1, AF]` to characteristic `559644B8-E0BC-4011-929B-5CF9199851E7`.

## Roadmap (see FEASIBILITY.md §6)

**BLE and Wi-Fi are mutually exclusive** on the GR III — an active BLE control session keeps the
camera's Wi-Fi AP off. So the connect flow is a **transport chooser**: Bluetooth (shutter/control)
OR Wi-Fi (live view/transfer). Wi-Fi path: user turns Wi-Fi on at the camera → app joins the AP →
HTTP `/v1/` via `WifiNetworkSpecifier`. Credentials read during a BLE pairing are cached
(`CameraCredentialStore`) so Wi-Fi mode joins without BLE.

**Note:** BLE auto-wake of Wi-Fi is NOT possible on shipping firmware — the camera rejects the
WLAN `Network Type` write (`0x80`). Full hardware investigation + RE tooling in
`research/BLE_WIFI_WAKE_INVESTIGATION.md` and `research/tools/`.

## Testing note

BLE cannot be exercised on the CI/build host — it needs a physical GR III and a real Android
device (emulators have no BLE radio). The build verifies compilation; on-device testing is manual.

## The app must never crash — hard rule

A user action failing is acceptable and must surface a message; the app **crashing is never
acceptable**. When adding or changing any user-triggered flow, obey all of these:

- **Catch `Throwable`, not just `Exception`, at every user-action boundary** (the coroutine a
  button launches). `OutOfMemoryError`, `StackOverflowError`, etc. are `Error`s and slip past
  `catch (Exception)`. Always re-throw `CancellationException` first so structured concurrency
  still works. Convert everything else into a visible status message — never let it propagate.
- **Never hold a full-resolution bitmap (or per-pixel buffers over one) on the heap.** The GR III
  shoots ~24MP; a naive decode plus the develop engine's per-channel float buffers will
  `OutOfMemoryError`. Decode downsampled with `inSampleSize` to a bounded working resolution
  (see `PhotoSave.decodeBounded` / `MAX_EDIT_PIXELS`), recycle bitmaps you own, and be explicit
  about bitmap ownership so nothing is double-recycled or leaked.
- **Do all image/pixel/network work off the main thread** (`withContext(Dispatchers.Default/IO)`).
  Heavy work on the UI thread is an ANR, which the user experiences as a crash.
- **A failure path must leave a status the user can read** ("Save failed: not enough memory"),
  not a silent no-op and not a stack trace.

When you touch such a flow, mentally walk the worst case (largest image, no memory, camera
dropped mid-transfer) and confirm it ends in a message, not a crash.
