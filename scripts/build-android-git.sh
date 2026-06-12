#!/usr/bin/env bash
# Build git for android-arm64 with the NDK and stage it as a jniLib
# (libgit.so) for the embedded LettaCode runtime's local memfs
# (letta-mobile-xa92p). app filesDir is noexec on API 29+, but the APK's
# nativeLibraryDir is executable, so the binary ships disguised as a
# native library and gets symlinked onto PATH at runtime.
#
# Output: android-compose/app/libs/embedded-git/arm64-v8a/libgit.so
# (gitignored; run this script once per checkout / git version bump).
set -euo pipefail

GIT_VERSION="${GIT_VERSION:-2.47.3}"
API_LEVEL="${API_LEVEL:-26}"
SDK_DIR="${ANDROID_SDK_ROOT:-/usr/lib/android-sdk}"
NDK_DIR="${ANDROID_NDK_ROOT:-$(ls -d "$SDK_DIR"/ndk/* | sort -V | tail -1)}"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$REPO_ROOT/android-compose/app/libs/embedded-git/arm64-v8a"
WORK_DIR="${WORK_DIR:-/tmp/android-git-build}"

export CC="$TOOLCHAIN/bin/aarch64-linux-android${API_LEVEL}-clang"
export AR="$TOOLCHAIN/bin/llvm-ar"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
[[ -x "$CC" ]] || { echo "NDK clang not found at $CC" >&2; exit 1; }

mkdir -p "$WORK_DIR" "$OUT_DIR"
cd "$WORK_DIR"
TARBALL="git-$GIT_VERSION.tar.xz"
if [[ ! -f "$TARBALL" ]]; then
  curl -fsSLO "https://www.kernel.org/pub/software/scm/git/$TARBALL"
fi
rm -rf "git-$GIT_VERSION"
tar xf "$TARBALL"
cd "git-$GIT_VERSION"

# Cache vars for the two configure checks that want to EXECUTE a test
# binary (impossible when cross-compiling); values are the known Linux
# answers. Everything else configure derives by compiling only.
./configure \
  --host=aarch64-linux-android \
  --prefix=/data/local/git \
  ac_cv_fread_reads_directories=yes \
  ac_cv_snprintf_returns_bogus=no \
  ac_cv_iconv_omits_bom=no \
  ac_cv_lib_curl_curl_global_init=no

# Local memfs only needs init/add/commit/log/diff/show — all builtins.
# Strip every optional subsystem; bionic has zlib (-lz) in the sysroot.
MAKE_FLAGS=(
  # bionic has no pthread cancellation (pthread_setcancelstate); memfs
  # only does small local commits, so single-threaded git is fine.
  NO_PTHREADS=1
  # bionic folds librt into libc; configure wrongly adds -lrt.
  NEEDS_LIBRT=
  NO_OPENSSL=1
  NO_CURL=1
  NO_EXPAT=1
  NO_GETTEXT=1
  NO_ICONV=1
  NO_PERL=1
  NO_PYTHON=1
  NO_TCLTK=1
  NO_GITWEB=1
  NO_INSTALL_HARDLINKS=1
  RUNTIME_PREFIX=1
  INSTALL_SYMLINKS=1
)
make -j"$(nproc)" "${MAKE_FLAGS[@]}" git

"$STRIP" git
cp git "$OUT_DIR/libgit.so"
echo "Staged: $OUT_DIR/libgit.so ($(du -h "$OUT_DIR/libgit.so" | cut -f1))"
file "$OUT_DIR/libgit.so"
