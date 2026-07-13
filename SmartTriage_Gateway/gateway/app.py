"""
SmartTriage Gateway — FastAPI app.

One process, four jobs:
  1. PASS-THROUGH: the real ESP32 monitor points its SERVER_BASE here and
     its requests are forwarded to SmartTriage verbatim (same paths, its
     own API key). It appears on the kiosk as a live "REAL MONITOR" tile.
  2. SIMULATION: bedside / triage / paramedic virtual monitors, each a
     registered SmartTriage device, driven by scenario buttons.
  3. RESILIENCE: store-and-forward queue when the backend is unreachable.
  4. THE KIOSK: an 800×480 touch page (static/index.html) fed by WebSocket.
"""
from __future__ import annotations

import asyncio
import json
import time
from pathlib import Path

from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse, JSONResponse, Response

from .config import GatewayConfig
from .forwarder import Forwarder, INGEST, TELEMETRY, HEARTBEAT
from .scenarios import family_for
from .simulator import SimulatorEngine, SimState

app = FastAPI(title="SmartTriage Gateway")

cfg: GatewayConfig
fwd: Forwarder
engine: SimulatorEngine
real_monitors: dict[str, dict] = {}       # serial → {name, last_seen, last_payload, ack}
_ws_clients: set[WebSocket] = set()
_event_queue: asyncio.Queue = asyncio.Queue()


def notify(event: dict) -> None:
    event.setdefault("ts", time.time())
    try:
        _event_queue.put_nowait(event)
    except asyncio.QueueFull:
        pass


# ====================================================================
#  Startup
# ====================================================================
@app.on_event("startup")
async def startup() -> None:
    global cfg, fwd, engine
    cfg = GatewayConfig.load()
    fwd = Forwarder(cfg.backend_url, cfg.queue_db, notify)
    engine = SimulatorEngine(cfg.tx_interval_seconds, _send_sim, notify)

    for dev in cfg.devices:
        engine.add(dev)
        asyncio.create_task(engine.run(dev.serial))

    asyncio.create_task(fwd.drain_loop())
    asyncio.create_task(fwd.health_loop())
    asyncio.create_task(_ws_pump())


async def _send_sim(st: SimState, payload: dict) -> str:
    path = TELEMETRY if st.device.role == "paramedic" else INGEST
    # stale telemetry is worthless (latest-value semantics) → never queued
    status = await fwd.post(path, st.device.api_key, payload,
                            queue_on_failure=(path == INGEST))
    notify({"type": "tx", "source": st.device.name, "serial": st.device.serial,
            "role": st.device.role, "scenario": st.scenario_key,
            "severity": st.severity(), "ack": status, "payload": payload})
    return status


# ====================================================================
#  PASS-THROUGH — the real monitor's endpoints (byte-identical contract)
# ====================================================================
async def _passthrough(request: Request, path: str) -> Response:
    api_key = request.headers.get("X-Device-API-Key", "")
    raw = await request.body()

    serial, summary = "", {}
    if raw:
        try:
            body = json.loads(raw)
            serial = str(body.get("serialNumber", ""))
            summary = {k: body.get(k) for k in
                       ("heartRate", "spo2", "respiratoryRate", "temperature",
                        "systolicBp", "diastolicBp", "ecgRhythm") if body.get(k) is not None}
        except Exception:
            pass
    tile_id = serial or ("key-" + api_key[-6:] if api_key else "unknown")

    status, text = await fwd.passthrough(path, api_key, raw)
    compact = text.replace(" ", "")
    ack = "ok" if status == 200 and '"accepted":true' in compact else \
          "idle" if "Noactivemonitoringsession" in compact else \
          "queued" if "gatewayQueued" in text else "failed"

    if path != HEARTBEAT:
        real_monitors[tile_id] = {
            "name": "REAL MONITOR" if len(real_monitors) == 0 or tile_id in real_monitors
                    else f"REAL {tile_id}",
            "serial": tile_id, "last_seen": time.time(),
            "last_payload": summary, "ack": ack,
        }
        notify({"type": "tx", "source": real_monitors[tile_id]["name"], "serial": tile_id,
                "role": "real", "scenario": "live", "severity": -1,
                "ack": ack, "payload": summary})

    return Response(content=text, status_code=status, media_type="application/json")


@app.post(INGEST)
async def ingest(request: Request) -> Response:
    return await _passthrough(request, INGEST)


@app.post(TELEMETRY)
async def telemetry(request: Request) -> Response:
    return await _passthrough(request, TELEMETRY)


@app.post(HEARTBEAT)
async def heartbeat(request: Request) -> Response:
    return await _passthrough(request, HEARTBEAT)


# ====================================================================
#  Kiosk control API (localhost only in kiosk deployment)
# ====================================================================
@app.get("/api/state")
async def state() -> JSONResponse:
    sims = []
    for st in engine.sims.values():
        fam = family_for(st.device.role)
        sims.append({
            "serial": st.device.serial, "name": st.device.name, "role": st.device.role,
            "running": st.running, "scenario": st.scenario_key,
            "severity": st.severity(), "ack": st.last_ack,
            "payload": st.last_payload,
            "scenarios": [{"key": s.key, "label": s.label, "severity": s.severity}
                          for s in fam.values()],
        })
    now = time.time()
    reals = [
        {**rm, "stale": now - rm["last_seen"] > 20}
        for rm in real_monitors.values()
    ]
    return JSONResponse({
        "backendUp": fwd.backend_up,
        "backendUrl": cfg.backend_url,
        "queueDepth": fwd.queue_depth(),
        "txOk": fwd.tx_ok, "txFail": fwd.tx_fail,
        "sims": sims, "real": reals,
    })


@app.post("/api/sim/{serial}/scenario/{key}")
async def set_scenario(serial: str, key: str) -> JSONResponse:
    ok = engine.set_scenario(serial, key)
    return JSONResponse({"ok": ok}, status_code=200 if ok else 404)


@app.post("/api/sim/{serial}/toggle")
async def toggle(serial: str) -> JSONResponse:
    ok = engine.toggle(serial)
    return JSONResponse({"ok": ok}, status_code=200 if ok else 404)


# ====================================================================
#  WebSocket event stream → kiosk UI
# ====================================================================
@app.websocket("/ws")
async def ws(websocket: WebSocket) -> None:
    await websocket.accept()
    _ws_clients.add(websocket)
    try:
        while True:
            await websocket.receive_text()      # keepalive pings from the page
    except WebSocketDisconnect:
        _ws_clients.discard(websocket)


async def _ws_pump() -> None:
    while True:
        event = await _event_queue.get()
        dead = []
        for client in _ws_clients:
            try:
                await client.send_text(json.dumps(event))
            except Exception:
                dead.append(client)
        for d in dead:
            _ws_clients.discard(d)


# ====================================================================
#  Kiosk page
# ====================================================================
@app.get("/")
async def index() -> HTMLResponse:
    html = (Path(__file__).parent / "static" / "index.html").read_text(encoding="utf-8")
    return HTMLResponse(html)
