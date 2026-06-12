#!/usr/bin/env bash
# Push a locally cached .litertlm model into the dev app's files dir so we
# never re-download 3GB from HuggingFace after app reinstalls / test runs.
# Cache location: /opt/stacks/letta-mobile/models-cache/
set -euo pipefail
DEVICE_SERIAL="${DEVICE_SERIAL:-192.168.50.235:5555}"
PKG="${PKG:-com.letta.mobile.dev}"
CACHE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/models-cache"
MODEL_FILE="${1:-$(ls "$CACHE_DIR"/*.litertlm 2>/dev/null | head -1)}"

if [[ -z "$MODEL_FILE" || ! -f "$MODEL_FILE" ]]; then
  echo "No .litertlm found. Put one in $CACHE_DIR or pass a path." >&2
  exit 1
fi
NAME="$(basename "$MODEL_FILE")"
adb -s "$DEVICE_SERIAL" wait-for-device
echo "Pushing $NAME ($(du -h "$MODEL_FILE" | cut -f1)) to /data/local/tmp..."
adb -s "$DEVICE_SERIAL" push "$MODEL_FILE" "/data/local/tmp/$NAME"
echo "Copying into $PKG files/embedded-lettacode/models/ via run-as..."
adb -s "$DEVICE_SERIAL" shell run-as "$PKG" mkdir -p files/embedded-lettacode/models
adb -s "$DEVICE_SERIAL" shell "cat /data/local/tmp/$NAME | run-as $PKG sh -c 'cat > files/embedded-lettacode/models/$NAME'"
adb -s "$DEVICE_SERIAL" shell rm "/data/local/tmp/$NAME"
adb -s "$DEVICE_SERIAL" shell run-as "$PKG" ls -la files/embedded-lettacode/models/
echo "Done. Select the model in the dev app (or it will be picked up by Tier 3)."
