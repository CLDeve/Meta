#!/usr/bin/env python3
"""Local command-centre dashboard server for CameraAccess."""

from __future__ import annotations

import argparse
import json
import os
import sqlite3
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
CAS_API_KEY = os.getenv("CAS_API_KEY", "").strip()
ADMIN_TOKEN = os.getenv("DASHBOARD_ADMIN_TOKEN", "").strip()
FLIGHTS_CACHE: dict[str, dict[str, Any]] = {}
CAS_THROTTLED_UNTIL = 0.0
DB_CONN: sqlite3.Connection | None = None


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
EVENTS_MAXLEN = _read_int_env("EVENTS_MAXLEN", 500)


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


def _cache_set(cache: dict[str, dict[str, Any]], key: str, payload: dict[str, Any]) -> None:
    if CAS_CACHE_TTL_SECONDS <= 0:
        return
    with LOCK:
        cache[key] = {
            "expiresAtEpoch": time.time() + CAS_CACHE_TTL_SECONDS,
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
            _cache_set(FLIGHTS_CACHE, cache_key, result)
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

        self._send_json({"error": "Not found"}, status=HTTPStatus.NOT_FOUND)

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
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
