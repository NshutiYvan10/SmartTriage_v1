#!/usr/bin/env python3
"""
provision.py — register the simulated monitors in SmartTriage and write
the gateway's devices.yaml.

Run ONCE per environment, by an admin:

    python3 provision.py \
        --backend http://192.168.1.50:8080 \
        --email admin@smarttriage.com \
        --hospital-id <hospital-uuid> \
        --out /etc/smarttriage/devices.yaml

Prompts for the admin password (never passed on the command line).
Registers: 2 bedside sims, 1 triage sim, 1 paramedic sim — all named
SIM-* so simulated vitals can never be mistaken for a real patient's.
The generated file contains the device API keys: keep it out of git,
chmod 600, readable only by the gateway's service user.
"""
from __future__ import annotations

import argparse
import getpass
import json
import stat
import sys
import urllib.request

# APPEND-ONLY: serials are derived from this list's index (see main()), so
# inserting or reordering entries would rename devices that are already
# registered in the backend and already listed in a deployed devices.yaml.
SIM_DEVICES = [
    {"role": "bedside",  "name": "SIM-BED-01",    "deviceType": "ESP32_MONITOR"},
    {"role": "bedside",  "name": "SIM-BED-02",    "deviceType": "ESP32_MONITOR"},
    {"role": "triage",   "name": "SIM-TRIAGE-01", "deviceType": "ESP32_MONITOR"},
    # PARAMEDIC_MONITOR is a USER-OWNED device type: canOperateDevice admits the
    # owner, a SUPER_ADMIN, or the hospital's admin — nobody else. Registered
    # through the admin endpoint it has NO owner, so it never appears in a
    # paramedic's /devices/mine and "Pull from my monitor" 403s. Pass
    # --paramedic-email to register it as that crew member instead.
    {"role": "paramedic","name": "SIM-EMS-01",    "deviceType": "PARAMEDIC_MONITOR",
     "ownedByParamedic": True},
    # The General-zone rounds cart. Deliberately an ESP32_MONITOR, not a new
    # device type: the nurse's roaming-monitor picker offers ESP32_MONITORs that
    # are neither the triage monitor nor assigned to a bed, so a bespoke type
    # would be invisible there. "Roaming" is a workflow, not a kind of hardware.
    {"role": "roaming",  "name": "SIM-ROUNDS-01", "deviceType": "ESP32_MONITOR"},
]


def post(url: str, body: dict, token: str | None = None) -> dict:
    req = urllib.request.Request(url, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read())


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--backend", required=True)
    ap.add_argument("--email", required=True)
    ap.add_argument("--hospital-id", required=True)
    ap.add_argument("--out", default="devices.yaml")
    ap.add_argument("--serial-prefix", default="SIM")
    ap.add_argument("--gateway-name", default="SmartTriage Gateway",
                    help="Display name on the kiosk lock screen and top bar")
    ap.add_argument("--pin", action="store_true",
                    help="Prompt for a kiosk unlock PIN (stored as a salted hash)")
    ap.add_argument("--paramedic-email",
                    help="Crew member who OWNS the simulated field monitor. Without it "
                         "the paramedic sim has no owner and no paramedic can pull from "
                         "it (see SIM_DEVICES).")
    ap.add_argument("--only",
                    help="Comma-separated roles to register (e.g. 'roaming'). Adds to an "
                         "EXISTING deployment: skips the gateway, leaves --out untouched, "
                         "and prints the devices.yaml block to append. Without it the whole "
                         "fleet is registered and --out is rewritten.")
    args = ap.parse_args()
    base = args.backend.rstrip("/")
    only = {r.strip().lower() for r in args.only.split(",")} if args.only else None
    if only:
        unknown = only - {d["role"] for d in SIM_DEVICES}
        if unknown:
            print(f"unknown role(s): {', '.join(sorted(unknown))}", file=sys.stderr)
            return 1

    password = getpass.getpass(f"Password for {args.email}: ")
    login = post(f"{base}/api/v1/auth/login", {"email": args.email, "password": password})
    token = login["data"]["accessToken"]
    print("authenticated.")

    # A crew member's own login, only if a paramedic monitor is in scope. Its key
    # must come back from the PARAMEDIC self-register endpoint or the device ends
    # up ownerless and unusable by the crew.
    medic_token = ""
    wants_medic = any(d.get("ownedByParamedic") and (only is None or d["role"] in only)
                      for d in SIM_DEVICES)
    if wants_medic and args.paramedic_email:
        medic_pw = getpass.getpass(f"Password for {args.paramedic_email} (paramedic): ")
        medic_login = post(f"{base}/api/v1/auth/login",
                           {"email": args.paramedic_email, "password": medic_pw})
        medic_token = medic_login["data"]["accessToken"]
        print(f"authenticated as paramedic {args.paramedic_email}.")

    # The gateway itself is a first-class, hospital-owned device (V114):
    # registered by the admin like any monitor, with its OWN API key. That
    # key is the only credential the gateway uses against the backend
    # (device registry reads + its own heartbeat) — revoke the device,
    # revoke the gateway.
    gw_serial = f"{args.serial_prefix}-GATEWAY-01"
    gw_key = ""
    if only:
        print("incremental mode — leaving the gateway identity and", args.out, "untouched.")
    else:
      gw = post(f"{base}/api/v1/iot/devices", {
        "serialNumber": gw_serial,
        "deviceName": args.gateway_name,
        "deviceType": "GATEWAY",
        "hospitalId": args.hospital_id,
        "firmwareVersion": "gw-2.0",
        "notes": "Ward gateway appliance (Raspberry Pi) — fronts the bedside monitors",
      }, token)
      gw_key = gw["data"]["apiKey"]
      print(f"registered GATEWAY '{args.gateway_name}'  serial={gw_serial}")

    lines = [
        "# SmartTriage gateway device registry — CONTAINS API KEYS.",
        "# Keep OUT of git. chmod 600. Owned by the gateway service user.",
        f"backend_url: {base}",
        "listen_port: 8090",
        "tx_interval_seconds: 5",
        f"gateway_name: \"{args.gateway_name}\"",
        f"gateway_serial: {gw_serial}",
        f"gateway_api_key: {gw_key}",
    ]
    if args.pin:
        import hashlib, secrets
        pin = getpass.getpass("Kiosk unlock PIN (digits, e.g. 4-6): ").strip()
        pin2 = getpass.getpass("Repeat PIN: ").strip()
        if not pin or pin != pin2:
            print("PINs empty or mismatched — aborting.", file=sys.stderr)
            return 1
        salt = secrets.token_hex(16)
        digest = hashlib.sha256((salt + pin).encode()).hexdigest()
        lines += [f"kiosk_pin_salt: {salt}", f"kiosk_pin_sha256: {digest}"]
    lines += ["devices:"]
    device_lines: list[str] = []
    # enumerate over the WHOLE list even when filtering, so serials stay stable
    for i, spec in enumerate(SIM_DEVICES, start=1):
        if only is not None and spec["role"] not in only:
            continue
        serial = f"{args.serial_prefix}-{spec['role'].upper()}-{i:02d}"
        notes = "SIMULATED device (demo gateway) — not a real patient monitor"
        try:
            if spec.get("ownedByParamedic") and medic_token:
                # PARAMEDIC-gated self-register: sets registered_by_user_id to the
                # crew member and takes the hospital from their account, so the
                # monitor shows up in their "My Monitor" picker.
                resp = post(f"{base}/api/v1/iot/devices/self-register", {
                    "serialNumber": serial,
                    "deviceName": spec["name"],
                    "notes": notes,
                }, medic_token)
                owner = f"  owner={args.paramedic_email}"
            else:
                resp = post(f"{base}/api/v1/iot/devices", {
                    "serialNumber": serial,
                    "deviceName": spec["name"],
                    "deviceType": spec["deviceType"],
                    "hospitalId": args.hospital_id,
                    "firmwareVersion": "sim-1.0",
                    "notes": notes,
                }, token)
                owner = ""
                if spec.get("ownedByParamedic"):
                    owner = "  ⚠ NO OWNER — no paramedic can pull from it"
            key = resp["data"]["apiKey"]
            print(f"registered {spec['name']}  serial={serial}{owner}")
        except Exception as e:
            print(f"FAILED to register {spec['name']}: {e}", file=sys.stderr)
            print("(already registered? deactivate the old device or change --serial-prefix)",
                  file=sys.stderr)
            return 1
        device_lines += [
            f"  - role: {spec['role']}",
            f"    name: {spec['name']}",
            f"    serial: {serial}",
            f"    api_key: {key}",
        ]
        if spec["role"] == "roaming":
            device_lines += [
                "    zone: GENERAL           # worklist zone the cart offers",
                "    bp_after_seconds: 45    # cuff cycle — BP is what ENDS a spot check",
            ]
    lines += device_lines

    if only:
        print("\nAppend this under `devices:` in " + args.out + " (chmod 600), "
              "then restart the gateway:\n")
        print("\n".join(device_lines))
        return 0

    with open(args.out, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    try:
        import os
        os.chmod(args.out, stat.S_IRUSR | stat.S_IWUSR)   # 600
    except OSError:
        pass
    print(f"\nwrote {args.out} (chmod 600). Start the gateway with:")
    print(f"  GATEWAY_CONFIG={args.out} uvicorn gateway.app:app --host 0.0.0.0 --port 8090")
    return 0


if __name__ == "__main__":
    sys.exit(main())
