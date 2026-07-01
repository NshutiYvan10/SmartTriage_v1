#!/usr/bin/env python3
"""
paramedic_monitor_simulator.py — simulates a paramedic's self-registered field
monitor (DeviceType.PARAMEDIC_MONITOR, V98).

Unlike monitor_simulator.py (which streams visit-bound vitals to /ingest for the
ED bedside pipeline), this posts a DEVICE-KEYED latest-vitals SNAPSHOT to
    POST {server}/api/v1/iot/stream/device-telemetry
authenticated with the device's X-Device-API-Key. That snapshot is what a
paramedic pulls into the EMS field-vitals via "Pull from my monitor".

Flow to test end-to-end:
  1. As a PARAMEDIC in the app, register your monitor
     (Dashboard → My Field Monitor → Register) and copy the pairing API key.
  2. Run this with that key:  python paramedic_monitor_simulator.py --api-key <KEY>
  3. In an EMS run's Step 2 vitals, tap "Pull from my monitor" — the values
     posted here appear (editable).

Scenarios: normal | tachy | hypoxic | shock  (shapes the posted numbers).
--once posts a single snapshot; otherwise posts every --interval seconds.
Catches connection errors and prints an OFFLINE line (as the device screen would).
"""
import argparse
import random
import sys
import time

try:
    import requests
except ImportError:
    print("This simulator needs 'requests' — pip install -r requirements.txt")
    sys.exit(1)


def _vary(base: int, spread: int) -> int:
    return max(0, base + random.randint(-spread, spread))


def snapshot(scenario: str) -> dict:
    """Build a device-telemetry snapshot for the chosen scenario."""
    if scenario == "tachy":
        return {"heartRate": _vary(135, 8), "respiratoryRate": _vary(24, 3),
                "spo2": _vary(95, 2), "systolicBp": _vary(105, 8),
                "diastolicBp": _vary(70, 6), "temperature": round(random.uniform(37.0, 38.2), 1)}
    if scenario == "hypoxic":
        return {"heartRate": _vary(110, 8), "respiratoryRate": _vary(28, 4),
                "spo2": _vary(84, 3), "systolicBp": _vary(120, 10),
                "diastolicBp": _vary(78, 6), "temperature": round(random.uniform(36.5, 37.4), 1)}
    if scenario == "shock":
        return {"heartRate": _vary(128, 10), "respiratoryRate": _vary(26, 3),
                "spo2": _vary(90, 3), "systolicBp": _vary(82, 6),
                "diastolicBp": _vary(52, 5), "temperature": round(random.uniform(35.6, 36.4), 1),
                "glucose": round(random.uniform(4.0, 7.0), 1)}
    # normal
    return {"heartRate": _vary(78, 6), "respiratoryRate": _vary(16, 2),
            "spo2": _vary(98, 1), "systolicBp": _vary(122, 8),
            "diastolicBp": _vary(80, 6), "temperature": round(random.uniform(36.4, 37.1), 1),
            "glucose": round(random.uniform(4.5, 6.5), 1)}


def post_snapshot(base_url: str, api_key: str, body: dict) -> bool:
    try:
        resp = requests.post(
            f"{base_url}/api/v1/iot/stream/device-telemetry",
            headers={"X-Device-API-Key": api_key, "Content-Type": "application/json"},
            json=body,
            timeout=5,
        )
        if resp.status_code == 200:
            print(f"  ✓ snapshot sent  {body}")
            return True
        if resp.status_code == 401:
            print("  ✗ 401 — bad/unknown API key (is the device registered + active?)")
        else:
            print(f"  ✗ {resp.status_code} — {resp.text[:120]}")
        return False
    except requests.exceptions.RequestException:
        # The physical monitor would show its own offline indicator here.
        print("  … OFFLINE — no connection to the server; will retry")
        return False


def main():
    p = argparse.ArgumentParser(description="Paramedic field-monitor telemetry simulator (V98)")
    p.add_argument("--server", default="http://localhost:8080", help="API base URL")
    p.add_argument("--api-key", required=True, help="the monitor's pairing API key (from self-register)")
    p.add_argument("--scenario", default="normal", choices=["normal", "tachy", "hypoxic", "shock"])
    p.add_argument("--interval", type=float, default=5.0, help="seconds between snapshots")
    p.add_argument("--once", action="store_true", help="post a single snapshot and exit")
    args = p.parse_args()

    print(f"Paramedic monitor → {args.server}  scenario={args.scenario}")
    if args.once:
        ok = post_snapshot(args.server, args.api_key, snapshot(args.scenario))
        sys.exit(0 if ok else 1)

    print("Posting snapshots (Ctrl-C to stop)…")
    try:
        while True:
            post_snapshot(args.server, args.api_key, snapshot(args.scenario))
            time.sleep(args.interval)
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()
