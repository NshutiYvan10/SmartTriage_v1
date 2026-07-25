# SmartTriage Gateway Dashboard (Raspberry Pi 5)

The Pi is the hub between the bedside hardware and the SmartTriage backend —
and the presentation console for panel demos, on its 800×480 DSI touchscreen.

One FastAPI service, five jobs:

1. **Gateway** — every monitor (including the **real ESP32-S3 monitor**) sends
   to the Pi instead of opening its own backend connection. The Pi forwards
   each reading upstream **with that device's own API key** (identity is never
   blurred), caches to SQLite when the backend is unreachable, and replays
   oldest-first on reconnect.
2. **Authenticated kiosk** — the touchscreen is locked. Unlock with real
   SmartTriage staff credentials (proxied to the backend; JWTs stay inside the
   gateway process, never in the browser) or with an **offline PIN** (salted
   hash in the config) so the console stays usable when the backend is down.
3. **Live view** — the real monitor's vitals with a pulse ring that beats on
   every successful post (red on failure), and honest delivery states:
   `DELIVERED · AWAITING PATIENT BIND · QUEUED ON PI · FAILED`.
4. **Simulation** — virtual monitors driven by big severity-coded scenario
   buttons; simulated data rides the exact same pipeline as real data, so
   alerts, triage and dashboards react for real:

   | Monitor | Backend path | Scenarios |
   |---|---|---|
   | SIM-BED-* (bedside) | `/iot/stream/ingest` | Normal · Hypoxia · HTN crisis · Fever+tachy · Brady · Deteriorate (2-min slide) · **SEPSIS** |
   | SIM-TRIAGE-* | `/iot/stream/ingest` | **GREEN / YELLOW / ORANGE / RED / SEPSIS** — SATS-banded so triage lands in the intended category |
   | SIM-EMS-* (paramedic) | `/iot/stream/device-telemetry` | Stable · Shock · Resp. distress · **Hypoglycemia (glu 2.1)** — pullable from the EMS run form |

   Severity colors are consistent everywhere: green · yellow · orange · red ·
   **sepsis purple**. Scenario changes drift believably (no teleporting numbers).
5. **Sync console** — offline/syncing/synced status, queue depth with ages,
   recovered-readings counter, and an **outage drill** button that severs the
   uplink on purpose so the store-and-forward story can be demonstrated
   without touching a cable.

> **CRITICAL for the demo:** the backend has its own fleet simulator
> (`VitalSimulatorService`, enabled by `smarttriage.simulation.enabled=true`)
> which generates vitals for EVERY session-bound device. Run the demo with
> `smarttriage.simulation.enabled=false` on the backend — otherwise it
> double-writes contradicting vitals on top of the Pi's scenarios (verified:
> two interleaved streams per device). The Pi gateway replaces it.

---

## Deployment — step by step (Pi 5, Raspberry Pi OS Bookworm)

### 0. Prerequisites
- Raspberry Pi OS (64-bit) with the DSI touchscreen working.
- The Pi and the backend host on the same network; note the backend's
  address, an admin login, and your hospital's UUID.

### 1. Install the service

```bash
cd ~ && git clone <repo> && cd SmartTriage_v1/SmartTriage_Gateway
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
```

### 2. Provision devices + kiosk PIN (one-time, run by an admin)

```bash
sudo mkdir -p /etc/smarttriage
.venv/bin/python provision.py \
    --backend http://<backend-host>:8080 \
    --email <admin-email> \
    --hospital-id <hospital-uuid> \
    --gateway-name "Kigali ED — Ward Gateway" \
    --pin \
    --out /tmp/devices.yaml
sudo mv /tmp/devices.yaml /etc/smarttriage/devices.yaml
sudo chown root:pi /etc/smarttriage/devices.yaml
sudo chmod 640 /etc/smarttriage/devices.yaml
```

This registers the SIM-* devices in SmartTriage (each with its own API key),
prompts for the offline PIN (stored as a salted SHA-256 hash), and writes the
config. **The file contains live keys — never commit it.**

### 3. Run as a service

```bash
sudo cp smarttriage-gateway.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now smarttriage-gateway
curl -s http://localhost:8090/kiosk/api/me   # → {"authenticated":false,...}
```

### 4. Kiosk mode on the touchscreen

Wayland (Bookworm default, wayfire): add to `~/.config/wayfire.ini`:
```ini
[autostart]
kiosk = chromium-browser --kiosk --noerrdialogs --disable-infobars --check-for-update-interval=31536000 http://localhost:8090
```
labwc (newer Bookworm images): add the same command to
`~/.config/labwc/autostart`. X11 fallback: prefix with `@` in
`~/.config/lxsession/LXDE-pi/autostart`.

Optional polish: `raspi-config` → Display → disable screen blanking.

### 5. Point the real monitor at the gateway

In the ESP32 firmware's `config.h`:
```cpp
#define SERVER_BASE "http://<pi-address>:8090"
```
No other firmware change — the gateway exposes the identical `/ingest`,
`/device-telemetry` and `/heartbeat` contract and passes the monitor's own
`X-Device-API-Key` through untouched. Its card appears on the Live tab
automatically on the first reading (grey after 20 s of silence).

### 6. Demo-day checklist

- Backend: `smarttriage.simulation.enabled=false`.
- Unlock once with staff credentials → the Registry tab works (it needs a
  backend identity). The PIN is the fallback if the network drops.
- Rehearse the outage drill: Sync tab → *Simulate backend outage* → watch
  readings queue → *Restore link* → watch **SYNCING → SYNCED** with the
  recovered counter climbing.
- The lock button (⏻, top right) ends the session between rehearsals.

---

## Authentication model

**The gateway is a first-class, hospital-owned device.** The hospital admin
registers it (provision.py does this) as a device of type `GATEWAY` — owned
by exactly one hospital, with its **own API key**. That key is the only
credential the gateway uses against the backend:

- the **Registry tab** reads `GET /iot/stream/hospital-registry` with the
  gateway key — the response is scoped to the owning hospital *by the key
  itself* (no staff identity involved), and the backend strips every
  device's API key from it (a leaked gateway key cannot harvest the fleet);
- the gateway **heartbeats** with its key, so the admin's device registry
  shows the Pi itself ONLINE/OFFLINE like any monitor;
- **revocation is device revocation**: the admin flips the gateway out of
  service (or deactivates it) and the backend answers 403 on its next poll —
  verified live.

Unlocking the **touchscreen** is separate from the appliance identity:

- **Staff login** (backend credentials, proxied to `/auth/login`; JWTs held
  server-side with auto-refresh, browser gets only an opaque cookie) — gives
  named attribution for who is operating the kiosk.
- **Offline PIN** (salted hash in the config) — works with the backend down,
  which is exactly when the offline-resilience demo needs the screen. With
  the gateway key configured, the Registry works from a PIN session too.
- Sessions live in process memory (a reboot logs everyone out) and expire
  after 12 h idle. Device pass-through endpoints are **not** behind the kiosk
  session — devices authenticate with their own API keys, as always.

## Key management

- **One key per device identity** — real and simulated alike. The backend
  cross-validates serial↔key, so attribution and revocation stay per-device;
  the gateway never re-signs traffic with its own credentials.
- Keys live **only** in `/etc/smarttriage/devices.yaml` (outside the repo —
  this repo ships `devices.example.yaml` with placeholders). The kiosk
  browser never sees a key; the Registry tab shows them masked
  (`st_dev_…abcd`), masking done server-side.
- Revoke one device server-side and nothing else is touched. Rotation =
  deactivate + re-provision that one entry.
- Demo runs plain HTTP on a closed LAN; production would front the backend
  with TLS — per-device identity makes mTLS/HMAC a drop-in upgrade later.

## Store-and-forward semantics

- Failed `/ingest` posts (network down or outage drill) are queued in SQLite
  with `capturedAt` inside the payload, so late delivery keeps clinical time.
  The queue survives gateway restarts and power loss.
- Telemetry snapshots are latest-value semantics — a stale snapshot is
  worthless, so they are dropped, never queued.
- Drain is oldest-first, gentle (≤3/s). A replayed reading the backend
  consciously declines ("no active monitoring session") is dropped rather
  than retried forever — replaying it later can never succeed.
- Auth/contract rejections (401/400) are never queued: they would loop.

## Endpoints

| | |
|---|---|
| `GET /` | kiosk UI (login-gated views) |
| `POST /kiosk/api/login` · `/logout` · `GET /kiosk/api/me` | session auth |
| `GET /kiosk/api/state` | link state, sync state, queue, sims, real tiles |
| `POST /kiosk/api/sim/{serial}/scenario/{key}` | switch scenario |
| `POST /kiosk/api/sim/{serial}/toggle` | pause/resume a simulator |
| `POST /kiosk/api/outage/{on\|off}` | demo outage drill |
| `GET /kiosk/api/registry` | backend device registry (staff session required) |
| `POST /api/v1/iot/stream/ingest` | device pass-through + store-and-forward |
| `POST /api/v1/iot/stream/device-telemetry` | device pass-through (never queued) |
| `POST /api/v1/iot/stream/heartbeat` | device pass-through |
| `WS /ws` | live event feed (session-gated) |
