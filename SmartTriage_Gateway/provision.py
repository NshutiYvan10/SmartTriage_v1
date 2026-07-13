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

SIM_DEVICES = [
    {"role": "bedside",  "name": "SIM-BED-01",    "deviceType": "ESP32_MONITOR"},
    {"role": "bedside",  "name": "SIM-BED-02",    "deviceType": "ESP32_MONITOR"},
    {"role": "triage",   "name": "SIM-TRIAGE-01", "deviceType": "ESP32_MONITOR"},
    {"role": "paramedic","name": "SIM-EMS-01",    "deviceType": "PARAMEDIC_MONITOR"},
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
    args = ap.parse_args()
    base = args.backend.rstrip("/")

    password = getpass.getpass(f"Password for {args.email}: ")
    login = post(f"{base}/api/v1/auth/login", {"email": args.email, "password": password})
    token = login["data"]["accessToken"]
    print("authenticated.")

    lines = [
        "# SmartTriage gateway device registry — CONTAINS API KEYS.",
        "# Keep OUT of git. chmod 600. Owned by the gateway service user.",
        f"backend_url: {base}",
        "listen_port: 8090",
        "tx_interval_seconds: 5",
        "devices:",
    ]
    for i, spec in enumerate(SIM_DEVICES, start=1):
        serial = f"{args.serial_prefix}-{spec['role'].upper()}-{i:02d}"
        try:
            resp = post(f"{base}/api/v1/iot/devices", {
                "serialNumber": serial,
                "deviceName": spec["name"],
                "deviceType": spec["deviceType"],
                "hospitalId": args.hospital_id,
                "firmwareVersion": "sim-1.0",
                "notes": "SIMULATED device (demo gateway) — not a real patient monitor",
            }, token)
            key = resp["data"]["apiKey"]
            print(f"registered {spec['name']}  serial={serial}")
        except Exception as e:
            print(f"FAILED to register {spec['name']}: {e}", file=sys.stderr)
            print("(already registered? deactivate the old device or change --serial-prefix)",
                  file=sys.stderr)
            return 1
        lines += [
            f"  - role: {spec['role']}",
            f"    name: {spec['name']}",
            f"    serial: {serial}",
            f"    api_key: {key}",
        ]

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
