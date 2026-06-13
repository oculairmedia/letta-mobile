#!/usr/bin/env bash
# Build a tiny curl-compatible Android helper for the embedded LettaCode
# runtime. It is NOT full libcurl: it implements the common agent-facing
# subset (-sS, -I, -X, -H, -d/--data, -o) and delegates real DNS/TLS/HTTP to
# the app's AndroidNetworkBridge via LETTA_ANDROID_NETWORK_BRIDGE_URL.
#
# Output: android-compose/app/libs/embedded-curl/arm64-v8a/libcurl.so
# (gitignored; run this script once per checkout / helper bump).
set -euo pipefail

API_LEVEL="${API_LEVEL:-26}"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}"
NDK_DIR="${ANDROID_NDK_ROOT:-$(ls -d "$SDK_DIR"/ndk/* | sort -V | tail -1)}"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$REPO_ROOT/android-compose/app/src/main/cpp/embedded_curl_bridge.c"
OUT_DIR="$REPO_ROOT/android-compose/app/libs/embedded-curl/arm64-v8a"
OUT="$OUT_DIR/libcurl.so"
CC="$TOOLCHAIN/bin/aarch64-linux-android${API_LEVEL}-clang"
STRIP="$TOOLCHAIN/bin/llvm-strip"

[[ -x "$CC" ]] || { echo "NDK clang not found at $CC" >&2; exit 1; }
[[ -f "$SRC" ]] || { echo "source missing: $SRC" >&2; exit 1; }
mkdir -p "$OUT_DIR"
"$CC" -O2 -Wall -Wextra -Werror -static -o "$OUT" "$SRC"
"$STRIP" "$OUT"
echo "Staged: $OUT ($(du -h "$OUT" | cut -f1))"
file "$OUT"
