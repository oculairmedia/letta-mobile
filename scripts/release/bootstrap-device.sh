#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DEVICE=""
APK=""
BASE_URL=""
API_KEY=""
AGENT=""
CONV=""
APP_PACKAGE="com.letta.mobile"
APP_COMPONENT="com.letta.mobile/.MainActivity"

usage() {
  printf 'Usage: %s --device <serial> --apk <path> --base-url <url> --api-key <token> --agent <id> --conv <id>\n' "$0"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)
      DEVICE="${2:-}"
      shift 2
      ;;
    --apk)
      APK="${2:-}"
      shift 2
      ;;
    --base-url)
      BASE_URL="${2:-}"
      shift 2
      ;;
    --api-key)
      API_KEY="${2:-}"
      shift 2
      ;;
    --agent)
      AGENT="${2:-}"
      shift 2
      ;;
    --conv)
      CONV="${2:-}"
      shift 2
      ;;
    --help)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown argument: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "$DEVICE" || -z "$APK" || -z "$BASE_URL" || -z "$API_KEY" || -z "$AGENT" || -z "$CONV" ]]; then
  usage >&2
  exit 2
fi

if [[ ! -f "$APK" ]]; then
  printf 'APK not found: %s\n' "$APK" >&2
  exit 11
fi

adb_cmd=(adb -s "$DEVICE")
log_file="$(mktemp)"
log_pid=""

cleanup() {
  if [[ -n "$log_pid" ]] && kill -0 "$log_pid" 2>/dev/null; then
    kill "$log_pid" 2>/dev/null || true
    wait "$log_pid" 2>/dev/null || true
  fi
  rm -f "$log_file"
}
trap cleanup EXIT

wait_for_log() {
  local needle="$1"
  local timeout_seconds="$2"
  local waited=0
  while (( waited < timeout_seconds )); do
    if grep -Fq "$needle" "$log_file"; then
      return 0
    fi
    sleep 1
    ((waited += 1))
  done
  return 1
}

if ! "${adb_cmd[@]}" get-state >/dev/null 2>&1; then
  printf 'Device %s is not available via adb.\n' "$DEVICE" >&2
  exit 2
fi

if "${adb_cmd[@]}" shell pm path "$APP_PACKAGE" >/dev/null 2>&1; then
  uninstall_output="$(${adb_cmd[@]} uninstall "$APP_PACKAGE" 2>&1 || true)"
  if [[ "$uninstall_output" != *"Success"* ]]; then
    printf 'Unexpected uninstall failure: %s\n' "$uninstall_output" >&2
    exit 10
  fi
fi

if ! install_output="$(${adb_cmd[@]} install -r "$APK" 2>&1)"; then
  printf 'Install failed: %s\n' "$install_output" >&2
  exit 11
fi

"${adb_cmd[@]}" logcat -c >/dev/null 2>&1 || true
"${adb_cmd[@]}" logcat -v brief -s AutomationAuth:V AgentListViewModel:V AdminChatViewModel:V >"$log_file" 2>&1 &
log_pid=$!

if ! launch_output="$(${adb_cmd[@]} shell am start -n "$APP_COMPONENT" 2>&1)"; then
  printf 'Launch failed: %s\n' "$launch_output" >&2
  exit 12
fi
if [[ "$launch_output" == *"Error:"* ]]; then
  printf 'Launch failed: %s\n' "$launch_output" >&2
  exit 12
fi

if ! ANDROID_SERIAL="$DEVICE" AUTOMATION_SERVER_URL="$BASE_URL" AUTOMATION_ACCESS_TOKEN="$API_KEY" "$SCRIPT_DIR/inject-automation-creds.sh"; then
  printf 'Auth injection failed.\n' >&2
  exit 13
fi

if ! wait_for_log "Imported automation credentials for" 30; then
  printf 'Timed out waiting for automation auth import marker.\n' >&2
  exit 13
fi

if ! agent_list_output="$(${adb_cmd[@]} shell am start -n "$APP_COMPONENT" --ez automation_open_agent_list true 2>&1)"; then
  printf 'Agent list launch failed: %s\n' "$agent_list_output" >&2
  exit 14
fi
if [[ "$agent_list_output" == *"Error:"* ]]; then
  printf 'Agent list launch failed: %s\n' "$agent_list_output" >&2
  exit 14
fi

if ! wait_for_log "AgentList hydrated count=" 30; then
  printf 'Timed out waiting for agent-list hydration marker.\n' >&2
  exit 14
fi

if ! intent_output="$(${adb_cmd[@]} shell am start -n "$APP_COMPONENT" --es notification_agent_id "$AGENT" --es notification_conversation_id "$CONV" 2>&1)"; then
  printf 'Conversation launch failed: %s\n' "$intent_output" >&2
  exit 15
fi
if [[ "$intent_output" == *"Error:"* ]]; then
  printf 'Conversation launch failed: %s\n' "$intent_output" >&2
  exit 15
fi

if ! wait_for_log "Timeline ready conv=$CONV" 30; then
  printf 'Timed out waiting for timeline readiness marker.\n' >&2
  exit 16
fi

tail -n 20 "$log_file"
