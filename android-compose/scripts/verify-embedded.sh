#!/usr/bin/env bash
# Device-gated smoke harness for embedded-runtime regressions.
set -euo pipefail

ANDROID_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_DIR="$(cd "$ANDROID_DIR/.." && pwd)"
DEVICE_SERIAL="${DEVICE_SERIAL:-192.168.50.235:5555}"
PKG="${PKG:-com.letta.mobile.dev}"
BASE_URL="${BASE_URL:-http://192.168.50.90:8082/v1}"
MODEL="${MODEL:-default}"
API_KEY="${API_KEY:-not-needed}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-420}"
APP_APK="$ANDROID_DIR/app/build/outputs/apk/root/debug/app-root-debug.apk"
ACTION="com.letta.mobile.VERIFY_EMBEDDED"
COMPONENT="$PKG/com.letta.mobile.runtime.local.VerifyEmbeddedSmokeReceiver"
MARKER="VERIFY_EMBEDDED:"
REPORT_DIR="$REPO_DIR/reports/verify-embedded"
mkdir -p "$REPORT_DIR"
LOGCAT_FILE="$REPORT_DIR/logcat-$(date +%Y%m%d-%H%M%S).txt"

if [[ -z "${JAVA_HOME:-}" && -d /usr/lib/jvm/jdk-26 ]]; then
  export JAVA_HOME=/usr/lib/jvm/jdk-26
fi
if [[ -z "${ANDROID_HOME:-}" && -d "$HOME/Android/Sdk" ]]; then
  export ANDROID_HOME="$HOME/Android/Sdk"
fi
export ANDROID_SERIAL="$DEVICE_SERIAL"

fail() {
  echo "verify-embedded: FAIL $*" >&2
  exit 1
}

wait_for_markers() {
  local deadline=$((SECONDS + TIMEOUT_SECONDS))
  while (( SECONDS < deadline )); do
    adb -s "$DEVICE_SERIAL" logcat -d > "$LOGCAT_FILE" || true
    if grep -q "$MARKER END" "$LOGCAT_FILE"; then
      return 0
    fi
    sleep 2
  done
  return 1
}

summarize() {
  echo "=== verify-embedded markers ==="
  grep "$MARKER" "$LOGCAT_FILE" || true
  echo "=== logcat: $LOGCAT_FILE ==="
}

cd "$ANDROID_DIR"
echo "=== verify-embedded: building dev APK with embedded runtime ==="
./gradlew :app:assembleRootDebug -PembedLettaCodeNative=true -PembedLettaCodeAssets=true || fail "build failed"

echo "=== verify-embedded: installing $APP_APK on $DEVICE_SERIAL ==="
adb -s "$DEVICE_SERIAL" wait-for-device
adb -s "$DEVICE_SERIAL" install -r -t "$APP_APK" || fail "install failed"
adb -s "$DEVICE_SERIAL" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

echo "=== verify-embedded: launching app process ==="
adb -s "$DEVICE_SERIAL" logcat -c || true
adb -s "$DEVICE_SERIAL" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null || fail "launch failed"
sleep 5
pid="$(adb -s "$DEVICE_SERIAL" shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)"
[[ -n "$pid" ]] || fail "app process is not alive after launch (letta-mobile-esoaw)"

echo "=== verify-embedded: patching provider config via dev hook when present ==="
adb -s "$DEVICE_SERIAL" shell am start -n "$PKG/.debug.DebugLocalProviderConfigPatchActivity" --es base_url "$BASE_URL" --es model "$MODEL" >/dev/null 2>&1 || true

echo "=== verify-embedded: triggering debug receiver ==="
adb -s "$DEVICE_SERIAL" shell am broadcast -a "$ACTION" -n "$COMPONENT" --es base_url "$BASE_URL" --es model "$MODEL" --es api_key "$API_KEY" >/dev/null || fail "broadcast failed"
wait_for_markers || { summarize; fail "timed out waiting for VERIFY_EMBEDDED END"; }
summarize

required=(launch_alive remote_model_starts switch_two_agents local_turn_e2e)
for pathway in "${required[@]}"; do
  if ! grep -q "$MARKER $pathway PASS" "$LOGCAT_FILE"; then
    fail "missing PASS marker for $pathway"
  fi
  if grep -q "$MARKER $pathway FAIL" "$LOGCAT_FILE"; then
    fail "FAIL marker for $pathway"
  fi
done

echo "verify-embedded: PASS device=$DEVICE_SERIAL pkg=$PKG base_url=$BASE_URL model=$MODEL"
