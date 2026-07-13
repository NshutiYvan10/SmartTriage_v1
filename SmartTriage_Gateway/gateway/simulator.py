"""
Simulator engine — one asyncio task per virtual monitor.

Each tick the current vitals DRIFT toward the active scenario's targets
(first-order lag over scenario.ramp_seconds) with small correlated noise,
so dashboards see believable physiology: a scenario switch is a slide,
not a jump; two consecutive readings are never identical; and HR/RR/BP
stay mutually coherent because the targets themselves are coherent.

Simulated devices are REAL registered devices in SmartTriage with their
own serial + API key — but named SIM-* so simulated vitals can never be
mistaken for a real patient's, even inside the demo database.
"""
from __future__ import annotations

import asyncio
import random
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Callable, Awaitable

from .config import SimDevice
from .scenarios import Scenario, family_for, DEFAULT_SCENARIO


def _iso_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


@dataclass
class SimState:
    device: SimDevice
    scenario_key: str
    running: bool = True
    seq: int = 0
    # live values (start at the scenario's targets)
    hr: float = 0
    spo2: float = 0
    rr: float = 0
    temp: float = 0
    sys: float = 0
    dia: float = 0
    glucose: float | None = None
    last_payload: dict = field(default_factory=dict)
    last_ack: str = "-"        # ok | queued | failed | -

    def scenario(self) -> Scenario:
        return family_for(self.device.role)[self.scenario_key]

    def severity(self) -> int:
        return self.scenario().severity


class SimulatorEngine:
    """Owns every SimState; app.py starts one runner task per device."""

    def __init__(self, interval_seconds: float,
                 send: Callable[[SimState, dict], Awaitable[str]],
                 notify: Callable[[dict], None]):
        self._interval = interval_seconds
        self._send = send          # returns ack status: ok|queued|failed
        self._notify = notify      # push an event to the UI bus
        self.sims: dict[str, SimState] = {}

    def add(self, device: SimDevice) -> SimState:
        fam = family_for(device.role)
        key = device.scenario if device.scenario in fam else DEFAULT_SCENARIO[device.role]
        st = SimState(device=device, scenario_key=key, running=device.enabled)
        sc = st.scenario()
        st.hr, st.spo2, st.rr = sc.hr, sc.spo2, sc.rr
        st.temp, st.sys, st.dia, st.glucose = sc.temp, sc.sys, sc.dia, sc.glucose
        self.sims[device.serial] = st
        return st

    def set_scenario(self, serial: str, key: str) -> bool:
        st = self.sims.get(serial)
        if not st or key not in family_for(st.device.role):
            return False
        st.scenario_key = key
        self._notify({"type": "scenario", "serial": serial, "scenario": key})
        return True

    def toggle(self, serial: str) -> bool:
        st = self.sims.get(serial)
        if not st:
            return False
        st.running = not st.running
        self._notify({"type": "toggle", "serial": serial, "running": st.running})
        return True

    async def run(self, serial: str) -> None:
        st = self.sims[serial]
        # de-synchronise the fleet so posts don't burst together
        await asyncio.sleep(random.uniform(0, self._interval))
        while True:
            t0 = time.monotonic()
            if st.running:
                self._tick(st)
                payload = self._payload(st)
                st.last_payload = payload
                st.last_ack = await self._send(st, payload)
            elapsed = time.monotonic() - t0
            await asyncio.sleep(max(0.5, self._interval - elapsed))

    # ---------------- physiology ----------------
    def _tick(self, st: SimState) -> None:
        sc = st.scenario()
        # first-order lag toward target over ramp_seconds
        alpha = min(1.0, self._interval / max(sc.ramp_seconds, self._interval))

        def drift(cur: float, target: float, jitter: float) -> float:
            cur += (target - cur) * alpha
            return cur + random.uniform(-jitter, jitter)

        st.hr   = drift(st.hr,   sc.hr,   2.0)
        st.spo2 = min(100.0, drift(st.spo2, sc.spo2, 0.6))
        st.rr   = drift(st.rr,   sc.rr,   0.8)
        st.temp = drift(st.temp, sc.temp, 0.05)
        st.sys  = drift(st.sys,  sc.sys,  2.5)
        st.dia  = drift(st.dia,  sc.dia,  1.8)
        if st.dia > st.sys - 15:                 # keep pulse pressure sane
            st.dia = st.sys - 15
        if sc.glucose is not None:
            st.glucose = drift(st.glucose if st.glucose is not None else sc.glucose,
                               sc.glucose, 0.1)

    # ---------------- wire formats ----------------
    def _payload(self, st: SimState) -> dict:
        if st.device.role == "paramedic":
            # DeviceTelemetryRequest → /device-telemetry
            p = {
                "heartRate": round(st.hr),
                "respiratoryRate": round(st.rr),
                "spo2": round(st.spo2),
                "systolicBp": round(st.sys),
                "diastolicBp": round(st.dia),
                "temperature": round(st.temp, 1),
            }
            if st.glucose is not None:
                p["glucose"] = round(st.glucose, 1)
            return p
        # DeviceVitalPayload → /ingest (bedside + triage)
        st.seq += 1
        return {
            "serialNumber": st.device.serial,
            "capturedAt": _iso_now(),
            "sequenceNumber": st.seq,
            "heartRate": round(st.hr),
            "spo2": round(st.spo2),
            "respiratoryRate": round(st.rr),
            "temperature": round(st.temp, 1),
            "systolicBp": round(st.sys),
            "diastolicBp": round(st.dia),
        }
