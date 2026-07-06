#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
# Iroh on-device streaming E2E gate (letta-mobile-myv7c, epic phase P5).
#
# Drives the REAL dev APK on a physical device through the myv7c scenario and
# asserts — via adb logcat + /proc/net/tcp + the P1 probe assertion library
# (:cli app-server-iroh-device-gate) — that:
#   * visible streaming frames arrive over Iroh (IrohTransport frame.recv),
#   * the active chat's uiProjection.snapshot grows DURING the burst, not only
#     after a recentReconcile / navigation,
#   * exactly ONE terminal per send (AdminChatVM ws.turnComplete),
#   * isStreaming clears (true -> false, final false),
#   * ZERO TCP sockets from the app uid to the backend HTTP port.
#
# All parsing + assertion logic lives in Kotlin (:cli, unit-tested); this script
# only does the adb capture and hands the artifacts to the CLI.
#
# ----------------------------------------------------------------------------
# RUNBOOK — exact commands Emmanuel runs on the host wired to the Pixel 9 Pro
# ----------------------------------------------------------------------------
# 0. One-time env (per shell):
#      export JAVA_HOME=/usr/lib/jvm/jdk-26
#      export ANDROID_HOME=/root/Android/Sdk
#
# 1. Confirm the assertion engine is healthy WITHOUT a device (dry run):
#      scripts/iroh_device_gate.sh --self-check
#    Expected tail:
#      [iroh-device-gate] self-check ok: green passes, missing-terminal and
#      http-leak regressions are both detected
#
# 2. Install the dev APK built by this branch (artifact path is printed by the
#    build step in the PR; do NOT let CI install it):
#      adb -s <SERIAL> install -r \
#        android-compose/app/build/outputs/apk/root/debug/app-root-debug.apk
#
# 3. In the app, point the backend at the Iroh wrapper ticket and open the
#    known test agent once so the conversation exists. (The app owns backend
#    config; this script does not mutate it.)
#
# 4. Run the gate. Default send mode is MANUAL (you send the prompt in-app while
#    the script captures — most reliable on a device you are holding):
#      scripts/iroh_device_gate.sh \
#        --serial <SERIAL> \
#        --ticket "iroh://<node-id>@<host:port>..." \
#        --agent  agent-ca46df7f-... \
#        --conversation conv-8d4b6225-...
#    When it prints "SEND NOW", type a prompt in the open chat and hit send,
#    then press Enter in the terminal.
#
#    Fully hands-free variant (types + taps the send button via adb input):
#      scripts/iroh_device_gate.sh --serial <SERIAL> --ticket iroh://... \
#        --agent agent-... --conversation conv-... \
#        --auto-send --send-tap "980 2120" --message "device gate ping"
#
# 5. Expected GREEN output ends with:
#      [iroh-device-gate] conversation=conv-... ok=true
#      [iroh-device-gate] PASS
#    Exit code 0 = gate green. Nonzero = a violation token is printed
#    (e.g. terminal_count_0, update_only_after_reconcile, no_http_tcp_connects).
#    Trace artifacts (logcat + net samples) are left in the printed OUT_DIR.
# ============================================================================

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_DIR="$ROOT_DIR/android-compose"
FIXTURE_DIR="$ROOT_DIR/scripts/fixtures/iroh_device_gate"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/jdk-26}"
export ANDROID_HOME="${ANDROID_HOME:-/root/Android/Sdk}"

SERIAL=""
TICKET=""
AGENT=""
CONVERSATION=""
MESSAGE="device gate ping $(date +%s)"
PACKAGE="com.letta.mobile.dev"
ACTIVITY="com.letta.mobile/com.letta.mobile.MainActivity"
HTTP_PORT="8291"
CAPTURE_SECS="45"
SEND_MODE="manual" # manual | auto
SEND_TAP="" # "x y" for --auto-send
SELF_CHECK="0"
OUT_DIR=""

usage() {
  sed -n '2,80p' "${BASH_SOURCE[0]}"
  exit "${1:-2}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial) SERIAL="$2"; shift 2 ;;
    --ticket) TICKET="$2"; shift 2 ;;
    --agent) AGENT="$2"; shift 2 ;;
    --conversation) CONVERSATION="$2"; shift 2 ;;
    --message) MESSAGE="$2"; shift 2 ;;
    --package) PACKAGE="$2"; shift 2 ;;
    --activity) ACTIVITY="$2"; shift 2 ;;
    --http-port) HTTP_PORT="$2"; shift 2 ;;
    --capture-secs) CAPTURE_SECS="$2"; shift 2 ;;
    --auto-send) SEND_MODE="auto"; shift ;;
    --manual-send) SEND_MODE="manual"; shift ;;
    --send-tap) SEND_TAP="$2"; shift 2 ;;
    --self-check) SELF_CHECK="1"; shift ;;
    --out-dir) OUT_DIR="$2"; shift 2 ;;
    -h|--help) usage 0 ;;
    *) echo "unknown arg: $1" >&2; usage 2 ;;
  esac
done

# Run the :cli assertion command. Args are all space-free (paths/ids/ints).
run_gate_cli() {
  ( cd "$COMPOSE_DIR" && ./gradlew --quiet :cli:run -PcliArgs="app-server-iroh-device-gate $*" )
}

# --------------------------------------------------------------------------
# Self-check / dry-run: exercise the FULL parse+assert path against bundled
# fixtures with NO device attached. Green fixture must pass; the missing-
# terminal and http-leak regressions must each be detected (exit nonzero with
# the expected violation token). This is the gate's mandatory offline check.
# --------------------------------------------------------------------------
if [[ "$SELF_CHECK" == "1" ]]; then
  echo "[iroh-device-gate] self-check: green fixture must PASS" >&2
  if ! run_gate_cli --logcat "$FIXTURE_DIR/green.logcat" --net "$FIXTURE_DIR/green.net" \
      --app-uid 10234 --http-port 8291 --conversation conv-selfcheck; then
    echo "[iroh-device-gate] SELF-CHECK FAILED: green fixture did not pass" >&2
    exit 1
  fi

  echo "[iroh-device-gate] self-check: missing-terminal fixture must FAIL with terminal_count_0" >&2
  out="$(run_gate_cli --logcat "$FIXTURE_DIR/missing_terminal.logcat" \
      --conversation conv-selfcheck 2>&1)" && {
    echo "[iroh-device-gate] SELF-CHECK FAILED: missing-terminal fixture passed" >&2
    echo "$out" >&2; exit 1
  }
  if ! grep -q "terminal_count_0" <<<"$out"; then
    echo "[iroh-device-gate] SELF-CHECK FAILED: missing-terminal did not report terminal_count_0" >&2
    echo "$out" >&2; exit 1
  fi

  echo "[iroh-device-gate] self-check: http-leak net sample must FAIL with no_http_tcp_connects" >&2
  out="$(run_gate_cli --logcat "$FIXTURE_DIR/green.logcat" --net "$FIXTURE_DIR/http_leak.net" \
      --app-uid 10234 --http-port 8291 --conversation conv-selfcheck 2>&1)" && {
    echo "[iroh-device-gate] SELF-CHECK FAILED: http-leak fixture passed" >&2
    echo "$out" >&2; exit 1
  }
  if ! grep -q "no_http_tcp_connects" <<<"$out"; then
    echo "[iroh-device-gate] SELF-CHECK FAILED: http-leak did not report no_http_tcp_connects" >&2
    echo "$out" >&2; exit 1
  fi

  echo "[iroh-device-gate] self-check ok: green passes, missing-terminal and http-leak regressions are both detected" >&2
  exit 0
fi

# --------------------------------------------------------------------------
# Device mode.
# --------------------------------------------------------------------------
[[ -n "$SERIAL" ]] || { echo "--serial is required (see --help)" >&2; exit 2; }
[[ -n "$CONVERSATION" ]] || { echo "--conversation is required" >&2; exit 2; }
[[ -n "$AGENT" ]] || { echo "--agent is required" >&2; exit 2; }
if [[ -n "$TICKET" && "$TICKET" != iroh://* ]]; then
  echo "--ticket must be an iroh:// URL" >&2; exit 2
fi

adb() { command adb -s "$SERIAL" "$@"; }

# adb reachability + app running.
adb get-state >/dev/null 2>&1 || { echo "device $SERIAL not reachable via adb" >&2; exit 2; }

OUT_DIR="${OUT_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/iroh-device-gate.XXXXXX")}"
LOGCAT_FILE="$OUT_DIR/android.logcat"
NET_FILE="$OUT_DIR/net.samples"
echo "[iroh-device-gate] serial=$SERIAL conversation=$CONVERSATION out=$OUT_DIR" >&2
[[ -n "$TICKET" ]] && echo "[iroh-device-gate] backend ticket (app must already point here): $TICKET" >&2

LOGCAT_PID=""
SAMPLER_PID=""
cleanup() {
  [[ -n "$LOGCAT_PID" ]] && kill "$LOGCAT_PID" 2>/dev/null || true
  [[ -n "$SAMPLER_PID" ]] && kill "$SAMPLER_PID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# Open the ROUTED conversation deterministically via the notification-target
# intent extras (NotificationNavigationTarget) — no fragile UI coordinates.
adb logcat -c || true
echo "[iroh-device-gate] opening conversation via intent extras..." >&2
adb shell am start -n "$ACTIVITY" \
  --es notification_agent_id "$AGENT" \
  --es notification_conversation_id "$CONVERSATION" \
  --activity-clear-top >/dev/null 2>&1 || true
sleep 4

PID="$(adb shell pidof "$PACKAGE" | tr -d '\r' | awk '{print $1}')"
[[ -n "$PID" ]] || { echo "app $PACKAGE not running on $SERIAL after intent" >&2; exit 2; }
APP_UID="$(adb shell cat /proc/"$PID"/status | tr -d '\r' | awk '/^Uid:/{print $2; exit}')"
[[ -n "$APP_UID" ]] || { echo "could not resolve app uid for pid $PID" >&2; exit 2; }
echo "[iroh-device-gate] app pid=$PID uid=$APP_UID" >&2

# Background: capture logcat for the whole window; sample /proc/net/tcp{,6}.
adb logcat -v threadtime > "$LOGCAT_FILE" 2>/dev/null &
LOGCAT_PID=$!
(
  end=$(( $(date +%s) + CAPTURE_SECS + 5 ))
  while [[ $(date +%s) -lt $end ]]; do
    command adb -s "$SERIAL" shell cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | tr -d '\r' >> "$NET_FILE" || true
    sleep 1
  done
) &
SAMPLER_PID=$!

# Send the prompt.
if [[ "$SEND_MODE" == "auto" ]]; then
  echo "[iroh-device-gate] auto-send: typing prompt + tapping send" >&2
  adb shell input text "${MESSAGE// /%s}" >/dev/null 2>&1 || true
  sleep 1
  if [[ -n "$SEND_TAP" ]]; then
    # shellcheck disable=SC2086
    adb shell input tap $SEND_TAP >/dev/null 2>&1 || true
  else
    adb shell input keyevent 66 >/dev/null 2>&1 || true # KEYCODE_ENTER
  fi
else
  echo "" >&2
  echo ">>> SEND NOW: type a prompt in the open chat on $SERIAL and send it," >&2
  echo ">>> then press Enter here to continue capturing." >&2
  read -r _ || true
fi

echo "[iroh-device-gate] capturing for ${CAPTURE_SECS}s..." >&2
sleep "$CAPTURE_SECS"
cleanup
sleep 1

echo "[iroh-device-gate] logcat=$LOGCAT_FILE net=$NET_FILE" >&2

set +e
run_gate_cli \
  --logcat "$LOGCAT_FILE" \
  --net "$NET_FILE" \
  --app-uid "$APP_UID" \
  --http-port "$HTTP_PORT" \
  --conversation "$CONVERSATION" \
  --json
STATUS=$?
set -e

if [[ $STATUS -eq 0 ]]; then
  echo "[iroh-device-gate] PASS"
else
  echo "[iroh-device-gate] FAIL (exit $STATUS); trace bundle: $OUT_DIR" >&2
fi
exit $STATUS
