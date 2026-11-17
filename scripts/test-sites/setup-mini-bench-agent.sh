#!/usr/bin/env bash
set -euo pipefail
# Run on the mini test server, from a checkout containing this script.
root=/opt/letta-test-server
fixture="$root/bench-fixture"
source_dir="$(cd "$(dirname "$0")/mini-bench" && pwd)"
[[ -d "$root/lc-0.34.39" ]] || { echo 'Run only on the mini test server' >&2; exit 1; }
mkdir -p "$fixture"
stop() {
  local name="$1" pid
  if [[ -f "$fixture/$name.pid" ]]; then
    pid=$(cat "$fixture/$name.pid")
    if [[ -r "/proc/$pid/cmdline" ]] && tr '\0' ' ' < "/proc/$pid/cmdline" | grep -Fq "$fixture/$name.cjs"; then
      kill "$pid"
      for _ in {1..50}; do [[ ! -e "/proc/$pid" ]] && break; sleep 0.1; done
    fi
    rm -f "$fixture/$name.pid"
  fi
}
if [[ "${1:-}" == --teardown ]]; then stop worker; stop model; exit 0; fi
[[ $# == 0 ]] || { echo 'Usage: setup-mini-bench-agent.sh [--teardown]' >&2; exit 2; }
stop worker
stop model
install -m 644 "$source_dir/model.cjs" "$source_dir/worker.cjs" "$source_dir/create.cjs" "$fixture/"
if ss -ltn | grep -qE '127\.0\.0\.1:11434[[:space:]]'; then echo 'Port 11434 is already in use' >&2; exit 1; fi
setsid node "$fixture/model.cjs" >> "$fixture/model.out" 2>&1 < /dev/null &
echo $! > "$fixture/model.pid"
if [[ ! -s "$fixture/scope.json" ]]; then node "$fixture/create.cjs"; fi
BENCH_SECONDS="${BENCH_SECONDS:-40}" setsid node "$fixture/worker.cjs" >> "$fixture/worker.out" 2>&1 < /dev/null &
echo $! > "$fixture/worker.pid"
cat "$fixture/scope.json"
