#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
APP_PACKAGE="${APP_PACKAGE:-com.letta.mobile}"
APP_COMPONENT="${APP_COMPONENT:-com.letta.mobile/.MainActivity}"
ANDROID_SERIAL="${ANDROID_SERIAL:-}"
AUTOMATION_CONFIG_ID="${AUTOMATION_CONFIG_ID:-automation-auth}"
AUTOMATION_MODE="${AUTOMATION_MODE:-}"
AUTOMATION_SERVER_URL="${AUTOMATION_SERVER_URL:-${LETTA_SERVER_URL:-}}"
AUTOMATION_ACCESS_TOKEN="${AUTOMATION_ACCESS_TOKEN:-${LETTA_TOKEN:-}}"

print_usage() {
  printf 'Usage: AUTOMATION_SERVER_URL=... AUTOMATION_ACCESS_TOKEN=... %s\n' "$0"
  printf 'Optional env: AUTOMATION_CONFIG_ID, AUTOMATION_MODE, ANDROID_SERIAL, APP_PACKAGE, APP_COMPONENT\n'
  printf 'Payload JSON contract:\n'
  printf '%s\n' '{"serverUrl":"https://api.letta.com","accessToken":"token-value","configId":"automation-auth","mode":"CLOUD"}'
}

if [[ "${1:-}" == "--help" ]]; then
  print_usage
  exit 0
fi

if [[ -z "$AUTOMATION_SERVER_URL" || -z "$AUTOMATION_ACCESS_TOKEN" ]]; then
  print_usage >&2
  printf 'Missing automation credentials. Set AUTOMATION_SERVER_URL/LETTA_SERVER_URL and AUTOMATION_ACCESS_TOKEN/LETTA_TOKEN.\n' >&2
  exit 2
fi

adb_args=()
if [[ -n "$ANDROID_SERIAL" ]]; then
  adb_args=("-s" "$ANDROID_SERIAL")
fi

adb_cmd=(adb "${adb_args[@]}")

if ! "${adb_cmd[@]}" get-state >/dev/null 2>&1; then
  printf 'adb cannot reach the target device.\n' >&2
  exit 2
fi

if ! "${adb_cmd[@]}" shell pm path "$APP_PACKAGE" >/dev/null 2>&1; then
  printf 'Package %s is not installed. Install a debug APK first (for example: cd %s/android-compose && ./gradlew :app:installDebug).\n' "$APP_PACKAGE" "$REPO_ROOT" >&2
  exit 2
fi

if ! "${adb_cmd[@]}" shell run-as "$APP_PACKAGE" true >/dev/null 2>&1; then
  printf 'run-as is unavailable for %s. This script requires a debuggable build.\n' "$APP_PACKAGE" >&2
  exit 2
fi

payload_json="$({
  AUTOMATION_SERVER_URL="$AUTOMATION_SERVER_URL" \
  AUTOMATION_ACCESS_TOKEN="$AUTOMATION_ACCESS_TOKEN" \
  AUTOMATION_CONFIG_ID="$AUTOMATION_CONFIG_ID" \
  AUTOMATION_MODE="$AUTOMATION_MODE" \
  python3 - <<'PY'
import json
import os

payload = {
    "serverUrl": os.environ["AUTOMATION_SERVER_URL"],
    "accessToken": os.environ["AUTOMATION_ACCESS_TOKEN"],
    "configId": os.environ.get("AUTOMATION_CONFIG_ID", "automation-auth"),
}
mode = os.environ.get("AUTOMATION_MODE", "").strip()
if mode:
    payload["mode"] = mode
print(json.dumps(payload, separators=(",", ":")))
PY
} | base64 -w0)"

xml_value="$(PAYLOAD_BASE64="$payload_json" python3 - <<'PY'
import os
from xml.sax.saxutils import escape

payload = escape(os.environ["PAYLOAD_BASE64"])
print("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>")
print("<map>")
print(f'    <string name="config_payload_base64">{payload}</string>')
print("</map>")
PY
)"

printf 'Staging automation credentials for %s...\n' "$APP_PACKAGE"
"${adb_cmd[@]}" shell am force-stop "$APP_PACKAGE"
printf '%s\n' "$xml_value" | "${adb_cmd[@]}" exec-out run-as "$APP_PACKAGE" sh -c 'mkdir -p shared_prefs && cat > shared_prefs/letta_automation.xml'
"${adb_cmd[@]}" shell am start -n "$APP_COMPONENT" >/dev/null

printf 'Automation credentials injected. Launch complete; the app will import the payload once and clear the staging preference.\n'
