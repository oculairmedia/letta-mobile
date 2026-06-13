#!/usr/bin/env bash
# Build GNU bash for android-arm64 with the NDK and stage it as a jniLib
# (libbash.so) for the embedded LettaCode runtime's Bash tool. letta.js's
# shell-runner spawns the literal executable "bash" via PATH on non-Windows
# platforms; without this binary the runtime falls back to symlinking
# /system/bin/sh under the name, which gives toybox/mksh semantics instead
# of real bash (arrays, [[ ]] pattern matching, process substitution).
#
# Same packaging trick as build-android-git.sh: app filesDir is noexec on
# API 29+, but the APK's nativeLibraryDir is executable, so the binary
# ships disguised as a native library and gets symlinked onto PATH at
# runtime.
#
# Output: android-compose/app/libs/embedded-bash/arm64-v8a/libbash.so
# (gitignored; run this script once per checkout / bash version bump).
set -euo pipefail

BASH_VERSION_TARGET="${BASH_VERSION_TARGET:-5.2.37}"
# sha256 of the GNU release tarball; matches distro packaging of 5.2.37.
# Update together with BASH_VERSION_TARGET.
BASH_SHA256="${BASH_SHA256:-9599b22ecd1d5787ad7d3b7bf0c59f312b3396d1e281175dd1f8a4014da621ff}"
API_LEVEL="${API_LEVEL:-26}"
SDK_DIR="${ANDROID_SDK_ROOT:-/usr/lib/android-sdk}"
NDK_DIR="${ANDROID_NDK_ROOT:-$(ls -d "$SDK_DIR"/ndk/* | sort -V | tail -1)}"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$REPO_ROOT/android-compose/app/libs/embedded-bash/arm64-v8a"
WORK_DIR="${WORK_DIR:-/tmp/android-bash-build}"

export CC="$TOOLCHAIN/bin/aarch64-linux-android${API_LEVEL}-clang"
export AR="$TOOLCHAIN/bin/llvm-ar"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
[[ -x "$CC" ]] || { echo "NDK clang not found at $CC" >&2; exit 1; }

mkdir -p "$WORK_DIR" "$OUT_DIR"
cd "$WORK_DIR"
TARBALL="bash-$BASH_VERSION_TARGET.tar.gz"
if [[ ! -f "$TARBALL" ]]; then
  curl -fsSLO "https://ftp.gnu.org/gnu/bash/$TARBALL"
fi
echo "$BASH_SHA256  $TARBALL" | sha256sum -c - || {
  echo "Checksum mismatch for $TARBALL — refusing to build from it." >&2
  exit 1
}
rm -rf "bash-$BASH_VERSION_TARGET"
tar xf "$TARBALL"
cd "bash-$BASH_VERSION_TARGET"

# Cache vars answer the configure checks that want to EXECUTE a test
# binary (impossible when cross-compiling); values are the known
# Linux/bionic answers.
#  - --without-bash-malloc: bash's own malloc assumes sbrk layout and
#    breaks on bionic; use the system allocator.
#  - --disable-readline/--disable-nls: the tool runner is non-interactive
#    (-c only), so no line editing and no termcap/locale dependencies.
#  - --enable-static-link: a single self-contained binary; no DT_NEEDED
#    lookups against the app's linker namespace.
./configure \
  --host=aarch64-linux-android \
  --without-bash-malloc \
  --disable-readline \
  --disable-nls \
  --enable-static-link \
  bash_cv_job_control_missing=present \
  bash_cv_sys_named_pipes=present \
  bash_cv_unusable_rtsigs=no \
  bash_cv_func_sigsetjmp=present \
  bash_cv_func_strcoll_broken=no \
  bash_cv_func_ctype_nonascii=yes \
  bash_cv_must_reinstall_sighandlers=no \
  bash_cv_wcontinued_broken=no \
  bash_cv_getcwd_malloc=yes \
  bash_cv_opendir_not_robust=no \
  bash_cv_ulimit_maxfds=yes \
  bash_cv_fnmatch_equiv_fallback=no \
  bash_cv_printf_a_format=yes \
  ac_cv_func_mmap_fixed_mapped=yes \
  ac_cv_rl_version=8.2

make -j"$(nproc)"

"$STRIP" bash
cp bash "$OUT_DIR/libbash.so"
echo "Staged: $OUT_DIR/libbash.so ($(du -h "$OUT_DIR/libbash.so" | cut -f1))"
file "$OUT_DIR/libbash.so"
