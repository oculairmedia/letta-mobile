#!/usr/bin/env bash

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFY="$SCRIPT_DIR/verify-iroh-wrapper-ready.sh"
WORK="$(mktemp -d -t verify-iroh-ready-XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

PASS=0
FAIL=0

assert_case() {
  local name="$1" expected="$2" needle="$3"
  shift 3
  local output status
  output="$($@ 2>&1)"
  status=$?
  if [ "$status" = "$expected" ] && printf '%s' "$output" | grep -qF "$needle"; then
    PASS=$((PASS + 1))
    printf 'PASS  %s\n' "$name"
  else
    FAIL=$((FAIL + 1))
    printf 'FAIL  %s expected_status=%s actual_status=%s needle=%q output=%q\n' \
      "$name" "$expected" "$status" "$needle" "$output" >&2
  fi
}

make_systemctl() {
  local dir="$1" pid_file="$2"
  mkdir -p "$dir"
  cat >"$dir/systemctl" <<EOF
#!/usr/bin/env bash
cat "$pid_file"
EOF
  chmod +x "$dir/systemctl"
}

case_ready() {
  local dir="$WORK/ready" log="$WORK/ready.log" pid="$WORK/ready.pid"
  make_systemctl "$dir" "$pid"
  printf '101\n' >"$pid"
  : >"$log"
  (sleep 0.1; printf '[INFO] Telemetry/AppServerReconnect: generation.ready attempt=0\n' >>"$log") &
  PATH="$dir:$PATH" IROH_WRAPPER_LOG="$log" IROH_WRAPPER_READY_START_OFFSET=0 IROH_WRAPPER_READY_TIMEOUT_SECONDS=2 \
    IROH_WRAPPER_READY_POLL_SECONDS=1 bash "$VERIFY"
}

case_stale_ready() {
  local dir="$WORK/stale" log="$WORK/stale.log" pid="$WORK/stale.pid"
  make_systemctl "$dir" "$pid"
  printf '102\n' >"$pid"
  printf '[INFO] Telemetry/AppServerReconnect: generation.ready attempt=0\n' >"$log"
  local offset
  offset="$(stat -c %s "$log")"
  PATH="$dir:$PATH" IROH_WRAPPER_LOG="$log" IROH_WRAPPER_READY_START_OFFSET="$offset" IROH_WRAPPER_READY_TIMEOUT_SECONDS=1 \
    IROH_WRAPPER_READY_POLL_SECONDS=1 bash "$VERIFY"
}

case_pid_changed() {
  local dir="$WORK/pid" log="$WORK/pid.log" pid="$WORK/pid.pid"
  make_systemctl "$dir" "$pid"
  printf '103\n' >"$pid"
  : >"$log"
  (sleep 0.1; printf '104\n' >"$pid") &
  PATH="$dir:$PATH" IROH_WRAPPER_LOG="$log" IROH_WRAPPER_READY_START_OFFSET=0 IROH_WRAPPER_READY_TIMEOUT_SECONDS=2 \
    IROH_WRAPPER_READY_POLL_SECONDS=1 bash "$VERIFY"
}

case_gave_up() {
  local dir="$WORK/gave-up" log="$WORK/gave-up.log" pid="$WORK/gave-up.pid"
  make_systemctl "$dir" "$pid"
  printf '105\n' >"$pid"
  : >"$log"
  (sleep 0.1; printf '[INFO] Telemetry/AppServerReconnect: gave_up reason=boom\n' >>"$log") &
  PATH="$dir:$PATH" IROH_WRAPPER_LOG="$log" IROH_WRAPPER_READY_START_OFFSET=0 IROH_WRAPPER_READY_TIMEOUT_SECONDS=2 \
    IROH_WRAPPER_READY_POLL_SECONDS=1 bash "$VERIFY"
}

assert_case "fresh ready succeeds" 0 "reached generation.ready" case_ready
assert_case "stale ready cannot pass" 1 "did not reach generation.ready" case_stale_ready
assert_case "pid replacement fails" 1 "restarted while readiness was being measured" case_pid_changed
assert_case "terminal give-up fails" 1 "gave up before reaching generation.ready" case_gave_up

printf 'pass=%d fail=%d\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
