# Feasibility Study — Android app for Ricoh GR III / GR IIIx

**Date:** 2026-07-11
**Question:** Can we build an Android app that connects to and controls a Ricoh GR III
(griii) wirelessly?

## Verdict: ✅ FEASIBLE (high confidence)

The GR III / GR IIIx expose **two complementary, well-reverse-engineered wireless control
planes** — a Wi-Fi HTTP REST API and a Bluetooth LE GATT API. Both have been documented in
detail by the community and validated by multiple independent projects and working apps.
Nothing about the app requires proprietary Ricoh tooling; it can be built entirely on
standard Android networking + BLE APIs.

---

## 1. Connectivity overview

| Plane | Transport | Role | Power | Bandwidth |
|-------|-----------|------|-------|-----------|
| **BLE** | Bluetooth Low Energy GATT | Control, status, wake Wi-Fi, geotag | Very low | Low |
| **Wi-Fi** | Camera as AP, HTTP/1.1 | Live view, settings, photo transfer | Higher | High |

The camera acts as its own **Wi-Fi access point**. Once the phone joins, the camera is at
`http://192.168.0.1` port 80, REST API rooted at `/v1/`. BLE is used for lightweight
always-on control and — crucially — to **remotely enable the Wi-Fi AP** so the user doesn't
have to touch the camera.

---

## 2. Wi-Fi HTTP REST API (`http://192.168.0.1/v1/`)

Reverse-engineered from Ricoh's "Image Sync" app and camera firmware. Confirmed working on
GR IIIx via independent shell-script implementations.

### Key endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/v1/ping` | Connectivity check |
| `GET` | `/v1/props` | All camera properties |
| `GET` | `/v1/photos` | List photos (with filtering) |
| `GET` | `/v1/photos/{folder}/{file}` | Download a photo (size options) |
| `GET` | `/v1/photos/{folder}/{file}/info` | Photo EXIF metadata |
| `PUT` | `/v1/photos/{folder}/{file}/transfer` | Mark transfer status |
| `GET` | `/v1/transfers` | List photos by transfer status |
| `POST` | `/v1/camera/shoot` | Single capture |
| `POST` | `/v1/camera/shoot/start` · `/compose` · `/finish` · `/cancel` | Multi-shot sequences |
| `POST` | `/v1/lens/focus` · `/focus/lock` · `/focus/unlock` | AF control |
| `PUT` | `/v1/params/camera` | Set ISO / shutter / aperture / effect etc. |
| `PUT` | `/v1/params/device` · `/params/lens` | Device / lens params |
| `GET` | `/v1/liveview` | **MJPEG live view stream** |
| `GET` | `/v1/changes` | WebSocket changeset monitoring |
| `POST` | `/v1/device/wlan/finish` · `/device/finish` | Disable Wi-Fi / power off |

### GR III-specific parameter enumerations (verified in cloned specs)

- **ISO:** full range `AUTO`, `ISO100` … `ISO102400` (and beyond, model-dependent)
- **Shutter speed:** `1/24000` … long exposures, as `tv` values
- **Custom Image / effect:** `off`, `col_vivid`, `efc_monochrome`, `efc_posiFilm`,
  `efc_bleachBypass`, `efc_HDRTone`, etc. — the GR III's signature film simulations
- **Wi-Fi:** channel 1–11, SSID/key configurable
- **BLE enable condition, geotagging, GPS info, operation mode** (capture/playback/
  bleStartup/powerOffTransfer)

See [`references/ricoh-wireless-protocol/definitions/camera_ricoh_gr_iii.yaml`](references/ricoh-wireless-protocol/definitions/camera_ricoh_gr_iii.yaml)
and `capture_ricoh_gr_iii.yaml`.

---

## 3. Bluetooth LE GATT API

Reverse-engineered (unofficial), cross-referenced with Ricoh's public THETA API specs.
Supported models explicitly include **GR II / GR III / GR IIIx** (plus G900SE, WG-M2, and
several Pentax bodies).

### Services & what they give us

| Service | Capabilities |
|---------|-------------|
| **Camera Information** | Firmware, manufacturer, model, serial, BT name, MAC (all read) |
| **Camera** | Power on/off, battery level, date/time sync, operation mode, geotag, storage info, file-transfer list, power-off-during-transfer, Grad ND |
| **Shooting** | Shutter speed, aperture, ISO, exposure comp, white balance, drive/shooting/metering/capture mode, file type, JPEG size, movie config, **Operation Request (shutter trigger)**, focus mode, AF status, capture status, shot count, self timer |
| **GPS Control** | Push GPS coordinates to camera for geotagging |
| **WLAN Control** | Read/write SSID, passphrase, channel, network type (→ configure & wake Wi-Fi) |
| **Bluetooth Control** | BLE enable condition, paired device name |

### The critical one — remote shutter (Operation Request)

- **Service UUID:** `9F00F387-8345-4BBC-8B92-B87B52E3091A`
- **Characteristic UUID:** `559644B8-E0BC-4011-929B-5CF9199851E7` (Write)
- **Payload:** `[OperationCode: sint8, Parameter: sint8]`
  - OperationCode: `0`=NOP, `1`=Start Shooting/Recording, `2`=Stop
  - Parameter: `0`=No AF, `1`=AF, `2`=Green Button Function

This is directly actionable: writing `[1, 1]` to that characteristic fires the shutter with
autofocus. Confirmed independently — people have triggered the GR III shutter using the
generic **nRF Connect** app, proving no proprietary handshake is needed.

See [`references/ricoh-gr-bluetooth-api/`](references/ricoh-gr-bluetooth-api/) —
`characteristics_list.md` has the full service/characteristic matrix.

---

## 4. Evidence base (independent corroboration)

1. **`CursedHardware/ricoh-wireless-protocol`** — OpenAPI 3.0.3 spec of the Wi-Fi `/v1/`
   API, with **per-model definitions for GR III and GR IIIx** (Wi-Fi and BLE). Cloned into
   `references/`.
2. **`dm-zharov/ricoh-gr-bluetooth-api`** — full BLE GATT spec with UUIDs, explicitly
   listing GR III / GR IIIx as supported. Cloned into `references/`.
3. **`clyang/GRsync`** — working tool that syncs photos from GR II / GR III over Wi-Fi.
   Cloned into `references/`.
4. **secretsauce.net GR IIIx 802.11 reverse engineering** — independent confirmation of the
   AP-mode + `192.168.0.1/v1/` HTTP workflow, incl. deep firmware notes (Poky Linux, kernel
   4.4, `webapid`/`camctrld` daemons).
5. **Existing shipping apps** — Ricoh's own *Image Sync* (Android 13, iOS 16) and 3rd-party
   *GR Control* (App Store) already control GR III / IIIx / III HDF, proving the surface is
   both stable and app-controllable.
6. **Ricoh Camera SDK** (`api.ricoh`) — official USB + Wireless SDKs exist (Android/iOS
   wireless). Model support is Pentax-DSLR-oriented; the GR III is **not** clearly in the
   official wireless list, which is *why* we target the reverse-engineered protocol
   directly. (This is the app's main external dependency risk — see below.)

---

## 5. Risks & unknowns

| Risk | Severity | Mitigation |
|------|----------|-----------|
| Reverse-engineered protocol is unofficial → could change with firmware updates | Medium | Pin to current firmware; the `/v1/` API has been stable across GR III lifetime; degrade gracefully |
| Android Wi-Fi AP switching UX is clunky (must join camera SSID, which has no internet) | Medium | Use `WifiNetworkSpecifier` / `bindProcessToNetwork` (Android 10+) to route only our traffic to the camera AP |
| BLE bonding/pairing quirks per Android OEM | Low–Med | Use a robust BLE lib (Nordic); handle re-bonding |
| No official GR III SDK support / no vendor docs | Low | Community specs are detailed enough; we depend on `references/`, not on Ricoh |
| Live view latency / MJPEG decode load | Low | MJPEG is trivial to decode; acceptable for a companion app |
| Legal/ToS | Low | Interoperating with a device you own via its own network API is standard; not distributing Ricoh code |

**No blockers identified.** The hardest engineering piece is the Android Wi-Fi AP handoff
UX, which is a solved problem via `WifiNetworkSpecifier`.

---

## 6. Recommended build order (proof of concept first)

1. **BLE discovery + connect** — scan, bond, read Camera Information (model/firmware). ✅ proves link.
2. **BLE remote shutter** — write `[1,1]` to Operation Request. ✅ proves control, high demo value.
3. **BLE → wake Wi-Fi** — read SSID/passphrase via WLAN Control, enable AP.
4. **Wi-Fi join + `/v1/ping` + `/v1/props`** — confirm HTTP plane.
5. **Live view** — render `/v1/liveview` MJPEG.
6. **Settings control** — `PUT /v1/params/camera` (ISO/shutter/effect).
7. **Photo browse + download** — `/v1/photos`, thumbnails, JPEG/RAW pull.

Steps 1–2 alone (a day or two of work) will empirically confirm feasibility on real hardware.

---

## 7. References (URLs)

- Wi-Fi protocol spec: https://github.com/CursedHardware/ricoh-wireless-protocol
- BLE GATT spec: https://github.com/dm-zharov/ricoh-gr-bluetooth-api
- GRsync (Wi-Fi photo sync): https://github.com/clyang/GRsync
- GR IIIx 802.11 RE writeup: https://notes.secretsauce.net/notes/2022/06/16_ricoh-gr-iiix-80211-reverse-engineering.html
- GRIII firmware hacking: https://hackaday.io/project/191721-ricoh-griiix-firmware-exploration-and-hacking
- Ricoh Camera SDK (official, Pentax-oriented): https://api.ricoh/products/camera-sdk/
- Ricoh Image Sync app: https://www.ricoh-imaging.co.jp/english/products/app/image-sync2/
