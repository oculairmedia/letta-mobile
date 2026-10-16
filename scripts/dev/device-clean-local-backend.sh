#!/usr/bin/env bash
# One-time cleanup of the on-device App Server's agent store (letta-mobile-by4vm.121.2).
#
# Removes the agents, conversations and agent memory the local runtime keeps under
# files/embedded-lettacode/local-backend/ of the dev app, e.g. the `device-smoke-*` agents that
# LocalAppServerDeviceSmokeTest left before it cleaned up after itself. An app reinstall does not
# remove them (it resets Room, not this directory). See docs/testing/on-device-local-backend.md.
#
# Kept: the downloaded models (files/embedded-lettacode/models/*.litertlm), the runtime itself,
# providers/ (auth.json) and ws-token (both rewritten on the next dial), and every other app file.
#
# Dry run by default (lists what would go). Deletes only with --yes, and only while the app is
# not running: the runtime lives in the app process and would write its in-memory store back.
#
# usage: device-clean-local-backend.sh [--yes] [--serial <adb serial>] [--package <application id>]
# env:   ANDROID_SERIAL (default serial), PKG (default com.letta.mobile.dev)
set -euo pipefail

PKG=${PKG:-com.letta.mobile.dev}
SERIAL=${ANDROID_SERIAL:-}
APPLY=0
BACKEND=files/embedded-lettacode/local-backend
# app-agents and model-cache are dead pre-W5 dirs (no model weights: those are in ../models/).
TARGETS=(agents app-agents conversations memfs model-cache)

usage() { sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; }

while [[ $# -gt 0 ]]; do
  case $1 in
    --yes) APPLY=1 ;;
    --serial) SERIAL=${2:?--serial needs a value}; shift ;;
    --package) PKG=${2:?--package needs a value}; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

ADB=(adb)
[[ -n "$SERIAL" ]] && ADB+=(-s "$SERIAL")
dev() { "${ADB[@]}" shell "$@"; }

"${ADB[@]}" get-state > /dev/null || { echo "no device reachable over adb" >&2; exit 1; }
dev run-as "$PKG" true || { echo "run-as $PKG failed (not installed, or not a debuggable build)" >&2; exit 1; }

resumed=$(dev dumpsys activity activities | grep mResumedActivity || true)
if [[ "$resumed" == *"$PKG/"* ]]; then
  echo "refusing: $PKG is in the foreground ($resumed). Leave the app first." >&2
  exit 1
fi
if dev pidof "$PKG" > /dev/null 2>&1; then
  echo "refusing: $PKG is running in the background; its runtime would rewrite the store." >&2
  echo "stop it first:  ${ADB[*]} shell am force-stop $PKG" >&2
  exit 1
fi

existing=()
for target in "${TARGETS[@]}"; do
  if dev run-as "$PKG" test -e "$BACKEND/$target"; then
    existing+=("$BACKEND/$target")
    count=$(dev run-as "$PKG" ls "$BACKEND/$target" 2> /dev/null | wc -l | tr -d ' ')
    echo "$BACKEND/$target ($count entries)"
  fi
done
if [[ ${#existing[@]} -eq 0 ]]; then
  echo "nothing to remove under $BACKEND"
  exit 0
fi
if [[ "$APPLY" -ne 1 ]]; then
  echo "dry run: re-run with --yes to delete the paths above (models and providers are kept)"
  exit 0
fi

dev run-as "$PKG" rm -rf "${existing[@]}"
echo "removed ${#existing[@]} paths; kept:"
dev run-as "$PKG" ls "$BACKEND" files/embedded-lettacode/models || true
