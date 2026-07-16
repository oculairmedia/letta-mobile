#!/usr/bin/env bash
set -euo pipefail

# Hermetic headless TWO-CLIENT live-sync probe (letta-mobile-r3i1z, deliverable B).
#
# Boots the SAME deterministic stub app-server used by the single-client probe
# gate (a real Iroh endpoint + local HTTP admin API, throwaway key, free ports),
# then runs `app-server-iroh-two-client-probe` against the printed ticket. The
# stub shares ONE ConnectionRegistry across connections, so its server-side turn
# fanout is identical to the deployed wrapper — the whole two-client loop
# (A sends -> B observes, B sends -> A observes, B redials -> auto-re-subscribe
# -> A sends -> B observes) runs with NO devices and NO human.
#
# This is the headless acceptance test for the multi-client live-sync feature:
# it removes the need for two physical screens.
#
# Usage:
#   scripts/iroh_two_client_hermetic.sh [extra two-client-probe args...]
#     # default args: --timeout-ms 90000
#
# Environment:
#   LETTA_IROH_AUTH_TOKEN   Bearer token shared by stub + both clients (random default)
#   LETTA_PROBE_IROH_PORT   UDP port for the stub Iroh endpoint (default 0 = kernel-assigned)

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_DIR="$ROOT_DIR/android-compose"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"

AUTH_TOKEN="${LETTA_IROH_AUTH_TOKEN:-2client-token-$(date +%s)-$RANDOM}"
PORT="${LETTA_PROBE_IROH_PORT:-0}"
SECRET_KEY_DIR="$(mktemp -d "${TMPDIR:-/tmp}/letta-iroh-2client-key.XXXXXX")"
SECRET_KEY_FILE="${SECRET_KEY_DIR}/iroh.key"
SERVE_LOG="$(mktemp "${TMPDIR:-/tmp}/letta-iroh-2client-serve.XXXXXX.log")"
PROBE_LOG="$(mktemp "${TMPDIR:-/tmp}/letta-iroh-2client-probe.XXXXXX.log")"
SERVE_PID=""
STUB_PID=""

kill_process_tree() {
  local pid="$1"
  local child
  while read -r child; do
    [[ -z "${child}" ]] && continue
    kill_process_tree "${child}"
  done < <(pgrep -P "${pid}" 2>/dev/null || true)
  kill "${pid}" 2>/dev/null || true
}

cleanup() {
  local status=$?
  if [[ -n "${STUB_PID}" ]] && kill -0 "${STUB_PID}" 2>/dev/null; then
    kill "${STUB_PID}" 2>/dev/null || true
  fi
  if [[ -n "${SERVE_PID}" ]] && kill -0 "${SERVE_PID}" 2>/dev/null; then
    kill_process_tree "${SERVE_PID}"
    wait "${SERVE_PID}" 2>/dev/null || true
  fi
  rm -rf "${SECRET_KEY_DIR}"
  echo "[iroh-2client-hermetic] serve log: ${SERVE_LOG}" >&2
  echo "[iroh-2client-hermetic] probe log: ${PROBE_LOG}" >&2
  exit "${status}"
}
trap cleanup EXIT INT TERM

cd "${COMPOSE_DIR}"

quote_cli_args() {
  python3 - "$@" <<'QUOTEPY'
import sys

def quote(arg: str) -> str:
    return '"' + arg.replace('\\', '\\\\').replace('"', '\\"') + '"'

print(' '.join(quote(arg) for arg in sys.argv[1:]))
QUOTEPY
}

echo "[iroh-2client-hermetic] pre-building :cli..." >&2
./gradlew --quiet :cli:compileDebugUnitTestKotlin :cli:processDebugUnitTestJavaRes >/dev/null

SERVE_ARGS="$(quote_cli_args \
  app-server-serve-iroh-stub \
  --iroh-port "${PORT}" \
  --iroh-secret-key-file "${SECRET_KEY_FILE}" \
  --auth-token "${AUTH_TOKEN}" \
  --admin-port 0 \
)"
./gradlew --quiet :cli:run -PcliArgs="${SERVE_ARGS}" >"${SERVE_LOG}" 2>&1 &
SERVE_PID=$!

ADDRESS=""
ADMIN_BASE=""
for _ in $(seq 1 180); do
  if ! kill -0 "${SERVE_PID}" 2>/dev/null; then
    echo "[iroh-2client-hermetic] serve process exited before readiness" >&2
    tail -200 "${SERVE_LOG}" >&2 || true
    exit 1
  fi
  READY="$(python3 - "${SERVE_LOG}" <<'ADDRPY' || true
import re, sys
text = open(sys.argv[1], errors='ignore').read()
if 'admin_rpc handlers registered' not in text or 'Listening on Iroh' not in text:
    sys.exit(0)
ticket = re.findall(r'Ticket: (\S+)', text)
admin = re.findall(r'Admin base: (\S+)', text)
pid = re.findall(r'\[iroh-stub-server\] PID: (\d+)', text)
if ticket and admin:
    print(ticket[-1])
    print(admin[-1])
    print(pid[-1] if pid else '')
ADDRPY
)"
  if [[ -n "${READY}" ]]; then
    ADDRESS="$(sed -n 1p <<<"${READY}")"
    ADMIN_BASE="$(sed -n 2p <<<"${READY}")"
    STUB_PID="$(sed -n 3p <<<"${READY}")"
    break
  fi
  sleep 1
done

if [[ -z "${ADDRESS}" ]]; then
  echo "[iroh-2client-hermetic] timed out waiting for iroh address" >&2
  tail -200 "${SERVE_LOG}" >&2 || true
  exit 1
fi

echo "[iroh-2client-hermetic] address=${ADDRESS}" >&2

PROBE_DEFAULT_ARGS=(--timeout-ms 90000)
PROBE_USER_ARGS=("$@")
if [[ ${#PROBE_USER_ARGS[@]} -eq 0 ]]; then
  PROBE_USER_ARGS=("${PROBE_DEFAULT_ARGS[@]}")
fi

set +e
PROBE_ARGS="$(quote_cli_args \
  app-server-iroh-two-client-probe \
  --backend "${ADDRESS}" \
  --token "${AUTH_TOKEN}" \
  --agent-id probe-agent \
  --json \
  "${PROBE_USER_ARGS[@]}" \
)"
./gradlew --quiet :cli:run -PcliArgs="${PROBE_ARGS}" | tee "${PROBE_LOG}"
PROBE_STATUS=${PIPESTATUS[0]}
set -e

exit "${PROBE_STATUS}"
