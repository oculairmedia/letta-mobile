#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
assert_json() { python3 - "$@" <<'PY'
import json, sys
payload = json.load(open(sys.argv[1]))
for check in sys.argv[2:]:
    assert eval(check, {}, {"data": payload}), check
PY
}

make_tools() {
  mkdir -p "$TMP/bin" "$TMP/flows"
  cat > "$TMP/bin/adb" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *wait-for-device*) exit "${ADB_WAIT_STATUS:-0}";;
  *'pm path'*) [[ "${ADB_PACKAGE_STATUS:-0}" == 0 ]] && printf 'package:/fake.apk\n'; exit "${ADB_PACKAGE_STATUS:-0}";;
  *'sha256sum /fake.apk'*) printf 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa  /fake.apk\n';;
  *'dumpsys gfxinfo'*reset*) exit "${ADB_GFX_RESET_STATUS:-0}";;
  *'dumpsys gfxinfo'*) [[ ("${ADB_GFX_STATUS:-0}" == 0 && "${ADB_GFX_EMPTY:-0}" == 0) || "${ADB_GFX_PARTIAL:-0}" == 1 ]] && printf 'gfx\n'; exit "${ADB_GFX_STATUS:-0}";;
  *'logcat -c'*) exit "${ADB_LOGCAT_CLEAR_STATUS:-0}";;
  *'logcat -d'*) [[ "${ADB_LOGCAT_STATUS:-0}" == 0 && "${ADB_LOGCAT_EMPTY:-0}" == 0 ]] && printf 'logcat\n'; exit "${ADB_LOGCAT_STATUS:-0}";;
  *'uiautomator dump'*) exit 1;;
  *'shell rm -f /sdcard/letta-maestro-hierarchy.xml'*) exit 0;;
  *'getprop'*) printf 'fake\n';;
  *'wm size'*) printf 'Physical size: 1080x1920\n';;
  *'dumpsys package'*) printf 'versionName=1.0\n';;
esac
EOF
  cat > "$TMP/bin/maestro" <<'EOF'
#!/usr/bin/env bash
[[ "$1" == --version ]] && { printf 'maestro fake\n'; exit 0; }
printf 'maestro output\n'
printf '%s\n' "${MAESTRO_SECRET_OUTPUT:-}"
printf 'maestro error\n' >&2
if [[ "${MAESTRO_SKIP_SCREENSHOT:-0}" != 1 ]] && grep -q 'takeScreenshot:' "${@: -1}"; then
  mkdir -p screenshots
  printf 'fake png' > screenshots/fake.png
fi
exit "${MAESTRO_STATUS:-0}"
EOF
  chmod +x "$TMP/bin/adb" "$TMP/bin/maestro"
  printf 'appId: com.example\n---\n- launchApp\n' > "$TMP/flows/smoke-pass.yaml"
  printf 'appId: com.example\n---\n- launchApp\n- takeScreenshot: smoke-ok\n' > "$TMP/flows/smoke-shot.yaml"
}
run_case() {
  local name="$1"; shift
  local report="$TMP/$name"
  mkdir -p "$report"
  set +e
  PATH="${TEST_PATH:-$TMP/bin:$PATH}" MAESTRO_BIN="${MAESTRO_BIN_OVERRIDE:-$TMP/bin/maestro}" ADB_BIN="${ADB_BIN:-adb}" FLOWS_DIR="$TMP/flows" REPORT_ROOT="$report" PKG_UNDER_TEST=com.example SKIP_PRECHECK=1 "$@" >"$report/out" 2>&1
  local status=$?
  set -e
  printf '%s\n' "$status"
}

make_tools
status="$(run_case pass bash "$ROOT/scripts/maestro/run-pixel2xl-smokes.sh" smoke-pass.yaml)"
[[ "$status" == 0 ]] || fail "pass case failed"
report="$(ls -d "$TMP/pass"/*/ | head -n1)"
assert_json "$report/results.json" 'data["overall_status"] == "PASS"' 'data["flows"][0]["status"] == "PASS"'
[[ -f "$report/junit.xml" ]] || fail "missing junit"
assert_json "$report/run-manifest.json" '"device_serial" not in data["provenance"]' 'data["provenance"]["device_serial_sha256"] != ""'
assert_json "$report/run-manifest.json" 'data["provenance"]["installed_apk_artifacts"].endswith("=" + "a" * 64)' 'data["flows"][0]["sha256"] != ""'
python3 - "$report/junit.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
assert root.attrib["tests"] == "1"
assert root.attrib["failures"] == "0"
PY

status="$(MAESTRO_STATUS=7 run_case maestro-fail bash "$ROOT/scripts/maestro/run-pixel2xl-smokes.sh" smoke-pass.yaml)"
[[ "$status" != 0 ]] || fail "maestro failure passed"
report="$(ls -d "$TMP/maestro-fail"/*/ | head -n1)"; assert_json "$report/results.json" 'data["flows"][0]["status"] == "FAIL"'
python3 - "$report/junit.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
assert root.attrib["failures"] == "1"
assert "maestro failed" in root.find("testcase/failure").attrib["message"]
PY

status="$(run_case missing bash "$ROOT/scripts/maestro/run-pixel2xl-smokes.sh" absent.yaml)"
[[ "$status" != 0 ]] || fail "missing requested flow passed"
report="$(ls -d "$TMP/missing"/*/ | head -n1)"; assert_json "$report/results.json" 'data["flows"][0]["reason"] == "requested flow missing"'

status="$(ADB_GFX_STATUS=1 ADB_GFX_PARTIAL=1 run_case evidence-fail bash "$ROOT/scripts/maestro/run-pixel2xl-smokes.sh" smoke-pass.yaml)"
[[ "$status" != 0 ]] || fail "failed evidence capture passed"
report="$(ls -d "$TMP/evidence-fail"/*/ | head -n1)"; assert_json "$report/results.json" 'data["flows"][0]["status"] == "FAIL"'

status="$(ADB_GFX_EMPTY=1 run_case evidence-empty bash "$ROOT/scripts/maestro/run-pixel2xl-smokes.sh" smoke-pass.yaml)"
[[ "$status" != 0 ]] || fail "empty evidence passed"
report="$(ls -d "$TMP/evidence-empty"/*/ | head -n1)"; assert_json "$report/results.json" '"mandatory evidence empty" in data["flows"][0]["reason"]'

status="$(run_case screenshot-pass bash "$ROOT/scripts/maestro/run-pixel2xl-smokes.sh" smoke-shot.yaml)"
[[ "$status" == 0 ]] || fail "screenshot evidence case failed"
status="$(MAESTRO_SKIP_SCREENSHOT=1 run_case screenshot-missing bash "$ROOT/scripts/maestro/run-pixel2xl-smokes.sh" smoke-shot.yaml)"
[[ "$status" != 0 ]] || fail "missing expected screenshot passed"
report="$(ls -d "$TMP/screenshot-missing"/*/ | head -n1)"; assert_json "$report/results.json" '"expected screenshot evidence missing" in data["flows"][0]["reason"]'

status="$(ADB_PACKAGE_STATUS=1 run_case package-missing bash "$ROOT/scripts/maestro/run-pixel2xl-smokes.sh" smoke-pass.yaml)"
[[ "$status" != 0 ]] || fail "missing package passed"
report="$(ls -d "$TMP/package-missing"/*/ | head -n1)"; assert_json "$report/results.json" 'data["flows"][0]["reason"] == "package not installed"'

status="$(ADB_BIN=missing-adb run_case tool-missing bash "$ROOT/scripts/maestro/run-pixel2xl-smokes.sh" smoke-pass.yaml)"
[[ "$status" != 0 ]] || fail "missing adb passed"
report="$(ls -d "$TMP/tool-missing"/*/ | head -n1)"; assert_json "$report/results.json" 'data["flows"][0]["reason"] == "adb not found"'

mkdir -p "$TMP/adb-only"
cp "$TMP/bin/adb" "$TMP/adb-only/adb"
status="$(TEST_PATH="$TMP/adb-only:/usr/bin:/bin" MAESTRO_BIN_OVERRIDE="$TMP/bin/missing-maestro" run_case maestro-missing bash "$ROOT/scripts/maestro/run-pixel2xl-smokes.sh" smoke-pass.yaml)"
[[ "$status" != 0 ]] || fail "missing Maestro passed"
report="$(ls -d "$TMP/maestro-missing"/*/ | head -n1)"; assert_json "$report/results.json" 'data["flows"][0]["reason"] == "maestro not found"'

status="$(run_case invalid-name bash "$ROOT/scripts/maestro/run-pixel2xl-smokes.sh" ../smoke-pass.yaml)"
[[ "$status" != 0 ]] || fail "unsafe flow name passed"

export SECRET_SERVER_URL='iroh://secret-ticket'
export SECRET_ACCESS_TOKEN='secret-token'
export MAESTRO_SECRET_OUTPUT='iroh://raw-evidence-is-local'
status="$(run_case redaction bash "$ROOT/scripts/maestro/run-pixel2xl-smokes.sh" smoke-pass.yaml)"
unset SECRET_SERVER_URL SECRET_ACCESS_TOKEN MAESTRO_SECRET_OUTPUT
[[ "$status" == 0 ]] || fail "redaction case failed"
if grep -R -E 'secret-ticket|secret-token|raw-evidence-is-local' "$TMP/redaction" >/dev/null; then
  fail "secret value leaked into report"
fi
grep -R -q '\[REDACTED_URL\]' "$TMP/redaction" || fail "raw evidence was not redacted"

cat > "$TMP/bin/python-fail" <<'EOF'
#!/usr/bin/env bash
exit 9
EOF
chmod +x "$TMP/bin/python-fail"
status="$(PYTHON_BIN="$TMP/bin/python-fail" run_case writer-fail bash "$ROOT/scripts/maestro/run-pixel2xl-smokes.sh" smoke-pass.yaml)"
[[ "$status" != 0 ]] || fail "report-writer failure passed"
grep -q 'OVERALL: FAIL' "$TMP/writer-fail/out" || fail "writer failure did not report overall failure"
if grep -q 'OVERALL: PASS' "$TMP/writer-fail/out"; then
  fail "writer failure printed contradictory PASS"
fi

status="$(printf 'n\n' | PATH="$TMP/bin:$PATH" MAESTRO_BIN="$TMP/bin/maestro" ADB_BIN=adb FLOWS_DIR="$TMP/flows" REPORT_ROOT="$TMP/declined" PKG_UNDER_TEST=com.example "$ROOT/scripts/maestro/run-pixel2xl-smokes.sh" smoke-iroh-empty.yaml >/dev/null 2>&1; printf '%s' "$?")"
[[ "$status" != 0 ]] || fail "declined precondition passed"
echo "maestro truthful evidence tests: PASS"
