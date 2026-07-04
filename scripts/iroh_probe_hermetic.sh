#!/usr/bin/env bash
set -euo pipefail

# Hermetic Iroh probe gate (letta-mobile-q5iiv, epic phase P1).
#
# Boots a fully local stack — a deterministic stub app-server + its HTTP admin
# API served over a REAL Iroh endpoint with a throwaway key on free ports —
# then runs `app-server-iroh-probe` against the printed ticket and propagates
# its exit code. No letta backend, no admin-shim, no devices.
#
# Usage:
#   scripts/iroh_probe_hermetic.sh [probe args...]
#     # default probe args: --scenario all --idle-ms 2000 --timeout-ms 90000
#
# Acceptance self-check (red path — the gate must FAIL when a known regression
# is injected; the wrapper exits 0 only if the probe exits NONZERO):
#   LETTA_PROBE_SELF_CHECK=suppress-terminal scripts/iroh_probe_hermetic.sh
#     # stub drops terminal stop_reason frames -> probe must report
#     # timeout_missing_terminal and exit nonzero
#   LETTA_PROBE_SELF_CHECK=untyped-frames scripts/iroh_probe_hermetic.sh
#     # stub strips message_type from assistant deltas -> probe must report
#     # untyped_frames_* and exit nonzero
#   LETTA_PROBE_SELF_CHECK=wrong-address scripts/iroh_probe_hermetic.sh
#     # probe dials a dead ticket -> dial/setup failure -> exit nonzero
#
# Environment:
#   LETTA_IROH_AUTH_TOKEN   Bearer token shared by stub/probe (random default)
#   LETTA_PROBE_IROH_PORT   UDP port for the stub Iroh endpoint (random default)
#   LETTA_PROBE_SELF_CHECK  suppress-terminal | untyped-frames | wrong-address

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_DIR="$ROOT_DIR/android-compose"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"

AUTH_TOKEN="${LETTA_IROH_AUTH_TOKEN:-probe-token-$(date +%s)-$RANDOM}"
SELF_CHECK="${LETTA_PROBE_SELF_CHECK:-}"
PORT="${LETTA_PROBE_IROH_PORT:-$(python3 - <<'PORTPY'
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.bind(('127.0.0.1', 0))
print(s.getsockname()[1])
s.close()
PORTPY
)}"
# The key store generates a fresh key only when the file does NOT exist, so
# hand it a path inside a throwaway directory instead of a pre-created file.
SECRET_KEY_DIR="$(mktemp -d "${TMPDIR:-/tmp}/letta-iroh-probe-key.XXXXXX")"
SECRET_KEY_FILE="${SECRET_KEY_DIR}/iroh.key"
SERVE_LOG="$(mktemp "${TMPDIR:-/tmp}/letta-iroh-serve.XXXXXX.log")"
PROBE_LOG="$(mktemp "${TMPDIR:-/tmp}/letta-iroh-probe.XXXXXX.log")"
SERVE_PID=""

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
  if [[ -n "${SERVE_PID}" ]] && kill -0 "${SERVE_PID}" 2>/dev/null; then
    kill_process_tree "${SERVE_PID}"
    wait "${SERVE_PID}" 2>/dev/null || true
  fi
  rm -rf "${SECRET_KEY_DIR}"
  echo "[iroh-probe-hermetic] serve log: ${SERVE_LOG}" >&2
  echo "[iroh-probe-hermetic] probe log: ${PROBE_LOG}" >&2
  exit "${status}"
}
trap cleanup EXIT INT TERM

cd "${COMPOSE_DIR}"

# Quote CLI args for gradle's -PcliArgs (parsed by splitCliArgs in
# cli/build.gradle.kts: double quotes group, backslash escapes inside quotes).
quote_cli_args() {
  python3 - "$@" <<'QUOTEPY'
import sys

def quote(arg: str) -> str:
    return '"' + arg.replace('\\', '\\\\').replace('"', '\\"') + '"'

print(' '.join(quote(arg) for arg in sys.argv[1:]))
QUOTEPY
}

# Pre-build the CLI once so the backgrounded serve invocation and the probe
# invocation are both cheap `run` tasks instead of two concurrent full builds.
echo "[iroh-probe-hermetic] pre-building :cli..." >&2
./gradlew --quiet :cli:compileDebugUnitTestKotlin :cli:processDebugUnitTestJavaRes >/dev/null

STUB_ENV=("IGNORED_EMPTY_ENV=1")
case "${SELF_CHECK}" in
  suppress-terminal) STUB_ENV+=("LETTA_PROBE_STUB_SUPPRESS_TERMINAL=1") ;;
  untyped-frames) STUB_ENV+=("LETTA_PROBE_STUB_UNTYPED_FRAMES=1") ;;
  wrong-address|"") ;;
  *)
    echo "[iroh-probe-hermetic] unknown LETTA_PROBE_SELF_CHECK: ${SELF_CHECK}" >&2
    exit 2
    ;;
esac

SERVE_ARGS="$(quote_cli_args \
  app-server-serve-iroh-stub \
  --iroh-port "${PORT}" \
  --iroh-secret-key-file "${SECRET_KEY_FILE}" \
  --auth-token "${AUTH_TOKEN}" \
  --admin-port 0 \
)"
env "${STUB_ENV[@]}" ./gradlew --quiet :cli:run -PcliArgs="${SERVE_ARGS}" >"${SERVE_LOG}" 2>&1 &
SERVE_PID=$!

ADDRESS=""
ADMIN_BASE=""
for _ in $(seq 1 180); do
  if ! kill -0 "${SERVE_PID}" 2>/dev/null; then
    echo "[iroh-probe-hermetic] serve process exited before readiness" >&2
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
if ticket and admin:
    print(ticket[-1])
    print(admin[-1])
ADDRPY
)"
  if [[ -n "${READY}" ]]; then
    ADDRESS="$(sed -n 1p <<<"${READY}")"
    ADMIN_BASE="$(sed -n 2p <<<"${READY}")"
    break
  fi
  sleep 1
done

if [[ -z "${ADDRESS}" ]]; then
  echo "[iroh-probe-hermetic] timed out waiting for iroh address" >&2
  tail -200 "${SERVE_LOG}" >&2 || true
  exit 1
fi

if [[ "${SELF_CHECK}" == "wrong-address" ]]; then
  # Red-path validation: dial a node id that is not listening. The probe must
  # fail its dial/setup stage and exit nonzero.
  ADDRESS="iroh://$(printf '0%.0s' $(seq 1 64))@127.0.0.1:1"
fi

echo "[iroh-probe-hermetic] address=${ADDRESS}" >&2
echo "[iroh-probe-hermetic] admin_base=${ADMIN_BASE}" >&2

PROBE_DEFAULT_ARGS=(--scenario all --idle-ms 2000 --timeout-ms 90000)
if [[ "${SELF_CHECK}" == "wrong-address" ]]; then
  PROBE_DEFAULT_ARGS=(--scenario idle-send --idle-ms 1000 --timeout-ms 20000)
fi
PROBE_USER_ARGS=("$@")
if [[ ${#PROBE_USER_ARGS[@]} -eq 0 ]]; then
  PROBE_USER_ARGS=("${PROBE_DEFAULT_ARGS[@]}")
fi

set +e
PROBE_ARGS="$(quote_cli_args \
  app-server-iroh-probe \
  --backend "${ADDRESS}" \
  --token "${AUTH_TOKEN}" \
  --agent-id probe-agent \
  --admin-base-url "${ADMIN_BASE}" \
  --json \
  "${PROBE_USER_ARGS[@]}" \
)"
./gradlew --quiet :cli:run -PcliArgs="${PROBE_ARGS}" | tee "${PROBE_LOG}"
PROBE_STATUS=${PIPESTATUS[0]}
set -e

if [[ -n "${SELF_CHECK}" ]]; then
  if [[ "${PROBE_STATUS}" -eq 0 ]]; then
    echo "[iroh-probe-hermetic] SELF-CHECK FAILED: probe passed despite injected regression '${SELF_CHECK}'" >&2
    exit 1
  fi
  echo "[iroh-probe-hermetic] self-check ok: injected regression '${SELF_CHECK}' was detected (probe exit ${PROBE_STATUS})" >&2
  exit 0
fi

exit "${PROBE_STATUS}"
