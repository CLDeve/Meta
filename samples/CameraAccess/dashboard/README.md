# CameraAccess Dashboard

Local web dashboard for viewing incoming question/answer events (with captured frame snapshots) from the Android app.

## Run

```bash
cd SEE/meta-wearables-dat-android/samples/CameraAccess/dashboard
CAS_API_KEY="<your-x-api-key>" python3 server.py --host 0.0.0.0 --port 5055
```

Optional throttle settings:

- `CAS_CACHE_TTL_SECONDS` (default `45`)
- `CAS_THROTTLE_BACKOFF_SECONDS` (default `45`)
- `EVENTS_MAXLEN` (default `500`, keeps the newest N events)
- `EVENTS_DB_PATH` (default `./events.db` beside `server.py`)
- `DASHBOARD_ADMIN_TOKEN` (optional; required for `DELETE /api/events` if set)

Open in browser:

- `http://localhost:5055` (same laptop)
- `http://<laptop-lan-ip>:5055` (other devices on same network)
- `http://localhost:5055/flights` (flight departures page)

## Android app config

Build/install the app with:

```bash
COMMAND_CENTER_URL="http://<laptop-lan-ip>:5055/api/events" \
OPENAI_BASE_URL="https://api.openai.com/v1" \
OPENAI_MODEL="gpt-4o-mini" \
OPENAI_API_KEY="<your-openai-api-key>" \
./gradlew :app:installDebug --rerun-tasks
```

Endpoints:

- `POST /api/events` stores Q/A events in SQLite and keeps the latest `EVENTS_MAXLEN`.
- `GET /api/events` reads stored events.
- `DELETE /api/events` clears all stored events.
  If `DASHBOARD_ADMIN_TOKEN` is set, send it as header `X-Admin-Token`.
- `GET /api/flights?date=YYYY-MM-DD&type=scheduled&flightno=` proxies:
  `https://api.cas.certispsb.net/api-ext/v1/flights/departure/list`
  (requires env var `CAS_API_KEY` when starting server; returns cached data when upstream is throttled)

## Render persistence note

For `onrender.com`, set `EVENTS_DB_PATH` to a mounted persistent disk path
(for example `/var/data/events.db`) so records survive service restarts/redeploys.
