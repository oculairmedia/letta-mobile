#!/usr/bin/env bash
# Verify that the deployed wrapper has completed App Server recovery.

set -uo pipefail

UNIT="${IROH_WRAPPER_UNIT:-meridian-iroh-wrapper.service}"
LOG_FILE="${IROH_WRAPPER_LOG:-/var/log/meridian-iroh-wrapper.log}"
TIMEOUT_SECONDS="${IROH_WRAPPER_READY_TIMEOUT_SECONDS:-130}"
POLL_SECONDS="${IROH_WRAPPER_READY_POLL_SECONDS:-1}"
START_OFFSET="${IROH_WRAPPER_READY_START_OFFSET:-}"

die() {
  printf 'FAIL  %s\n' "$*" >&2
  exit 1
}

case "$TIMEOUT_SECONDS" in
  ''|*[!0-9]*) die "IROH_WRAPPER_READY_TIMEOUT_SECONDS must be a positive integer" ;;
  0) die "IROH_WRAPPER_READY_TIMEOUT_SECONDS must be greater than zero" ;;
esac

case "$POLL_SECONDS" in
  ''|*[!0-9]*) die "IROH_WRAPPER_READY_POLL_SECONDS must be a positive integer" ;;
  0) die "IROH_WRAPPER_READY_POLL_SECONDS must be greater than zero" ;;
esac

[ -r "$LOG_FILE" ] || die "wrapper log is not readable: $LOG_FILE"

if [ -n "$START_OFFSET" ]; then
  case "$START_OFFSET" in
    *[!0-9]*) die "IROH_WRAPPER_READY_START_OFFSET must be a non-negative integer" ;;
  esac
fi

MAIN_PID="$(systemctl show "$UNIT" -p MainPID --value 2>/dev/null)" ||
  die "could not resolve MainPID for $UNIT"
case "$MAIN_PID" in
  ''|*[!0-9]*|0) die "$UNIT has no live MainPID" ;;
esac

# Read only bytes appended after invocation. Historical ready lines from the
# previous process must never satisfy this deployment gate.
if [ -z "$START_OFFSET" ]; then
  START_OFFSET="$(stat -c %s "$LOG_FILE")" || die "could not stat wrapper log: $LOG_FILE"
fi
DEADLINE=$((SECONDS + TIMEOUT_SECONDS))

while [ "$SECONDS" -lt "$DEADLINE" ]; do
  CURRENT_PID="$(systemctl show "$UNIT" -p MainPID --value 2>/dev/null || true)"
  [ "$CURRENT_PID" = "$MAIN_PID" ] ||
    die "$UNIT restarted while readiness was being measured (expected pid=$MAIN_PID actual=${CURRENT_PID:-none})"

  NEW_LOG="$(tail -c "+$((START_OFFSET + 1))" "$LOG_FILE" 2>/dev/null || true)"
  if printf '%s\n' "$NEW_LOG" | grep -qF 'Telemetry/AppServerReconnect: generation.ready'; then
    printf 'PASS  %s reached generation.ready (pid=%s)\n' "$UNIT" "$MAIN_PID"
    exit 0
  fi
  if printf '%s\n' "$NEW_LOG" | grep -qF 'Telemetry/AppServerReconnect: gave_up'; then
    die "$UNIT gave up before reaching generation.ready (pid=$MAIN_PID)"
  fi
  sleep "$POLL_SECONDS"
done

die "$UNIT did not reach generation.ready within ${TIMEOUT_SECONDS}s (pid=$MAIN_PID)"
