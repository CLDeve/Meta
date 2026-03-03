#!/usr/bin/env python3
"""Local command-centre dashboard server for CameraAccess."""

from __future__ import annotations

import argparse
import json
import os
import threading
from collections import deque
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlencode, urlparse
from urllib.request import Request, urlopen

EVENTS_MAXLEN = 500
EVENTS: deque[dict[str, Any]] = deque(maxlen=EVENTS_MAXLEN)
NEXT_ID = 1
LOCK = threading.Lock()
FLIGHTS_API_URL = "https://api.cas.certispsb.net/api-ext/v1/flights/departure/list"
CAS_API_KEY = os.getenv("CAS_API_KEY", "").strip()


def utc_iso_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def add_event(payload: dict[str, Any]) -> dict[str, Any]:
    global NEXT_ID
    with LOCK:
        event = {
            "id": NEXT_ID,
            "receivedAtIso": utc_iso_now(),
            **payload,
        }
        NEXT_ID += 1
        EVENTS.append(event)
    return event


def list_events(since: int | None, limit: int) -> list[dict[str, Any]]:
    with LOCK:
        items = list(EVENTS)

    if since is not None:
        items = [event for event in items if int(event.get("id", 0)) > since]

    if limit > 0:
        items = items[-limit:]

    return items


def fetch_departures(date: str, flight_type: str, flight_no: str) -> tuple[int, dict[str, Any]]:
    if not CAS_API_KEY:
        return HTTPStatus.BAD_REQUEST, {
            "ok": False,
            "error": "Missing CAS_API_KEY. Set env var CAS_API_KEY before starting server.",
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
            return HTTPStatus.OK, {
                "ok": True,
                "query": {"date": date, "type": flight_type, "flightno": flight_no},
                "data": payload,
            }
    except HTTPError as err:
        body = err.read().decode("utf-8", errors="replace")
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
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
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

        if parsed.path == "/healthz":
            self._send_json({"ok": True, "events": len(EVENTS)})
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


def run_server(host: str, port: int) -> None:
    server = ThreadingHTTPServer((host, port), DashboardHandler)
    print(f"Dashboard listening on http://{host}:{port}")
    server.serve_forever()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="CameraAccess local dashboard server")
    parser.add_argument("--host", default="0.0.0.0", help="Bind host (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=5055, help="Bind port (default: 5055)")
    return parser.parse_args()


if __name__ == "__main__":
    args = parse_args()
    run_server(host=args.host, port=args.port)
