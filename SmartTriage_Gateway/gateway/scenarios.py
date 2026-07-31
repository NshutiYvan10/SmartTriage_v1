"""
Scenario definitions — the clinical personalities the demo can trigger.

Three simulator families, each speaking the backend path its real
counterpart uses:

  bedside   → /api/v1/iot/stream/ingest      (continuous ED monitoring)
  triage    → /api/v1/iot/stream/ingest      (presets banded so the vitals,
              when triaged, land in the intended SATS category)
  paramedic → /api/v1/iot/stream/device-telemetry
              (per-device snapshot the EMS run form pulls from)

Values are TARGETS. The engine drifts current values toward the target
with physiological co-variation and noise, so a scenario change plays
out as a believable transition on the SmartTriage dashboards, not a
teleporting number.

`severity` drives the tile colour on the kiosk: 0 green · 1 yellow ·
2 orange · 3 red · 4 sepsis (purple — its own colour because a sepsis
demo is qualitatively different from "very sick": it exercises the
backend's sepsis screening + hour-1 bundle machinery, not just triage).
"""
from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True)
class Scenario:
    key: str
    label: str
    severity: int                 # 0 green, 1 yellow, 2 orange, 3 red, 4 sepsis
    hr: float
    spo2: float
    rr: float
    temp: float
    sys: float
    dia: float
    glucose: float | None = None  # mmol/L — paramedic telemetry only
    ramp_seconds: float = 25.0    # how long the transition takes
    note: str = ""


BEDSIDE: dict[str, Scenario] = {s.key: s for s in [
    Scenario("normal",       "Normal",              0,  76, 98, 15, 36.8, 118, 76,
             note="baseline healthy adult"),
    Scenario("hypoxia",      "Hypoxia",             3,  98, 86, 26, 37.0, 124, 80,
             note="SpO2 < 90 fires the desaturation pathway"),
    Scenario("hypertensive", "HTN crisis", 2,  92, 97, 17, 36.9, 196, 112,
             note="SBP > 180"),
    Scenario("fever_tachy",  "Fever+tachy", 2, 124, 96, 24, 39.3, 108, 70,
             note="sepsis-flavoured presentation"),
    Scenario("bradycardia",  "Brady",         3,  38, 95, 12, 36.2, 100, 64,
             note="HR < 40"),
    Scenario("deteriorating","Deteriorate",       2, 118, 91, 27, 38.4,  96, 60,
             ramp_seconds=120.0,
             note="slow 2-minute slide — watch the trend lines move"),
    Scenario("sepsis",       "SEPSIS",            4, 132, 90, 32, 39.4,  82, 50,
             ramp_seconds=90.0,
             note="septic shock physiology: fever + tachycardia + tachypnea +"
                  " falling BP — trips the sepsis screening & hour-1 bundle"),
]}

# SATS-banded presets: the point of the triage monitor is that THESE
# vitals, carried into triage, produce the intended category.
TRIAGE: dict[str, Scenario] = {s.key: s for s in [
    Scenario("green",  "GREEN",   0,  78, 98, 16, 36.8, 122, 78,
             note="TEWS ~0-2"),
    Scenario("yellow", "YELLOW",   1, 106, 96, 21, 37.9, 128, 82,
             note="mild derangement, TEWS ~3-4"),
    Scenario("orange", "ORANGE", 2, 126, 93, 26, 38.7,  94, 60,
             note="TEWS ~5-6"),
    Scenario("red",    "RED",   3, 138, 85, 33, 39.2,  80, 52,
             note="TEWS ≥7 — resus-level physiology"),
    Scenario("sepsis", "SEPSIS", 4, 130, 91, 31, 39.5,  84, 52,
             ramp_seconds=90.0,
             note="septic presentation at the front door — triage lands RED"
                  " and the sepsis screen goes positive"),
]}

# ROAMING — the shared General-zone spot-check cart. Not a continuous
# monitor: one device wheeled between chair patients, taking a single set of
# observations each time (the backend closes the SPOT_CHECK session itself
# once HR + SpO2 + a BP have landed). The presets are therefore whole
# OBSERVATION SETS, chosen to exercise what a rounds nurse actually meets in
# a chair area — including the one case the zone exists to catch: a patient
# who looked GREEN at the front door and is quietly deteriorating.
ROAMING: dict[str, Scenario] = {s.key: s for s in [
    Scenario("reassuring",   "Reassuring",    0,  74, 98, 15, 36.7, 120, 78, glucose=5.4,
             ramp_seconds=8.0,
             note="unchanged since triage — the check simply resets the recheck clock"),
    Scenario("drifting",     "Drifting",      1, 104, 95, 20, 37.6, 126, 80, glucose=5.8,
             ramp_seconds=8.0,
             note="mild derangement — earns a shorter recheck interval, not a move"),
    Scenario("silent_hypoxia", "Silent hypoxia", 3,  96, 88, 24, 37.1, 118, 74, glucose=5.5,
             ramp_seconds=8.0,
             note="SpO2 88 in a chair patient — auto-retriages and raises a"
                  " GENERAL→RESUS transfer for a bed"),
    Scenario("occult_sepsis", "Occult sepsis", 4, 126, 91, 28, 38.9,  92, 58, glucose=6.4,
             ramp_seconds=8.0,
             note="the chair patient who should never have been in a chair —"
                  " trips sepsis screening off a single rounds observation set"),
    Scenario("hypoglycemic", "Hypoglycemia",  3,  98, 96, 18, 36.3, 116, 72, glucose=2.3,
             ramp_seconds=8.0,
             note="glucose 2.3 mmol/L found on rounds — fires the hypoglycemia pathway"),
]}

PARAMEDIC: dict[str, Scenario] = {s.key: s for s in [
    Scenario("stable",        "Stable",   0,  84, 97, 16, 36.9, 126, 80, glucose=5.6),
    Scenario("shock",         "Shock",     3, 132, 92, 28, 36.0,  78, 50, glucose=6.8,
             note="hypotensive + tachycardic"),
    Scenario("resp_distress", "Resp distress",     2, 112, 88, 30, 37.2, 138, 88, glucose=5.9),
    Scenario("hypoglycemia",  "Hypoglycemia",       3,  96, 96, 18, 36.4, 118, 74, glucose=2.1,
             note="glucose 2.1 mmol/L — exercises the ED glucose pipeline on arrival"),
]}

FAMILIES: dict[str, dict[str, Scenario]] = {
    "bedside": BEDSIDE,
    "triage": TRIAGE,
    "paramedic": PARAMEDIC,
    "roaming": ROAMING,
}

DEFAULT_SCENARIO = {"bedside": "normal", "triage": "green", "paramedic": "stable",
                    "roaming": "reassuring"}

#: The only roles devices.yaml may declare. Validated at load so a typo fails
#: with a readable message instead of either silently becoming a bedside monitor
#: (FAMILIES.get fallback) or killing the kiosk with a KeyError inside FastAPI's
#: startup event, which leaves uvicorn up but serving nothing.
ROLES: tuple[str, ...] = tuple(FAMILIES.keys())


def family_for(role: str) -> dict[str, Scenario]:
    return FAMILIES.get(role, BEDSIDE)
