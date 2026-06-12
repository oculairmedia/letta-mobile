#!/usr/bin/env bash
# Device loop for the embedded local runtime.
#
# IMPORTANT: this deliberately does NOT use gradle connectedAndroidTest.
# That task uninstalls the app APK + test APK after the run (and
# leaveApksInstalledAfterTest proved unreliable), wiping app data including
# multi-GB downloaded models. Instead we:
#   1. gradle assemble the app + androidTest APKs
#   2. adb install -r -t both (upgrade-in-place, data preserved)
#   3. ensure a cached .litertlm model is present in the app files dir
#      (pushed from models-cache/ — never re-downloaded from HF)
#   4. adb shell am instrument directly
#
# Usage:
#   ./scripts/device-loop.sh                         # tier1 + tier2
#   ./scripts/device-loop.sh 'Class#method'          # specific test
#   STRICT=1 ./scripts/device-loop.sh 'Class#tier3…' # tier3 must produce real assistant text
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT_DIR/android-compose"
DEVICE_SERIAL="${DEVICE_SERIAL:-192.168.50.235:5555}"
CLASS_FILTER="${1:-}"
TEST_CLASS="com.letta.mobile.runtime.local.EmbeddedRuntimeDeviceLoopTest"
PKG="com.letta.mobile.dev"
TEST_PKG="com.letta.mobile.dev.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
APP_APK="$ANDROID_DIR/app/build/outputs/apk/root/debug/app-root-debug.apk"
TEST_APK="$ANDROID_DIR/app/build/outputs/apk/androidTest/root/debug/app-root-debug-androidTest.apk"
MODELS_CACHE="$ROOT_DIR/models-cache"

if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -d /usr/lib/jvm/java-21-openjdk-amd64 ]]; then
    export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  elif [[ -d /usr/lib/jvm/jdk-26 ]]; then
    export JAVA_HOME=/usr/lib/jvm/jdk-26
  fi
fi
if [[ -z "${ANDROID_HOME:-}" && -d /opt/android-sdk ]]; then
  export ANDROID_HOME=/opt/android-sdk
fi
# Pin every adb/gradle interaction to exactly one device entry (the Pixel is
# often attached twice: USB serial + wifi).
export ANDROID_SERIAL="$DEVICE_SERIAL"

if [[ -n "$CLASS_FILTER" ]]; then
  FILTERS=("$CLASS_FILTER")
else
  FILTERS=(
    "$TEST_CLASS#tier1NodeBootSmokePrintsEmbeddedNodeVersion"
    "$TEST_CLASS#tier2RuntimeStatusReportsRunnableForEmbeddedBuild"
  )
fi

mkdir -p "$ROOT_DIR/reports/device-loop"

adb -s "$DEVICE_SERIAL" wait-for-device

echo "=== device-loop: building APKs ==="
(
  cd "$ANDROID_DIR"
  ./gradlew :app:assembleRootDebug :app:assembleRootDebugAndroidTest \
    -PembedLettaCodeNative=true -PembedLettaCodeAssets=true
) || { echo "device-loop: build failed"; exit 1; }

echo "=== device-loop: installing APKs (upgrade-in-place, data preserved) ==="
adb -s "$DEVICE_SERIAL" install -r -t "$APP_APK" || { echo "app install failed"; exit 1; }
adb -s "$DEVICE_SERIAL" install -r -t "$TEST_APK" || { echo "test apk install failed"; exit 1; }

# LocalLettaCodeService is a foreground service and refuses to start without
# POST_NOTIFICATIONS (tier3 fails with "foreground service could not start").
adb -s "$DEVICE_SERIAL" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

ensure_model() {
  # Only needed for tier3; harmless otherwise. Pushes from local cache so the
  # 3.6GB model is NEVER re-downloaded from HuggingFace.
  # NOTE: quote the whole remote command as ONE adb-shell arg — nested
  # sh -c quoting gets eaten by adb and false-positives.
  local have
  have=$(adb -s "$DEVICE_SERIAL" shell "run-as $PKG ls files/embedded-lettacode/models/ 2>/dev/null" | tr -d '\r' | grep '\.litertlm$' | head -1)
  if [[ -n "$have" ]]; then
    echo "device-loop: model present on device: $have"
    return 0
  fi
  local cached
  cached=$(ls "$MODELS_CACHE"/*.litertlm 2>/dev/null | head -1)
  if [[ -z "$cached" ]]; then
    if [[ "${STRICT:-0}" == "1" ]]; then
      echo "device-loop: STRICT requires a model but none on device or in $MODELS_CACHE" >&2
      return 1
    fi
    echo "device-loop: no model on device and none cached in $MODELS_CACHE (tier3 will SKIP)"
    return 0
  fi
  echo "device-loop: pushing cached model $(basename "$cached") to device..."
  DEVICE_SERIAL="$DEVICE_SERIAL" PKG="$PKG" "$ROOT_DIR/scripts/push-model.sh" "$cached" || return 1
  # Verify it actually landed with the right size.
  local pushed
  pushed=$(adb -s "$DEVICE_SERIAL" shell "run-as $PKG ls files/embedded-lettacode/models/ 2>/dev/null" | tr -d '\r' | grep '\.litertlm$' | head -1)
  if [[ -z "$pushed" ]]; then
    echo "device-loop: model push verification failed" >&2
    return 1
  fi
  echo "device-loop: model in place: $pushed"
}

run_filter() {
  local filter="$1"
  local logcat_file="$ROOT_DIR/reports/device-loop/logcat-$(date +%Y%m%d-%H%M%S)-$(basename "$filter" | tr '#.' '---').txt"
  local instrument_args=(-w -e class "$filter")
  if [[ "${STRICT:-0}" == "1" ]]; then
    # Tier 3 only passes on real assistant text (a clean Failed lifecycle
    # event is rejected). Use to prove the model actually answered.
    instrument_args+=(-e requireAssistantText true)
  fi

  echo "=== device-loop: $filter ==="
  adb -s "$DEVICE_SERIAL" logcat -c || true

  local out
  out=$(adb -s "$DEVICE_SERIAL" shell am instrument "${instrument_args[@]}" "$TEST_PKG/$RUNNER" 2>&1)
  local status=$?
  echo "$out"

  adb -s "$DEVICE_SERIAL" logcat -d > "$logcat_file" || true

  # am instrument exits 0 even on assertion failures; parse the output.
  if [[ $status -ne 0 || "$out" == *"FAILURES!!!"* || "$out" == *"INSTRUMENTATION_FAILED"* || "$out" == *"Process crashed"* ]]; then
    echo "device-loop FAILED for $filter"
    echo ""
    echo "=== Relevant logcat ==="
    grep -E "LettaCodeNodeBridge|LettaCodeRuntime|Fatal signal|AndroidRuntime|nodejs|TestRunner" "$logcat_file" | tail -60 || true
    echo ""
    echo "Full logcat: $logcat_file"
    return 1
  fi
  if [[ "$out" == *"assumption"* || "$out" == *"skipped"* ]]; then
    echo "device-loop: NOTE — test may have been skipped via assumption; check output above"
  fi

  echo "device-loop passed for $filter"
  echo "Full logcat: $logcat_file"
}

ensure_model || { echo "device-loop: model staging failed"; exit 1; }

overall=0
for filter in "${FILTERS[@]}"; do
  run_filter "$filter" || overall=1
done

if [[ $overall -ne 0 ]]; then
  echo "device-loop FAILED"
  exit 1
fi
echo "device-loop passed"
