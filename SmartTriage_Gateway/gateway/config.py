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

DEFAULT_CONFIG_PATH = "/etc/smarttriage/devices.yaml"


@dataclass
class SimDevice:
    role: str          # bedside | triage | paramedic
    name: str          # tile label, e.g. "SIM-BED-01"
    serial: str        # registered device serial
    api_key: str       # that device's key (per-device, never shared)
    enabled: bool = True
    scenario: str = ""  # startup scenario; engine default if empty


@dataclass
class GatewayConfig:
    backend_url: str = "http://localhost:8080"
    listen_port: int = 8090
    tx_interval_seconds: float = 5.0
    queue_db: str = "gateway-queue.db"
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
        )
        for d in raw.get("devices", []):
            cfg.devices.append(SimDevice(
                role=str(d["role"]).lower(),
                name=str(d.get("name") or d["serial"]),
                serial=str(d["serial"]),
                api_key=str(d["api_key"]),
                enabled=bool(d.get("enabled", True)),
                scenario=str(d.get("scenario", "")),
            ))
        return cfg
