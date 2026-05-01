#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
trace_dir="$repo_root/scripts/perf/traces"
mkdir -p "$trace_dir"

duration_secs="${1:-10}"
app_id="${2:-com.letta.mobile}"
timestamp="$(date +%Y%m%d-%H%M%S)"
trace_name="trace-${timestamp}.perfetto-trace"
local_trace_path="$trace_dir/$trace_name"
device_trace_path="/data/local/tmp/$trace_name"

adb_args=()
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  adb_args+=("-s" "$ANDROID_SERIAL")
fi

if ! command -v adb >/dev/null 2>&1; then
  printf 'adb is required on PATH. Install Android platform-tools first.\n' >&2
  exit 1
fi

printf 'Checking for a connected Android device...\n'
adb "${adb_args[@]}" get-state >/dev/null

printf 'Capturing %ss Perfetto trace for %s...\n' "$duration_secs" "$app_id"
adb "${adb_args[@]}" shell perfetto \
  --out "$device_trace_path" \
  --time "${duration_secs}s" \
  --buffer 32768 \
  --app "$app_id" \
  sched freq idle am wm view input binder_driver

printf 'Pulling trace to %s...\n' "$local_trace_path"
adb "${adb_args[@]}" pull "$device_trace_path" "$local_trace_path" >/dev/null
adb "${adb_args[@]}" shell rm -f "$device_trace_path" >/dev/null

cat <<EOF

Trace saved to:
  $local_trace_path

Open it in Perfetto UI:
  https://ui.perfetto.dev/

What to inspect in this repo:
  - TimelineSync/*, AdminChatVM/*, Send/*: existing Telemetry trace sections
  - StrictMode/violation: debug-only policy hits added by lane o7ob.1.4
  - JankStats/slowFrame: slow frame breadcrumbs added by lane o7ob.1.3

Tip:
  Set ANDROID_SERIAL to target a specific device, or pass a custom app id as the second arg.
EOF
