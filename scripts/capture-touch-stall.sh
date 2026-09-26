#!/usr/bin/env bash
# Capture everything needed to diagnose a "touch stopped working" stall
# (letta-mobile-erx7m) in one go. Run it the moment the stall recurs, BEFORE
# switching apps (an app switch clears the stall and the evidence with it).
#
# Usage:
#   scripts/capture-touch-stall.sh <adb-serial> [out-root]
#
#   PKG=com.letta.mobile.dev   app package to inspect (default shown)
#   GETEVENT_SECONDS=10        how long to record raw touch events; tap the
#                              stuck screen a few times while it records
#
# Writes a timestamped folder (default under ./touch-stall-captures) and
# prints its path. Nothing is ever deleted.
set -uo pipefail

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <adb-serial> [out-root]" >&2
  exit 2
fi

SERIAL="$1"
OUT_ROOT="${2:-touch-stall-captures}"
PKG="${PKG:-com.letta.mobile.dev}"
GETEVENT_SECONDS="${GETEVENT_SECONDS:-10}"
OUT="$OUT_ROOT/touch-stall-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"

adb_() { adb -s "$SERIAL" "$@"; }

note() { echo "[capture-touch-stall] $*" >&2; }

capture_meta() {
  {
    echo "host_date: $(date -Is)"
    echo "device_date: $(adb_ shell date)"
    echo "serial: $SERIAL"
    echo "package: $PKG"
    echo "pid: $APP_PID"
    echo "build: $(adb_ shell dumpsys package "$PKG" | grep -m1 versionName)"
  } >"$OUT/meta.txt"
}

capture_input() {
  adb_ shell dumpsys input >"$OUT/dumpsys-input.txt" 2>&1
  # The two sections that matter most, pulled out for a quick read.
  grep -A40 "RecentQueue" "$OUT/dumpsys-input.txt" >"$OUT/input-recentqueue.txt"
  grep -B2 -A12 "$PKG" "$OUT/dumpsys-input.txt" >"$OUT/input-app-connection.txt"
}

capture_window() {
  adb_ shell dumpsys window >"$OUT/dumpsys-window.txt" 2>&1
  grep -E "mCurrentFocus|mFocusedApp|mFocusedWindow|mInputMethodTarget|mObscuringWindow|mHoldScreenWindow" \
    "$OUT/dumpsys-window.txt" >"$OUT/window-focus.txt"
}

capture_ui() {
  adb_ shell uiautomator dump /sdcard/touch-stall-ui.xml >"$OUT/uiautomator.log" 2>&1
  adb_ pull /sdcard/touch-stall-ui.xml "$OUT/ui.xml" >>"$OUT/uiautomator.log" 2>&1
  adb_ shell screencap -p /sdcard/touch-stall-screen.png
  adb_ pull /sdcard/touch-stall-screen.png "$OUT/screen.png" >"$OUT/screencap.log" 2>&1
}

# The first input device that reports multitouch positions is the touchscreen.
touch_device() {
  adb_ shell getevent -pl 2>/dev/null | tr -d '\r' | awk '
    /^add device/ { dev = $NF }
    /ABS_MT_POSITION_X/ && dev != "" { print dev; exit }'
}

capture_getevent() {
  local dev
  dev="$(touch_device)"
  echo "touch_device: ${dev:-unknown}" >>"$OUT/meta.txt"
  note "recording ${GETEVENT_SECONDS}s of touch events on ${dev:-all devices} - tap the stuck screen now"
  # shellcheck disable=SC2086 # an empty $dev means "all devices"
  adb_ shell timeout "$GETEVENT_SECONDS" getevent -t -l $dev >"$OUT/getevent.txt" 2>&1
}

capture_logcat() {
  local now since
  now="$(adb_ shell date +%s | tr -d '\r')"
  since="$(adb_ shell date -d "@$((now - 120))" "'+%m-%d %H:%M:%S.000'" | tr -d '\r')"
  if [[ -n "$APP_PID" ]]; then
    adb_ logcat -d -v threadtime --pid="$APP_PID" -T "$since" >"$OUT/logcat-app.txt" 2>&1
  fi
  adb_ logcat -d -v threadtime -T "$since" >"$OUT/logcat-all.txt" 2>&1
  # The debug-build touch diagnostics (touch.dispatch, pointer.root, drawer.state, swipeUpToCanvas.*).
  grep -F "Telemetry/Input" \
    "$OUT/logcat-app.txt" >"$OUT/input-diagnostics.txt" 2>/dev/null
}

APP_PID="$(adb_ shell pidof "$PKG" | tr -d '\r')"
capture_meta
note "dumpsys input/window, ui tree, screenshot"
capture_input
capture_window
capture_ui
capture_getevent
note "logcat (last 2 min)"
capture_logcat

echo "$OUT"
