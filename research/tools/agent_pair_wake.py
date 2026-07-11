#!/usr/bin/env python3
"""Register a BlueZ pairing agent that auto-confirms the passkey, bond the GR III,
then read WLAN creds and write Network Type = AP over the bonded link."""
import asyncio, os
from dbus_fast.aio import MessageBus
from dbus_fast.service import ServiceInterface, method
from dbus_fast import BusType, Variant
from bleak import BleakClient, BleakScanner

ADDR_HINT = "GR"
WLAN_SERVICE = "f37f568f-9071-445d-a938-5441f2e82399"
SSID         = "90638e5a-e77d-409d-b550-78f7e1ca5ab4"
PASSPHRASE   = "0f38279c-fe9e-461b-8596-81287e8c9a81"
NETWORK_TYPE = "9111cdd0-9f01-45c4-a2d4-e09e8fb0424d"
MODEL        = "00002a24-0000-1000-8000-00805f9b34fb"

AGENT_PATH = "/test/agent"
CAPABILITY = "KeyboardDisplay"

class Agent(ServiceInterface):
    def __init__(self):
        super().__init__("org.bluez.Agent1")

    @method()
    def Release(self): print("[agent] Release")

    @method()
    def RequestPinCode(self, device: 'o') -> 's':
        print(f"[agent] RequestPinCode {device} -> 0000"); return "0000"

    @method()
    async def RequestPasskey(self, device: 'o') -> 'u':
        print(f"[agent] RequestPasskey {device} — WAITING for camera code in passkey.txt ...",
              flush=True)
        pkfile = os.path.join(os.path.dirname(__file__), "passkey.txt")
        # Poll up to 120s for the user to drop the 6-digit code the camera displays.
        for _ in range(240):
            if os.path.exists(pkfile):
                txt = open(pkfile).read().strip()
                if txt.isdigit():
                    os.remove(pkfile)
                    pk = int(txt)
                    print(f"[agent] using passkey {pk:06}", flush=True)
                    return pk
            await asyncio.sleep(0.5)
        print("[agent] no passkey provided in time -> 0", flush=True)
        return 0

    @method()
    def DisplayPasskey(self, device: 'o', passkey: 'u', entered: 'q'):
        print(f"[agent] DisplayPasskey {device} passkey={passkey:06} entered={entered}")

    @method()
    def DisplayPinCode(self, device: 'o', pincode: 's'):
        print(f"[agent] DisplayPinCode {device} {pincode}")

    @method()
    def RequestConfirmation(self, device: 'o', passkey: 'u'):
        print(f"[agent] RequestConfirmation {device} passkey={passkey:06} -> AUTO-YES")
        return  # returning without error == confirm

    @method()
    def RequestAuthorization(self, device: 'o'):
        print(f"[agent] RequestAuthorization {device} -> AUTO-YES"); return

    @method()
    def AuthorizeService(self, device: 'o', uuid: 's'):
        print(f"[agent] AuthorizeService {device} {uuid} -> AUTO-YES"); return

    @method()
    def Cancel(self): print("[agent] Cancel")

async def register_agent(bus):
    agent = Agent()
    bus.export(AGENT_PATH, agent)
    intro = await bus.introspect("org.bluez", "/org/bluez")
    obj = bus.get_proxy_object("org.bluez", "/org/bluez", intro)
    mgr = obj.get_interface("org.bluez.AgentManager1")
    await mgr.call_register_agent(AGENT_PATH, CAPABILITY)
    await mgr.call_request_default_agent(AGENT_PATH)
    print("[agent] registered + default")

async def main():
    bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
    await register_agent(bus)

    print("Scanning for camera (must be in pairing mode)...")
    dev = None
    d = await BleakScanner.discover(timeout=10, return_adv=True)
    for a,(dv,adv) in d.items():
        if ADDR_HINT in (dv.name or adv.local_name or "").upper():
            dev = dv; break
    if not dev:
        print("Camera not found."); return
    print(f"Found {dev.address} {dev.name}\n")

    async with BleakClient(dev, timeout=25) as c:
        print(f"Connected = {c.is_connected}")
        try:
            print(f"pair() -> {await c.pair()}")
        except Exception as e:
            print(f"pair() FAILED {type(e).__name__}: {e}")
        print()

        async def rd(label, uuid, as_int=False):
            try:
                v = await c.read_gatt_char(uuid)
                s = int.from_bytes(v,'little') if as_int else v.decode('utf-8','replace').strip(chr(0)).strip()
                print(f"OK   {label}: {s!r}"); return v
            except Exception as e:
                print(f"FAIL {label}: {type(e).__name__}: {e}")

        await rd("Model", MODEL)
        ssid = await rd("SSID", SSID)
        await rd("Passphrase", PASSPHRASE)
        await rd("NetworkType(before)", NETWORK_TYPE, as_int=True)

        print("\n=== Writing Network Type = 1 (AP mode) ===")
        try:
            await c.write_gatt_char(NETWORK_TYPE, bytes([1]), response=True)
            print("write OK — camera acknowledged")
        except Exception as e:
            print(f"write FAILED {type(e).__name__}: {e}")
        await asyncio.sleep(3)
        await rd("NetworkType(after)", NETWORK_TYPE, as_int=True)
        print("\n>>> Look at the camera: did Wi-Fi turn ON? <<<")

asyncio.run(main())
