# Viewer-open crash — fix + E2E testing gap

## Summary

Opening a photo from the Library crashed the app. Root cause was a **navigation route
mismatch**, now fixed. This doc records the finding, how it was verified, and — importantly —
a **testing gap we still need to close**: there are currently **no automated E2E / instrumented
tests**. The crash flow was verified once by hand on an emulator; that verification is not
repeatable in CI.

## The bug

- `PhotoId.toString()` is `folder/file`, e.g. `100RICOH/R0000001.JPG`.
- The gallery navigated with `navigate(Screen.Viewer.buildRoute(id.toString()))`, producing the
  route string `viewer/100RICOH/R0000001.JPG`.
- The viewer route pattern is a **single** path segment: `viewer/{photoId}`. Navigation-Compose
  splits routes on `/`, so the embedded slash made the route un-matchable and threw on every open:

  ```
  FATAL EXCEPTION: main
  java.lang.IllegalArgumentException: Navigation destination that matches route
    viewer/100RICOH/R0000001.JPG cannot be found in the navigation graph
    at androidx.navigation.NavController.navigate(NavController.kt:2399)
  ```

This crashed for **every real photo**, since every `PhotoId` contains a `/`.

## The fix

Introduced a symmetric, single-segment encode/decode pair on `PhotoId`
(`app/src/main/java/com/ricohgr3/app/data/PhotoRepository.kt`):

- `PhotoId.toRouteArg()` — percent-encodes `folder/file` into ONE opaque path segment
  (RFC 3986 unreserved set). Pure JVM strings (no `android.net.Uri`) so it unit-tests.
- `PhotoId.fromRouteArg()` — inverse; returns `null` on malformed/unsplittable input so the
  existing `ViewerError` screen shows instead of crashing.

Wiring:
- `AppNavHost` gallery `onOpenPhoto` now calls `buildRoute(id.toRouteArg())`.
- `AppNavHost.parsePhotoId` delegates to `PhotoId.fromRouteArg`.

> Gotcha found while fixing: inside `buildString { ... }`, a bare `toString()` binds to the
> StringBuilder's (empty) `toString`, not `PhotoId`'s — use `"$folder/$file"` explicitly. The
> round-trip unit test caught this.

## How it was verified

1. **Unit tests** — `app/src/test/java/com/ricohgr3/app/data/PhotoIdRouteTest.kt` (6 cases):
   round-trips normal / RAW / unicode / spaces / `%` ids, asserts no bare `/` survives, and
   rejects malformed input. Runs in `./gradlew :app:testDebugUnitTest`.

2. **Manual, one-off emulator check** (API 34, KVM) — NOT committed, NOT repeatable:
   - Fixed path (encoded arg) → Viewer rendered correctly (`R0000001.JPG` / `100RICOH`).
   - Reproduced the ORIGINAL crash by temporarily calling `navigate()` with the raw
     `viewer/100RICOH/R0000001.JPG` string → the `IllegalArgumentException` above.
   - All temporary scaffolding (debug deep links + manifest intent-filters) was reverted.

## TODO — automated E2E tests (NOT yet done)

**We still need real, repeatable E2E coverage.** None exists today: no `app/src/androidTest`
source set, and no instrumented-test dependencies. The emulator verification above was manual
and thrown away.

Work required:

1. **Add instrumented-test infra**
   - Deps: `androidx.compose.ui:ui-test-junit4`, `androidx.test:runner`, `androidx.test:rules`,
     `debugImplementation` of `androidx.compose.ui:ui-test-manifest`.
   - Create an `app/src/androidTest/...` source set.

2. **Add test seams so features are exercisable without a physical camera**
   - `MainActivity` hard-wires a real `PhotoRepository` / Wi-Fi controller, and every feature
     (gallery, viewer, live view, shutter) needs the camera. A unit-test `FakeCameraWifiController`
     already exists but is not reachable from the app.
   - Inject a fake backend (expose the controller for tests) OR point the HTTP client at a
     `MockWebServer` in instrumented tests.

3. **Write the flows** (target: most features, "optional" suite)
   - **Viewer-open (regression for this bug):** Gallery with fake photos → tap frame → Viewer
     opens and shows the frame's `folder`/`file` → apply a look → edited mark appears back in the
     gallery.
   - Connect chooser (Bluetooth / Wi-Fi selection).
   - Live view (MJPEG) with a fake frame source.
   - Wi-Fi shutter with a fake controller.

**Cheaper interim option (no emulator, runs in `./gradlew test`):** Robolectric + Compose UI
tests driving the real `NavHost` and screens with the existing fakes. Covers the crash flow and
gallery/viewer as committed, CI-runnable tests without the instrumented-test infra above.

## Environment note

Running the emulator on the dev host required adding the user to the `kvm` group
(`sudo usermod -aG kvm <user>`). CI has no emulator wired up; instrumented tests would need a
runner with hardware acceleration (or Robolectric to sidestep it).
