#!/usr/bin/env bash
# Pixel 2 XL Maestro smoke-test wrapper.
#
# Runs each scripts/maestro/flows/smoke-*.yaml against the dev sibling on
# the Pixel 2 XL (USB serial 711KPAE0914240, com.letta.mobile.dev),
# captures screenshots + dumpsys gfxinfo + logcat per flow into a dated
# report directory, and writes a summary.
#
# Design:
#   - Pin every adb / maestro interaction to the Pixel 2 XL USB serial.
#   - Refuse to run if the device is not reachable AND we cannot find it.
#   - Treat each flow as a separate evidence slice; do NOT reset logcat
#     between flows (only at start, so early evidence survives).
#   - Per-flow dumpsys gfxinfo reset only at start; final state captured.
#   - Exit non-zero on any flow failure.
#
# Usage:
#   ./scripts/maestro/run-pixel2xl-smokes.sh                    # all flows
#   ./scripts/maestro/run-pixel2xl-smokes.sh smoke-launch.yaml  # subset
#   VERBOSE=1 ./scripts/maestro/run-pixel2xl-smokes.sh          # echo maestro output
#   SKIP_PRECHECK=1 ./scripts/maestro/run-pixel2xl-smokes.sh    # don't pause on pre-checks
#
# Environment overrides:
#   DEVICE_SERIAL        USB serial (default 711KPAE0914240 = Pixel 2 XL taimen)
#   PKG_UNDER_TEST       Package under test (default com.letta.mobile.dev)
#   FLOWS_DIR            Where smoke-*.yaml live (default scripts/maestro/flows)
#   REPORT_ROOT          Where reports land (default scripts/maestro/reports)

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEVICE_SERIAL="${DEVICE_SERIAL:-711KPAE0914240}"
PKG_UNDER_TEST="${PKG_UNDER_TEST:-com.letta.mobile.dev}"
FLOWS_DIR="${FLOWS_DIR:-$ROOT_DIR/scripts/maestro/flows}"
REPORT_ROOT="${REPORT_ROOT:-$ROOT_DIR/scripts/maestro/reports}"
MAESTRO_BIN="${MAESTRO_BIN:-$HOME/.maestro/bin/maestro}"
VERBOSE="${VERBOSE:-0}"
SKIP_PRECHECK="${SKIP_PRECHECK:-0}"

# Pin every adb / maestro call to one device.
export ANDROID_SERIAL="$DEVICE_SERIAL"

# --- Helpers ---
log() { echo "[$(date +%H:%M:%S)] $*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

# --- 1. Pre-flight ---
log "Pixel 2 XL Maestro wrapper"
log "  device-serial: $DEVICE_SERIAL"
log "  pkg-under-test: $PKG_UNDER_TEST"
log "  flows-dir: $FLOWS_DIR"
log "  report-root: $REPORT_ROOT"

if [[ ! -x "$MAESTRO_BIN" ]]; then
  # Fallback: try on $PATH
  if command -v maestro >/dev/null 2>&1; then
    MAESTRO_BIN="$(command -v maestro)"
  else
    fail "maestro not found at $MAESTRO_BIN and not on \$PATH"
  fi
fi
log "  maestro-bin: $MAESTRO_BIN"

if ! adb -s "$DEVICE_SERIAL" wait-for-device 2>&1 | head -1; then
  fail "device $DEVICE_SERIAL not reachable via adb"
fi

DEVICE_MODEL=$(adb -s "$DEVICE_SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
log "  device-model: $DEVICE_MODEL"
if [[ "$DEVICE_MODEL" != "Pixel_2_XL" && "$DEVICE_MODEL" != "taimen" ]]; then
  log "WARN: device model is $DEVICE_MODEL, expected Pixel_2_XL (taimen)"
  log "WARN: continuing, but confirm DEVICE_SERIAL is correct"
fi

INSTALLED=$(adb -s "$DEVICE_SERIAL" shell pm path "$PKG_UNDER_TEST" 2>/dev/null | tr -d '\r')
if [[ -z "$INSTALLED" ]]; then
  fail "$PKG_UNDER_TEST not installed on $DEVICE_SERIAL; install with letta-mobile-apk-build-push skill first"
fi
VERSION_NAME=$(adb -s "$DEVICE_SERIAL" shell dumpsys package "$PKG_UNDER_TEST" 2>/dev/null | grep -m1 versionName | sed 's/.*versionName=//' | tr -d '\r')
log "  installed-version: $VERSION_NAME"

# --- 2. Resolve flows ---
if [[ $# -gt 0 ]]; then
  FLOWS=("$@")
else
  # Default: every smoke-*.yaml, alphabetical.
  FLOWS=()
  for f in "$FLOWS_DIR"/smoke-*.yaml; do
    [[ -f "$f" ]] || continue
    FLOWS+=("$(basename "$f")")
  done
  if [[ ${#FLOWS[@]} -eq 0 ]]; then
    fail "no smoke-*.yaml flows found in $FLOWS_DIR"
  fi
fi
log "  flows: ${FLOWS[*]}"

# --- 3. Per-run report directory ---
RUN_ID="$(date -u +%Y-%m-%d_%H%M%SZ)"
REPORT_DIR="$REPORT_ROOT/$RUN_ID"
mkdir -p "$REPORT_DIR/screenshots"
log "  report-dir: $REPORT_DIR"

# Capture initial state.
echo "$VERSION_NAME" > "$REPORT_DIR/installed-versionName.txt"
adb -s "$DEVICE_SERIAL" shell dumpsys package "$PKG_UNDER_TEST" 2>/dev/null | grep -E 'versionName|firstInstallTime|lastUpdateTime' > "$REPORT_DIR/package-info.txt"

# Reset gfxinfo + clear logcat ONCE at start (we want early-stage evidence).
adb -s "$DEVICE_SERIAL" shell dumpsys gfxinfo "$PKG_UNDER_TEST" reset >/dev/null 2>&1 || true
adb -s "$DEVICE_SERIAL" logcat -c >/dev/null 2>&1 || true

# --- 4. Pre-check pause for flows that require operator setup ---
for flow in "${FLOWS[@]}"; do
  case "$flow" in
    smoke-iroh-seeded.yaml|smoke-iroh-empty.yaml)
      if [[ "$SKIP_PRECHECK" != "1" ]]; then
        log ""
        log "Pre-condition for $flow (see YAML header comments):"
        case "$flow" in
          smoke-iroh-seeded.yaml)
            log "  1. mini's wrapper is up (run start-test-appserver.sh)"
            log "  2. scripts/test-sites/seed-test-site-agent.sh applied"
            log "  3. AUTOMATION_SERVER_URL injected as iroh://<ticket>"
            ;;
          smoke-iroh-empty.yaml)
            log "  1. local appserver is up"
            log "  2. AUTOMATION_SERVER_URL injected as iroh://<ticket>"
            ;;
        esac
        read -r -p "  Continue? [y/N] " reply
        [[ "$reply" =~ ^[Yy]$ ]] || { log "skipping $flow"; continue; }
      fi
      ;;
  esac
done

# --- 5. Run each flow ---
declare -a RESULTS
OVERALL=0

for flow in "${FLOWS[@]}"; do
  FLOW_PATH="$FLOWS_DIR/$flow"
  if [[ ! -f "$FLOW_PATH" ]]; then
    log "WARN: flow $flow not found at $FLOW_PATH; skipping"
    RESULTS+=("SKIP $flow (missing)")
    continue
  fi

  log ""
  log "=== running $flow ==="
  FLOW_DIR="$REPORT_DIR/$flow"
  mkdir -p "$FLOW_DIR"
  LOGCAT_FILE="$FLOW_DIR/logcat.txt"
  GFXINFO_FILE="$FLOW_DIR/gfxinfo.txt"

  # Capture logcat slice tagged to this flow.
  adb -s "$DEVICE_SERIAL" logcat -d > "$LOGCAT_FILE" 2>/dev/null || true

  # Run maestro.
  MAESTRO_OUT="$FLOW_DIR/maestro-stdout.txt"
  MAESTRO_ERR="$FLOW_DIR/maestro-stderr.txt"

  # Maestro CLI: `maestro test <flow.yaml>` for runs; `maestro record` for interactive.
  # We use `maestro test` so the flow YAML is authoritative and assertions are enforced.
  if [[ "$VERBOSE" == "1" ]]; then
    "$MAESTRO_BIN" test --device "$DEVICE_SERIAL" "$FLOW_PATH" \
      2> >(tee "$MAESTRO_ERR" >&2) \
      | tee "$MAESTRO_OUT"
  else
    "$MAESTRO_BIN" test --device "$DEVICE_SERIAL" "$FLOW_PATH" \
      >"$MAESTRO_OUT" 2>"$MAESTRO_ERR"
  fi
  STATUS=$?

  # Move screenshots to flow-specific dir.
  if [[ -d ./screenshots ]]; then
    # Maestro drops screenshots in cwd/screenshots/ by default.
    mv ./screenshots/* "$REPORT_DIR/screenshots/" 2>/dev/null || true
    rmdir ./screenshots 2>/dev/null || true
  fi

  # Capture dumpsys gfxinfo for ConversationsRoute analysis.
  adb -s "$DEVICE_SERIAL" shell dumpsys gfxinfo "$PKG_UNDER_TEST" \
    > "$GFXINFO_FILE" 2>/dev/null || true

  if [[ $STATUS -eq 0 ]]; then
    log "PASS: $flow"
    RESULTS+=("PASS $flow")
  else
    log "FAIL: $flow (status=$STATUS; see $FLOW_DIR/maestro-stderr.txt)"
    RESULTS+=("FAIL $flow")
    OVERALL=1
  fi
done

# --- 6. Final logcat + summary ---
adb -s "$DEVICE_SERIAL" logcat -d > "$REPORT_DIR/logcat-final.txt" 2>/dev/null || true

{
  echo "Pixel 2 XL Maestro smoke run"
  echo "  run-id: $RUN_ID"
  echo "  device-serial: $DEVICE_SERIAL"
  echo "  device-model: $DEVICE_MODEL"
  echo "  pkg-under-test: $PKG_UNDER_TEST"
  echo "  installed-version: $VERSION_NAME"
  echo "  maestro-bin: $MAESTRO_BIN"
  echo ""
  echo "Results:"
  for r in "${RESULTS[@]}"; do
    echo "  $r"
  done
  echo ""
  if [[ $OVERALL -eq 0 ]]; then
    echo "OVERALL: PASS"
  else
    echo "OVERALL: FAIL"
  fi
} | tee "$REPORT_DIR/summary.txt"

exit $OVERALL
