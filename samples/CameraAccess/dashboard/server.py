#!/usr/bin/env python3
"""Local command-centre dashboard server for CameraAccess."""

from __future__ import annotations

import argparse
import json
import os
import re
import sqlite3
import subprocess
import threading
import time
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlencode, urlparse
from urllib.request import Request, urlopen

LOCK = threading.Lock()
FLIGHTS_API_URL = "https://api.cas.certispsb.net/api-ext/v1/flights/departure/list"
OPENSKY_STATES_URL = "https://opensky-network.org/api/states/all"
OPENSKY_TOKEN_URL = (
    "https://auth.opensky-network.org/auth/realms/opensky-network/"
    "protocol/openid-connect/token"
)
CAS_API_KEY = os.getenv("CAS_API_KEY", "").strip()
OPENSKY_CLIENT_ID = os.getenv("OPENSKY_CLIENT_ID", "").strip()
OPENSKY_CLIENT_SECRET = os.getenv("OPENSKY_CLIENT_SECRET", "").strip()
ADMIN_TOKEN = os.getenv("DASHBOARD_ADMIN_TOKEN", "").strip()
FLIGHTS_CACHE: dict[str, dict[str, Any]] = {}
OPENSKY_CACHE: dict[str, dict[str, Any]] = {}
CAS_THROTTLED_UNTIL = 0.0
DB_CONN: sqlite3.Connection | None = None
OPENSKY_TOKEN: str | None = None
OPENSKY_TOKEN_EXPIRES_AT = 0.0
PTT_ADB_SERIAL = os.getenv("PTT_ADB_SERIAL", "").strip()
PTT_ANDROID_COMPONENT = os.getenv(
    "PTT_ANDROID_COMPONENT",
    "com.certis.kerbside/com.meta.wearable.dat.externalsampleapps.cameraaccess.MainActivity",
).strip()


def _read_int_env(name: str, default: int) -> int:
    raw = os.getenv(name, "").strip()
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError:
        return default
    return max(0, value)


CAS_CACHE_TTL_SECONDS = _read_int_env("CAS_CACHE_TTL_SECONDS", 45)
CAS_THROTTLE_BACKOFF_SECONDS = _read_int_env("CAS_THROTTLE_BACKOFF_SECONDS", 45)
EVENTS_MAXLEN = _read_int_env("EVENTS_MAXLEN", 0)
OPENSKY_CACHE_TTL_SECONDS = _read_int_env("OPENSKY_CACHE_TTL_SECONDS", 30)
OPENSKY_TIMEOUT_SECONDS = _read_int_env("OPENSKY_TIMEOUT_SECONDS", 15)
OPENSKY_POLL_ANON_MS = _read_int_env("OPENSKY_POLL_ANON_MS", 240_000)
OPENSKY_POLL_AUTH_MS = _read_int_env("OPENSKY_POLL_AUTH_MS", 30_000)


def _events_db_path() -> Path:
    raw = os.getenv("EVENTS_DB_PATH", "").strip()
    if raw:
        return Path(raw).expanduser()
    return Path(__file__).with_name("events.db")


def init_events_db() -> None:
    global DB_CONN
    path = _events_db_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            received_at_iso TEXT NOT NULL,
            payload_json TEXT NOT NULL
        )
        """
    )
    conn.commit()
    DB_CONN = conn


def _db() -> sqlite3.Connection:
    if DB_CONN is None:
        raise RuntimeError("Events database not initialized")
    return DB_CONN


def utc_iso_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def add_event(payload: dict[str, Any]) -> dict[str, Any]:
    received_at_iso = utc_iso_now()
    payload_json = json.dumps(payload, ensure_ascii=True, separators=(",", ":"))

    with LOCK:
        conn = _db()
        cursor = conn.execute(
            "INSERT INTO events (received_at_iso, payload_json) VALUES (?, ?)",
            (received_at_iso, payload_json),
        )
        if EVENTS_MAXLEN > 0:
            conn.execute(
                """
                DELETE FROM events
                WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT ?)
                """,
                (EVENTS_MAXLEN,),
            )
        conn.commit()

    event = {
        "id": int(cursor.lastrowid),
        "receivedAtIso": received_at_iso,
        **payload,
    }
    return event


def list_events(since: int | None, limit: int) -> list[dict[str, Any]]:
    since_id = 0 if since is None else max(since, 0)
    if limit < 1:
        return []

    with LOCK:
        conn = _db()
        rows = conn.execute(
            """
            SELECT id, received_at_iso, payload_json
            FROM events
            WHERE id > ?
            ORDER BY id DESC
            LIMIT ?
            """,
            (since_id, limit),
        ).fetchall()

    items: list[dict[str, Any]] = []
    for row in reversed(rows):
        try:
            payload = json.loads(row["payload_json"])
        except json.JSONDecodeError:
            payload = {"rawPayload": row["payload_json"]}
        if not isinstance(payload, dict):
            payload = {"payload": payload}
        items.append(
            {
                "id": int(row["id"]),
                "receivedAtIso": row["received_at_iso"],
                **payload,
            }
        )

    return items


def count_events() -> int:
    with LOCK:
        conn = _db()
        row = conn.execute("SELECT COUNT(*) AS count FROM events").fetchone()
    return int(row["count"]) if row is not None else 0


def clear_events() -> int:
    with LOCK:
        conn = _db()
        row = conn.execute("SELECT COUNT(*) AS count FROM events").fetchone()
        deleted = int(row["count"]) if row is not None else 0
        conn.execute("DELETE FROM events")
        conn.commit()
    return deleted


def _cache_get(cache: dict[str, dict[str, Any]], key: str, allow_stale: bool = False) -> tuple[dict[str, Any] | None, bool]:
    now = time.time()
    with LOCK:
        entry = cache.get(key)
    if not entry:
        return None, False

    expires_at = float(entry.get("expiresAtEpoch", 0.0))
    payload = entry.get("payload")
    if not isinstance(payload, dict):
        return None, False

    stale = now > expires_at
    if stale and not allow_stale:
        return None, True
    return payload, stale


def _cache_set(cache: dict[str, dict[str, Any]], key: str, payload: dict[str, Any], ttl_seconds: int) -> None:
    if ttl_seconds <= 0:
        return
    with LOCK:
        cache[key] = {
            "expiresAtEpoch": time.time() + ttl_seconds,
            "payload": payload,
        }


def _is_cas_throttled() -> bool:
    with LOCK:
        return time.time() < CAS_THROTTLED_UNTIL


def _mark_cas_throttled() -> None:
    global CAS_THROTTLED_UNTIL
    with LOCK:
        CAS_THROTTLED_UNTIL = max(CAS_THROTTLED_UNTIL, time.time() + CAS_THROTTLE_BACKOFF_SECONDS)


def _clear_cas_throttle() -> None:
    global CAS_THROTTLED_UNTIL
    with LOCK:
        CAS_THROTTLED_UNTIL = 0.0


def fetch_departures(date: str, flight_type: str, flight_no: str) -> tuple[int, dict[str, Any]]:
    if not CAS_API_KEY:
        return HTTPStatus.BAD_REQUEST, {
            "ok": False,
            "error": "Missing CAS_API_KEY. Set env var CAS_API_KEY before starting server.",
        }

    cache_key = f"{date}|{flight_type}|{flight_no}"
    cached_payload, _ = _cache_get(FLIGHTS_CACHE, cache_key, allow_stale=False)
    if cached_payload is not None:
        payload = dict(cached_payload)
        payload["cache"] = {"hit": True, "stale": False}
        return HTTPStatus.OK, payload

    if _is_cas_throttled():
        stale_payload, stale = _cache_get(FLIGHTS_CACHE, cache_key, allow_stale=True)
        if stale_payload is not None and stale:
            payload = dict(stale_payload)
            payload["cache"] = {"hit": True, "stale": True}
            payload["warning"] = "CAS API is temporarily throttled. Showing cached data."
            return HTTPStatus.OK, payload
        return HTTPStatus.TOO_MANY_REQUESTS, {
            "ok": False,
            "error": "CAS API is temporarily throttled. Retry shortly.",
        }

    query = urlencode({"date": date, "type": flight_type, "flightno": flight_no})
    url = f"{FLIGHTS_API_URL}?{query}"
    request = Request(
        url=url,
        method="GET",
        headers={
            "Accept": "application/json, text/plain, */*",
            "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X) CameraAccessDashboard/1.0",
            "x-api-key": CAS_API_KEY,
        },
    )
    try:
        with urlopen(request, timeout=20) as response:
            body = response.read().decode("utf-8")
            payload = json.loads(body)
            result = {
                "ok": True,
                "query": {"date": date, "type": flight_type, "flightno": flight_no},
                "data": payload,
            }
            _cache_set(FLIGHTS_CACHE, cache_key, result, ttl_seconds=CAS_CACHE_TTL_SECONDS)
            _clear_cas_throttle()
            return HTTPStatus.OK, result
    except HTTPError as err:
        body = err.read().decode("utf-8", errors="replace")
        if err.code in {HTTPStatus.FORBIDDEN, HTTPStatus.TOO_MANY_REQUESTS}:
            _mark_cas_throttled()
            stale_payload, stale = _cache_get(FLIGHTS_CACHE, cache_key, allow_stale=True)
            if stale_payload is not None and stale:
                payload = dict(stale_payload)
                payload["cache"] = {"hit": True, "stale": True}
                payload["warning"] = "CAS API is throttled/forbidden. Showing cached data."
                return HTTPStatus.OK, payload
        return err.code, {"ok": False, "error": "Upstream HTTP error", "status": err.code, "body": body}
    except URLError as err:
        return HTTPStatus.BAD_GATEWAY, {"ok": False, "error": f"Upstream connection failed: {err.reason}"}
    except json.JSONDecodeError:
        return HTTPStatus.BAD_GATEWAY, {"ok": False, "error": "Upstream returned non-JSON payload"}


def _to_float(value: str | None, fallback: float) -> float:
    if value is None:
        return fallback
    try:
        return float(value)
    except ValueError:
        return fallback


def _opensky_poll_ms(auth_mode: str) -> int:
    if auth_mode == "oauth2":
        return max(10_000, OPENSKY_POLL_AUTH_MS)
    return max(30_000, OPENSKY_POLL_ANON_MS)


def _fetch_opensky_token() -> str:
    global OPENSKY_TOKEN, OPENSKY_TOKEN_EXPIRES_AT
    if not OPENSKY_CLIENT_ID or not OPENSKY_CLIENT_SECRET:
        raise RuntimeError("OpenSky credentials are not configured")

    now = time.time()
    with LOCK:
        if OPENSKY_TOKEN and now < OPENSKY_TOKEN_EXPIRES_AT - 20:
            return OPENSKY_TOKEN

    payload = urlencode(
        {
            "grant_type": "client_credentials",
            "client_id": OPENSKY_CLIENT_ID,
            "client_secret": OPENSKY_CLIENT_SECRET,
        }
    ).encode("utf-8")

    req = Request(
        url=OPENSKY_TOKEN_URL,
        method="POST",
        data=payload,
        headers={
            "Content-Type": "application/x-www-form-urlencoded",
            "Accept": "application/json",
            "User-Agent": "CameraAccessDashboard/1.0",
        },
    )

    with urlopen(req, timeout=OPENSKY_TIMEOUT_SECONDS) as response:
        body = response.read().decode("utf-8")
    data = json.loads(body)
    token = str(data.get("access_token", "")).strip()
    expires_in = int(data.get("expires_in", 0) or 0)
    if not token or expires_in <= 0:
        raise RuntimeError("OpenSky token response missing access_token/expires_in")

    with LOCK:
        OPENSKY_TOKEN = token
        OPENSKY_TOKEN_EXPIRES_AT = time.time() + expires_in
    return token


def _parse_opensky_state(row: Any) -> dict[str, Any] | None:
    if not isinstance(row, list):
        return None

    def at(index: int) -> Any:
        return row[index] if index < len(row) else None

    callsign_raw = at(1)
    callsign = callsign_raw.strip() if isinstance(callsign_raw, str) else None
    if callsign == "":
        callsign = None

    return {
        "icao24": at(0),
        "callsign": callsign,
        "originCountry": at(2),
        "longitude": at(5),
        "latitude": at(6),
        "baroAltitude": at(7),
        "onGround": at(8),
        "velocity": at(9),
        "trueTrack": at(10),
        "verticalRate": at(11),
        "geoAltitude": at(13),
        "squawk": at(14),
        "positionSource": at(16),
        "category": at(17),
        "lastContact": at(4),
    }


def fetch_opensky_states(
    lamin: float,
    lomin: float,
    lamax: float,
    lomax: float,
    limit: int,
) -> tuple[int, dict[str, Any]]:
    min_lat, max_lat = sorted((lamin, lamax))
    min_lon, max_lon = sorted((lomin, lomax))
    max_items = min(max(limit, 1), 250)
    cache_key = f"{min_lat:.4f}|{min_lon:.4f}|{max_lat:.4f}|{max_lon:.4f}|{max_items}"

    cached_payload, _ = _cache_get(OPENSKY_CACHE, cache_key, allow_stale=False)
    if cached_payload is not None:
        payload = dict(cached_payload)
        payload["cache"] = {"hit": True, "stale": False}
        return HTTPStatus.OK, payload

    def stale_opensky_payload(message: str) -> tuple[int, dict[str, Any]] | None:
        stale_payload, stale = _cache_get(OPENSKY_CACHE, cache_key, allow_stale=True)
        if stale_payload is None or not stale:
            return None
        payload = dict(stale_payload)
        payload["cache"] = {"hit": True, "stale": True}
        payload["warning"] = message
        return HTTPStatus.OK, payload

    params = {
        "lamin": f"{min_lat:.4f}",
        "lomin": f"{min_lon:.4f}",
        "lamax": f"{max_lat:.4f}",
        "lomax": f"{max_lon:.4f}",
        "extended": "1",
    }
    url = f"{OPENSKY_STATES_URL}?{urlencode(params)}"
    base_headers = {
        "Accept": "application/json",
        "User-Agent": "CameraAccessDashboard/1.0",
    }

    auth_mode = "anonymous"
    warning: str | None = None
    request_headers = dict(base_headers)
    if OPENSKY_CLIENT_ID and OPENSKY_CLIENT_SECRET:
        try:
            token = _fetch_opensky_token()
            request_headers["Authorization"] = f"Bearer {token}"
            auth_mode = "oauth2"
        except (RuntimeError, HTTPError, URLError, json.JSONDecodeError) as err:
            warning = f"OpenSky OAuth unavailable. Falling back to anonymous mode: {err}"

    try:
        req = Request(url=url, method="GET", headers=request_headers)
        with urlopen(req, timeout=OPENSKY_TIMEOUT_SECONDS) as response:
            body = response.read().decode("utf-8")
            response_headers = {k.lower(): v for k, v in response.headers.items()}
    except HTTPError as err:
        body = err.read().decode("utf-8", errors="replace")
        if auth_mode == "oauth2" and err.code in {HTTPStatus.UNAUTHORIZED, HTTPStatus.FORBIDDEN}:
            try:
                req = Request(url=url, method="GET", headers=base_headers)
                with urlopen(req, timeout=OPENSKY_TIMEOUT_SECONDS) as response:
                    body = response.read().decode("utf-8")
                    response_headers = {k.lower(): v for k, v in response.headers.items()}
                auth_mode = "anonymous"
                warning = "OpenSky OAuth rejected. Fell back to anonymous mode."
            except HTTPError as fallback_err:
                fallback_body = fallback_err.read().decode("utf-8", errors="replace")
                stale_result = stale_opensky_payload("OpenSky is unavailable. Showing cached airspace data.")
                if stale_result is not None:
                    return stale_result
                return fallback_err.code, {
                    "ok": False,
                    "error": "OpenSky request failed",
                    "status": fallback_err.code,
                    "body": fallback_body,
                }
            except URLError as fallback_err:
                stale_result = stale_opensky_payload("OpenSky timed out. Showing cached airspace data.")
                if stale_result is not None:
                    return stale_result
                return HTTPStatus.BAD_GATEWAY, {
                    "ok": False,
                    "error": f"OpenSky connection failed: {fallback_err.reason}",
                }
        else:
            stale_result = stale_opensky_payload("OpenSky request failed. Showing cached airspace data.")
            if stale_result is not None:
                return stale_result
            return err.code, {
                "ok": False,
                "error": "OpenSky request failed",
                "status": err.code,
                "body": body,
            }
    except URLError as err:
        stale_result = stale_opensky_payload("OpenSky timed out. Showing cached airspace data.")
        if stale_result is not None:
            return stale_result
        return HTTPStatus.BAD_GATEWAY, {"ok": False, "error": f"OpenSky connection failed: {err.reason}"}

    try:
        root = json.loads(body)
    except json.JSONDecodeError:
        stale_result = stale_opensky_payload("OpenSky returned invalid data. Showing cached airspace data.")
        if stale_result is not None:
            return stale_result
        return HTTPStatus.BAD_GATEWAY, {"ok": False, "error": "OpenSky returned non-JSON payload"}

    raw_states = root.get("states")
    if raw_states is None:
        raw_states = []
    if not isinstance(raw_states, list):
        stale_result = stale_opensky_payload("OpenSky returned invalid state data. Showing cached airspace data.")
        if stale_result is not None:
            return stale_result
        return HTTPStatus.BAD_GATEWAY, {"ok": False, "error": "OpenSky payload missing states list"}

    parsed_states: list[dict[str, Any]] = []
    for row in raw_states:
        item = _parse_opensky_state(row)
        if item is None:
            continue
        lat = item.get("latitude")
        lon = item.get("longitude")
        if isinstance(lat, (int, float)) and isinstance(lon, (int, float)):
            parsed_states.append(item)
        if len(parsed_states) >= max_items:
            break

    payload = {
        "ok": True,
        "source": "opensky-network",
        "authMode": auth_mode,
        "recommendedPollMs": _opensky_poll_ms(auth_mode),
        "fetchedAtEpochMs": int(time.time() * 1000),
        "time": root.get("time"),
        "bbox": {
            "lamin": min_lat,
            "lomin": min_lon,
            "lamax": max_lat,
            "lomax": max_lon,
        },
        "count": len(parsed_states),
        "rawCount": len(raw_states),
        "states": parsed_states,
        "rateLimitRemaining": response_headers.get("x-rate-limit-remaining"),
        "rateLimitRetryAfterSeconds": response_headers.get("x-rate-limit-retry-after-seconds"),
    }
    if warning:
        payload["warning"] = warning

    _cache_set(OPENSKY_CACHE, cache_key, payload, ttl_seconds=OPENSKY_CACHE_TTL_SECONDS)
    return HTTPStatus.OK, payload


def _adb_connected_devices() -> list[str]:
    result = subprocess.run(
        ["adb", "devices"],
        check=False,
        capture_output=True,
        text=True,
        timeout=8,
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip() or "adb devices failed")

    serials: list[str] = []
    for line in result.stdout.splitlines()[1:]:
        entry = line.strip()
        if not entry:
            continue
        parts = entry.split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.append(parts[0])
    return serials


def trigger_phone_ptt() -> tuple[int, dict[str, Any]]:
    try:
        serials = _adb_connected_devices()
    except FileNotFoundError:
        return HTTPStatus.SERVICE_UNAVAILABLE, {
            "ok": False,
            "error": "adb is not installed on this host.",
        }
    except subprocess.TimeoutExpired:
        return HTTPStatus.GATEWAY_TIMEOUT, {
            "ok": False,
            "error": "adb devices timed out.",
        }
    except RuntimeError as err:
        return HTTPStatus.BAD_GATEWAY, {"ok": False, "error": str(err)}

    target_serial = PTT_ADB_SERIAL
    if target_serial:
        if target_serial not in serials:
            return HTTPStatus.BAD_REQUEST, {
                "ok": False,
                "error": f"Configured PTT_ADB_SERIAL '{target_serial}' is not connected.",
                "connected": serials,
            }
    else:
        if not serials:
            return HTTPStatus.SERVICE_UNAVAILABLE, {
                "ok": False,
                "error": "No Android device connected over adb.",
            }
        if len(serials) > 1:
            return HTTPStatus.CONFLICT, {
                "ok": False,
                "error": "Multiple adb devices connected. Set PTT_ADB_SERIAL.",
                "connected": serials,
            }
        target_serial = serials[0]

    cmd = [
        "adb",
        "-s",
        target_serial,
        "shell",
        "am",
        "start",
        "-W",
        "-a",
        "android.intent.action.VIEW",
        "-d",
        "cameraaccess://ptt",
        "-n",
        PTT_ANDROID_COMPONENT,
    ]
    try:
        result = subprocess.run(
            cmd,
            check=False,
            capture_output=True,
            text=True,
            timeout=12,
        )
    except subprocess.TimeoutExpired:
        return HTTPStatus.GATEWAY_TIMEOUT, {
            "ok": False,
            "error": "adb launch timed out.",
            "serial": target_serial,
        }

    if result.returncode != 0:
        return HTTPStatus.BAD_GATEWAY, {
            "ok": False,
            "error": result.stderr.strip() or result.stdout.strip() or "adb launch failed",
            "serial": target_serial,
        }

    return HTTPStatus.OK, {
        "ok": True,
        "message": "PTT launched on phone.",
        "serial": target_serial,
        "component": PTT_ANDROID_COMPONENT,
        "adbOutput": result.stdout.strip(),
    }


def load_gates_snapshot() -> tuple[int, dict[str, Any]]:
    gates_path = Path(__file__).with_name("gates20.json")
    if not gates_path.exists():
        return HTTPStatus.NOT_FOUND, {"ok": False, "error": "Missing gates20.json"}
    try:
        payload = json.loads(gates_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return HTTPStatus.INTERNAL_SERVER_ERROR, {"ok": False, "error": "Invalid gates20.json"}
    if not isinstance(payload, dict):
        return HTTPStatus.INTERNAL_SERVER_ERROR, {"ok": False, "error": "Invalid gates payload format"}
    payload.setdefault("ok", True)
    return HTTPStatus.OK, payload


def _extract_gate_from_payload(payload: dict[str, Any]) -> str | None:
    gate = payload.get("gate")
    if isinstance(gate, str) and gate.strip():
        return gate.strip().upper()
    question = payload.get("question")
    if not isinstance(question, str):
        return None
    match = re.search(r"\\b([A-Z]\\d{1,2}[A-Z]?)\\b", question.upper())
    return match.group(1) if match else None


def list_latest_gate_events(limit: int = 500) -> dict[str, dict[str, Any]]:
    events = list_events(since=None, limit=max(1, limit))
    latest: dict[str, dict[str, Any]] = {}
    for event in reversed(events):
        gate = _extract_gate_from_payload(event)
        if not gate:
            continue
        if gate not in latest:
            latest[gate] = event
    return latest


class DashboardHandler(BaseHTTPRequestHandler):
    server_version = "CameraAccessDashboard/1.0"

    def log_message(self, fmt: str, *args: Any) -> None:
        print(f"[{self.log_date_time_string()}] {self.address_string()} {fmt % args}")

    def end_headers(self) -> None:
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def _send_json(self, payload: dict[str, Any], status: int = HTTPStatus.OK) -> None:
        body = json.dumps(payload, ensure_ascii=True).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_text(self, body: bytes, content_type: str, status: int = HTTPStatus.OK) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self) -> None:
        self.send_response(HTTPStatus.NO_CONTENT)
        self.end_headers()

    def do_GET(self) -> None:
        parsed = urlparse(self.path)

        if parsed.path in {"/", "/index.html"}:
            index_path = Path(__file__).with_name("index.html")
            if not index_path.exists():
                self._send_json({"error": "Missing index.html"}, status=HTTPStatus.INTERNAL_SERVER_ERROR)
                return
            html = index_path.read_bytes()
            self._send_text(html, "text/html; charset=utf-8")
            return

        if parsed.path in {"/flights", "/flights.html"}:
            flights_path = Path(__file__).with_name("flights.html")
            if not flights_path.exists():
                self._send_json({"error": "Missing flights.html"}, status=HTTPStatus.INTERNAL_SERVER_ERROR)
                return
            html = flights_path.read_bytes()
            self._send_text(html, "text/html; charset=utf-8")
            return

        if parsed.path in {"/gates", "/gates.html"}:
            gates_path = Path(__file__).with_name("gates.html")
            if not gates_path.exists():
                self._send_json({"error": "Missing gates.html"}, status=HTTPStatus.INTERNAL_SERVER_ERROR)
                return
            html = gates_path.read_bytes()
            self._send_text(html, "text/html; charset=utf-8")
            return

        if parsed.path in {"/airspace", "/airspace.html"}:
            airspace_path = Path(__file__).with_name("airspace.html")
            if not airspace_path.exists():
                self._send_json({"error": "Missing airspace.html"}, status=HTTPStatus.INTERNAL_SERVER_ERROR)
                return
            html = airspace_path.read_bytes()
            self._send_text(html, "text/html; charset=utf-8")
            return

        if parsed.path in {"/worldview", "/worldview.html"}:
            worldview_path = Path(__file__).with_name("worldview.html")
            if not worldview_path.exists():
                self._send_json({"error": "Missing worldview.html"}, status=HTTPStatus.INTERNAL_SERVER_ERROR)
                return
            html = worldview_path.read_bytes()
            self._send_text(html, "text/html; charset=utf-8")
            return

        if parsed.path == "/healthz":
            self._send_json(
                {
                    "ok": True,
                    "events": count_events(),
                    "casThrottled": _is_cas_throttled(),
                    "flightsCacheEntries": len(FLIGHTS_CACHE),
                    "openSkyCacheEntries": len(OPENSKY_CACHE),
                    "openSkyOauthConfigured": bool(OPENSKY_CLIENT_ID and OPENSKY_CLIENT_SECRET),
                }
            )
            return

        if parsed.path == "/api/events":
            qs = parse_qs(parsed.query)
            since_raw = qs.get("since", [None])[0]
            limit_raw = qs.get("limit", ["100"])[0]

            since = None
            if since_raw is not None and since_raw != "":
                try:
                    since = int(since_raw)
                except ValueError:
                    self._send_json({"error": "Invalid 'since' query parameter"}, status=HTTPStatus.BAD_REQUEST)
                    return

            try:
                limit = int(limit_raw)
            except ValueError:
                self._send_json({"error": "Invalid 'limit' query parameter"}, status=HTTPStatus.BAD_REQUEST)
                return

            if limit < 1:
                limit = 1
            if limit > 500:
                limit = 500

            events = list_events(since=since, limit=limit)
            self._send_json({"events": events, "count": len(events)})
            return

        if parsed.path == "/api/flights":
            qs = parse_qs(parsed.query)
            date = (qs.get("date", [datetime.now().strftime("%Y-%m-%d")])[0] or "").strip()
            flight_type = (qs.get("type", ["scheduled"])[0] or "").strip()
            flight_no = (qs.get("flightno", [""])[0] or "").strip()
            if not date:
                self._send_json({"ok": False, "error": "Missing 'date' query parameter"}, status=HTTPStatus.BAD_REQUEST)
                return
            status, payload = fetch_departures(date=date, flight_type=flight_type, flight_no=flight_no)
            self._send_json(payload, status=status)
            return

        if parsed.path == "/api/opensky":
            qs = parse_qs(parsed.query)
            lamin = _to_float(qs.get("lamin", ["1.14"])[0], 1.14)
            lomin = _to_float(qs.get("lomin", ["103.47"])[0], 103.47)
            lamax = _to_float(qs.get("lamax", ["1.50"])[0], 1.50)
            lomax = _to_float(qs.get("lomax", ["104.15"])[0], 104.15)

            limit_raw = (qs.get("limit", ["120"])[0] or "").strip()
            try:
                limit = int(limit_raw)
            except ValueError:
                self._send_json({"ok": False, "error": "Invalid 'limit' query parameter"}, status=HTTPStatus.BAD_REQUEST)
                return

            limit = min(max(limit, 1), 250)
            status, payload = fetch_opensky_states(
                lamin=lamin,
                lomin=lomin,
                lamax=lamax,
                lomax=lomax,
                limit=limit,
            )
            self._send_json(payload, status=status)
            return

        if parsed.path == "/api/gates20":
            status, payload = load_gates_snapshot()
            self._send_json(payload, status=status)
            return

        if parsed.path == "/api/gates-live":
            qs = parse_qs(parsed.query)
            limit_raw = (qs.get("limit", ["500"])[0] or "").strip()
            try:
                limit = int(limit_raw)
            except ValueError:
                self._send_json({"ok": False, "error": "Invalid 'limit' query parameter"}, status=HTTPStatus.BAD_REQUEST)
                return
            limit = min(max(limit, 1), 2000)
            payload = {"ok": True, "gates": list_latest_gate_events(limit=limit)}
            self._send_json(payload)
            return

        self._send_json({"error": "Not found"}, status=HTTPStatus.NOT_FOUND)

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/api/ptt":
            status, payload = trigger_phone_ptt()
            self._send_json(payload, status=status)
            return
        if parsed.path != "/api/events":
            self._send_json({"error": "Not found"}, status=HTTPStatus.NOT_FOUND)
            return

        content_length = self.headers.get("Content-Length")
        if not content_length:
            self._send_json({"error": "Missing Content-Length"}, status=HTTPStatus.LENGTH_REQUIRED)
            return

        try:
            length = int(content_length)
        except ValueError:
            self._send_json({"error": "Invalid Content-Length"}, status=HTTPStatus.BAD_REQUEST)
            return

        raw = self.rfile.read(length)

        try:
            payload = json.loads(raw.decode("utf-8"))
        except json.JSONDecodeError:
            self._send_json({"error": "Invalid JSON"}, status=HTTPStatus.BAD_REQUEST)
            return

        if not isinstance(payload, dict):
            self._send_json({"error": "JSON payload must be an object"}, status=HTTPStatus.BAD_REQUEST)
            return

        event = add_event(payload)
        self._send_json({"ok": True, "event": event}, status=HTTPStatus.CREATED)

    def do_DELETE(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path != "/api/events":
            self._send_json({"error": "Not found"}, status=HTTPStatus.NOT_FOUND)
            return

        provided_token = self.headers.get("X-Admin-Token", "").strip()
        if ADMIN_TOKEN and provided_token != ADMIN_TOKEN:
            self._send_json({"ok": False, "error": "Unauthorized"}, status=HTTPStatus.UNAUTHORIZED)
            return

        deleted = clear_events()
        self._send_json({"ok": True, "deleted": deleted})


def run_server(host: str, port: int) -> None:
    init_events_db()
    events_db_path = _events_db_path().resolve()
    server = ThreadingHTTPServer((host, port), DashboardHandler)
    print(f"Dashboard listening on http://{host}:{port} (events db: {events_db_path})")
    server.serve_forever()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="CameraAccess local dashboard server")
    parser.add_argument("--host", default="0.0.0.0", help="Bind host (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=5055, help="Bind port (default: 5055)")
    return parser.parse_args()


if __name__ == "__main__":
    args = parse_args()
    run_server(host=args.host, port=args.port)
