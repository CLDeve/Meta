#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [[ ! -x "./gradlew" ]]; then
  echo "Error: gradlew not found in $SCRIPT_DIR" >&2
  exit 1
fi

# Optional: load key from file path if OPENAI_API_KEY is not already set.
if [[ -z "${OPENAI_API_KEY:-}" && -n "${OPENAI_API_KEY_FILE:-}" ]]; then
  if [[ -f "${OPENAI_API_KEY_FILE}" ]]; then
    OPENAI_API_KEY="$(tr -d '\r\n' < "${OPENAI_API_KEY_FILE}")"
    export OPENAI_API_KEY
  else
    echo "Error: OPENAI_API_KEY_FILE does not exist: ${OPENAI_API_KEY_FILE}" >&2
    exit 1
  fi
fi

if [[ -z "${OPENAI_API_KEY:-}" ]]; then
  cat >&2 <<'EOF'
Error: OPENAI_API_KEY is missing.

Usage:
  OPENAI_API_KEY="<key>" ./install_with_api.sh

Optional env vars:
  OPENAI_BASE_URL      (default: https://api.openai.com/v1)
  OPENAI_MODEL         (default: gpt-4o-mini)
  COMMAND_CENTER_URL   (default: https://cameraaccess-dashboard.onrender.com/api/events)
  ADB_SERIAL           (optional: select a specific Android device)
  OPENAI_API_KEY_FILE  (optional: read key from file)
EOF
  exit 1
fi

OPENAI_BASE_URL="${OPENAI_BASE_URL:-https://api.openai.com/v1}"
OPENAI_MODEL="${OPENAI_MODEL:-gpt-4o-mini}"
COMMAND_CENTER_URL="${COMMAND_CENTER_URL:-https://cameraaccess-dashboard.onrender.com/api/events}"

if [[ -n "${ADB_SERIAL:-}" ]]; then
  export ANDROID_SERIAL="${ADB_SERIAL}"
fi

echo "Installing debug APK with API config..."
echo "OPENAI_BASE_URL=${OPENAI_BASE_URL}"
echo "OPENAI_MODEL=${OPENAI_MODEL}"
echo "COMMAND_CENTER_URL=${COMMAND_CENTER_URL}"
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  echo "ANDROID_SERIAL=${ANDROID_SERIAL}"
fi

OPENAI_API_KEY="${OPENAI_API_KEY}" \
OPENAI_BASE_URL="${OPENAI_BASE_URL}" \
OPENAI_MODEL="${OPENAI_MODEL}" \
COMMAND_CENTER_URL="${COMMAND_CENTER_URL}" \
./gradlew :app:installDebug --rerun-tasks "$@"

