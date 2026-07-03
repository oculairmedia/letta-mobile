#!/usr/bin/env bash
set -euo pipefail

# Hermetic Iroh probe wrapper.
# Environment:
#   LETTA_PROBE_ADMIN_BASE       HTTP admin API base (default: http://127.0.0.1:8291)
#   LETTA_PROBE_APP_SERVER_URL   WebSocket app-server URL (default: ws://127.0.0.1:4500)
#   LETTA_IROH_AUTH_TOKEN        Bearer token shared by serve/probe commands
#   LETTA_PROBE_IROH_PORT        UDP port for the temporary Iroh endpoint

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_DIR="$ROOT_DIR/android-compose"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"

ADMIN_BASE="${LETTA_PROBE_ADMIN_BASE:-http://127.0.0.1:8291}"
APP_SERVER_URL="${LETTA_PROBE_APP_SERVER_URL:-ws://127.0.0.1:4500}"
AUTH_TOKEN="${LETTA_IROH_AUTH_TOKEN:-probe-token-$(date +%s)-$RANDOM}"
PORT="${LETTA_PROBE_IROH_PORT:-$(python3 - <<'PORTPY'
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.bind(('127.0.0.1', 0))
print(s.getsockname()[1])
s.close()
PORTPY
)}"
SECRET_KEY_FILE="$(mktemp -u "${TMPDIR:-/tmp}/letta-iroh-probe-key.XXXXXX")"
SERVE_LOG="$(mktemp "${TMPDIR:-/tmp}/letta-iroh-serve.XXXXXX.log")"
PROBE_LOG="$(mktemp "${TMPDIR:-/tmp}/letta-iroh-probe.XXXXXX.log")"
SERVE_PID=""

cleanup() {
  local status=$?
  if [[ -n "${SERVE_PID}" ]] && kill -0 "${SERVE_PID}" 2>/dev/null; then
    kill "${SERVE_PID}" 2>/dev/null || true
    wait "${SERVE_PID}" 2>/dev/null || true
  fi
  rm -f "${SECRET_KEY_FILE}"
  echo "[iroh-probe-hermetic] serve log: ${SERVE_LOG}" >&2
  echo "[iroh-probe-hermetic] probe log: ${PROBE_LOG}" >&2
  exit "${status}"
}
trap cleanup EXIT INT TERM

cd "${COMPOSE_DIR}"

quote_cli_args() {
  local quoted=""
  local arg
  for arg in "$@"; do
    printf -v arg '%q' "${arg}"
    quoted+=" ${arg}"
  done
  printf '%s' "${quoted# }"
}

SERVE_ARGS="$(quote_cli_args \
  app-server-serve-iroh \
  --iroh-port "${PORT}" \
  --iroh-secret-key-file "${SECRET_KEY_FILE}" \
  --auth-token "${AUTH_TOKEN}" \
  --admin-base-url "${ADMIN_BASE}" \
  --app-server-url "${APP_SERVER_URL}" \
)"
./gradlew --quiet :cli:run -PcliArgs="${SERVE_ARGS}" >"${SERVE_LOG}" 2>&1 &
SERVE_PID=$!

ADDRESS=""
for _ in $(seq 1 120); do
  if ! kill -0 "${SERVE_PID}" 2>/dev/null; then
    echo "[iroh-probe-hermetic] serve process exited before readiness" >&2
    tail -200 "${SERVE_LOG}" >&2 || true
    exit 1
  fi
  READY_AND_ADDRESS="$(python3 - "${SERVE_LOG}" <<'ADDRPY' || true
import re, sys
text = open(sys.argv[1], errors='ignore').read()
if 'admin_rpc handlers registered' not in text or 'Listening on Iroh' not in text:
    sys.exit(0)
short = re.findall(r'Short URL: (iroh://\S+)', text)
ticket = re.findall(r'Ticket: (\S+)', text)
if ticket:
    print(ticket[-1])
elif short:
    print(short[-1])
ADDRPY
)"
  if [[ -n "${READY_AND_ADDRESS}" ]]; then
    ADDRESS="${READY_AND_ADDRESS}"
    break
  fi
  sleep 1
done

if [[ -z "${ADDRESS}" ]]; then
  echo "[iroh-probe-hermetic] timed out waiting for iroh address" >&2
  tail -200 "${SERVE_LOG}" >&2 || true
  exit 1
fi

echo "[iroh-probe-hermetic] address=${ADDRESS}" >&2
set +e
PROBE_ARGS="$(quote_cli_args app-server-iroh-probe --address "${ADDRESS}" --token "${AUTH_TOKEN}" --json "$@")"
./gradlew --quiet :cli:run -PcliArgs="${PROBE_ARGS}" | tee "${PROBE_LOG}"
PROBE_STATUS=${PIPESTATUS[0]}
set -e
exit "${PROBE_STATUS}"
