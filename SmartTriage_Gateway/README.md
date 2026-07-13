# SmartTriage Gateway (Raspberry Pi 5)

One service, two jobs for the demo and beyond:

1. **Gateway** — every monitor (including the **real ESP32 monitor**) sends to
   the Pi instead of opening its own backend connection. The Pi forwards each
   reading upstream **with that device's own API key** (identity is never
   blurred), buffers to SQLite when the backend is unreachable, and replays
   oldest-first on reconnect.
2. **Demo console** — a touch kiosk on the Pi's 800×480 DSI screen with three
   simulator families, each speaking the same backend path as its real
   counterpart, plus a live tile proving the real monitor is flowing:

   | Tile | Backend path | Scenarios |
   |---|---|---|
   | REAL MONITOR | pass-through (verbatim) | whatever the hardware measures |
   | SIM-BED-01/02 (bedside) | `/iot/stream/ingest` | Normal · Hypoxia · Hypertensive crisis · Fever+tachy · Bradycardia · Deteriorating (2-min slide) |
   | SIM-TRIAGE-01 | `/iot/stream/ingest` | **GREEN / YELLOW / ORANGE / RED** — SATS-banded vitals so triage lands in the intended category |
   | SIM-EMS-01 (paramedic) | `/iot/stream/device-telemetry` | Stable · Shock · Resp. distress · **Hypoglycemia (glu 2.1)** — pullable from the EMS run form |

   Simulated devices are registered as `SIM-*` so demo vitals can never be
   mistaken for a real patient's. Scenario changes drift believably (no
   teleporting numbers).

> **CRITICAL for the demo:** the backend has its own fleet simulator
> (`VitalSimulatorService`, enabled by `smarttriage.simulation.enabled=true`)
> which generates vitals for EVERY session-bound device. Run the demo with
> `smarttriage.simulation.enabled=false` on the backend — otherwise it
> double-writes contradicting vitals on top of the Pi's scenarios (verified:
> two interleaved streams per device). The Pi gateway replaces it.

## Install (Pi 5, Raspberry Pi OS)

```bash
cd ~ && git clone <repo> && cd SmartTriage_v1/SmartTriage_Gateway
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt

# one-time: register the simulated devices + write the key file
sudo mkdir -p /etc/smarttriage
.venv/bin/python provision.py --backend http://<backend-host>:8080 \
    --email <admin-email> --hospital-id <hospital-uuid> \
    --out /tmp/devices.yaml
sudo mv /tmp/devices.yaml /etc/smarttriage/devices.yaml
sudo chown root:pi /etc/smarttriage/devices.yaml && sudo chmod 640 /etc/smarttriage/devices.yaml

# run as a service
sudo cp smarttriage-gateway.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now smarttriage-gateway
```

### Kiosk on the DSI screen

Wayland (Pi OS Bookworm): add to `~/.config/wayfire.ini`:
```ini
[autostart]
kiosk = chromium-browser --kiosk --noerrdialogs --disable-infobars http://localhost:8090
```
(X11 fallback: put the same command in `~/.config/lxsession/LXDE-pi/autostart` prefixed with `@`.)

### Point the real monitor at the gateway

In the ESP32 firmware's `config.h`, set `SERVER_BASE` to the Pi:
```cpp
#define SERVER_BASE "http://<pi-address>:8090"
```
No other firmware change — the gateway exposes the identical `/ingest`,
`/device-telemetry` and `/heartbeat` contract and passes the monitor's own
`X-Device-API-Key` through untouched. Its tile appears on the kiosk
automatically on the first reading (marked LIVE; grey after 20 s of silence).

## Key management (the professional version of "don't hardcode it")

- **One key per device identity** — real and simulated alike. The backend
  cross-validates serial↔key, so attribution and revocation stay per-device;
  the gateway never re-signs traffic with its own credentials.
- Keys live **only** in `/etc/smarttriage/devices.yaml` (chmod 600/640,
  outside the repo — this repo ships `devices.example.yaml` with
  placeholders). The kiosk browser never sees a key: it talks only to the
  local service.
- Revoke one device server-side and nothing else is touched. Rotation =
  deactivate + re-provision that one entry.
- Demo runs plain HTTP on a closed LAN; production would front the backend
  with TLS — per-device identity makes mTLS/HMAC a drop-in upgrade later.

## Endpoints

| | |
|---|---|
| `GET /` | kiosk UI |
| `GET /api/state` | tiles + link/queue status (JSON) |
| `POST /api/sim/{serial}/scenario/{key}` | switch scenario |
| `POST /api/sim/{serial}/toggle` | pause/resume a simulator |
| `POST /api/v1/iot/stream/ingest` | pass-through + store-and-forward |
| `POST /api/v1/iot/stream/device-telemetry` | pass-through (latest-value; never queued) |
| `POST /api/v1/iot/stream/heartbeat` | pass-through |
| `WS /ws` | live outgoing-payload feed for the ticker |
