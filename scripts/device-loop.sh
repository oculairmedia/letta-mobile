#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT_DIR/android-compose"
DEVICE_SERIAL="${DEVICE_SERIAL:-192.168.50.235:5555}"
CLASS_FILTER="${1:-}"
TEST_CLASS="com.letta.mobile.runtime.local.EmbeddedRuntimeDeviceLoopTest"

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

if [[ -n "$CLASS_FILTER" ]]; then
  FILTERS=("$CLASS_FILTER")
else
  FILTERS=(
    "$TEST_CLASS#tier1NodeBootSmokePrintsEmbeddedNodeVersion"
    "$TEST_CLASS#tier2RuntimeStatusReportsRunnableForEmbeddedBuild"
  )
fi

mkdir -p "$ROOT_DIR/reports/device-loop"
TEST_RESULTS_DIR="$ANDROID_DIR/app/build/outputs/androidTest-results/connected/rootDebug"

adb -s "$DEVICE_SERIAL" wait-for-device

run_filter() {
  local filter="$1"
  local logcat_file="$ROOT_DIR/reports/device-loop/logcat-$(date +%Y%m%d-%H%M%S)-$(basename "$filter" | tr '#.' '---').txt"
  local gradle_args=(
    :app:connectedRootDebugAndroidTest
    -PembedLettaCodeNative=true
    -PembedLettaCodeAssets=true
    # Keep the dev app + its data (3GB downloaded models!) installed after the
    # test run. Without this, connectedAndroidTest uninstalls the app and the
    # user has to re-download models from HF every time (learned the hard way).
    -Pandroid.injected.androidTest.leaveApksInstalledAfterTest=true
    "-Pandroid.testInstrumentationRunnerArguments.class=$filter"
  )

  echo "=== device-loop: $filter ==="
  adb -s "$DEVICE_SERIAL" logcat -c || true

  set +e
  (
    cd "$ANDROID_DIR"
    ./gradlew "${gradle_args[@]}"
  )
  local status=$?
  set -e

  adb -s "$DEVICE_SERIAL" logcat -d > "$logcat_file" || true

  if [[ $status -ne 0 ]]; then
    echo "device-loop failed for $filter with exit code $status"
    echo ""
    echo "=== Instrumentation failures ==="
    if [[ -d "$TEST_RESULTS_DIR" ]]; then
      python3 - "$TEST_RESULTS_DIR" <<'PY'
import pathlib
import sys
from xml.etree import ElementTree as ET
for path in pathlib.Path(sys.argv[1]).rglob('*.xml'):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    for case in root.iter('testcase'):
        problems = list(case.findall('failure')) + list(case.findall('error')) + list(case.findall('skipped'))
        for problem in problems:
            print(f"{case.get('classname')}.{case.get('name')}: {problem.tag}: {problem.get('message') or ''}")
            if problem.text:
                print(problem.text.strip())
PY
    else
      echo "No connected androidTest result directory found: $TEST_RESULTS_DIR"
    fi
    echo ""
    echo "=== Relevant logcat ==="
    python3 - "$logcat_file" <<'PY'
import sys
needles = ('LettaCodeNodeBridge', 'LettaCodeRuntime', 'Fatal signal', 'AndroidRuntime', 'DEBUG', 'auditd')
for line in open(sys.argv[1], errors='replace'):
    if any(needle in line for needle in needles):
        print(line.rstrip())
PY
    echo ""
    echo "Full logcat: $logcat_file"
    exit "$status"
  fi

  echo "device-loop passed for $filter"
  echo "Full logcat: $logcat_file"
}

for filter in "${FILTERS[@]}"; do
  run_filter "$filter"
done

echo "device-loop passed"
