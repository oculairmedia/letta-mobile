#!/usr/bin/env bash
# Pull the downloaded .litertlm OFF the device into the local cache, so the
# 3GB HuggingFace download only ever happens once.
set -euo pipefail
DEVICE_SERIAL="${DEVICE_SERIAL:-192.168.50.235:5555}"
PKG="${PKG:-com.letta.mobile.dev}"
CACHE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/models-cache"
mkdir -p "$CACHE_DIR"
adb -s "$DEVICE_SERIAL" wait-for-device
FILES=$(adb -s "$DEVICE_SERIAL" shell run-as "$PKG" ls files/embedded-lettacode/models/ 2>/dev/null | tr -d '\r' | grep '\.litertlm$' || true)
if [[ -z "$FILES" ]]; then echo "No models on device." >&2; exit 1; fi
for f in $FILES; do
  if [[ -f "$CACHE_DIR/$f" ]]; then echo "$f already cached"; continue; fi
  echo "Pulling $f..."
  adb -s "$DEVICE_SERIAL" shell run-as "$PKG" cat "files/embedded-lettacode/models/$f" > "$CACHE_DIR/$f.tmp"
  mv "$CACHE_DIR/$f.tmp" "$CACHE_DIR/$f"
  ls -la "$CACHE_DIR/$f"
done
echo "Cache: $CACHE_DIR"
