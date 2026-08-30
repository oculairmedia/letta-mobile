#!/usr/bin/env bash
# Run Pixel 2 XL Maestro smoke flows and retain attributable local evidence.
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEVICE_SERIAL="${DEVICE_SERIAL:-711KPAE0914240}"
PKG_UNDER_TEST="${PKG_UNDER_TEST:-com.letta.mobile.dev}"
FLOWS_DIR="${FLOWS_DIR:-$ROOT_DIR/scripts/maestro/flows}"
REPORT_ROOT="${REPORT_ROOT:-$ROOT_DIR/scripts/maestro/reports}"
MAESTRO_BIN="${MAESTRO_BIN:-$HOME/.maestro/bin/maestro}"
ADB_BIN="${ADB_BIN:-adb}"
PYTHON_BIN="${PYTHON_BIN:-python3}"
VERBOSE="${VERBOSE:-0}"
SKIP_PRECHECK="${SKIP_PRECHECK:-0}"
CAPTURE_SENSITIVE_EVIDENCE="${CAPTURE_SENSITIVE_EVIDENCE:-0}"
export ANDROID_SERIAL="$DEVICE_SERIAL"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
add_reason() { if [[ -n "$REASON" ]]; then REASON="$REASON; $1"; else REASON="$1"; fi; }
sha256() { sha256sum "$1" | awk '{print $1}'; }
version_of() { [[ -n "$1" ]] && "$1" --version 2>&1 | tr '\n' ' ' | cut -c1-500 || printf unavailable; }
prop() { [[ -n "$ADB_PATH" ]] && "$ADB_PATH" -s "$DEVICE_SERIAL" shell getprop "$1" 2>/dev/null | tr -d '\r' || true; }

if [[ $# -gt 0 ]]; then
  FLOWS=("$@")
else
  FLOWS=()
  for path in "$FLOWS_DIR"/smoke-*.yaml; do
    [[ -f "$path" ]] && FLOWS+=("$(basename "$path")")
  done
  [[ ${#FLOWS[@]} -gt 0 ]] || fail "no smoke-*.yaml flows found in $FLOWS_DIR"
fi

[[ "$PKG_UNDER_TEST" =~ ^[A-Za-z0-9._]+$ ]] || fail "invalid package name"

RUN_ID="$(date -u +%Y-%m-%d_%H%M%SZ)-$$"
REPORT_DIR="$REPORT_ROOT/$RUN_ID"
mkdir -p "$REPORT_DIR"
PROVENANCE_TSV="$REPORT_DIR/provenance.tsv"
FLOWS_TSV="$REPORT_DIR/flows.tsv"
printf 'key\tvalue\n' > "$PROVENANCE_TSV"
printf 'name\tsha256\tstatus\treason\tmaestro_stdout\tmaestro_stderr\tgfxinfo\tlogcat\thierarchy\tscreenshots\n' > "$FLOWS_TSV"

if [[ ! -x "$MAESTRO_BIN" ]]; then
  MAESTRO_BIN="$(command -v maestro 2>/dev/null || true)"
fi
ADB_PATH="$(command -v "$ADB_BIN" 2>/dev/null || true)"

# Preflight failures must still produce machine-readable truthful results.
PRECHECK_REASON=""
PACKAGE_PATHS=()
APK_ARTIFACTS=""
if [[ -z "$MAESTRO_BIN" ]]; then
  PRECHECK_REASON="maestro not found"
elif [[ -z "$ADB_PATH" ]]; then
  PRECHECK_REASON="adb not found"
elif ! "$ADB_PATH" -s "$DEVICE_SERIAL" wait-for-device >/dev/null 2>&1; then
  PRECHECK_REASON="device unavailable"
else
  mapfile -t PACKAGE_PATHS < <($ADB_PATH -s "$DEVICE_SERIAL" shell pm path "$PKG_UNDER_TEST" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p')
fi
if [[ -z "$PRECHECK_REASON" && ${#PACKAGE_PATHS[@]} -eq 0 ]]; then
  PRECHECK_REASON="package not installed"
elif [[ -z "$PRECHECK_REASON" ]]; then
  for package_path in "${PACKAGE_PATHS[@]}"; do
    apk_sha256="$($ADB_PATH -s "$DEVICE_SERIAL" shell sha256sum "$package_path" 2>/dev/null | awk 'NR == 1 {print $1}')"
    if [[ ! "$apk_sha256" =~ ^[[:xdigit:]]{64}$ ]]; then
      PRECHECK_REASON="unable to hash installed apk"
      break
    fi
    [[ -z "$APK_ARTIFACTS" ]] || APK_ARTIFACTS+=";"
    APK_ARTIFACTS+="$package_path=$apk_sha256"
  done
fi

GIT_HEAD="$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null || printf unknown)"
GIT_DIRTY="false"
[[ -z "$(git -C "$ROOT_DIR" status --porcelain --untracked-files=normal 2>/dev/null)" ]] || GIT_DIRTY="true"
SERIAL_HASH="$(printf '%s' "$DEVICE_SERIAL" | sha256sum | awk '{print $1}')"
{
  printf 'git_head\t%s\n' "$GIT_HEAD"
  printf 'git_dirty\t%s\n' "$GIT_DIRTY"
  printf 'device_serial_sha256\t%s\n' "$SERIAL_HASH"
  printf 'maestro_version\t%s\n' "$(version_of "$MAESTRO_BIN")"
  printf 'adb_version\t%s\n' "$(version_of "$ADB_PATH")"
  printf 'package\t%s\n' "$PKG_UNDER_TEST"
  printf 'package_version\t%s\n' "$([[ -n "$ADB_PATH" ]] && "$ADB_PATH" -s "$DEVICE_SERIAL" shell dumpsys package "$PKG_UNDER_TEST" 2>/dev/null | tr -d '\r' | awk -F= '/versionName=/{print $2; exit}' || true)"
  printf 'installed_apk_artifacts\t%s\n' "$APK_ARTIFACTS"
  printf 'evidence_policy\t%s\n' "local-only; known secret patterns are redacted; text and opt-in visual evidence may contain user content"
  printf 'model\t%s\n' "$(prop ro.product.model)"
  printf 'api\t%s\n' "$(prop ro.build.version.sdk)"
  printf 'abi\t%s\n' "$(prop ro.product.cpu.abi)"
  printf 'fingerprint\t%s\n' "$(prop ro.build.fingerprint)"
  printf 'display\t%s\n' "$([[ -n "$ADB_PATH" ]] && "$ADB_PATH" -s "$DEVICE_SERIAL" shell wm size 2>/dev/null | tr '\n' ' ' | tr -d '\r' || true)"
} >> "$PROVENANCE_TSV"

OVERALL=0
EXECUTED_COUNT=0
for flow in "${FLOWS[@]}"; do
  FLOW_PATH="$FLOWS_DIR/$flow"
  FLOW_DIR="$REPORT_DIR/${flow%.yaml}"
  mkdir -p "$FLOW_DIR"
  OUT="$FLOW_DIR/maestro-stdout.txt"
  ERR="$FLOW_DIR/maestro-stderr.txt"
  GFX="$FLOW_DIR/gfxinfo.txt"
  LOGCAT="$FLOW_DIR/logcat.txt"
  HIERARCHY="$FLOW_DIR/hierarchy.xml"
  SCREENSHOTS="$FLOW_DIR/screenshots"
  STATUS="FAIL"
  REASON=""
  FLOW_HASH=""
  MAESTRO_RAN=0

  if [[ ! "$flow" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*\.yaml$ ]]; then
    REASON="invalid flow name"
  elif [[ ! -f "$FLOW_PATH" ]]; then
    REASON="requested flow missing"
  else
    FLOW_HASH="$(sha256 "$FLOW_PATH")"
    case "$flow" in
      smoke-iroh-seeded.yaml|smoke-iroh-empty.yaml)
        if [[ "$SKIP_PRECHECK" != "1" ]]; then
          read -r -p "Precondition met for $flow? [y/N] " reply || reply=""
          [[ "$reply" =~ ^[Yy]$ ]] || REASON="operator declined or unmet precondition"
        fi
        ;;
    esac
    if [[ -z "$REASON" && -n "$PRECHECK_REASON" ]]; then
      REASON="$PRECHECK_REASON"
    fi
    if [[ -z "$REASON" ]]; then
      "$ADB_PATH" -s "$DEVICE_SERIAL" logcat -c >/dev/null 2>&1 || REASON="unable to reset logcat"
    fi
    if [[ -z "$REASON" ]]; then
      "$ADB_PATH" -s "$DEVICE_SERIAL" shell dumpsys gfxinfo "$PKG_UNDER_TEST" reset >/dev/null 2>&1 || REASON="unable to reset gfxinfo"
    fi
    if [[ -z "$REASON" ]]; then
      MAESTRO_RAN=1
      EXECUTED_COUNT=$((EXECUTED_COUNT + 1))
      if [[ "$VERBOSE" == "1" ]]; then
        (cd "$FLOW_DIR" && "$MAESTRO_BIN" test --device "$DEVICE_SERIAL" "$FLOW_PATH") > >(tee "$OUT") 2> >(tee "$ERR" >&2)
      else
        (cd "$FLOW_DIR" && "$MAESTRO_BIN" test --device "$DEVICE_SERIAL" "$FLOW_PATH") >"$OUT" 2>"$ERR"
      fi
      MAESTRO_STATUS=$?
      [[ $MAESTRO_STATUS -eq 0 ]] || add_reason "maestro failed (status=$MAESTRO_STATUS)"
    fi
    if [[ $MAESTRO_RAN -eq 1 ]]; then
      "$ADB_PATH" -s "$DEVICE_SERIAL" shell dumpsys gfxinfo "$PKG_UNDER_TEST" >"$GFX" 2>/dev/null || add_reason "gfxinfo capture failed"
      "$ADB_PATH" -s "$DEVICE_SERIAL" logcat -d -t 2000 >"$LOGCAT" 2>/dev/null || add_reason "logcat capture failed"
      if "$ADB_PATH" -s "$DEVICE_SERIAL" shell uiautomator dump /sdcard/letta-maestro-hierarchy.xml >/dev/null 2>&1; then
        "$ADB_PATH" -s "$DEVICE_SERIAL" pull /sdcard/letta-maestro-hierarchy.xml "$HIERARCHY" >/dev/null 2>&1 || rm -f "$HIERARCHY"
      fi
      "$ADB_PATH" -s "$DEVICE_SERIAL" shell rm -f /sdcard/letta-maestro-hierarchy.xml >/dev/null 2>&1 || true
      "$PYTHON_BIN" "$ROOT_DIR/scripts/maestro/redact_text_evidence.py" "$OUT" "$ERR" "$GFX" "$LOGCAT" || add_reason "text evidence redaction failed"
      for evidence in "$OUT" "$ERR" "$GFX" "$LOGCAT"; do
        [[ -f "$evidence" ]] || add_reason "mandatory evidence missing: $(basename "$evidence")"
      done
      [[ -s "$OUT" ]] || add_reason "mandatory evidence empty: $(basename "$OUT")"
      [[ -s "$GFX" ]] || add_reason "mandatory evidence empty: $(basename "$GFX")"
      [[ -s "$LOGCAT" ]] || add_reason "mandatory evidence empty: $(basename "$LOGCAT")"
      if grep -Eq '^[[:space:]]*-[[:space:]]*takeScreenshot:' "$FLOW_PATH"; then
        if [[ ! -d "$SCREENSHOTS" ]] || ! find "$SCREENSHOTS" -type f -size +0c -print -quit | grep -q .; then
          add_reason "expected screenshot evidence missing"
        fi
      fi
      if [[ "$CAPTURE_SENSITIVE_EVIDENCE" != "1" ]]; then
        rm -f "$HIERARCHY"
        if [[ -d "$SCREENSHOTS" ]]; then
          find "$SCREENSHOTS" -type f -delete
          rmdir "$SCREENSHOTS" 2>/dev/null || true
        fi
      fi
    fi
    [[ -n "$REASON" ]] || STATUS="PASS"
  fi
  [[ "$STATUS" == "PASS" ]] || OVERALL=1
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$flow" "$FLOW_HASH" "$STATUS" "$REASON" "${OUT#$REPORT_DIR/}" "${ERR#$REPORT_DIR/}" "${GFX#$REPORT_DIR/}" "${LOGCAT#$REPORT_DIR/}" "$([[ -f "$HIERARCHY" ]] && printf '%s' "${HIERARCHY#$REPORT_DIR/}" || true)" "$([[ -d "$SCREENSHOTS" ]] && printf '%s' "${SCREENSHOTS#$REPORT_DIR/}" || true)" >> "$FLOWS_TSV"
  log "$STATUS: $flow${REASON:+ ($REASON)}"
done

if [[ $OVERALL -eq 0 && $EXECUTED_COUNT -eq 0 ]]; then
  OVERALL=1
fi
if [[ $OVERALL -eq 0 ]]; then OVERALL_STATUS="PASS"; else OVERALL_STATUS="FAIL"; fi
if ! "$PYTHON_BIN" "$ROOT_DIR/scripts/maestro/report_writer.py" "$REPORT_DIR" "$RUN_ID" "$OVERALL_STATUS" "$PROVENANCE_TSV" "$FLOWS_TSV" "$REPORT_DIR/run-manifest.json" "$REPORT_DIR/results.json"; then
  printf 'FAIL: unable to write structured reports\n' >&2
  printf 'OVERALL: FAIL\n' | tee "$REPORT_DIR/summary.txt"
  exit 1
fi
printf 'OVERALL: %s\n' "$OVERALL_STATUS" | tee "$REPORT_DIR/summary.txt"
exit "$OVERALL"
