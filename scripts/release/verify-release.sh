#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"

DEVICE="${DEVICE:-}"
APK="${APK:-}"
BASE_URL="${BASE_URL:-${LETTA_URL:-http://192.168.50.90:8289}}"
API_KEY="${API_KEY:-${LETTA_TOKEN:-}}"
AGENT="${AGENT:-}"
CONV="${CONV:-}"
ITERATIONS="${ITERATIONS:-6}"
INTERVAL="${INTERVAL:-10}"
STREAM_TIMEOUT="${STREAM_TIMEOUT:-60}"
CLI="${CLI:-cli/letta-cli}"
ANDROID_DIR="${ANDROID_DIR:-android-compose}"
GRADLEW="${GRADLEW:-./gradlew}"
MAKE_BIN="${make:-make}"
JSON_MODE=false

usage() {
  cat <<'EOF'
Usage: scripts/release/verify-release.sh [--json]

Reads release gate configuration from the environment:
  DEVICE, APK, BASE_URL/LETTA_URL, API_KEY/LETTA_TOKEN, AGENT, CONV,
  ITERATIONS, INTERVAL, STREAM_TIMEOUT

Outputs a markdown report to stdout and reports/verify-release-<timestamp>.md.
With --json, prints structured JSON to stdout and still writes the markdown report.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --json)
      JSON_MODE=true
      shift
      ;;
    --help)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown argument: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

REPORTS_DIR="$REPO_ROOT/reports"
LEDGER_PATH="$REPO_ROOT/docs/RELEASE-GATE-LEDGER.md"
GATES_CONFIG_PATH="$REPO_ROOT/gates.yaml"
mkdir -p "$REPORTS_DIR"

timestamp_utc="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
report_path="$REPORTS_DIR/verify-release-$timestamp_utc.md"
results_tsv="$(mktemp)"
markdown_tmp="$(mktemp)"
json_tmp="$(mktemp)"

cleanup() {
  rm -f "$results_tsv" "$markdown_tmp" "$json_tmp"
}
trap cleanup EXIT

log_gate_line() {
  local line="$1"
  if [[ "$JSON_MODE" == "true" ]]; then
    printf '%s\n' "$line" >&2
  else
    printf '%s\n' "$line"
  fi
}

record_result() {
  local gate="$1"
  local level="$2"
  local blocking="$3"
  local status="$4"
  local duration_ms="$5"
  local detail="$6"
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$gate" "$level" "$blocking" "$status" "$duration_ms" "$detail" >>"$results_tsv"
  log_gate_line "$gate $status ${duration_ms}ms${detail:+ $detail}"
}

summarize_output() {
  python3 - "$1" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
text = path.read_text() if path.exists() else ""
lines = [line.strip() for line in text.splitlines() if line.strip()]
priority_markers = (
    "FAIL ",
    "FAILURE:",
    "ERROR:",
    "Timed out",
    "Unexpected uninstall failure",
    "Install failed",
    "Launch failed",
    "Auth injection failed",
    "NoSuchAlgorithmException",
    "KeyStoreException",
    "0 events received",
)
for line in lines:
    if any(marker in line for marker in priority_markers):
        print(line)
        break
else:
    ignored_prefixes = (
        "make[",
        "BUILD SUCCESSFUL",
        "BUILD FAILED",
    )
    for line in reversed(lines):
        if line.startswith(ignored_prefixes):
            continue
        print(line)
        break
    else:
        print(lines[-1] if lines else "")
PY
}

sanitize_detail() {
  python3 - "$1" <<'PY'
import sys

text = sys.argv[1].replace("\t", " ").replace("\n", " ").strip()
print(" ".join(text.split()))
PY
}

run_gate() {
  local gate="$1"
  local level="$2"
  local blocking="$3"
  local command="$4"
  local output_file
  output_file="$(mktemp)"
  local start_ms end_ms duration_ms status detail exit_code
  start_ms="$(python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
)"
  if bash -lc "$command" >"$output_file" 2>&1; then
    exit_code=0
  else
    exit_code=$?
  fi
  end_ms="$(python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
)"
  duration_ms=$((end_ms - start_ms))
  detail="$(summarize_output "$output_file")"
  detail="$(sanitize_detail "$detail")"
  if [[ $exit_code -eq 0 ]]; then
    status="PASS"
  else
    if [[ "$blocking" == "true" ]]; then
      status="FAIL"
    else
      status="ADVISORY"
    fi
  fi
  record_result "$gate" "$level" "$blocking" "$status" "$duration_ms" "$detail"
  rm -f "$output_file"
}

skip_gate() {
  local gate="$1"
  local level="$2"
  local blocking="$3"
  local detail="$4"
  detail="$(sanitize_detail "$detail")"
  record_result "$gate" "$level" "$blocking" "SKIP" "0" "$detail"
}

gate_field() {
  python3 - "$GATES_CONFIG_PATH" "$1" "$2" <<'PY'
import pathlib
import sys

config_path = pathlib.Path(sys.argv[1])
gate = sys.argv[2]
field = sys.argv[3]

if not config_path.exists():
    raise SystemExit(f"Missing gate config: {config_path}")

gates = {}
current = None
for raw_line in config_path.read_text().splitlines():
    if not raw_line.strip() or raw_line.lstrip().startswith('#'):
        continue
    if raw_line.startswith('gates:'):
        continue
    if raw_line.startswith('  ') and not raw_line.startswith('    '):
        current = raw_line.strip().rstrip(':')
        gates[current] = {}
        continue
    if current and raw_line.startswith('    '):
        key, value = raw_line.strip().split(':', 1)
        value = value.strip()
        if value.startswith('"') and value.endswith('"'):
            value = value[1:-1]
        gates[current][key] = value

value = gates.get(gate, {}).get(field, '')
print(value)
PY
}

gate_blocking() {
  local raw
  raw="$(gate_field "$1" "blocking")"
  if [[ "$raw" == "true" ]]; then
    printf 'true\n'
  else
    printf 'false\n'
  fi
}

gate_level() {
  gate_field "$1" "level"
}

has_device_prereqs=false
if [[ -n "$DEVICE" && -n "$APK" && -n "$BASE_URL" && -n "$API_KEY" && -n "$AGENT" && -n "$CONV" ]]; then
  has_device_prereqs=true
fi

has_stream_prereqs=false
if [[ -n "$CONV" && -n "$API_KEY" ]]; then
  has_stream_prereqs=true
fi

run_gate "verify-build" "$(gate_level verify-build)" "$(gate_blocking verify-build)" "cd '$REPO_ROOT/$ANDROID_DIR' && '$GRADLEW' :app:compileDebugKotlin"
run_gate "verify-unit-tests" "$(gate_level verify-unit-tests)" "$(gate_blocking verify-unit-tests)" "cd '$REPO_ROOT/$ANDROID_DIR' && '$GRADLEW' :app:testDebugUnitTest"

if [[ "$has_device_prereqs" == "true" ]]; then
  run_gate "verify-device-ready" "$(gate_level verify-device-ready)" "$(gate_blocking verify-device-ready)" "cd '$REPO_ROOT' && '$MAKE_BIN' verify-device-ready DEVICE='$DEVICE' APK='$APK' BASE_URL='$BASE_URL' API_KEY='$API_KEY' AGENT='$AGENT' CONV='$CONV'"
  device_status="$(python3 - "$results_tsv" <<'PY'
import csv
import sys

rows = list(csv.reader(open(sys.argv[1]), delimiter='\t'))
for row in rows:
    if row and row[0] == 'verify-device-ready':
        print(row[3])
        break
PY
)"
  if [[ "$device_status" == "PASS" ]]; then
    run_gate "verify-sync" "$(gate_level verify-sync)" "$(gate_blocking verify-sync)" "cd '$REPO_ROOT' && '$MAKE_BIN' verify-sync DEVICE='$DEVICE' LETTA_URL='$BASE_URL' AGENT='$AGENT' CONV='$CONV' ITERATIONS='$ITERATIONS' INTERVAL='$INTERVAL'"
  else
    skip_gate "verify-sync" "$(gate_level verify-sync)" "$(gate_blocking verify-sync)" "Skipped because verify-device-ready did not pass"
  fi
else
  skip_gate "verify-device-ready" "$(gate_level verify-device-ready)" "$(gate_blocking verify-device-ready)" "Missing DEVICE/APK/BASE_URL/API_KEY/AGENT/CONV prerequisites"
  skip_gate "verify-sync" "$(gate_level verify-sync)" "$(gate_blocking verify-sync)" "Missing hands-off device bootstrap prerequisites"
fi

if [[ "$has_stream_prereqs" == "true" ]]; then
  stream_text="verify-release stream probe $timestamp_utc"
  run_gate "verify-stream" "$(gate_level verify-stream)" "$(gate_blocking verify-stream)" "cd '$REPO_ROOT' && '$MAKE_BIN' verify-stream LETTA_URL='$BASE_URL' LETTA_TOKEN='$API_KEY' CONV='$CONV' STREAM_TIMEOUT='$STREAM_TIMEOUT' STREAM_SEND_TEXT='$stream_text'"
else
  skip_gate "verify-stream" "$(gate_level verify-stream)" "$(gate_blocking verify-stream)" "Missing CONV/API_KEY prerequisites for automated stream trigger"
fi

python3 - "$results_tsv" "$report_path" "$markdown_tmp" "$json_tmp" "$timestamp_utc" "$DEVICE" "$APK" "$AGENT" "$CONV" "$BASE_URL" "$GATES_CONFIG_PATH" <<'PY'
import csv
import json
import pathlib
import sys

results_path = pathlib.Path(sys.argv[1])
report_path = pathlib.Path(sys.argv[2])
markdown_tmp = pathlib.Path(sys.argv[3])
json_tmp = pathlib.Path(sys.argv[4])
timestamp = sys.argv[5]
device = sys.argv[6]
apk = sys.argv[7]
agent = sys.argv[8]
conv = sys.argv[9]
base_url = sys.argv[10]
gates_config_path = pathlib.Path(sys.argv[11])


def parse_gate_config(path: pathlib.Path):
    gates = {}
    current = None
    for raw_line in path.read_text().splitlines():
        if not raw_line.strip() or raw_line.lstrip().startswith('#'):
            continue
        if raw_line.startswith('gates:'):
            continue
        if raw_line.startswith('  ') and not raw_line.startswith('    '):
            current = raw_line.strip().rstrip(':')
            gates[current] = {}
            continue
        if current and raw_line.startswith('    '):
            key, value = raw_line.strip().split(':', 1)
            value = value.strip()
            if value.startswith('"') and value.endswith('"'):
                value = value[1:-1]
            gates[current][key] = value
    return gates


gate_config = parse_gate_config(gates_config_path)

rows = []
with results_path.open() as handle:
    for gate, level, blocking, status, duration_ms, detail in csv.reader(handle, delimiter='\t'):
        rows.append(
            {
                "name": gate,
                "level": level,
                "blocking": blocking == "true",
                "status": status,
                "duration_ms": int(duration_ms),
                "detail": detail,
            }
        )

summary = {
    "blocking_pass": sum(1 for row in rows if row["blocking"] and row["status"] == "PASS"),
    "blocking_fail": sum(1 for row in rows if row["blocking"] and row["status"] in {"FAIL", "ADVISORY"}),
    "blocking_skip": sum(1 for row in rows if row["blocking"] and row["status"] == "SKIP"),
    "advisory_fail": sum(1 for row in rows if not row["blocking"] and row["status"] in {"FAIL", "ADVISORY"}),
    "skipped": sum(1 for row in rows if row["status"] == "SKIP"),
}
exit_code = 0 if summary["blocking_fail"] == 0 else 1

report = {
    "timestamp": timestamp,
    "environment": "ci" if pathlib.os.environ.get("CI") else "local",
    "device": device or None,
    "apk": apk or None,
    "agent": agent or None,
    "conversation": conv or None,
    "base_url": base_url,
    "gates": rows,
    "summary": summary,
    "exit_code": exit_code,
    "report_path": str(report_path.relative_to(report_path.parent.parent)),
}

json_tmp.write_text(json.dumps(report, indent=2) + "\n")

lines = [
    "# Verify Release Report",
    "",
    f"- Timestamp: `{timestamp}`",
    f"- Environment: `{report['environment']}`",
    f"- Device: `{device or 'n/a'}`",
    f"- APK: `{apk or 'n/a'}`",
    f"- Agent: `{agent or 'n/a'}`",
    f"- Conversation: `{conv or 'n/a'}`",
    f"- Base URL: `{base_url}`",
    f"- Exit code: `{exit_code}`",
    "",
    "## Summary",
    "",
    f"- Blocking gates passed: **{summary['blocking_pass']}**",
    f"- Blocking gates failed: **{summary['blocking_fail']}**",
    f"- Blocking gates skipped: **{summary['blocking_skip']}**",
    f"- Advisory gates failed: **{summary['advisory_fail']}**",
    f"- Total skipped: **{summary['skipped']}**",
    "",
    "## Gate Results",
    "",
    "| Gate | Level | Blocking | Status | Duration | Detail |",
    "| --- | --- | --- | --- | ---: | --- |",
]

for row in rows:
    detail = row["detail"].replace("|", "\\|") if row["detail"] else ""
    lines.append(
        f"| {row['name']} | {row['level']} | {'yes' if row['blocking'] else 'no'} | {row['status']} | {row['duration_ms']} ms | {detail} |"
    )

lines.extend(
    [
        "",
        "## Report Artifacts",
        "",
        f"- Markdown: `{report_path.relative_to(report_path.parent.parent)}`",
        f"- JSON: `{pathlib.Path('reports') / (report_path.stem + '.json')}`",
        "",
        "## Gate Line Replay",
        "",
    ]
)

for row in rows:
    detail = f" {row['detail']}" if row["detail"] else ""
    lines.append(f"- `{row['name']} {row['status']} {row['duration_ms']}ms{detail}`")

markdown_tmp.write_text("\n".join(lines) + "\n")
report_path.write_text(markdown_tmp.read_text())
(report_path.parent / f"{report_path.stem}.json").write_text(json_tmp.read_text())
print(exit_code)
PY
exit_code="$?"

python3 - "$LEDGER_PATH" "$results_tsv" "$timestamp_utc" "$report_path" "$BASE_URL" "$GATES_CONFIG_PATH" <<'PY'
import csv
import pathlib
import sys

ledger_path = pathlib.Path(sys.argv[1])
results_path = pathlib.Path(sys.argv[2])
timestamp = sys.argv[3]
report_path = pathlib.Path(sys.argv[4]).relative_to(pathlib.Path(sys.argv[4]).parent.parent)
base_url = sys.argv[5]
gates_config_path = pathlib.Path(sys.argv[6])


def parse_gate_config(path: pathlib.Path):
    gates = {}
    current = None
    for raw_line in path.read_text().splitlines():
        if not raw_line.strip() or raw_line.lstrip().startswith('#'):
            continue
        if raw_line.startswith('gates:'):
            continue
        if raw_line.startswith('  ') and not raw_line.startswith('    '):
            current = raw_line.strip().rstrip(':')
            gates[current] = {}
            continue
        if current and raw_line.startswith('    '):
            key, value = raw_line.strip().split(':', 1)
            value = value.strip()
            if value.startswith('"') and value.endswith('"'):
                value = value[1:-1]
            gates[current][key] = value
    return gates


gate_config = parse_gate_config(gates_config_path)

rows = []
with results_path.open() as handle:
    for gate, level, blocking, status, duration_ms, detail in csv.reader(handle, delimiter='\t'):
        rows.append({
            'gate': gate,
            'level': level,
            'blocking': 'blocking' if blocking == 'true' else 'advisory',
            'status': status,
            'duration': duration_ms,
            'detail': detail,
        })

run_summary = {
    'blocking_pass': sum(1 for row in rows if row['blocking'] == 'blocking' and row['status'] == 'PASS'),
    'blocking_fail': sum(1 for row in rows if row['blocking'] == 'blocking' and row['status'] in {'FAIL', 'ADVISORY'}),
    'skipped': sum(1 for row in rows if row['status'] == 'SKIP'),
}

recent_rows = []
if ledger_path.exists():
    lines = ledger_path.read_text().splitlines()
    inside_runs = False
    for line in lines:
        if line.strip() == '<!-- BEGIN AUTO-RUNS -->':
            inside_runs = True
            continue
        if line.strip() == '<!-- END AUTO-RUNS -->':
            inside_runs = False
            continue
        if inside_runs and line.startswith('| ') and 'Timestamp' not in line and '---' not in line:
            recent_rows.append(line)

new_run_row = f"| {timestamp} | {base_url} | {run_summary['blocking_pass']} | {run_summary['blocking_fail']} | {run_summary['skipped']} | {report_path} |"
recent_rows = [new_run_row] + recent_rows[:19]

gate_lines = [
    '| Gate | Level | Classification | Notes |',
    '| --- | --- | --- | --- |',
]

for gate_name, metadata in gate_config.items():
    classification = 'blocking' if metadata.get('blocking') == 'true' else 'advisory'
    notes = metadata.get('notes', '')
    advisory_reason = metadata.get('advisory_reason', '')
    if advisory_reason:
        notes = f"{notes} Advisory reason: {advisory_reason}".strip()
    gate_lines.append(f"| {gate_name} | {metadata.get('level', '')} | {classification} | {notes} |")

run_lines = [
    '| Timestamp | Base URL | Blocking pass | Blocking fail | Skipped | Report |',
    '| --- | --- | ---: | ---: | ---: | --- |',
    *recent_rows,
]

ledger = [
    '# Release Gate Ledger',
    '',
    'Tracks the current verify-release gate policy and the most recent orchestrator runs.',
    '',
    '## Gate Policy',
    '',
    '<!-- BEGIN AUTO-POLICY -->',
    *gate_lines,
    '<!-- END AUTO-POLICY -->',
    '',
    '## Recent Runs',
    '',
    '<!-- BEGIN AUTO-RUNS -->',
    *run_lines,
    '<!-- END AUTO-RUNS -->',
    '',
    'Promotion and demotion decisions should reference concrete report paths plus the owning bead before changing a gate classification.',
]

ledger_path.write_text('\n'.join(ledger) + '\n')
PY

if [[ "$JSON_MODE" == "true" ]]; then
  cat "$json_tmp"
else
  cat "$markdown_tmp"
fi

exit "$exit_code"
