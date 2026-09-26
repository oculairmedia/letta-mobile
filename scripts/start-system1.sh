#!/usr/bin/env bash
# Start the System 1 (Laya) service in the background.
#
#   scripts/start-system1.sh [--port 8771] [--device cuda|cpu|mps]
#
# Logs go to reports/system1-laya.log; the PID is written next to it so
# scripts/stop-system1.sh can shut it down.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICE_DIR="$REPO_ROOT/tools/system1-laya"
LOG_DIR="$REPO_ROOT/reports"
LOG_FILE="$LOG_DIR/system1-laya.log"
PID_FILE="$LOG_DIR/system1-laya.pid"
PYTHON_BIN="${PYTHON_BIN:-python3}"

mkdir -p "$LOG_DIR"

if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "System 1 already running (pid $(cat "$PID_FILE"))." >&2
  exit 0
fi

"$PYTHON_BIN" "$SERVICE_DIR/start_server.py" "$@" >"$LOG_FILE" 2>&1 &
echo $! >"$PID_FILE"

echo "System 1 starting (pid $(cat "$PID_FILE")); logs: $LOG_FILE"
echo "Model load takes ~30s on a cold Hugging Face cache. Poll /health until it reports ok:"
echo "  curl -s http://127.0.0.1:${SYSTEM1_PORT:-8771}/health"
