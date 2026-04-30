#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
ANDROID_DIR="$REPO_ROOT/android-compose"
GRADLEW="$ANDROID_DIR/gradlew"

APP_PACKAGE="${APP_PACKAGE:-com.letta.mobile}"
APK="${APK:-$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk}"
BASE_URL="${AUTOMATION_SERVER_URL:-${LETTA_SERVER_URL:-${BASE_URL:-${LETTA_URL:-}}}}"
API_KEY="${AUTOMATION_ACCESS_TOKEN:-${LETTA_TOKEN:-${API_KEY:-}}}"
AUTOMATION_GATEWAY_URL="${AUTOMATION_GATEWAY_URL:-${LETTABOT_GATEWAY_URL:-}}"
AUTOMATION_GATEWAY_API_KEY="${AUTOMATION_GATEWAY_API_KEY:-${LETTABOT_API_KEY:-}}"
AUTOMATION_GATEWAY_ENABLED="${AUTOMATION_GATEWAY_ENABLED:-}"
ANDROID_SERIAL="${ANDROID_SERIAL:-${DEVICE:-}}"
SKIP_BUILD="${SKIP_BUILD:-0}"

usage() {
  printf 'Usage: AUTOMATION_SERVER_URL=... AUTOMATION_ACCESS_TOKEN=... %s\n' "$0"
  printf 'Optional env: ANDROID_SERIAL, DEVICE, APK, SKIP_BUILD=1, APP_PACKAGE, AUTOMATION_GATEWAY_URL, AUTOMATION_GATEWAY_API_KEY, AUTOMATION_GATEWAY_ENABLED\n'
  printf 'URL fallbacks: AUTOMATION_SERVER_URL -> LETTA_SERVER_URL -> BASE_URL -> LETTA_URL\n'
  printf 'Token fallbacks: AUTOMATION_ACCESS_TOKEN -> LETTA_TOKEN -> API_KEY\n'
  printf 'Gateway URL fallback: AUTOMATION_GATEWAY_URL -> LETTABOT_GATEWAY_URL\n'
  printf 'Gateway key fallback: AUTOMATION_GATEWAY_API_KEY -> LETTABOT_API_KEY\n'
}

if [[ "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ -z "$BASE_URL" || -z "$API_KEY" ]]; then
  usage >&2
  printf 'Missing credentials. Set AUTOMATION_SERVER_URL/LETTA_SERVER_URL/BASE_URL/LETTA_URL and AUTOMATION_ACCESS_TOKEN/LETTA_TOKEN/API_KEY.\n' >&2
  exit 2
fi

if ! command -v adb >/dev/null 2>&1; then
  printf 'adb is required on PATH.\n' >&2
  exit 2
fi

resolve_device() {
  if [[ -n "$ANDROID_SERIAL" ]]; then
    printf '%s\n' "$ANDROID_SERIAL"
    return 0
  fi

  mapfile -t devices < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  mapfile -t wireless_devices < <(printf '%s\n' "${devices[@]}" | awk '/:[0-9]+$/ {print $0}')

  if [[ ${#wireless_devices[@]} -eq 1 ]]; then
    printf '%s\n' "${wireless_devices[0]}"
    return 0
  fi

  if [[ ${#devices[@]} -eq 1 ]]; then
    printf '%s\n' "${devices[0]}"
    return 0
  fi

  if [[ ${#devices[@]} -eq 0 ]]; then
    printf 'No authorized adb devices found.\n' >&2
  else
    printf 'Multiple adb devices found; set ANDROID_SERIAL or DEVICE.\n' >&2
    printf 'Devices:\n' >&2
    printf '  %s\n' "${devices[@]}" >&2
  fi
  exit 2
}

ANDROID_SERIAL="$(resolve_device)"
adb_cmd=(adb -s "$ANDROID_SERIAL")

if [[ "$SKIP_BUILD" != "1" ]]; then
  "$GRADLEW" -p "$ANDROID_DIR" :app:assembleDebug
fi

if [[ ! -f "$APK" ]]; then
  printf 'Debug APK not found: %s\n' "$APK" >&2
  exit 11
fi

install_output=""
if ! install_output="$("${adb_cmd[@]}" install -r "$APK" 2>&1)"; then
  if [[ "$install_output" == *"INSTALL_FAILED_UPDATE_INCOMPATIBLE"* ]]; then
    printf 'Existing %s has a different signature; uninstalling and retrying (this clears app data).\n' "$APP_PACKAGE"
    uninstall_output="$("${adb_cmd[@]}" uninstall "$APP_PACKAGE" 2>&1 || true)"
    if [[ "$uninstall_output" != *"Success"* ]]; then
      printf 'Unexpected uninstall failure: %s\n' "$uninstall_output" >&2
      exit 12
    fi
    install_output="$("${adb_cmd[@]}" install -r "$APK" 2>&1)" || {
      printf 'Install failed after uninstall: %s\n' "$install_output" >&2
      exit 14
    }
  else
    printf 'Install failed: %s\n' "$install_output" >&2
    exit 13
  fi
fi

printf '%s\n' "$install_output"

ANDROID_SERIAL="$ANDROID_SERIAL" \
AUTOMATION_SERVER_URL="$BASE_URL" \
AUTOMATION_ACCESS_TOKEN="$API_KEY" \
AUTOMATION_GATEWAY_URL="$AUTOMATION_GATEWAY_URL" \
AUTOMATION_GATEWAY_API_KEY="$AUTOMATION_GATEWAY_API_KEY" \
AUTOMATION_GATEWAY_ENABLED="$AUTOMATION_GATEWAY_ENABLED" \
APP_PACKAGE="$APP_PACKAGE" \
"$SCRIPT_DIR/inject-automation-creds.sh"

printf 'Debug app installed and automation credentials staged for %s.\n' "$ANDROID_SERIAL"
