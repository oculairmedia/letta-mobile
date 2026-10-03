#!/usr/bin/env bash
# Stop the System 1 (Laya) service started by scripts/start-system1.sh.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_FILE="$REPO_ROOT/reports/system1-laya.pid"

if [[ ! -f "$PID_FILE" ]]; then
  echo "No System 1 pid file; nothing to stop." >&2
  exit 0
fi

PID="$(cat "$PID_FILE")"
if kill -0 "$PID" 2>/dev/null; then
  kill "$PID"
  echo "Stopped System 1 (pid $PID)."
else
  echo "System 1 (pid $PID) was not running."
fi
rm -f "$PID_FILE"
