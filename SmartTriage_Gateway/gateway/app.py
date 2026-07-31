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

from .auth import AuthManager, Session
from .config import GatewayConfig
from .forwarder import Forwarder, INGEST, TELEMETRY, HEARTBEAT, RFID_TAP
from .roaming import RoamingEngine, RoamingState
from .scenarios import family_for
from .simulator import SimulatorEngine, SimState

app = FastAPI(title="SmartTriage Gateway")

SESSION_COOKIE = "gw_session"

cfg: GatewayConfig
fwd: Forwarder
engine: SimulatorEngine
auth: AuthManager
roaming: RoamingEngine | None = None      # the General-zone spot-check cart, if configured
real_monitors: dict[str, dict] = {}       # serial → {name, last_seen, last_payload, ack}
#: serial → backend device UUID + status, refreshed from the registry. /monitoring/start
#: is keyed on the device's UUID, which only the backend knows, so the roaming cart
#: cannot open a check until it has been seen in the registry at least once.
device_ids: dict[str, dict] = {}
_ws_clients: set[WebSocket] = set()
_event_queue: asyncio.Queue = asyncio.Queue()


def _session_of(request: Request) -> Session | None:
    return auth.get(request.cookies.get(SESSION_COOKIE))


def _unauthorized() -> JSONResponse:
    return JSONResponse({"error": "unauthorized"}, status_code=401)


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
    global cfg, fwd, engine, auth, roaming
    cfg = GatewayConfig.load()
    fwd = Forwarder(cfg.backend_url, cfg.queue_db, notify)
    engine = SimulatorEngine(cfg.tx_interval_seconds, _send_sim, notify)
    auth = AuthManager(cfg.backend_url,
                       pin_sha256=cfg.kiosk_pin_sha256,
                       pin_salt=cfg.kiosk_pin_salt,
                       pin_plain=cfg.kiosk_pin,
                       gateway_api_key=cfg.gateway_api_key)

    for dev in cfg.devices:
        # The roaming cart is driven by its own engine: it is idle between
        # patients (heartbeat only) and streams only while a spot check is open,
        # which the free-running SimulatorEngine has no concept of.
        if dev.role == "roaming":
            if roaming is None:
                roaming = RoamingEngine(dev, cfg.tx_interval_seconds,
                                        _send_roaming, fwd.heartbeat, notify)
                asyncio.create_task(roaming.run())
            continue
        engine.add(dev)
        asyncio.create_task(engine.run(dev.serial))

    asyncio.create_task(fwd.drain_loop())
    asyncio.create_task(fwd.health_loop())
    asyncio.create_task(auth.refresh_loop())
    asyncio.create_task(_gateway_heartbeat())
    asyncio.create_task(_sim_heartbeats())
    asyncio.create_task(_registry_sync())
    asyncio.create_task(_ws_pump())


def _clinical_ctx(session: Session | None) -> tuple | None:
    """(backend_url, staff access token, hospital id) for THIS session, or None.

    Deliberately reads session.backend rather than auth.backend_identity: the
    AuthManager keeps one shared identity for the appliance, so the global would
    hand a PIN operator the last nurse's credentials — the spot check would be
    attributed to a clinician who never touched the cart, and the patient
    worklist would be readable behind the kiosk PIN. Opening a spot check is a
    clinical act, so it requires the operator's OWN staff login."""
    if session is None or session.kind != "staff":
        return None
    ident = session.backend
    if not ident or not ident.access_token or not ident.hospital_id:
        return None
    return cfg.backend_url, ident.access_token, ident.hospital_id


async def _sim_heartbeats() -> None:
    """Proof of life for every SIMULATED device, on its own key.

    Nothing did this before: a sim's ONLINE state was a side effect of whichever
    ingest path it used. /ingest stamps the heartbeat clock, so bedside and
    triage sims looked fine — but /device-telemetry did not, so the paramedic
    monitor read OFFLINE forever while streaming, and an idle roaming cart (which
    posts nothing at all between patients) would be demoted within ~60s and
    disappear from the nurse's monitor picker. Silent during the outage drill so
    a severed uplink still looks severed."""
    while True:
        if not fwd.forced_down:
            for dev in cfg.devices:
                # The roaming cart beats from inside its own loop, but only while
                # idle — mid-check its /ingest posts already prove it is alive.
                if dev.role == "roaming":
                    continue
                await fwd.heartbeat(dev.api_key)
        await asyncio.sleep(15)


async def _registry_sync() -> None:
    """Keep serial → backend device UUID + status fresh.

    The roaming cart needs its own UUID to open a spot check (/monitoring/start
    is keyed on deviceId) and the console shows the backend's view of its status,
    which is the thing that decides whether a nurse can pick it at all."""
    while True:
        devices, err = await auth.fetch_registry()
        if not err and devices:
            device_ids.clear()
            for d in devices:
                serial = str(d.get("serialNumber", ""))
                if serial:
                    device_ids[serial] = {"id": str(d.get("id", "")),
                                          "status": str(d.get("status", ""))}
        await asyncio.sleep(20)


async def _send_roaming(st: RoamingState, payload: dict) -> str:
    """Roaming readings are visit-bound observations — queue them if the uplink
    drops, exactly like a bedside reading, since capturedAt travels inside."""
    status = await fwd.post(INGEST, st.device.api_key, payload, queue_on_failure=True)
    notify({"type": "tx", "source": st.device.name, "serial": st.device.serial,
            "role": "roaming", "scenario": st.scenario_key,
            "severity": st.severity(), "ack": status, "payload": payload})
    return status


async def _gateway_heartbeat() -> None:
    """The gateway is a first-class device (V114): it heartbeats with its
    OWN key so the hospital admin's registry shows the Pi itself ONLINE —
    and so revoking the gateway is visible within one heartbeat period.
    Silent during the outage drill (a severed uplink must look severed)."""
    import httpx
    if not cfg.gateway_api_key:
        return
    client = httpx.AsyncClient(timeout=4.0)
    while True:
        if not fwd.forced_down:
            try:
                await client.post(cfg.backend_url + HEARTBEAT,
                                  headers={"X-Device-API-Key": cfg.gateway_api_key})
            except Exception:
                pass
        await asyncio.sleep(15)


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


@app.post(RFID_TAP)
async def rfid_tap(request: Request) -> Response:
    """RFID desk-reader pass-through — so the reader can live on the Pi's
    own WiFi network with a NEVER-CHANGING backend address (10.42.0.1),
    like the monitor. Verbatim proxy with the reader's own key; a tap is
    interactive, so an outage answers 502 immediately — a stale queued tap
    replayed minutes later would open the wrong patient at the desk."""
    api_key = request.headers.get("X-Device-API-Key", "")
    raw = await request.body()
    status, text = await fwd.passthrough(RFID_TAP, api_key, raw)
    return Response(content=text, status_code=status, media_type="application/json")


# ====================================================================
#  Kiosk auth
# ====================================================================
@app.post("/kiosk/api/login")
async def login(request: Request) -> JSONResponse:
    body = await request.json()
    mode = str(body.get("mode", "staff"))
    if mode == "pin":
        session = auth.login_pin(str(body.get("pin", "")))
        if not session:
            return JSONResponse({"error": "Wrong PIN"}, status_code=401)
        err = ""
    else:
        session, err = await auth.login_staff(
            str(body.get("email", "")).strip(), str(body.get("password", "")))
        if not session:
            code = 503 if err == "backend-down" else 401
            return JSONResponse({"error": err}, status_code=code)
    resp = JSONResponse({
        "name": session.display_name, "role": session.role, "kind": session.kind,
        "hospital": session.backend.hospital_name if session.backend else "",
    })
    resp.set_cookie(SESSION_COOKIE, session.token, httponly=True, samesite="lax")
    return resp


@app.post("/kiosk/api/logout")
async def logout(request: Request) -> JSONResponse:
    auth.logout(request.cookies.get(SESSION_COOKIE))
    resp = JSONResponse({"ok": True})
    resp.delete_cookie(SESSION_COOKIE)
    return resp


@app.get("/kiosk/api/me")
async def me(request: Request) -> JSONResponse:
    s = _session_of(request)
    if not s:
        return JSONResponse({"authenticated": False,
                             "pinConfigured": auth.pin_configured(),
                             "gatewayName": cfg.gateway_name})
    return JSONResponse({
        "authenticated": True, "name": s.display_name, "role": s.role,
        "kind": s.kind, "gatewayName": cfg.gateway_name,
        "hospital": s.backend.hospital_name if s.backend else "",
        "registryAvailable": auth.gateway_key_configured() or auth.backend_identity is not None,
    })


# ====================================================================
#  Kiosk control API (session-protected; device pass-through is NOT
#  behind this — devices authenticate with their own API keys)
# ====================================================================
@app.get("/kiosk/api/state")
async def state(request: Request) -> JSONResponse:
    if not _session_of(request):
        return _unauthorized()
    sims = []
    for st in engine.sims.values():
        fam = family_for(st.device.role)
        sims.append({
            "serial": st.device.serial, "name": st.device.name, "role": st.device.role,
            "running": st.running, "scenario": st.scenario_key,
            "severity": st.severity(), "ack": st.last_ack,
            "payload": st.last_payload,
            "scenarios": [{"key": s.key, "label": s.label, "severity": s.severity,
                           "note": s.note}
                          for s in fam.values()],
        })
    now = time.time()
    reals = [
        {**rm, "stale": now - rm["last_seen"] > 20}
        for rm in real_monitors.values()
    ]
    oldest = fwd.queue_oldest_age()
    reg = device_ids.get(roaming.st.device.serial, {}) if roaming else {}
    session = _session_of(request)
    return JSONResponse({
        "backendUp": fwd.backend_up,
        "backendUrl": cfg.backend_url,
        "syncState": fwd.sync_state(),          # offline | syncing | synced
        "forcedDown": fwd.forced_down,
        "queueDepth": fwd.queue_depth(),
        "queueOldestSeconds": oldest,
        "queuePreview": fwd.queue_preview(),
        "drainedTotal": fwd.drained_total,
        "txOk": fwd.tx_ok, "txFail": fwd.tx_fail,
        "sims": sims, "real": reals,
        "roaming": roaming.snapshot(reg.get("id", ""), reg.get("status", ""),
                                    _clinical_ctx(session) is not None)
                   if roaming else None,
    })


# ── Roaming cart (General-zone spot checks) ──
@app.post("/kiosk/api/roaming/worklist")
async def roaming_worklist(request: Request) -> JSONResponse:
    """Refresh the list of patients due a recheck, in the cart's zone."""
    session = _session_of(request)
    if not session:
        return _unauthorized()
    if not roaming:
        return JSONResponse({"error": "no roaming cart configured"}, status_code=404)
    body = {}
    try:
        body = await request.json()
    except Exception:
        pass
    err = await roaming.refresh_worklist(_clinical_ctx(session),
                                        str(body.get("zone", "")))
    return JSONResponse({"ok": not err, "error": err})


@app.post("/kiosk/api/roaming/start")
async def roaming_start(request: Request) -> JSONResponse:
    """Wheel the cart to a patient: open a SPOT_CHECK session on their visit."""
    session = _session_of(request)
    if not session:
        return _unauthorized()
    if not roaming:
        return JSONResponse({"error": "no roaming cart configured"}, status_code=404)
    body = await request.json()
    visit_id = str(body.get("visitId", ""))
    if not visit_id:
        return JSONResponse({"ok": False, "error": "visitId required"}, status_code=400)
    reg = device_ids.get(roaming.st.device.serial, {})
    ok, err = await roaming.start_check(_clinical_ctx(session), visit_id,
                                       reg.get("id", ""))
    return JSONResponse({"ok": ok, "error": err}, status_code=200 if ok else 409)


@app.post("/kiosk/api/roaming/cancel")
async def roaming_cancel(request: Request) -> JSONResponse:
    session = _session_of(request)
    if not session:
        return _unauthorized()
    if not roaming:
        return JSONResponse({"error": "no roaming cart configured"}, status_code=404)
    ok, err = await roaming.cancel_check(_clinical_ctx(session))
    return JSONResponse({"ok": ok, "error": err})


@app.post("/kiosk/api/roaming/scenario/{key}")
async def roaming_scenario(request: Request, key: str) -> JSONResponse:
    """Choose what the cart will FIND at the next patient."""
    if not _session_of(request):
        return _unauthorized()
    if not roaming:
        return JSONResponse({"error": "no roaming cart configured"}, status_code=404)
    ok = roaming.set_scenario(key)
    return JSONResponse({"ok": ok}, status_code=200 if ok else 404)


@app.post("/kiosk/api/sim/{serial}/scenario/{key}")
async def set_scenario(request: Request, serial: str, key: str) -> JSONResponse:
    if not _session_of(request):
        return _unauthorized()
    ok = engine.set_scenario(serial, key)
    return JSONResponse({"ok": ok}, status_code=200 if ok else 404)


@app.post("/kiosk/api/sim/{serial}/toggle")
async def toggle(request: Request, serial: str) -> JSONResponse:
    if not _session_of(request):
        return _unauthorized()
    ok = engine.toggle(serial)
    return JSONResponse({"ok": ok}, status_code=200 if ok else 404)


@app.post("/kiosk/api/outage/{state_}")
async def outage(request: Request, state_: str) -> JSONResponse:
    """Demo outage drill: 'on' severs the uplink (readings queue), 'off'
    restores it (queue drains). Purely gateway-local."""
    if not _session_of(request):
        return _unauthorized()
    fwd.set_forced_down(state_ == "on")
    return JSONResponse({"ok": True, "forcedDown": fwd.forced_down})


@app.get("/kiosk/api/registry")
async def registry(request: Request) -> JSONResponse:
    """Backend device registry for the logged-in identity's hospital,
    enriched with what the gateway knows locally. API keys NEVER leave
    the server side un-masked."""
    s = _session_of(request)
    if not s:
        return _unauthorized()
    devices, err = await auth.fetch_registry()
    if err:
        return JSONResponse({"error": err}, status_code=200)

    local = {d.serial: d for d in cfg.devices}
    seen_real = set(real_monitors.keys())
    rows = []
    for d in devices:
        serial = str(d.get("serialNumber", ""))
        key = str(d.get("apiKey") or (local[serial].api_key if serial in local else ""))
        rows.append({
            "serial": serial,
            "name": d.get("deviceName"),
            "type": d.get("deviceType"),
            "status": d.get("status"),
            "lastHeartbeatAt": d.get("lastHeartbeatAt"),
            "lastDataAt": d.get("lastDataAt"),
            "battery": d.get("batteryLevel"),
            "wifiRssi": d.get("wifiRssi"),
            "firmware": d.get("firmwareVersion"),
            "inService": d.get("inService"),
            "activeVisit": bool(d.get("activeVisitId")),
            "keyMasked": (key[:7] + "…" + key[-4:]) if len(key) > 12 else ("set" if key else ""),
            "gatewaySim": serial in local,
            "seenLive": serial in seen_real,
        })
    return JSONResponse({"devices": rows})


# ====================================================================
#  WebSocket event stream → kiosk UI
# ====================================================================
@app.websocket("/ws")
async def ws(websocket: WebSocket) -> None:
    # same session gate as the REST API — the event stream carries vitals
    if not auth.get(websocket.cookies.get(SESSION_COOKIE)):
        await websocket.close(code=4401)
        return
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
