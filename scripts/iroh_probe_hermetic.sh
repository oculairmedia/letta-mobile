#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_DIR="$ROOT_DIR/android-compose"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"

ADMIN_BASE="${LETTA_PROBE_ADMIN_BASE:-http://127.0.0.1:8291}"
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

./gradlew --quiet :cli:run -PcliArgs="app-server-serve-iroh --iroh-port ${PORT} --iroh-secret-key-file ${SECRET_KEY_FILE} --auth-token ${AUTH_TOKEN} --admin-base-url ${ADMIN_BASE}" >"${SERVE_LOG}" 2>&1 &
SERVE_PID=$!

ADDRESS=""
for _ in $(seq 1 120); do
  if ! kill -0 "${SERVE_PID}" 2>/dev/null; then
    echo "[iroh-probe-hermetic] serve process exited before readiness" >&2
    tail -200 "${SERVE_LOG}" >&2 || true
    exit 1
  fi
  ADDRESS="$(python3 - "${SERVE_LOG}" <<'ADDRPY' || true
import re, sys
text = open(sys.argv[1], errors='ignore').read()
short = re.findall(r'Short URL: (iroh://\S+)', text)
ticket = re.findall(r'Ticket: (\S+)', text)
if ticket:
    print(ticket[-1])
elif short:
    print(short[-1])
ADDRPY
)"
  if [[ -n "${ADDRESS}" ]]; then
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
./gradlew --quiet :cli:run -PcliArgs="app-server-iroh-probe --address ${ADDRESS} --token ${AUTH_TOKEN} --json $*" | tee "${PROBE_LOG}"
PROBE_STATUS=${PIPESTATUS[0]}
set -e
exit "${PROBE_STATUS}"
