#!/usr/bin/env python3
"""
SmartTriage — External Vital-Sign Monitor Simulator
====================================================

Simulates one or more ESP32 multi-parameter monitors posting vitals to the
SmartTriage backend, exactly the way real hardware would.

Run on ANY computer on the same network as the SmartTriage server.
Each instance (or each --device entry) acts as a separate bedside monitor.

USAGE
-----
  # Single device (interactive — prompts for API key):
  python monitor_simulator.py --server 192.168.1.50:8080

  # Single device with API key on the command line:
  python monitor_simulator.py --server 192.168.1.50:8080 \
      --serial SIM-ESP32-002 --api-key st_dev_abc123...

  # Multiple devices from a config file:
  python monitor_simulator.py --server 192.168.1.50:8080 --config devices.json

  # Deteriorating patient scenario (vitals gradually worsen):
  python monitor_simulator.py --server 192.168.1.50:8080 \
      --serial SIM-ESP32-002 --api-key st_dev_abc123... --scenario deteriorating

SETUP
-----
  1. Register the device on the SmartTriage frontend (IoT Devices page)
     → copy the serial number and API key
  2. Power On the device via the admin UI
  3. Assign the device to a patient (nurse action)
  4. Run this script with the serial + API key → vitals stream in real-time

CONFIG FILE FORMAT  (devices.json)
-----------------------------------
  [
    { "serial": "MON-BED-01", "apiKey": "st_dev_...", "scenario": "normal" },
    { "serial": "MON-BED-02", "apiKey": "st_dev_...", "scenario": "deteriorating" },
    { "serial": "MON-BED-03", "apiKey": "st_dev_...", "scenario": "critical" }
  ]

SCENARIOS
---------
  normal        — stable adult, vitals in normal range
  tachycardic   — elevated heart rate (100-130 bpm), otherwise normal
  hypotensive   — low blood pressure (SBP 80-95), compensatory tachycardia
  hypoxic       — dropping SpO2 (88-93%), tachypnoea
  febrile       — fever (38.5-39.5°C), compensatory tachycardia
  deteriorating — starts normal, slowly worsens over 5 minutes
  critical      — multiple vitals in danger zone simultaneously
"""

import argparse
import json
import math
import os
import random
import sys
import threading
import time
from datetime import datetime, timezone

try:
    import requests
except ImportError:
    print("Missing 'requests' library. Install it with:")
    print("  pip install requests")
    sys.exit(1)


# ═══════════════════════════════════════════════════════════════════════
# VITAL GENERATION SCENARIOS
# ═══════════════════════════════════════════════════════════════════════

def normal_vitals(seq: int) -> dict:
    """Stable adult — all vitals within normal SATS range."""
    return {
        "heartRate": _vary(78, 5),            # 73–83 bpm
        "spo2": _clamp(_vary(97, 2), 92, 100),  # 95–99 %
        "respiratoryRate": _vary(16, 2),      # 14–18 /min
        "temperature": _vary_f(36.8, 0.3),    # 36.5–37.1 °C
        "systolicBp": _vary(120, 8),          # 112–128 mmHg
        "diastolicBp": _vary(75, 5),          # 70–80 mmHg
        "bloodGlucose": _vary_f(5.4, 0.4),   # 5.0–5.8 mmol/L
        "ecgRhythm": "NSR",
        "ecgQrsDuration": _vary(90, 8),       # 82–98 ms (normal)
        "ecgWaveform": _generate_ecg_waveform(78, "NSR"),
        "ecgStDeviation": round(random.uniform(-0.05, 0.1), 2),  # near-zero normal
    }


def tachycardic_vitals(seq: int) -> dict:
    """Elevated heart rate, otherwise normal."""
    return {
        "heartRate": _vary(115, 15),           # 100–130 bpm
        "spo2": _clamp(_vary(96, 2), 92, 100),
        "respiratoryRate": _vary(18, 2),       # 16–20
        "temperature": _vary_f(37.0, 0.3),
        "systolicBp": _vary(118, 8),
        "diastolicBp": _vary(73, 5),
        "bloodGlucose": _vary_f(5.4, 0.4),
        "ecgRhythm": "SINUS_TACHYCARDIA",
        "ecgQrsDuration": _vary(88, 8),
        "ecgWaveform": _generate_ecg_waveform(115, "SINUS_TACHYCARDIA"),
        "ecgStDeviation": round(random.uniform(-0.1, 0.15), 2),
    }


def hypotensive_vitals(seq: int) -> dict:
    """Low BP with compensatory tachycardia."""
    return {
        "heartRate": _vary(105, 10),           # 95–115 bpm
        "spo2": _clamp(_vary(96, 2), 92, 100),
        "respiratoryRate": _vary(19, 2),       # 17–21
        "temperature": _vary_f(36.7, 0.3),
        "systolicBp": _vary(87, 7),            # 80–94 mmHg (low!)
        "diastolicBp": _vary(55, 5),           # 50–60 mmHg
        "bloodGlucose": _vary_f(5.4, 0.4),
        "ecgRhythm": "SINUS_TACHYCARDIA",
        "ecgQrsDuration": _vary(92, 10),
        "ecgWaveform": _generate_ecg_waveform(105, "SINUS_TACHYCARDIA"),
        "ecgStDeviation": round(random.uniform(-0.2, 0.3), 2),  # mild ST changes
    }


def hypoxic_vitals(seq: int) -> dict:
    """Dropping SpO2 with tachypnoea."""
    return {
        "heartRate": _vary(100, 8),            # 92–108 bpm
        "spo2": _clamp(_vary(90, 2), 85, 94),  # 88–92 % (dangerous)
        "respiratoryRate": _vary(26, 3),       # 23–29 /min (tachypnoea)
        "temperature": _vary_f(36.9, 0.3),
        "systolicBp": _vary(115, 8),
        "diastolicBp": _vary(72, 5),
        "bloodGlucose": _vary_f(5.4, 0.4),
        "ecgRhythm": "NSR",
        "ecgQrsDuration": _vary(90, 8),
        "ecgWaveform": _generate_ecg_waveform(100, "NSR"),
        "ecgStDeviation": round(random.uniform(0.0, 0.2), 2),
    }


def febrile_vitals(seq: int) -> dict:
    """Fever with compensatory tachycardia."""
    return {
        "heartRate": _vary(105, 8),            # 97–113 bpm
        "spo2": _clamp(_vary(96, 2), 92, 100),
        "respiratoryRate": _vary(20, 2),       # 18–22
        "temperature": _vary_f(39.0, 0.5),     # 38.5–39.5 °C (fever!)
        "systolicBp": _vary(115, 8),
        "diastolicBp": _vary(70, 5),
        "bloodGlucose": _vary_f(5.8, 0.5),
        "ecgRhythm": "SINUS_TACHYCARDIA",
        "ecgQrsDuration": _vary(88, 8),
        "ecgWaveform": _generate_ecg_waveform(105, "SINUS_TACHYCARDIA"),
        "ecgStDeviation": round(random.uniform(-0.05, 0.15), 2),
    }


def deteriorating_vitals(seq: int) -> dict:
    """Starts normal, gradually deteriorates over ~60 readings (5 min)."""
    progress = min(seq / 60.0, 1.0)  # 0.0 → 1.0 over 60 ticks

    hr = int(78 + progress * 42)      # 78 → 120
    spo2 = int(97 - progress * 10)    # 97 → 87
    rr = int(16 + progress * 14)      # 16 → 30
    temp = round(36.8 + progress * 2.2, 1)  # 36.8 → 39.0
    sbp = int(120 - progress * 35)    # 120 → 85
    dbp = int(75 - progress * 20)     # 75 → 55
    qrs = int(90 + progress * 30)     # 90 → 120ms (widening)
    rhythm = "NSR" if progress < 0.5 else "SINUS_TACHYCARDIA" if progress < 0.8 else "ATRIAL_FIBRILLATION"

    return {
        "heartRate": _vary(hr, 3),
        "spo2": _clamp(_vary(spo2, 1), 80, 100),
        "respiratoryRate": _vary(rr, 2),
        "temperature": _vary_f(temp, 0.2),
        "systolicBp": _vary(sbp, 5),
        "diastolicBp": _vary(dbp, 3),
        "bloodGlucose": _vary_f(5.4 + progress * 2.0, 0.3),
        "ecgRhythm": rhythm,
        "ecgQrsDuration": _vary(qrs, 5),
        "ecgWaveform": _generate_ecg_waveform(hr, rhythm),
        "ecgStDeviation": round(progress * 1.5 + random.uniform(-0.1, 0.1), 2),  # 0→1.5 mV
    }


def critical_vitals(seq: int) -> dict:
    """Multiple vitals in danger zone — should trigger RED alerts."""
    return {
        "heartRate": _vary(135, 10),           # 125–145 bpm
        "spo2": _clamp(_vary(86, 3), 80, 92),  # 83–89 % (critical)
        "respiratoryRate": _vary(32, 3),       # 29–35 /min
        "temperature": _vary_f(39.5, 0.5),     # 39.0–40.0 °C
        "systolicBp": _vary(80, 8),            # 72–88 mmHg
        "diastolicBp": _vary(50, 5),           # 45–55 mmHg
        "bloodGlucose": _vary_f(8.5, 1.0),    # 7.5–9.5 mmol/L
        "ecgRhythm": "ATRIAL_FIBRILLATION",
        "ecgQrsDuration": _vary(130, 15),      # 115–145ms (wide QRS)
        "ecgWaveform": _generate_ecg_waveform(135, "ATRIAL_FIBRILLATION"),
        "ecgStDeviation": round(random.uniform(0.8, 2.0), 2),  # significant ST elevation
    }


SCENARIOS = {
    "normal": normal_vitals,
    "tachycardic": tachycardic_vitals,
    "hypotensive": hypotensive_vitals,
    "hypoxic": hypoxic_vitals,
    "febrile": febrile_vitals,
    "deteriorating": deteriorating_vitals,
    "critical": critical_vitals,
}


# ═══════════════════════════════════════════════════════════════════════
# UTILITY
# ═══════════════════════════════════════════════════════════════════════

def _vary(base: int, spread: int) -> int:
    return base + random.randint(-spread, spread)

def _vary_f(base: float, spread: float) -> float:
    return round(base + random.uniform(-spread, spread), 1)

def _clamp(val: int, lo: int, hi: int) -> int:
    return max(lo, min(hi, val))

def _generate_ecg_waveform(heart_rate: int, rhythm: str) -> str:
    """
    Generate a synthetic Lead-II ECG waveform (one cardiac cycle).
    Returns comma-separated ADC values simulating P-QRS-T morphology.
    """
    # One cycle duration at a sample rate of ~250 Hz
    cycle_ms = 60000.0 / max(heart_rate, 40)
    samples = int(cycle_ms * 250 / 1000)  # number of samples per cycle
    samples = max(samples, 50)  # minimum 50 samples

    waveform = []
    baseline = 512  # 10-bit ADC midpoint

    for i in range(samples):
        t = i / samples  # normalised position 0..1 in the cycle

        val = baseline

        # P wave (atrial depolarisation) ~ 0.08–0.18 of cycle
        if rhythm != "ATRIAL_FIBRILLATION":
            if 0.08 < t < 0.18:
                p_phase = (t - 0.08) / 0.10
                val += int(30 * math.sin(p_phase * math.pi))
        else:
            # Fibrillatory baseline — irregular small oscillations
            val += random.randint(-15, 15)

        # QRS complex (ventricular depolarisation) ~ 0.20–0.32
        if 0.20 < t < 0.22:
            q_phase = (t - 0.20) / 0.02
            val -= int(40 * q_phase)  # Q dip
        elif 0.22 < t < 0.26:
            r_phase = (t - 0.22) / 0.04
            val += int(350 * math.sin(r_phase * math.pi))  # R peak
        elif 0.26 < t < 0.30:
            s_phase = (t - 0.26) / 0.04
            val -= int(80 * math.sin(s_phase * math.pi))  # S dip

        # T wave (ventricular repolarisation) ~ 0.40–0.58
        if 0.40 < t < 0.58:
            t_phase = (t - 0.40) / 0.18
            val += int(60 * math.sin(t_phase * math.pi))

        # Add small noise
        val += random.randint(-5, 5)
        val = max(0, min(1023, val))  # 10-bit ADC range
        waveform.append(str(val))

    return ",".join(waveform)


# ═══════════════════════════════════════════════════════════════════════
# DEVICE SIMULATOR
# ═══════════════════════════════════════════════════════════════════════

class DeviceSimulator:
    """Simulates a single ESP32 monitor posting to the backend."""

    def __init__(self, server: str, serial: str, api_key: str,
                 scenario: str = "normal", interval: float = 5.0):
        # Accept both "localhost:8080" and "http://localhost:8080"
        s = server.rstrip("/")
        if not s.startswith("http://") and not s.startswith("https://"):
            s = f"http://{s}"
        self.base_url = f"{s}/api/v1/iot/stream"
        self.serial = serial
        self.api_key = api_key
        self.interval = interval
        self.seq = 0
        self.running = False

        if scenario not in SCENARIOS:
            print(f"  ⚠  Unknown scenario '{scenario}', falling back to 'normal'")
            scenario = "normal"
        self.scenario_name = scenario
        self.generate = SCENARIOS[scenario]

    def send_heartbeat(self) -> bool:
        """Send a keepalive heartbeat to the backend."""
        try:
            resp = requests.post(
                f"{self.base_url}/heartbeat",
                headers={
                    "X-Device-API-Key": self.api_key,
                    "X-Device-IP": "127.0.0.1",
                },
                timeout=5,
            )
            return resp.status_code == 200
        except requests.RequestException as e:
            print(f"  [{self.serial}] Heartbeat failed: {e}")
            return False

    def send_vitals(self) -> bool:
        """Generate and POST one vital reading."""
        self.seq += 1
        vitals = self.generate(self.seq)

        payload = {
            "serialNumber": self.serial,
            "capturedAt": datetime.now(timezone.utc).isoformat(),
            "sequenceNumber": self.seq,
            **vitals,
            "batteryLevel": _clamp(95 - self.seq // 200, 10, 100),
            "wifiRssi": -40 + random.randint(0, 10),
            "spo2PerfusionIndex": round(1.5 + random.random() * 3.0, 2),
            "firmwareVersion": "1.0.0-python-sim",
        }

        try:
            resp = requests.post(
                f"{self.base_url}/ingest",
                headers={
                    "X-Device-API-Key": self.api_key,
                    "Content-Type": "application/json",
                },
                json=payload,
                timeout=5,
            )
            data = resp.json()

            if resp.status_code == 200 and data.get("accepted"):
                if self.seq % 12 == 1:  # summary every ~60s
                    print(
                        f"  [{self.serial}] ✓ HR:{vitals['heartRate']} "
                        f"RR:{vitals['respiratoryRate']} "
                        f"SpO2:{vitals['spo2']}% "
                        f"T:{vitals['temperature']}°C "
                        f"BP:{vitals['systolicBp']}/{vitals['diastolicBp']} "
                        f"Glu:{vitals['bloodGlucose']} "
                        f"ECG:{vitals.get('ecgRhythm','?')} QRS:{vitals.get('ecgQrsDuration','?')}ms "
                        f"| seq:{self.seq} ({self.scenario_name})"
                    )
                return True
            else:
                reason = data.get("rejectionReason", resp.text[:100])
                print(f"  [{self.serial}] ✗ REJECTED: {reason}")
                return False

        except requests.RequestException as e:
            print(f"  [{self.serial}] ✗ Connection error: {e}")
            return False

    def run(self):
        """Continuously send heartbeats + vitals at the configured interval."""
        self.running = True
        print(f"  [{self.serial}] Starting ({self.scenario_name}) — interval {self.interval}s")

        # Initial heartbeat
        if self.send_heartbeat():
            print(f"  [{self.serial}] Heartbeat OK — device is online")
        else:
            print(f"  [{self.serial}] ⚠  Heartbeat failed — check API key & server")

        while self.running:
            self.send_vitals()
            time.sleep(self.interval)

    def stop(self):
        self.running = False


# ═══════════════════════════════════════════════════════════════════════
# MAIN — CLI ENTRY POINT
# ═══════════════════════════════════════════════════════════════════════

def main():
    parser = argparse.ArgumentParser(
        description="SmartTriage — Multi-Parameter Monitor Simulator",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
EXAMPLES:
  # Single device:
  python monitor_simulator.py --server 192.168.1.50:8080 \\
      --serial MON-BED-01 --api-key st_dev_abc123...

  # Deteriorating patient:
  python monitor_simulator.py --server 192.168.1.50:8080 \\
      --serial MON-BED-02 --api-key st_dev_xyz... --scenario deteriorating

  # Multiple devices from config file:
  python monitor_simulator.py --server 192.168.1.50:8080 --config devices.json

SCENARIOS: normal, tachycardic, hypotensive, hypoxic, febrile, deteriorating, critical
""",
    )
    parser.add_argument(
        "--server", required=True,
        help="SmartTriage server address (e.g. 192.168.1.50:8080 or localhost:8080)",
    )
    parser.add_argument("--serial", help="Device serial number (from IoT Devices page)")
    parser.add_argument("--api-key", help="Device API key (from registration)")
    parser.add_argument(
        "--scenario", default="normal", choices=list(SCENARIOS.keys()),
        help="Vital scenario to simulate (default: normal)",
    )
    parser.add_argument(
        "--interval", type=float, default=5.0,
        help="Seconds between readings (default: 5.0)",
    )
    parser.add_argument(
        "--config",
        help="Path to JSON config file with multiple devices (overrides --serial/--api-key)",
    )

    args = parser.parse_args()

    print()
    print("╔══════════════════════════════════════════════════════════════╗")
    print("║     SmartTriage — Monitor Simulator (Python)                ║")
    print("╚══════════════════════════════════════════════════════════════╝")
    print(f"  Server: {args.server}")
    print()

    # ── Build list of devices to simulate ──
    devices = []

    if args.config:
        # Multi-device mode from JSON config
        config_path = os.path.abspath(args.config)
        print(f"  Loading config: {config_path}")
        with open(config_path) as f:
            entries = json.load(f)

        for entry in entries:
            devices.append(DeviceSimulator(
                server=args.server,
                serial=entry["serial"],
                api_key=entry["apiKey"],
                scenario=entry.get("scenario", "normal"),
                interval=entry.get("interval", args.interval),
            ))
        print(f"  Loaded {len(devices)} device(s)")

    elif args.serial and args.api_key:
        # Single device from CLI args
        devices.append(DeviceSimulator(
            server=args.server,
            serial=args.serial,
            api_key=args.api_key,
            scenario=args.scenario,
            interval=args.interval,
        ))

    else:
        # Interactive mode
        print("  No device specified — entering interactive mode.")
        print("  (You can find serial & API key on the IoT Devices page)")
        print()
        serial = input("  Device Serial Number: ").strip()
        api_key = input("  Device API Key: ").strip()
        if not serial or not api_key:
            print("  ✗ Serial and API key are required.")
            sys.exit(1)

        print(f"  Available scenarios: {', '.join(SCENARIOS.keys())}")
        scenario = input(f"  Scenario [{args.scenario}]: ").strip() or args.scenario

        devices.append(DeviceSimulator(
            server=args.server,
            serial=serial,
            api_key=api_key,
            scenario=scenario,
            interval=args.interval,
        ))

    if not devices:
        print("  ✗ No devices configured.")
        sys.exit(1)

    print()
    print(f"  Starting {len(devices)} simulator(s)... Press Ctrl+C to stop.")
    print("  ─────────────────────────────────────────────────────────")
    print()

    # ── Launch each device on its own thread ──
    threads = []
    for dev in devices:
        t = threading.Thread(target=dev.run, daemon=True, name=dev.serial)
        t.start()
        threads.append(t)

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n\n  Stopping all simulators...")
        for dev in devices:
            dev.stop()
        for t in threads:
            t.join(timeout=2)
        print("  Done. Devices may show as OFFLINE after heartbeat timeout (30s).")
        print()


if __name__ == "__main__":
    main()
