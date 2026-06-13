#!/usr/bin/env bash
# Build a tiny Android node CLI executable linked against nodejs-mobile's
# libnode.so. The APK already packages libnode.so; this wrapper gives local
# tools a familiar `node` command on PATH for JSON parsing and scripting.
#
# Output: android-compose/app/libs/embedded-node-cli/arm64-v8a/libnodecli.so
# (gitignored; Gradle builds it in CI when embedded native runtime is enabled).
set -euo pipefail

API_LEVEL="${API_LEVEL:-26}"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}"
NDK_DIR="${ANDROID_NDK_ROOT:-$(ls -d "$SDK_DIR"/ndk/* | sort -V | tail -1)}"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$REPO_ROOT/android-compose/app/src/main/cpp/embedded_node_cli.cpp"
LIBNODE_ROOT="$REPO_ROOT/android-compose/app/build/generated/embedded-lettacode-libnode"
LIBNODE_LIB="$LIBNODE_ROOT/bin/arm64-v8a/libnode.so"
NODE_INCLUDE="$LIBNODE_ROOT/include/node"
OUT_DIR="$REPO_ROOT/android-compose/app/libs/embedded-node-cli/arm64-v8a"
OUT="$OUT_DIR/libnodecli.so"
CXX="$TOOLCHAIN/bin/aarch64-linux-android${API_LEVEL}-clang++"
STRIP="$TOOLCHAIN/bin/llvm-strip"

[[ -x "$CXX" ]] || { echo "NDK clang++ not found at $CXX" >&2; exit 1; }
[[ -f "$SRC" ]] || { echo "source missing: $SRC" >&2; exit 1; }
[[ -f "$LIBNODE_LIB" ]] || { echo "libnode missing: $LIBNODE_LIB (run prepareEmbeddedLettaCodeLibnode first)" >&2; exit 1; }
[[ -d "$NODE_INCLUDE" ]] || { echo "node headers missing: $NODE_INCLUDE" >&2; exit 1; }
mkdir -p "$OUT_DIR"
"$CXX" -O2 -Wall -Wextra -Werror -Wno-unused-parameter -I"$NODE_INCLUDE" -L"$(dirname "$LIBNODE_LIB")" -Wl,-rpath,'$ORIGIN' -o "$OUT" "$SRC" -lnode
"$STRIP" "$OUT"
echo "Staged: $OUT ($(du -h "$OUT" | cut -f1))"
file "$OUT"
