#!/usr/bin/env python3
"""Scan for the Ricoh GR III over BLE. Flags by name AND by advertised service UUID."""
import asyncio
from bleak import BleakScanner

WLAN_SERVICE = "f37f568f-9071-445d-a938-5441f2e82399"
SHOOTING_SERVICE = "9f00f387-8345-4bbc-8b92-b87b52e3091a"

def looks_ricoh(name: str) -> bool:
    n = (name or "").upper()
    return any(k in n for k in ("GR", "RICOH", "PENTAX"))

async def main():
    print("Scanning 12s for BLE devices (phone Bluetooth should be OFF)...")
    devices = await BleakScanner.discover(timeout=12.0, return_adv=True)
    print(f"\nFound {len(devices)} devices total:\n")
    hits = []
    for addr, (dev, adv) in devices.items():
        name = dev.name or adv.local_name or ""
        svcs = [s.lower() for s in (adv.service_uuids or [])]
        by_svc = WLAN_SERVICE in svcs or SHOOTING_SERVICE in svcs
        by_name = looks_ricoh(name)
        mark = "RICOH? " if (by_svc or by_name) else "       "
        if by_svc or by_name:
            hits.append((addr, name, adv.rssi))
        print(f"{mark}{addr}  rssi={adv.rssi:>4}  name={name!r}"
              + (f"  svcs={svcs}" if svcs else ""))
    print()
    if hits:
        print("Candidate cameras (strongest first):")
        for addr, name, rssi in sorted(hits, key=lambda x: -x[2]):
            print(f"  {addr}  rssi={rssi}  {name!r}")
    else:
        print("No Ricoh camera found. Make sure phone Bluetooth is OFF and the camera")
        print("Bluetooth is enabled (Set 'enable anytime' on the camera).")

asyncio.run(main())
