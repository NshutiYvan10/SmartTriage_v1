"""
Configuration — loaded from a YAML file OUTSIDE the repository.

Security model (mirrors the firmware's config.h policy):
  - device API keys live only in the config file on the Pi
    (default /etc/smarttriage/devices.yaml, chmod 600), never in git,
    never in the browser — the kiosk UI talks only to this service.
  - one key per device identity, including every simulated monitor,
    so revocation and attribution stay per-device.

Override the config location with GATEWAY_CONFIG=/path/to/devices.yaml.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field

import yaml

from .scenarios import ROLES

DEFAULT_CONFIG_PATH = "/etc/smarttriage/devices.yaml"


@dataclass
class SimDevice:
    role: str          # bedside | triage | paramedic | roaming
    name: str          # tile label, e.g. "SIM-BED-01"
    serial: str        # registered device serial
    api_key: str       # that device's key (per-device, never shared)
    enabled: bool = True
    scenario: str = ""  # startup scenario; engine default if empty
    # ── roaming only ──
    #: Zone whose recheck worklist the roaming cart offers by default. The
    #: worklist call is zone-scoped for a ward nurse (an unfiltered read needs
    #: see-all-zones authority), so this must match the operator's own zone.
    zone: str = "GENERAL"
    #: Seconds of "cuff inflating" before the roaming cart reports a BP. The
    #: backend closes a spot check as soon as HR + SpO2 + systolic have all
    #: landed across two validated readings, so BP is what ENDS the check —
    #: withholding it is what makes a spot check take a clinically believable
    #: minute instead of finishing in ten seconds.
    bp_after_seconds: float = 45.0


@dataclass
class GatewayConfig:
    backend_url: str = "http://localhost:8080"
    listen_port: int = 8090
    tx_interval_seconds: float = 5.0
    queue_db: str = "gateway-queue.db"
    gateway_name: str = "SmartTriage Gateway"
    # The gateway's OWN backend identity (V114): a device of type GATEWAY,
    # registered by the HOSPITAL_ADMIN and owned by one hospital. Its key is
    # the only credential the gateway uses against the backend — the device
    # registry is read with it, scoped server-side to that hospital.
    gateway_serial: str = ""
    gateway_api_key: str = ""
    # Kiosk unlock PIN — store the salted hash (provision.py --pin writes it).
    # kiosk_pin (plain) is a development convenience only.
    kiosk_pin_sha256: str = ""
    kiosk_pin_salt: str = ""
    kiosk_pin: str = ""
    devices: list[SimDevice] = field(default_factory=list)

    @staticmethod
    def load(path: str | None = None) -> "GatewayConfig":
        path = path or os.environ.get("GATEWAY_CONFIG", DEFAULT_CONFIG_PATH)
        with open(path, "r", encoding="utf-8") as f:
            raw = yaml.safe_load(f) or {}
        cfg = GatewayConfig(
            backend_url=str(raw.get("backend_url", "http://localhost:8080")).rstrip("/"),
            listen_port=int(raw.get("listen_port", 8090)),
            tx_interval_seconds=float(raw.get("tx_interval_seconds", 5.0)),
            queue_db=str(raw.get("queue_db", "gateway-queue.db")),
            gateway_name=str(raw.get("gateway_name", "SmartTriage Gateway")),
            gateway_serial=str(raw.get("gateway_serial", "")),
            gateway_api_key=str(raw.get("gateway_api_key", "")),
            kiosk_pin_sha256=str(raw.get("kiosk_pin_sha256", "")),
            kiosk_pin_salt=str(raw.get("kiosk_pin_salt", "")),
            kiosk_pin=str(raw.get("kiosk_pin", "")),
        )
        for d in raw.get("devices", []):
            role = str(d["role"]).lower()
            if role not in ROLES:
                # Fail here, with the file and the offending value, rather than
                # later and silently: an unknown role otherwise falls back to the
                # bedside scenario family and posts the bedside wire format to
                # the bedside endpoint, so a mis-typed device looks like it works.
                raise ValueError(
                    f"{path}: device {d.get('name') or d.get('serial')!r} has "
                    f"role {role!r}; expected one of {', '.join(ROLES)}")
            cfg.devices.append(SimDevice(
                role=role,
                name=str(d.get("name") or d["serial"]),
                serial=str(d["serial"]),
                api_key=str(d["api_key"]),
                enabled=bool(d.get("enabled", True)),
                scenario=str(d.get("scenario", "")),
                zone=str(d.get("zone", "GENERAL")).upper(),
                bp_after_seconds=float(d.get("bp_after_seconds", 45.0)),
            ))
        return cfg
