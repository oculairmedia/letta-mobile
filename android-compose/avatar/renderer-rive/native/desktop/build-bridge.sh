#!/usr/bin/env bash
# Builds rive_desktop_bridge.dll from scratch: clones rive-runtime at the pinned commit, applies the
# local patch, builds its libraries with the tools/ shims, then compiles the bridge with build.ps1.
# The steps README.md documents by hand, as one command CI and a fresh machine can run.
#
#   build-bridge.sh <work-dir> <out-dir>
#
# The link inputs the bridge needs (rive-runtime's headers and ten release .libs, ~1.7 GB) are kept
# as <work-dir>/sdk, stamped with the pinned commit, and the ~4 GB checkout is deleted afterwards.
# A <work-dir>/sdk stamped with the current commit is reused, so CI caches just that directory and
# skips the clone and the ~3 min library build. The bridge itself always recompiles.
# Needs Git for Windows (bash), Python 3, and VS 2022 C++ tools with the Windows 10/11 SDK.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work="${1:?usage: build-bridge.sh <work-dir> <out-dir>}"
out="${2:?usage: build-bridge.sh <work-dir> <out-dir>}"
commit="$(tr -d '[:space:]' < "$here/rive-runtime.version")"
runtime="$work/rive-runtime"
sdk="$work/sdk"
libs=(rive_pls_renderer rive rive_decoders rive_harfbuzz rive_sheenbidi rive_yoga libpng zlib libjpeg libwebp)

build_runtime() {
    rm -rf "$runtime"
    mkdir -p "$work"
    git init -q "$runtime"
    git -C "$runtime" remote add origin https://github.com/rive-app/rive-runtime
    git -C "$runtime" fetch -q --depth 1 origin "$commit"
    git -C "$runtime" checkout -q FETCH_HEAD
    git -C "$runtime" apply "$here/tools/rive-runtime-texture-include.patch"

    # The workarounds README.md explains: no Ore D3D12 canvas, make/python3 shims, MSBuild on PATH.
    local vswhere="/c/Program Files (x86)/Microsoft Visual Studio/Installer/vswhere.exe"
    local msbuild
    msbuild="$("$vswhere" -latest -products '*' -requires Microsoft.Component.MSBuild \
        -find 'MSBuild/**/Bin/MSBuild.exe' | head -n 1 | tr -d '\r')"
    export RIVE_PREMAKE_ARGS="--with_rive_text --with_rive_layout --no_gl"
    export PATH="$here/tools:$(dirname "$(cygpath -u "$msbuild")"):$PATH"
    # path_fiddle (a sample app) fails to link on a plain install; the libraries the bridge links are
    # what matter, so check for them instead of trusting the exit code.
    (cd "$runtime/renderer" && ../build/build_rive.sh --toolset=msc release) || true
    for lib in "${libs[@]}"; do
        [ -f "$runtime/renderer/out/release/$lib.lib" ] || { echo "rive-runtime build did not produce $lib.lib" >&2; exit 1; }
    done

    rm -rf "$sdk"
    mkdir -p "$sdk/renderer/out/release"
    cp -r "$runtime/include" "$sdk/include"
    cp -r "$runtime/renderer/include" "$sdk/renderer/include"
    for lib in "${libs[@]}"; do cp "$runtime/renderer/out/release/$lib.lib" "$sdk/renderer/out/release/"; done
    echo "$commit" > "$sdk/rive-runtime.commit"
    rm -rf "$runtime"
}

if [ "$(tr -d '[:space:]' < "$sdk/rive-runtime.commit" 2>/dev/null)" = "$commit" ]; then
    echo "reusing rive-runtime $commit libraries in $sdk"
else
    build_runtime
fi

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$here/build.ps1")" \
    -RiveRuntime "$(cygpath -w "$sdk")" -OutDir "$(cygpath -w "$out")"
[ -f "$out/rive_desktop_bridge.dll" ] || { echo "build.ps1 did not produce rive_desktop_bridge.dll" >&2; exit 1; }
echo "rive_desktop_bridge.dll: $(cygpath -w "$out/rive_desktop_bridge.dll")"
