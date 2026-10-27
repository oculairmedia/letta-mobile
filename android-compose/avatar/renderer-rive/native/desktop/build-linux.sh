#!/usr/bin/env bash
# Pinned Rive OpenGL renderer; no window toolkit, Skia renderer, or process-global X11 changes.
set -euo pipefail
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
mkdir -p "${1:?usage: build-linux.sh <work-dir> <out-dir>}" "${2:?usage: build-linux.sh <work-dir> <out-dir>}"
work=$(realpath "$1")
out=$(realpath "$2")
runtime="$work/rive-runtime"
commit=$(tr -d '[:space:]' < "$here/rive-runtime.version")
[[ $(uname -m) == x86_64 ]] || { echo 'Linux Rive currently requires x86-64' >&2; exit 1; }
for tool in git clang++ make python3 pkg-config; do command -v "$tool" >/dev/null; done
pkg-config --exists egl gl
archiver=$(clang++ -print-prog-name=llvm-ar)
if ! command -v "$archiver" >/dev/null; then
    archiver="$(clang++ -print-resource-dir)/../../../bin/llvm-ar"
fi
[[ -x $(command -v "$archiver") ]] || { echo 'Clang LLVM archiver unavailable' >&2; exit 1; }
if [[ ! -d $runtime/.git ]]; then
    git init -q "$runtime"
    git -C "$runtime" remote add origin https://github.com/rive-app/rive-runtime
    git -C "$runtime" fetch --depth 1 origin "$commit"
    git -C "$runtime" checkout -q FETCH_HEAD
fi
[[ $(git -C "$runtime" rev-parse HEAD) == "$commit" ]] || {
    echo "Wrong rive-runtime in $runtime; expected $commit" >&2; exit 1;
}
if git -C "$runtime" apply --check "$here/tools/rive-runtime-texture-include.patch" 2>/dev/null; then
    git -C "$runtime" apply "$here/tools/rive-runtime-texture-include.patch"
fi
export MAKEFLAGS=-j2
export RIVE_PREMAKE_ARGS='--with_rive_text --with_rive_layout --with-pic'
(
    cd "$runtime/renderer"
    # Upstream's build launcher chooses all host CPUs. Generate only, then bound our make explicitly.
    ../build/build_rive.sh nobuild release
    make -C out/release -j2 AR="$archiver" rive_pls_renderer rive rive_decoders rive_harfbuzz rive_sheenbidi rive_yoga libpng zlib libjpeg libwebp
)
libs="$runtime/renderer/out/release"
# A failed/repeated link must not truncate a bridge another JVM may already have loaded.
temporary_bridge="$out/.rive_desktop_bridge.$$.so"
trap 'rm -f "$temporary_bridge"' EXIT
clang++ -flto -shared -fPIC -fvisibility=hidden -O2 -std=c++17 -fno-exceptions -fno-rtti \
    -DRIVE_DESKTOP_GL -DWITH_RIVE_TEXT -DWITH_RIVE_LAYOUT -DRELEASE -DNDEBUG -DYOGA_EXPORT= \
    -I"$runtime/include" -I"$runtime/renderer/include" -I"$runtime/renderer/glad" -I"$runtime/renderer/glad/include" \
    "$here/rive_desktop_bridge.cpp" -o "$temporary_bridge" \
    -Wl,--exclude-libs,ALL -Wl,-z,defs -Wl,-plugin-opt=jobs=2 -Wl,--start-group \
    "$libs"/librive_pls_renderer.a "$libs"/librive.a "$libs"/librive_decoders.a \
    "$libs"/librive_harfbuzz.a "$libs"/librive_sheenbidi.a "$libs"/librive_yoga.a \
    "$libs"/liblibpng.a "$libs"/libzlib.a "$libs"/liblibjpeg.a "$libs"/liblibwebp.a \
    -Wl,--end-group $(pkg-config --libs egl gl) -ldl -pthread
mv -f "$temporary_bridge" "$out/librive_desktop_bridge.so"
echo "Built $out/librive_desktop_bridge.so (rive-runtime $commit)"
