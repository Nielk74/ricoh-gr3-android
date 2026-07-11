#!/usr/bin/env python3
"""Join the camera AP and import ONE photo over HTTP /v1/. Verifies the real Wi-Fi workflow.

Usage: wifi_import.py <SSID> <PASSWORD>
Assumes: camera Wi-Fi is ON (broadcasting) and camera is OFF USB.
"""
import sys, subprocess, time, json, os, urllib.request

BASE = "http://192.168.0.1/v1"
IFACE = "wlp165s0"
OUT_DIR = os.path.dirname(__file__)

def sh(*a, check=True):
    print("  $", " ".join(a), flush=True)
    return subprocess.run(a, capture_output=True, text=True, check=check)

def http_get(path):
    return urllib.request.urlopen(f"{BASE}{path}", timeout=8)

def main():
    if len(sys.argv) < 3:
        print("usage: wifi_import.py <SSID> <PASSWORD>"); sys.exit(2)
    ssid, pw = sys.argv[1], sys.argv[2]

    print(f"[1] Joining AP {ssid!r} on {IFACE} ...")
    r = sh("nmcli", "device", "wifi", "connect", ssid, "password", pw, "ifname", IFACE, check=False)
    print(r.stdout.strip(), r.stderr.strip())
    if "successfully" not in (r.stdout + r.stderr).lower():
        print("  (join may have failed — continuing to probe anyway)")
    time.sleep(3)

    print("[2] Probing camera API ...")
    for attempt in range(5):
        try:
            with http_get("/ping") as resp:
                print("  /ping ->", resp.read().decode()[:200]); break
        except Exception as e:
            print(f"  ping attempt {attempt+1}: {type(e).__name__}: {e}"); time.sleep(2)
    else:
        print("  camera API unreachable — not on the AP or Wi-Fi off"); sys.exit(1)

    print("[3] props ...")
    try:
        with http_get("/props") as resp:
            props = json.load(resp)
            print("  model:", props.get("model"), "| firmware:", props.get("firmwareVersion"))
    except Exception as e:
        print("  props failed:", e)

    print("[4] Listing photos ...")
    with http_get("/photos") as resp:
        photos = json.load(resp)
    dirs = photos.get("dirs", [])
    print("  storages/dirs:", json.dumps(dirs)[:400])
    # Flatten to (folder, file)
    picks = []
    for d in dirs:
        folder = d.get("name")
        for f in d.get("files", []):
            picks.append((folder, f))
    if not picks:
        print("  No photos found on camera."); sys.exit(1)
    print(f"  {len(picks)} photos. Newest:", picks[-1])

    folder, fname = picks[-1]
    print(f"[5] Downloading {folder}/{fname} ...")
    url = f"{BASE}/photos/{folder}/{fname}"
    dest = os.path.join(OUT_DIR, f"imported_{fname}")
    with urllib.request.urlopen(url, timeout=30) as resp, open(dest, "wb") as out:
        data = resp.read(); out.write(data)
    print(f"  SAVED {dest} ({len(data)} bytes)")
    print("\n>>> WORKFLOW VERIFIED: joined AP -> listed -> downloaded one photo. <<<")

if __name__ == "__main__":
    main()
