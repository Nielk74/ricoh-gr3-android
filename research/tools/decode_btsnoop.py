#!/usr/bin/env python3
"""Pure-Python btsnoop decoder focused on BLE ATT writes/reads/notifications.

Usage: python decode_btsnoop.py <btsnoop_hci.log>

Parses the btsnoop container + HCI ACL + L2CAP(ATT) enough to dump every ATT
Write Request/Command, Read Response, and Handle-Value Notification — which is
exactly what we need to see how the official app wakes the camera Wi-Fi.
"""
import struct, sys

# --- Ricoh characteristic handles are dynamic; we match by looking for the
# --- WLAN Control values. But handles map to UUIDs only if we also see the
# --- GATT discovery. So we print handle + bytes for every ATT op and flag
# --- writes whose payload looks like network-type (single small int) etc.

ATT_OPS = {
    0x01: "Error Rsp", 0x02: "MTU Req", 0x03: "MTU Rsp",
    0x04: "Find Info Req", 0x05: "Find Info Rsp",
    0x08: "Read By Type Req", 0x09: "Read By Type Rsp",
    0x0A: "Read Req", 0x0B: "Read Rsp",
    0x10: "Read By Group Req", 0x11: "Read By Group Rsp",
    0x12: "Write Req", 0x13: "Write Rsp",
    0x52: "Write Cmd",
    0x1B: "Handle Value Notify", 0x1D: "Handle Value Indicate",
    0x16: "Prepare Write Req", 0x18: "Execute Write Req",
}

def read_btsnoop(path):
    with open(path, "rb") as f:
        data = f.read()
    if data[:8] != b"btsnoop\x00":
        raise SystemExit("not a btsnoop file (missing magic)")
    off = 16  # magic(8) + version(4) + datalink(4)
    recs = []
    while off + 24 <= len(data):
        (orig_len, incl_len, flags, drops, ts) = struct.unpack(">IIIIq", data[off:off+24])
        off += 24
        pkt = data[off:off+incl_len]
        off += incl_len
        # flags bit0: 0=sent(host->ctrl), 1=received(ctrl->host)
        direction = "RX" if (flags & 0x01) else "TX"
        recs.append((direction, ts, pkt))
    return recs

def parse_hci_acl(pkt):
    # pkt[0] = HCI packet type; 0x02 = ACL data
    if not pkt or pkt[0] != 0x02:
        return None
    # ACL header: handle+flags (2), total len (2)
    if len(pkt) < 5: return None
    _handle_flags, acl_len = struct.unpack("<HH", pkt[1:5])
    l2cap = pkt[5:5+acl_len]
    if len(l2cap) < 4: return None
    l2_len, cid = struct.unpack("<HH", l2cap[:4])
    if cid != 0x0004:  # ATT fixed channel
        return None
    return l2cap[4:4+l2_len]

def main():
    if len(sys.argv) < 2:
        raise SystemExit("usage: decode_btsnoop.py <btsnoop_hci.log>")
    recs = read_btsnoop(sys.argv[1])
    print(f"{len(recs)} HCI records\n")
    n = 0
    for direction, ts, pkt in recs:
        att = parse_hci_acl(pkt)
        if not att: continue
        op = att[0]
        name = ATT_OPS.get(op, f"op 0x{op:02x}")
        body = att[1:]
        # For write/notify, first 2 bytes = handle, rest = value
        if op in (0x12, 0x52, 0x1B, 0x1D) and len(body) >= 2:
            handle = struct.unpack("<H", body[:2])[0]
            val = body[2:]
            printable = val.decode("latin1") if all(31 < b < 127 for b in val) else ""
            flag = ""
            # heuristic flags of interest
            if len(val) == 1: flag = "  <-- 1-byte (mode/enable/networktype?)"
            print(f"{direction} {name:20} handle=0x{handle:04x} len={len(val):3} "
                  f"val={val.hex()} {printable!r}{flag}")
            n += 1
        elif op in (0x0A, 0x0B):  # read req/rsp
            print(f"{direction} {name:20} {body.hex()}")
            n += 1
    print(f"\n{n} ATT ops of interest")

if __name__ == "__main__":
    main()
