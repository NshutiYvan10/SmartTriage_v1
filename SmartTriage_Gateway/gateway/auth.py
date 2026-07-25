"""
Authentication for the kiosk dashboard.

Two ways to unlock the touchscreen:

  1. STAFF LOGIN — real SmartTriage credentials, proxied to the backend's
     /api/v1/auth/login. The JWTs stay SERVER-SIDE in the gateway process
     (the browser only ever holds an opaque gateway session cookie), and a
     background task refreshes the access token before it expires. The
     backend session is what powers the Registry tab.

  2. OFFLINE PIN — a gateway-local PIN, verified against a salted SHA-256
     hash in devices.yaml. Exists so the dashboard stays usable when the
     backend is unreachable — which is exactly when the offline-resilience
     demo needs the screen. A PIN session cannot read the registry (no
     backend identity), and the UI says so.

Security model (consistent with the rest of the gateway):
  - no secret ever reaches the browser: not device API keys, not JWTs;
  - sessions are opaque random tokens in process memory (a kiosk reboot
    logs everyone out — correct for a shared touchscreen);
  - PIN comparison is constant-time on the digest.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import secrets
import time
from dataclasses import dataclass, field
from typing import Optional

import httpx

LOGIN_PATH = "/api/v1/auth/login"
REFRESH_PATH = "/api/v1/auth/refresh"
DEVICES_PATH = "/api/v1/iot/devices/hospital/{hospital_id}"

SESSION_TTL_SECONDS = 12 * 3600          # one shift
ACCESS_REFRESH_MARGIN = 120              # refresh 2 min before expiry


def _jwt_exp(token: str) -> float:
    """Read the exp claim without verification — we only schedule refreshes
    with it; the backend remains the authority on validity."""
    try:
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        return float(json.loads(base64.urlsafe_b64decode(payload)).get("exp", 0))
    except Exception:
        return 0.0


@dataclass
class BackendIdentity:
    access_token: str
    refresh_token: str
    email: str
    display_name: str
    role: str
    hospital_id: str
    hospital_name: str

    def access_expires_at(self) -> float:
        return _jwt_exp(self.access_token)


@dataclass
class Session:
    token: str
    kind: str                            # "staff" | "pin"
    display_name: str
    role: str                            # backend role, or "KIOSK" for PIN
    created_at: float = field(default_factory=time.time)
    last_seen: float = field(default_factory=time.time)
    backend: Optional[BackendIdentity] = None

    def expired(self) -> bool:
        return time.time() - self.last_seen > SESSION_TTL_SECONDS


class AuthManager:
    """Owns kiosk sessions + at most one live backend identity.

    One backend identity (the most recent staff login) serves the whole
    kiosk: it is a shared bedside appliance, not a per-user workstation.
    """

    def __init__(self, backend_url: str,
                 pin_sha256: str = "", pin_salt: str = "", pin_plain: str = ""):
        self._base = backend_url
        self._pin_sha256 = (pin_sha256 or "").lower()
        self._pin_salt = pin_salt or ""
        self._pin_plain = pin_plain or ""          # dev convenience only
        self._client = httpx.AsyncClient(timeout=6.0)
        self._sessions: dict[str, Session] = {}
        self.backend_identity: Optional[BackendIdentity] = None

    # ---------------- login paths ----------------
    async def login_staff(self, email: str, password: str) -> tuple[Optional[Session], str]:
        """Returns (session, error). error = '' on success, else a
        human-readable reason ('backend-down' when unreachable so the UI
        can steer the user to the PIN)."""
        try:
            r = await self._client.post(self._base + LOGIN_PATH,
                                        json={"email": email, "password": password})
        except Exception:
            return None, "backend-down"
        if r.status_code != 200:
            try:
                msg = r.json().get("message") or "Login failed"
            except Exception:
                msg = "Login failed"
            return None, msg
        data = (r.json() or {}).get("data") or {}
        ident = BackendIdentity(
            access_token=str(data.get("accessToken", "")),
            refresh_token=str(data.get("refreshToken", "")),
            email=str(data.get("email", email)),
            display_name=(f"{data.get('firstName','')} {data.get('lastName','')}".strip()
                          or str(data.get("email", email))),
            role=str(data.get("role", "")),
            hospital_id=str(data.get("hospitalId", "")),
            hospital_name=str(data.get("hospitalName", "")),
        )
        if not ident.access_token:
            return None, "Login failed (no token in response)"
        self.backend_identity = ident
        return self._create("staff", ident.display_name, ident.role, ident), ""

    def login_pin(self, pin: str) -> Optional[Session]:
        pin = (pin or "").strip()
        if not pin:
            return None
        ok = False
        if self._pin_sha256:
            digest = hashlib.sha256((self._pin_salt + pin).encode()).hexdigest()
            ok = hmac.compare_digest(digest, self._pin_sha256)
        elif self._pin_plain:
            ok = hmac.compare_digest(pin, self._pin_plain)
        if not ok:
            return None
        return self._create("pin", "Kiosk operator", "KIOSK", None)

    def _create(self, kind: str, name: str, role: str,
                ident: Optional[BackendIdentity]) -> Session:
        token = secrets.token_urlsafe(32)
        s = Session(token=token, kind=kind, display_name=name, role=role, backend=ident)
        self._sessions[token] = s
        return s

    # ---------------- session checks ----------------
    def get(self, token: str | None) -> Optional[Session]:
        if not token:
            return None
        s = self._sessions.get(token)
        if not s:
            return None
        if s.expired():
            self._sessions.pop(token, None)
            return None
        s.last_seen = time.time()
        return s

    def logout(self, token: str | None) -> None:
        if token:
            self._sessions.pop(token, None)

    def pin_configured(self) -> bool:
        return bool(self._pin_sha256 or self._pin_plain)

    # ---------------- backend identity upkeep ----------------
    async def refresh_loop(self) -> None:
        """Keep the shared backend identity's access token fresh."""
        import asyncio
        while True:
            await asyncio.sleep(20)
            ident = self.backend_identity
            if not ident:
                continue
            if ident.access_expires_at() - time.time() > ACCESS_REFRESH_MARGIN:
                continue
            try:
                r = await self._client.post(self._base + REFRESH_PATH,
                                            json={"refreshToken": ident.refresh_token})
                if r.status_code == 200:
                    data = (r.json() or {}).get("data") or {}
                    tok = data.get("accessToken")
                    if tok:
                        ident.access_token = str(tok)
                # a failed refresh keeps the old token; registry calls will
                # surface the 401 and the UI will ask for a fresh login
            except Exception:
                pass

    # ---------------- backend data ----------------
    async def fetch_registry(self) -> tuple[Optional[list], str]:
        """Device registry for the identity's hospital.
        Returns (devices, error): error is '' on success, 'no-identity',
        'backend-down', 'unauthorized', or an http status message."""
        ident = self.backend_identity
        if not ident or not ident.hospital_id:
            return None, "no-identity"
        url = self._base + DEVICES_PATH.format(hospital_id=ident.hospital_id)
        try:
            r = await self._client.get(
                url, headers={"Authorization": f"Bearer {ident.access_token}"})
        except Exception:
            return None, "backend-down"
        if r.status_code == 401:
            return None, "unauthorized"
        if r.status_code != 200:
            return None, f"backend answered {r.status_code}"
        data = (r.json() or {}).get("data")
        # tolerate both shapes: bare list, or Spring Page {content: [...]}
        if isinstance(data, dict) and isinstance(data.get("content"), list):
            data = data["content"]
        if not isinstance(data, list):
            return None, "unexpected response shape"
        return data, ""
