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
AUTOMATION_GATEWAY_URL="${AUTOMATION_GATEWAY_URL:-${LETTABOT_GATEWAY_URL:-}}"
AUTOMATION_GATEWAY_API_KEY="${AUTOMATION_GATEWAY_API_KEY:-${LETTABOT_API_KEY:-}}"
AUTOMATION_GATEWAY_ENABLED="${AUTOMATION_GATEWAY_ENABLED:-}"

print_usage() {
  printf 'Usage: AUTOMATION_SERVER_URL=... AUTOMATION_ACCESS_TOKEN=... %s\n' "$0"
  printf 'Optional env: AUTOMATION_CONFIG_ID, AUTOMATION_MODE, AUTOMATION_GATEWAY_URL, AUTOMATION_GATEWAY_API_KEY, AUTOMATION_GATEWAY_ENABLED, ANDROID_SERIAL, APP_PACKAGE, APP_COMPONENT\n'
  printf 'Payload JSON contract:\n'
  printf '%s\n' '{"serverUrl":"https://api.letta.com","accessToken":"token-value","configId":"automation-auth","mode":"CLOUD","gatewayUrl":"ws://192.168.50.90:8407/api/v1/agent-gateway","gatewayApiKey":"token-value","gatewayEnabled":true}'
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
host_tmp_xml=""
device_tmp_xml="/data/local/tmp/${APP_PACKAGE}.automation.xml"

cleanup() {
  if [[ -n "$host_tmp_xml" && -f "$host_tmp_xml" ]]; then
    rm -f "$host_tmp_xml"
  fi
  "${adb_cmd[@]}" shell rm -f "$device_tmp_xml" >/dev/null 2>&1 || true
}
trap cleanup EXIT

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
  AUTOMATION_GATEWAY_URL="$AUTOMATION_GATEWAY_URL" \
  AUTOMATION_GATEWAY_API_KEY="$AUTOMATION_GATEWAY_API_KEY" \
  AUTOMATION_GATEWAY_ENABLED="$AUTOMATION_GATEWAY_ENABLED" \
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
gateway_url = os.environ.get("AUTOMATION_GATEWAY_URL", "").strip()
gateway_api_key = os.environ.get("AUTOMATION_GATEWAY_API_KEY", "").strip()
gateway_enabled = os.environ.get("AUTOMATION_GATEWAY_ENABLED", "").strip().lower()
if gateway_url or gateway_api_key or gateway_enabled:
    if not gateway_url or not gateway_api_key:
        raise SystemExit("gatewayUrl and gatewayApiKey must both be provided when bootstrapping gateway credentials (gatewayEnabled alone is not enough)")
    payload["gatewayUrl"] = gateway_url
    payload["gatewayApiKey"] = gateway_api_key
    if gateway_enabled:
        if gateway_enabled in {"1", "true", "yes", "on"}:
            payload["gatewayEnabled"] = True
        elif gateway_enabled in {"0", "false", "no", "off"}:
            payload["gatewayEnabled"] = False
        else:
            raise SystemExit("gatewayEnabled must be one of: true/false, 1/0, yes/no, on/off")
print(json.dumps(payload, separators=(",", ":")))
PY
} | base64 | tr -d '\n')"

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

host_tmp_xml="$(mktemp)"
printf '%s\n' "$xml_value" > "$host_tmp_xml"

printf 'Staging automation credentials for %s...\n' "$APP_PACKAGE"
"${adb_cmd[@]}" shell am force-stop "$APP_PACKAGE"
"${adb_cmd[@]}" push "$host_tmp_xml" "$device_tmp_xml" >/dev/null
"${adb_cmd[@]}" shell run-as "$APP_PACKAGE" mkdir -p shared_prefs
"${adb_cmd[@]}" shell run-as "$APP_PACKAGE" cp "$device_tmp_xml" shared_prefs/letta_automation.xml
"${adb_cmd[@]}" shell am start -n "$APP_COMPONENT" >/dev/null

printf 'Automation credentials injected. Launch complete; the app will import the payload once and clear the staging preference.\n'
