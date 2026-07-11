#!/usr/bin/env python3
"""Bond (if needed) and read WLAN credentials over BLE — READ ONLY, no wake write.
Prints SSID + passphrase so we can join the camera AP from this host."""
import asyncio, os
from dbus_fast.aio import MessageBus
from dbus_fast.service import ServiceInterface, method
from dbus_fast import BusType
from bleak import BleakClient, BleakScanner

ADDR_HINT = "GR"
SSID       = "90638e5a-e77d-409d-b550-78f7e1ca5ab4"
PASSPHRASE = "0f38279c-fe9e-461b-8596-81287e8c9a81"
NETWORK_TYPE = "9111cdd0-9f01-45c4-a2d4-e09e8fb0424d"
MODEL      = "00002a24-0000-1000-8000-00805f9b34fb"

AGENT_PATH = "/test/agent"

class Agent(ServiceInterface):
    def __init__(self): super().__init__("org.bluez.Agent1")
    @method()
    def Release(self): pass
    @method()
    def RequestPinCode(self, device: 'o') -> 's': return "0000"
    @method()
    async def RequestPasskey(self, device: 'o') -> 'u':
        print("[agent] RequestPasskey — waiting for passkey.txt ...", flush=True)
        pk = os.path.join(os.path.dirname(__file__), "passkey.txt")
        for _ in range(240):
            if os.path.exists(pk):
                t = open(pk).read().strip()
                if t.isdigit():
                    os.remove(pk); print(f"[agent] passkey {int(t):06}", flush=True); return int(t)
            await asyncio.sleep(0.5)
        return 0
    @method()
    def DisplayPasskey(self, device: 'o', passkey: 'u', entered: 'q'): pass
    @method()
    def DisplayPinCode(self, device: 'o', pincode: 's'): pass
    @method()
    def RequestConfirmation(self, device: 'o', passkey: 'u'): return
    @method()
    def RequestAuthorization(self, device: 'o'): return
    @method()
    def AuthorizeService(self, device: 'o', uuid: 's'): return
    @method()
    def Cancel(self): pass

async def main():
    bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
    agent = Agent(); bus.export(AGENT_PATH, agent)
    intro = await bus.introspect("org.bluez", "/org/bluez")
    mgr = bus.get_proxy_object("org.bluez", "/org/bluez", intro).get_interface("org.bluez.AgentManager1")
    await mgr.call_register_agent(AGENT_PATH, "KeyboardDisplay")
    await mgr.call_request_default_agent(AGENT_PATH)

    dev = None
    for a,(dv,adv) in (await BleakScanner.discover(timeout=10, return_adv=True)).items():
        if ADDR_HINT in (dv.name or adv.local_name or "").upper(): dev = dv; break
    if not dev: print("Camera not found."); return
    print(f"Found {dev.address} {dev.name}")

    async with BleakClient(dev, timeout=25) as c:
        print(f"Connected={c.is_connected}")
        try: await c.pair()
        except Exception as e: print(f"pair(): {type(e).__name__}: {e}")
        async def rd(label, uuid, as_int=False):
            try:
                v = await c.read_gatt_char(uuid)
                s = int.from_bytes(v,'little') if as_int else v.decode('utf-8','replace').strip(chr(0)).strip()
                print(f"OK   {label}: {s!r}"); return s
            except Exception as e:
                print(f"FAIL {label}: {type(e).__name__}: {e}")
        await rd("Model", MODEL)
        await rd("SSID", SSID)
        await rd("Passphrase", PASSPHRASE)
        await rd("NetworkType", NETWORK_TYPE, as_int=True)

asyncio.run(main())
