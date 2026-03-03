# CameraAccess Dashboard

Local web dashboard for viewing incoming question/answer events (with captured frame snapshots) from the Android app.

## Run

```bash
cd SEE/meta-wearables-dat-android/samples/CameraAccess/dashboard
CAS_API_KEY="<your-x-api-key>" python3 server.py --host 0.0.0.0 --port 5055
```

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

- `POST /api/events` stores up to 500 recent Q/A events in memory.
- `GET /api/events` reads stored events.
- `GET /api/flights?date=YYYY-MM-DD&type=scheduled&flightno=` proxies:
  `https://api.cas.certispsb.net/api-ext/v1/flights/departure/list`
  (requires env var `CAS_API_KEY` when starting server)
