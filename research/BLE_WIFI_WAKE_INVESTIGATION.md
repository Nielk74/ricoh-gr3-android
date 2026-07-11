# BLE → Wi-Fi Wake Investigation (GR III)

**Status:** Unresolved — the documented BLE "wake Wi-Fi AP" write is rejected by the camera
firmware. We ship a manual-activation fallback instead (see *Decision* below).

**Date:** 2026-07-11 · **Firmware tested:** GR III 1.92 **and** 2.10 (latest) · same result on both.

---

## 1. Symptom

The app hung indefinitely on the connect flow's step 2, **"Waking camera Wi-Fi…"**. BLE pairing,
device-info reads, and the BLE shutter all worked; only the Wi-Fi handoff stalled with no error.

## 2. What the app does (the intended flow)

`MainViewModel.startWifiHandoff()` → over BLE:
1. read the WLAN Control **SSID** + **Passphrase** characteristics, then
2. write **Network Type = 1 (AP mode)** to `9111CDD0-9F01-45C4-A2D4-E09E8FB0424D` (WLAN Control
   service `F37F568F-…`) to wake the camera's Wi-Fi AP,
3. join that AP with `WifiNetworkSpecifier` and talk HTTP to `http://192.168.0.1/v1/`.

Per the community spec (`research/references/ricoh-gr-bluetooth-api`) and the decrypted protocol
(`research/references/ricoh-wireless-protocol/.../def_ble.decrypted.yaml`), Network Type is a
single `sint8`: `0 = OFF`, `1 = AP mode`. Our payload/UUIDs matched the spec exactly.

## 3. How we investigated (direct hardware RE, no phone)

The bug lives in the phone↔camera BLE exchange, so an emulator can't reproduce it. Instead we
drove the **real camera directly from the dev Linux box** (Intel BLE 5.2 adapter `hci0`) using
Python **`bleak`**. Scripts live in the session scratchpad; the reusable ones are summarized here.

1. **Scan** (`scan.py`) — found the camera advertising as `GR_1DBF6D` @ `44:91:60:1D:BF:6D`
   (only advertises when the phone isn't holding the BLE link — turn phone Bluetooth off first).
2. **Bonding is mandatory.** On an *unbonded* link the camera rejects nearly every GATT op with
   `UNLIKELY_ERROR (0x0E)` and disconnects. Pairing uses **`RequestPasskey`**: the **camera
   displays a 6-digit code** that the central must *enter* (camera must be in pairing mode via its
   menu). We bonded via a Python BlueZ agent that fed in the code (`agent_pair_wake.py`).
3. Once **bonded**, reads work cleanly: `Model = "RICOH GR III"`, `SSID = "GR_1D75AD"`,
   `Passphrase = "10020917"`, `NetworkType = 0`.
4. **The blocker:** writing **Network Type = 1** is **rejected with GATT application error
   `0x80`** ("Application-specific Error"). We reproduced this under *every* condition:
   - after **Camera Power = On** (`B58CE84C-…` = 1)
   - after **Operation Mode = Capture(0)** and **Playback(1)** (both confirmed via read-back;
     modes 2/4 are themselves write-rejected; `OperationModeList` reports only 1 permitted mode)
   - after enabling the camera's **Wireless LAN** menu setting — on the camera body this same
     rejection surfaces as the on-screen message **"Can't turn it on in this mode"**, i.e. `0x80`
     is literally that guard
   - with the Network Type **notify CCCD subscribed** before the write
   - `response=True` and `response=False`; and a 2-byte payload → `INVALID_ATTRIBUTE_VALUE_LENGTH`
     (confirms strict 1-byte).
   - **Contrast:** **SSID / Passphrase** writes are *accepted* (they're settings storage);
     **Network Type** and **Channel** — the radio-activating writes — both reject `0x80`.
5. **Camera does not auto-start Wi-Fi** from a held/subscribed BLE session either (subscribed to
   Camera Service Notification `FAA0AEAF-…`, File Transfer List, Network Type; held 45s — camera
   pushed only repeated `NetworkType=00`, never woke Wi-Fi).
6. **Firmware 2.10** (latest) — identical GATT table, identical `0x80` rejection. Not a fw bug that
   a later release fixes; the write is simply **not the mechanism** on shipping firmware.

## 4. Conclusion

Ricoh's official docs describe the real flow as **camera-driven**: *"After Bluetooth connection is
completed, the connection automatically switches to Wireless LAN after several seconds."* The
camera's `bled` daemon guards the Network Type write behind a runtime condition **not present in
the community spec** — most likely an authenticated app-session handshake that the official
Image Sync / GR World app performs. From first principles alone (every documented precondition
ruled out) we cannot see that guard's trigger.

**To resolve properly** we'd need to observe the official app's BLE writes (Android **Bluetooth
HCI snoop log** → decode with `research`-side `decode_btsnoop.py`) or statically reverse the app
APK (jadx). Deferred by choice — see below.

## 5. Decision (current)

Ship a **functional app without automatic BLE Wi-Fi wake**: the connect flow asks the user to
**turn Wi-Fi on from the camera** (Playback → transfer / the camera's wireless button), then the
app auto-joins the `GR_…` AP via `WifiNetworkSpecifier` and does everything else (live view,
gallery, transfer, settings) over HTTP `/v1/`. BLE remains for discovery, device info, and the
remote shutter. The credential **reads** over BLE still work, so the app can still auto-fill the
SSID/passphrase for the join — only the *wake write* is dropped.

## 6. If we revisit BLE wake

- Capture `btsnoop_hci.log` from a successful official-app Wi-Fi connect; decode the ATT writes
  around the moment Wi-Fi comes up. That is the definitive answer.
- Or decompile GR World / Image Sync (jadx) and read the BLE wake code path + the guard it satisfies.
- Re-test against these known-good facts (bond required; `0x80` = "not permitted in current state").
