#!/usr/bin/env python3
"""
SmartTriage — RFID Reader Simulator (V95)

Simulates the physical ESP32 + RFID reader at a registration desk WITHOUT hardware. It makes
EXACTLY the call the firmware makes: POST /api/v1/iot/rfid/tap with the device's pre-shared
`X-Device-API-Key` header and a {cardId, tappedAt} body, then renders the server's response the way
the device's screen + buzzer would:

  • FOUND          → patient name on screen, positive (rising) tone.
  • NOT_FOUND      → "register manually" on screen, distinct (low) tone.
  • CARD_CAPTURED  → "card captured" on screen (the reader was armed in registration bind mode).
  • offline/error  → the device renders its own offline state when it can't reach the server.

Register an RFID_READER device first (IoT Devices page or POST /api/v1/iot/devices with
deviceType=RFID_READER) to obtain its API key.

Examples:
  # Identify a returning patient's card
  python rfid_simulator.py --server localhost:8080 --api-key <KEY> --card 04A2B9C1

  # Loop, tapping the same card every few seconds (demo)
  python rfid_simulator.py --server localhost:8080 --api-key <KEY> --card 04A2B9C1 --repeat 3 --interval 4

  # Demonstrate the offline state (point at a dead port)
  python rfid_simulator.py --server localhost:9999 --api-key <KEY> --card 04A2B9C1
"""
import argparse
import sys
import time

import requests

# Buzzer/screen cues per result — what the ESP32 would do.
CUES = {
    "FOUND":         ("🔊 rising tone",  "✅ FOUND"),
    "NOT_FOUND":     ("🔉 low tone",     "❌ NOT FOUND — register manually"),
    "CARD_CAPTURED": ("🔊 short beep",   "🆕 CARD CAPTURED (bind mode)"),
}


def tap(server: str, api_key: str, card_id: str) -> None:
    url = f"http://{server}/api/v1/iot/rfid/tap"
    try:
        resp = requests.post(
            url,
            headers={"X-Device-API-Key": api_key, "Content-Type": "application/json"},
            json={"cardId": card_id, "tappedAt": time.strftime("%Y-%m-%dT%H:%M:%S")},
            timeout=5,
        )
    except requests.exceptions.RequestException as e:
        # The real device shows its own offline screen + error tone here.
        print(f"  📵 OFFLINE — server unreachable ({e.__class__.__name__}). "
              f"Device shows 'No connection', retries locally.")
        return

    if resp.status_code == 401:
        print("  🔒 401 — device authentication failed (bad/disabled API key).")
        return
    if resp.status_code >= 400:
        print(f"  ⚠️  HTTP {resp.status_code}: {resp.text[:200]}")
        return

    body = resp.json() if resp.content else {}
    result = body.get("result", "NOT_FOUND")
    tone, screen = CUES.get(result, ("🔉 low tone", result))
    line = f"  {tone:18} {screen}"
    if result == "FOUND":
        name = body.get("patientName") or "(unknown)"
        dob = body.get("dateOfBirth") or "?"
        sex = body.get("gender") or "?"
        line += f"  →  {name}  (DOB {dob}, {sex})"
    print(line)


def main():
    parser = argparse.ArgumentParser(
        description="SmartTriage RFID reader simulator",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--server", required=True,
                        help="SmartTriage server address (e.g. localhost:8080)")
    parser.add_argument("--api-key", required=True,
                        help="RFID reader device API key (from registration)")
    parser.add_argument("--card", required=True,
                        help="Card UID to tap (the factory UID printed on the card)")
    parser.add_argument("--repeat", type=int, default=1,
                        help="How many taps to send (default: 1)")
    parser.add_argument("--interval", type=float, default=3.0,
                        help="Seconds between taps when --repeat > 1 (default: 3.0)")

    args = parser.parse_args()

    print()
    print("╔══════════════════════════════════════════════════════════════╗")
    print("║     SmartTriage — RFID Reader Simulator (Python)             ║")
    print("╚══════════════════════════════════════════════════════════════╝")
    print(f"  Server: {args.server}   Card: {args.card}")
    print()

    for i in range(max(1, args.repeat)):
        if args.repeat > 1:
            print(f"  Tap {i + 1}/{args.repeat}:")
        tap(args.server, args.api_key, args.card)
        if i < args.repeat - 1:
            time.sleep(args.interval)

    print()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
