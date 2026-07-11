# BLE reverse-engineering tools (Ricoh GR III)

Direct-hardware BLE reverse-engineering tools for the Ricoh GR III / GR IIIx.
These talk to the camera's GATT profile from a Linux host (via BlueZ/`bleak`) to
discover it, bond with it, read its WLAN credentials, and wake its Wi-Fi AP — and
to decode captured Bluetooth HCI logs of the official app's traffic. They informed
the findings written up in [../BLE_WIFI_WAKE_INVESTIGATION.md](../BLE_WIFI_WAKE_INVESTIGATION.md).

## Scripts

- **`scan.py`** — BLE scan that flags the Ricoh camera by name and by advertised
  service UUID.

  ```bash
  venv/bin/python scan.py
  ```

- **`agent_pair_wake.py`** — registers a BlueZ pairing agent, bonds the camera,
  reads WLAN creds (SSID/passphrase), then writes Network Type = AP to wake Wi-Fi.
  The camera **displays** a 6-digit passkey and the central must **enter** it: drop
  that code into a `passkey.txt` file next to the script (the agent polls for it).

  ```bash
  # terminal 1
  venv/bin/python agent_pair_wake.py
  # terminal 2, once the camera shows the code
  echo 123456 > passkey.txt
  ```

- **`decode_btsnoop.py`** — pure-Python `btsnoop_hci.log` decoder that dumps ATT
  writes/reads/notifications, for analyzing a capture of the official app's BLE.

  ```bash
  venv/bin/python decode_btsnoop.py btsnoop_hci.log
  ```

## Requirements

- A Linux host with a BlueZ Bluetooth adapter.
- Python `bleak` (and `dbus-fast` for the pairing agent):

  ```bash
  python3 -m venv venv
  venv/bin/pip install bleak dbus-fast
  ```

## Notes from our tests

- The camera's BLE address was `44:91:60:1D:BF:6D`, advertising as `GR_1DBF6D`.
- Pairing requires the camera to be in pairing mode and entering the 6-digit code
  it displays on its screen (see `agent_pair_wake.py` above).
- For reliable scanning, keep the phone's Bluetooth OFF so it doesn't grab the
  camera first.

See [../BLE_WIFI_WAKE_INVESTIGATION.md](../BLE_WIFI_WAKE_INVESTIGATION.md) for the
full findings.
