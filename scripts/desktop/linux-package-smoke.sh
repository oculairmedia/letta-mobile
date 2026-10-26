#!/usr/bin/env bash
# Inspect the actual distributable and run it without FUSE or a developer runtime.
set -euo pipefail
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
image=$(realpath "${1:?usage: linux-package-smoke.sh app.AppImage}")
work=$(mktemp -d "${TMPDIR:-/tmp}/letta-linux-package-smoke.XXXXXX")
app_pid=
wm_pid=
cleanup() {
  result=$?
  if [[ -n $app_pid ]]; then kill "$app_pid" 2>/dev/null || true; wait "$app_pid" 2>/dev/null || true; fi
  if [[ -n $wm_pid ]]; then kill "$wm_pid" 2>/dev/null || true; wait "$wm_pid" 2>/dev/null || true; fi
  if [[ -n ${LETTA_PACKAGE_SMOKE_EVIDENCE:-} ]]; then
    mkdir -p "$LETTA_PACKAGE_SMOKE_EVIDENCE" || result=1
    # Keep diagnostics in CI without uploading the extracted payload or a user profile.
    for name in extract.log payload.log startup.log window-manager.log window.txt window-tree.txt check/layout.json; do
      if [[ -f $work/$name ]]; then
        cp -f "$work/$name" "$LETTA_PACKAGE_SMOKE_EVIDENCE/" || result=1
      fi
    done
    find "$work" -type f \( -name 'hs_err_pid*.log' -o -name 'java_error_in*.log' \) \
      -exec cp -f '{}' "$LETTA_PACKAGE_SMOKE_EVIDENCE/" \; || result=1
    printf '%s\n' "$result" > "$LETTA_PACKAGE_SMOKE_EVIDENCE/exit-status.txt" || result=1
  fi
  if [[ $result -ne 0 || ${LETTA_KEEP_PACKAGE_SMOKE:-0} == 1 ]]; then
    echo "Smoke evidence retained: $work (exit $result)" >&2
  else
    rm -rf "$work"
  fi
  exit "$result"
}
trap cleanup EXIT
[[ $(uname -m) == x86_64 ]] || { echo 'x86-64 runner required' >&2; exit 1; }
chmod +x "$image"
cd "$work"
"$image" --appimage-extract > extract.log
# Space-containing location catches broken quoting in AppRun and jpackage options.
mv -f squashfs-root 'relocated app'
root="$work/relocated app"
# Catch accidental fallback to build-runner Java/Node/npm, even when CI installed them.
mkdir -p "$work/no-host-runtime"
for tool in java node npm; do
  cat > "$work/no-host-runtime/$tool" <<'SHIM'
#!/bin/sh
echo "ERROR: packaged app tried to use a system Java/Node/npm command" >&2
exit 127
SHIM
  chmod 755 "$work/no-host-runtime/$tool"
done
export PATH="$work/no-host-runtime:$PATH"
python3 "$here/inspect-linux-package.py" "$root" "$work/check" --forbid-path "${GITHUB_WORKSPACE:-$here}" --run 2>&1 | tee "$work/payload.log"
entry=$(find "$root" -maxdepth 1 -name '*.desktop' -print -quit)
desktop-file-validate "$entry"
for tool in xdotool xprop xwininfo openbox; do
  command -v "$tool" >/dev/null || { echo "Missing window-smoke dependency: $tool" >&2; exit 127; }
done
[[ -n ${DISPLAY:-} && -n ${DBUS_SESSION_BUS_ADDRESS:-} ]] || {
  echo 'Run smoke under xvfb-run and dbus-run-session' >&2; exit 1;
}
mkdir -p "$work/profile" "$work/cache" "$work/config" "$work/data" "$work/runtime"
chmod 700 "$work/runtime"
export XDG_CACHE_HOME="$work/cache" XDG_CONFIG_HOME="$work/config"
export XDG_DATA_HOME="$work/data" XDG_RUNTIME_DIR="$work/runtime"
# A real WM handles map/focus requests; bare Xvfb is not a desktop session.
openbox --sm-disable > "$work/window-manager.log" 2>&1 &
wm_pid=$!
wm_ready=
for ((i=0; i<15; i++)); do
  kill -0 "$wm_pid" 2>/dev/null || { cat "$work/window-manager.log"; exit 1; }
  if xprop -root _NET_SUPPORTING_WM_CHECK | grep -q 'window id # 0x[1-9a-fA-F]'; then wm_ready=1; break; fi
  sleep 1
done
[[ -n $wm_ready ]] || { echo 'Window manager did not become ready' >&2; exit 1; }
# CI uses software rendering on Xvfb. Production keeps the normal rendering selection.
export JAVA_TOOL_OPTIONS="-Dskiko.renderApi=SOFTWARE -Duser.home=$work/profile"
# Explicit runtime environment overrides must not mask the packaged property bindings.
unset LETTA_DESKTOP_RUNTIME_NODE LETTA_DESKTOP_RUNTIME_JS JAVA_HOME JDK_HOME
"$root/AppRun" > "$work/startup.log" 2>&1 &
app_pid=$!
window=
for ((i=0; i<60; i++)); do
  kill -0 "$app_pid" 2>/dev/null || { cat "$work/startup.log"; echo 'Packaged app exited before showing a window' >&2; exit 1; }
  window=$(xdotool search --onlyvisible --name 'Letta|Meridian' 2>/dev/null | head -n 1 || true)
  [[ -n $window ]] && break
  sleep 1
done
[[ -n $window ]] || { cat "$work/startup.log"; echo 'No visible desktop window' >&2; exit 1; }
sleep 5
kill -0 "$app_pid"
kill -0 "$wm_pid"
if grep -E 'UnsupportedClassVersionError|NoClassDefFoundError|UnsatisfiedLinkError|Failed to launch JVM|Could not find or load main class|Exception in thread|X Error of failed request|A fatal error has been detected|FATAL ERROR' "$work/startup.log"; then
  cat "$work/startup.log"
  exit 1
fi
crash=$(find "$work" -type f \( -name 'hs_err_pid*.log' -o -name 'java_error_in*.log' -o -name 'core' -o -name 'core.[0-9]*' \) -print -quit)
[[ -z $crash ]] || { echo "New crash evidence: $crash" >&2; exit 1; }
xdotool search --onlyvisible --name 'Letta|Meridian' | grep -Fx "$window"
xdotool getwindowname "$window" | tee "$work/window.txt"
xwininfo -root -tree > "$work/window-tree.txt"
cat "$work/startup.log"
echo 'PASS: extracted Linux desktop opens a window with bundled Java/Node and Linux JNI'
