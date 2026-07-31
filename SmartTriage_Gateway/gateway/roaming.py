"""
Roaming spot-check cart — the General-zone monitor that is MOVED, not mounted.

Why this is not just another entry in SimulatorEngine
----------------------------------------------------
The bedside and triage sims are free-running streams: one device, one patient,
readings forever. The roaming cart is the opposite shape — a single device that
visits a patient, takes ONE set of observations, is released, and moves on. So
it is event-driven, and it needs two identities at once:

  * the SESSION is opened by a logged-in nurse (JWT). A device API key cannot
    do it: /iot/monitoring/start is @PreAuthorize'd to human roles and is not
    in SecurityConfig's anonymous allow-list. That is correct — starting a spot
    check is a clinical act and must be attributable to a person.
  * the READINGS are posted by the device (X-Device-API-Key → /iot/stream/ingest),
    exactly like every other monitor. The backend binds a reading to a session
    by DEVICE, never by a session id in the body.

The check ends by itself
------------------------
The backend closes a SPOT_CHECK as soon as two validated readings have covered
HR + SpO2 + systolic BP, then writes the vitals snapshot that resets the
patient's recheck clock. We do not poll to discover that: the next /ingest
after closure answers "No active monitoring session for this device", which the
forwarder already reports as ack state "idle". That transition IS our
completion signal.

Which is also why the cuff is deliberately slow. BP is the last of the three
required vitals, so BP is what ends the check. Sending it on the first tick
would complete a spot check in ten seconds and tell the panel a lie about how
rounds work; `bp_after_seconds` holds it back so the check lasts about a
minute, like a real automatic cuff cycle.

Between patients the cart must keep HEARTBEATING. getAvailableDevices requires
status exactly ONLINE and the watchdog demotes a silent device after ~60s — an
idle cart that stopped talking would quietly vanish from the nurse's monitor
picker.
"""
from __future__ import annotations

import asyncio
import random
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Awaitable, Callable, Optional

import httpx

from .config import SimDevice
from .scenarios import Scenario, DEFAULT_SCENARIO, family_for

WORKLIST_PATH = "/api/v1/retriage/recheck-worklist/{hospital_id}"
START_PATH = "/api/v1/iot/monitoring/start"
STOP_PATH = "/api/v1/iot/monitoring/stop/{session_id}"

IDLE, CHECKING = "idle", "checking"


@dataclass
class Patient:
    """One row of the nurse's recheck worklist, as the console shows it."""
    visit_id: str
    visit_number: str
    name: str
    category: str
    zone: str
    bed_code: str
    minutes_until_due: float
    overdue: bool
    check_in_progress: bool


@dataclass
class RoamingState:
    device: SimDevice
    scenario_key: str
    phase: str = IDLE
    # live physiology (spot check = a settled observation set, so short ramps)
    hr: float = 0
    spo2: float = 0
    rr: float = 0
    temp: float = 0
    sys: float = 0
    dia: float = 0
    glucose: Optional[float] = None
    # current check
    session_id: str = ""
    visit_id: str = ""
    patient_name: str = ""
    started_at: float = 0.0
    readings_sent: int = 0
    bp_reported: bool = False
    seq: int = 0
    last_ack: str = "-"
    last_payload: dict = field(default_factory=dict)
    # operator-facing trail
    last_result: str = ""
    completed_count: int = 0
    history: list = field(default_factory=list)

    def scenario(self) -> Scenario:
        return family_for(self.device.role)[self.scenario_key]

    def severity(self) -> int:
        return self.scenario().severity

    def elapsed(self) -> float:
        return time.time() - self.started_at if self.started_at else 0.0


class RoamingEngine:
    """Owns the single roaming cart: its clinical state and its current check.

    Every staff-authed call takes its `ctx` — (base_url, bearer_token, hospital_id)
    — as an ARGUMENT from the calling request, never from ambient state. The kiosk
    shares one backend identity across the appliance, so reading it globally would
    let a PIN-only operator act with the last nurse's credentials: the spot check
    would be attributed to a clinician who never touched the cart, and the patient
    worklist would be readable behind a 4-digit code. ctx is None for a PIN
    session, and then roaming refuses.
    """

    def __init__(self, device: SimDevice, interval_seconds: float,
                 send: Callable[["RoamingState", dict], Awaitable[str]],
                 heartbeat: Callable[[str], Awaitable[bool]],
                 notify: Callable[[dict], None]):
        fam = family_for(device.role)
        key = device.scenario if device.scenario in fam else DEFAULT_SCENARIO[device.role]
        self.st = RoamingState(device=device, scenario_key=key)
        self._settle(initial=True)
        self._interval = interval_seconds
        self._send = send
        self._heartbeat = heartbeat
        self._notify = notify
        self._client = httpx.AsyncClient(timeout=6.0)
        self.worklist: list[Patient] = []
        self.worklist_error: str = ""
        self.worklist_at: float = 0.0

    # ---------------- physiology ----------------
    def _settle(self, initial: bool = False) -> None:
        """A spot check reads a patient who is ALREADY in some state — there is
        no slow slide to watch, so land near the scenario's targets at once and
        let per-reading noise do the rest."""
        sc = self.st.scenario()
        s = self.st
        if initial:
            s.hr, s.spo2, s.rr = sc.hr, sc.spo2, sc.rr
            s.temp, s.sys, s.dia, s.glucose = sc.temp, sc.sys, sc.dia, sc.glucose
            return
        jitter = lambda v, j: v + random.uniform(-j, j)  # noqa: E731
        s.hr, s.spo2 = jitter(sc.hr, 3.0), min(100.0, jitter(sc.spo2, 0.8))
        s.rr, s.temp = jitter(sc.rr, 1.0), jitter(sc.temp, 0.1)
        s.sys, s.dia = jitter(sc.sys, 3.0), jitter(sc.dia, 2.0)
        s.glucose = jitter(sc.glucose, 0.15) if sc.glucose is not None else None

    def set_scenario(self, key: str) -> bool:
        if key not in family_for(self.st.device.role):
            return False
        self.st.scenario_key = key
        self._settle(initial=True)
        self._notify({"type": "roaming", "event": "scenario", "scenario": key})
        return True

    # ---------------- worklist (staff JWT) ----------------
    async def refresh_worklist(self, ctx: Optional[tuple], zone: str = "") -> str:
        """Pull the patients due a recheck. Returns '' or an error string."""
        if not ctx:
            self.worklist, self.worklist_error = [], "no-staff-session"
            return self.worklist_error
        base, token, hospital_id = ctx
        zone = (zone or self.st.device.zone or "").upper()
        url = base + WORKLIST_PATH.format(hospital_id=hospital_id)
        try:
            r = await self._client.get(url, params={"zone": zone} if zone else None,
                                       headers={"Authorization": f"Bearer {token}"})
        except Exception:
            self.worklist_error = "backend-down"
            return self.worklist_error
        if r.status_code in (401, 403):
            # A ward nurse may only read their OWN zone; an unfiltered read needs
            # see-all-zones authority. Say which, or this looks like a bad login.
            self.worklist_error = ("not-authorised-for-zone" if zone
                                   else "not-authorised-pass-a-zone")
            return self.worklist_error
        if r.status_code != 200:
            self.worklist_error = f"backend answered {r.status_code}"
            return self.worklist_error
        rows = (r.json() or {}).get("data") or []
        self.worklist = [Patient(
            visit_id=str(x.get("visitId", "")),
            visit_number=str(x.get("visitNumber", "")),
            name=str(x.get("patientName", "")),
            category=str(x.get("category", "")),
            zone=str(x.get("zone", "")),
            bed_code=str(x.get("bedCode") or ""),
            minutes_until_due=float(x.get("minutesUntilDue") or 0),
            overdue=bool(x.get("overdue")),
            check_in_progress=bool(x.get("checkInProgress")),
        ) for x in rows]
        self.worklist_error, self.worklist_at = "", time.time()
        return ""

    # ---------------- start / cancel a check ----------------
    async def start_check(self, ctx: Optional[tuple], visit_id: str,
                          device_id: str) -> tuple[bool, str]:
        if self.st.phase == CHECKING:
            return False, "A check is already running — finish or cancel it first."
        if not ctx:
            return False, ("Sign in with staff credentials to run a spot check "
                           "(a PIN session cannot open one).")
        if not device_id:
            return False, ("This cart is not in the backend registry yet — it must "
                           "heartbeat once before a check can be started.")
        base, token, _ = ctx
        try:
            r = await self._client.post(
                base + START_PATH,
                json={"deviceId": device_id, "visitId": visit_id, "spotCheck": True},
                headers={"Authorization": f"Bearer {token}"})
        except Exception:
            return False, "Backend unreachable."
        if r.status_code not in (200, 201):
            try:
                msg = (r.json() or {}).get("message") or f"HTTP {r.status_code}"
            except Exception:
                msg = f"HTTP {r.status_code}"
            return False, msg
        data = (r.json() or {}).get("data") or {}
        s = self.st
        s.session_id = str(data.get("id", ""))
        s.visit_id = visit_id
        s.patient_name = str(data.get("patientName") or "")
        s.phase = CHECKING
        s.started_at = time.time()
        s.readings_sent = 0
        s.bp_reported = False
        s.last_result = ""
        self._settle()
        self._notify({"type": "roaming", "event": "started", "patient": s.patient_name,
                      "scenario": s.scenario_key, "severity": s.severity()})
        return True, ""

    async def cancel_check(self, ctx: Optional[tuple],
                           reason: str = "Cancelled on the ward cart") -> tuple[bool, str]:
        """Abort early. Without this the device stays logically glued to the
        patient until the backend's 10-minute timeout sweep releases it."""
        s = self.st
        if s.phase != CHECKING:
            return False, "No check is running."
        session_id = s.session_id
        self._release("cancelled")
        if not ctx or not session_id:
            return True, ""
        base, token, _ = ctx
        try:
            await self._client.post(
                base + STOP_PATH.format(session_id=session_id),
                params={"reason": reason},
                headers={"Authorization": f"Bearer {token}"})
        except Exception:
            return True, "Cancelled on the cart, but the backend did not confirm."
        return True, ""

    def _release(self, result: str) -> None:
        s = self.st
        if result == "completed":
            s.completed_count += 1
        s.history.insert(0, {"patient": s.patient_name, "result": result,
                             "scenario": s.scenario_key, "severity": s.severity(),
                             "seconds": round(s.elapsed()), "at": time.time()})
        del s.history[8:]
        s.last_result = result
        s.phase = IDLE
        s.session_id = s.visit_id = ""
        s.started_at = 0.0
        s.bp_reported = False
        self._notify({"type": "roaming", "event": result, "patient": s.patient_name})

    # ---------------- the loop ----------------
    async def run(self) -> None:
        await asyncio.sleep(random.uniform(0, 2.0))
        while True:
            t0 = time.monotonic()
            try:
                if self.st.phase == CHECKING:
                    await self._tick_check()
                else:
                    # Idle carts must still prove they are alive or they drop out
                    # of the nurse's monitor picker within ~60 seconds.
                    await self._heartbeat(self.st.device.api_key)
            except Exception:
                pass
            await asyncio.sleep(max(0.5, self._interval - (time.monotonic() - t0)))

    async def _tick_check(self) -> None:
        s = self.st
        self._settle()
        payload = self._payload()
        s.last_payload = payload
        s.last_ack = await self._send(s, payload)
        s.readings_sent += 1
        if s.last_ack == "ok":
            return
        if s.last_ack == "idle":
            # The backend no longer has a session for this device. If we had
            # already reported a BP, it closed the check itself (the designed
            # ending); otherwise something else released it.
            self._release("completed" if s.bp_reported else "released-early")

    def _payload(self) -> dict:
        """/ingest wire format. The cart is a registered ESP32_MONITOR — the
        roaming-ness is a workflow property, not a device type, and the nurse's
        monitor picker only offers ESP32_MONITORs that are neither the triage
        monitor nor bolted to a bed."""
        s = self.st
        s.seq += 1
        p = {
            "serialNumber": s.device.serial,
            "capturedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "sequenceNumber": s.seq,
            "heartRate": round(s.hr),
            "spo2": round(s.spo2),
            "respiratoryRate": round(s.rr),
            "temperature": round(s.temp, 1),
        }
        if s.glucose is not None:
            p["bloodGlucose"] = round(s.glucose, 1)
        # The cuff finishes mid-check; BP is the vital that completes the set.
        if s.elapsed() >= s.device.bp_after_seconds:
            p["systolicBp"] = round(s.sys)
            p["diastolicBp"] = round(s.dia)
            s.bp_reported = True
        return p

    # ---------------- console view ----------------
    def snapshot(self, device_id: str, device_status: str,
                 staff_session: bool) -> dict:
        s = self.st
        fam = family_for(s.device.role)
        cuff_in = max(0.0, s.device.bp_after_seconds - s.elapsed()) if s.phase == CHECKING else None
        return {
            "serial": s.device.serial, "name": s.device.name, "zone": s.device.zone,
            "phase": s.phase, "scenario": s.scenario_key, "severity": s.severity(),
            "deviceId": device_id, "deviceStatus": device_status,
            "patientName": s.patient_name if s.phase == CHECKING else "",
            "elapsed": round(s.elapsed()) if s.phase == CHECKING else 0,
            "cuffInSeconds": round(cuff_in) if cuff_in is not None else None,
            "bpReported": s.bp_reported,
            "readingsSent": s.readings_sent, "ack": s.last_ack,
            "payload": s.last_payload,
            "completedCount": s.completed_count, "lastResult": s.last_result,
            "history": s.history,
            "staffSession": staff_session,
            "worklistError": self.worklist_error,
            "worklistAgeSeconds": round(time.time() - self.worklist_at) if self.worklist_at else None,
            "worklist": [p.__dict__ for p in self.worklist],
            "scenarios": [{"key": x.key, "label": x.label, "severity": x.severity,
                           "note": x.note} for x in fam.values()],
        }
