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
- `EVENTS_MAXLEN` (default `0`, no auto-trim; set >0 to keep only newest N events)
- `EVENTS_DB_PATH` (default `./events.db` beside `server.py`)
- `DASHBOARD_ADMIN_TOKEN` (optional; required for `DELETE /api/events` if set)
- `OPENSKY_CLIENT_ID` / `OPENSKY_CLIENT_SECRET` (optional; enables OpenSky OAuth2 mode)
- `OPENSKY_CACHE_TTL_SECONDS` (default `30`)
- `OPENSKY_TIMEOUT_SECONDS` (default `15`)
- `OPENSKY_POLL_ANON_MS` (default `240000`)
- `OPENSKY_POLL_AUTH_MS` (default `30000`)
- `PTT_ADB_SERIAL` (optional; target a specific adb device for dashboard PTT button)
- `PTT_ANDROID_COMPONENT` (optional; default `com.certis.kerbside/com.meta.wearable.dat.externalsampleapps.cameraaccess.MainActivity`)

Open in browser:

- `http://localhost:5055` (same laptop)
- `http://<laptop-lan-ip>:5055` (other devices on same network)
- `http://localhost:5055/flights` (flight departures page)
- `http://localhost:5055/worldview` (geospatial intelligence style page)

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
- `GET /api/opensky?lamin=...&lomin=...&lamax=...&lomax=...&limit=180` proxies:
  `https://opensky-network.org/api/states/all`
  (uses OAuth2 when `OPENSKY_CLIENT_ID` and `OPENSKY_CLIENT_SECRET` are set; otherwise anonymous mode)
- `POST /api/ptt` sends `cameraaccess://ptt` to the adb-connected phone app from the laptop backend.
  Requires adb on the same machine running `server.py`.

## Render persistence note

For `onrender.com`, set `EVENTS_DB_PATH` to a mounted persistent disk path
(for example `/var/data/events.db`) so records survive service restarts/redeploys.
