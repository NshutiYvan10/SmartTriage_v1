"""
Forwarder — the gateway's single upstream mouth.

Everything leaving the Pi for SmartTriage goes through here:
  - simulated bedside/triage readings   → POST /api/v1/iot/stream/ingest
  - simulated paramedic telemetry       → POST /api/v1/iot/stream/device-telemetry
  - the REAL monitor's requests, passed through verbatim with the
    monitor's own X-Device-API-Key (the gateway never re-signs — identity
    stays per-device end to end).

Resilience: failed /ingest posts go to a SQLite store-and-forward queue
(capturedAt travels inside the payload, so late delivery keeps clinical
time). Telemetry snapshots are latest-value semantics — a stale snapshot
is worthless, so they are dropped, not queued. A background task drains
the queue oldest-first whenever the backend is healthy.
"""
from __future__ import annotations

import asyncio
import json
import sqlite3
import time
from typing import Callable

import httpx

INGEST = "/api/v1/iot/stream/ingest"
TELEMETRY = "/api/v1/iot/stream/device-telemetry"
HEARTBEAT = "/api/v1/iot/stream/heartbeat"


class Forwarder:
    def __init__(self, backend_url: str, queue_db: str, notify: Callable[[dict], None]):
        self._base = backend_url
        self._notify = notify
        self._client = httpx.AsyncClient(timeout=4.0)
        self._db = sqlite3.connect(queue_db)
        self._db.execute(
            "CREATE TABLE IF NOT EXISTS queue ("
            " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            " path TEXT NOT NULL, api_key TEXT NOT NULL, body TEXT NOT NULL,"
            " queued_at REAL NOT NULL)")
        self._db.commit()
        self.backend_up = False
        self.last_ack_at: float = 0.0
        self.tx_ok = 0
        self.tx_fail = 0

    # ---------------- outbound ----------------
    async def post(self, path: str, api_key: str, body: dict,
                   queue_on_failure: bool = True) -> str:
        """Returns 'ok' | 'idle' | 'queued' | 'failed'.

        'idle' = the backend answered but no monitoring session binds this
        device to a patient yet — the DESIGNED state for a bedside device
        before the ED assigns it. Not an error, never queued (the readings
        would be rejected again on replay)."""
        try:
            r = await self._client.post(
                self._base + path, json=body,
                headers={"X-Device-API-Key": api_key})
            compact = r.text.replace(" ", "")
            if r.status_code == 200 and '"accepted":true' in compact:
                self._mark_up()
                return "ok"
            if r.status_code == 200 and "Noactivemonitoringsession" in compact:
                self._link_ok()          # link healthy; device just not bound yet
                return "idle"
            # 401/400 = auth/contract problem — queueing would loop forever
            self.tx_fail += 1
            self._notify({"type": "reject", "path": path,
                          "status": r.status_code, "body": r.text[:120]})
            return "failed"
        except Exception:
            self.backend_up = False
            self.tx_fail += 1
            if queue_on_failure:
                self._enqueue(path, api_key, body)
                return "queued"
            return "failed"

    async def passthrough(self, path: str, api_key: str, raw_body: bytes) -> tuple[int, str]:
        """Real-monitor pass-through: same path, same key, byte-identical body.
        Returns (status_code, response_text); on outage the reading is queued
        and an accepted=true ack is synthesised so the monitor doesn't
        double-buffer what the gateway now guarantees to deliver."""
        try:
            r = await self._client.post(
                self._base + path, content=raw_body,
                headers={"X-Device-API-Key": api_key,
                         "Content-Type": "application/json"})
            if r.status_code == 200:
                self._mark_up()
            return r.status_code, r.text
        except Exception:
            self.backend_up = False
            if path == INGEST and raw_body:
                try:
                    self._enqueue(path, api_key, json.loads(raw_body))
                    return 200, json.dumps({"accepted": True, "gatewayQueued": True,
                                            "serverTimestamp": int(time.time() * 1000)})
                except Exception:
                    pass
            return 502, json.dumps({"accepted": False, "rejectionReason": "gateway: backend unreachable"})

    # ---------------- store & forward ----------------
    def _enqueue(self, path: str, api_key: str, body: dict) -> None:
        self._db.execute("INSERT INTO queue (path, api_key, body, queued_at) VALUES (?,?,?,?)",
                         (path, api_key, json.dumps(body), time.time()))
        self._db.commit()

    def queue_depth(self) -> int:
        return self._db.execute("SELECT COUNT(*) FROM queue").fetchone()[0]

    async def drain_loop(self) -> None:
        """Oldest-first, gentle (3 per second) so a long outage replays calmly."""
        while True:
            await asyncio.sleep(1.0)
            if not self.backend_up or self.queue_depth() == 0:
                continue
            row = self._db.execute(
                "SELECT id, path, api_key, body FROM queue ORDER BY id LIMIT 3").fetchall()
            for rid, path, key, body in row:
                status = await self.post(path, key, json.loads(body), queue_on_failure=False)
                if status != "ok":
                    break
                self._db.execute("DELETE FROM queue WHERE id = ?", (rid,))
                self._db.commit()
                self._notify({"type": "drain", "remaining": self.queue_depth()})

    async def health_loop(self) -> None:
        """Probe the backend when idle/down so 'link restored' is noticed
        even before the next reading is due."""
        while True:
            await asyncio.sleep(5.0)
            if self.backend_up and time.time() - self.last_ack_at < 30:
                continue
            try:
                r = await self._client.get(self._base + "/actuator/health", timeout=2.5)
                up = r.status_code < 500
            except Exception:
                try:
                    r = await self._client.get(self._base + "/", timeout=2.5)
                    up = r.status_code < 500
                except Exception:
                    up = False
            if up and not self.backend_up:
                self._notify({"type": "link", "up": True})
            if not up and self.backend_up:
                self._notify({"type": "link", "up": False})
            self.backend_up = up

    def _link_ok(self) -> None:
        if not self.backend_up:
            self._notify({"type": "link", "up": True})
        self.backend_up = True
        self.last_ack_at = time.time()

    def _mark_up(self) -> None:
        self._link_ok()
        self.tx_ok += 1
