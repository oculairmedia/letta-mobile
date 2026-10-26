#!/usr/bin/env bash
# Linux x86-64 packaging only. Windows retains the existing PowerShell pipeline.
set -euo pipefail
[[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || { echo 'Linux x86-64 host required' >&2; exit 1; }
mode=${1:?usage: linux-runtime.sh download|extract|appimage-tools|npm|stage ...}
shift
case "$mode" in
  download)
    url=${1:?} dest=${2:?} bits=${3:?} expected=${4:?}
    [[ $bits == 256 || $bits == 512 ]] || exit 1
    verify() { [[ $("sha${bits}sum" "$1" | cut -d ' ' -f 1) == "$expected" ]]; }
    if [[ -f $dest ]] && verify "$dest"; then exit 0; fi
    mkdir -p "$(dirname "$dest")"
    temporary=$(mktemp "${dest}.XXXXXX")
    trap 'rm -f "$temporary"' EXIT
    curl --fail --location --retry 3 --proto '=https' --proto-redir '=https' "$url" -o "$temporary"
    verify "$temporary" || { echo "Checksum mismatch: $dest" >&2; exit 1; }
    mv -f "$temporary" "$dest"
    ;;
  extract)
    archive=${1:?} dest=${2:?}
    mkdir -p "$(dirname "$dest")"
    temporary=$(mktemp -d "${dest}.XXXXXX")
    trap 'rm -rf "$temporary"' EXIT
    # Both verified upstream tarballs contain exactly one enclosing directory.
    # GNU tar preserves symlinks and executable bits (unlike ZIP extraction).
    tar -xzf "$archive" --strip-components=1 --no-same-owner -C "$temporary"
    [[ -x $temporary/bin/java || -x $temporary/bin/node ]] || { echo 'Runtime executable missing' >&2; exit 1; }
    rm -rf "$dest"
    mv -f "$temporary" "$dest"
    ;;
  appimage-tools)
    archive=${1:?} dest=${2:?}
    mkdir -p "$(dirname "$dest")"
    temporary=$(mktemp -d "${dest}.XXXXXX")
    trap 'rm -rf "$temporary"' EXIT
    tar -xzf "$archive" --no-same-owner -C "$temporary"
    for name in mksquashfs desktop-file-validate; do
      [[ -x $temporary/$name ]] || { echo "AppImage tool missing: $name" >&2; exit 1; }
    done
    [[ -f $temporary/runtimes/runtime-x64 ]] || { echo 'AppImage runtime missing' >&2; exit 1; }
    for name in libappindicator3.so.1 libindicator3.so.7; do
      [[ -f $temporary/lib/x64/$name ]] || { echo "GTK3 AppImage library missing: $name" >&2; exit 1; }
    done
    # Nucleus 2.1.5 does not expose electron-builder's toolsets.appimage option.
    # Its supported APPIMAGE_TOOLS_PATH override expects the FUSE2 directory
    # layout. Adapt the verified modern toolset, retaining its entire GTK3 set.
    mkdir -p "$temporary/linux-x64"
    for name in mksquashfs desktop-file-validate; do
      cat > "$temporary/linux-x64/$name" <<'WRAPPER'
#!/usr/bin/env bash
set -euo pipefail
exec "$(dirname "${BASH_SOURCE[0]}")/../$(basename "$0")" "$@"
WRAPPER
      chmod 755 "$temporary/linux-x64/$name"
    done
    ln -s runtimes/runtime-x64 "$temporary/runtime-x64"
    rm -rf "$dest"
    mv -f "$temporary" "$dest"
    ;;
  npm)
    node_root=${1:?} install=${2:?}
    export PATH="$node_root/bin:$PATH"
    cd "$install"
    # Run npm through the pinned bundled Node, never a developer's Node/npm.
    "$node_root/bin/node" "$node_root/lib/node_modules/npm/bin/npm-cli.js" ci --omit=dev --no-audit --no-fund
    ;;
  stage)
    node=${1:?} install=${2:?} dest=${3:?}
    [[ -x $node && -f $install/node_modules/@letta-ai/letta-code/letta.js ]] || { echo 'Incomplete runtime install' >&2; exit 1; }
    rm -rf "$dest"
    mkdir -p "$dest"
    cp -f "$node" "$dest/node"
    chmod 755 "$dest/node"
    for name in package.json package-lock.json runtime-manifest.json; do cp -f "$install/$name" "$dest/"; done
    cp -a "$install/node_modules" "$dest/"
    # npm 11 can install every nested optional sharp platform. Keep glibc x64 only;
    # sharp-electron's Linux implementation is REQUIRED here, unlike on Windows.
    while IFS= read -r -d '' dir; do
      [[ ${dir%/*} == */@img ]] || continue
      case "${dir##*/}" in sharp-linux-x64|sharp-libvips-linux-x64) ;; *) rm -rf "$dir" ;; esac
    done < <(find "$dest/node_modules" -depth -type d -name 'sharp-*' -print0)
    # Apply npm platform metadata to other nested optional prebuilts too (notably ripgrep).
    python3 - "$dest/node_modules" <<'PYTHON'
import json
from pathlib import Path
import shutil
import sys

def supports(values, target):
    if isinstance(values, str):
        values = [values]
    return not values or ('!' + target not in values and
                          (not any(not v.startswith('!') for v in values) or target in values))

for path in sorted(Path(sys.argv[1]).rglob('package.json'), key=lambda p: len(p.parts), reverse=True):
    if not path.is_file():
        continue
    if not (path.parent.parent.name == 'node_modules' or
            (path.parent.parent.name.startswith('@') and path.parent.parent.parent.name == 'node_modules')):
        continue  # Bundled test/example package.json files are not installed package metadata.
    package = json.loads(path.read_text())
    if any(not supports(package.get(key, []), target)
           for key, target in [('os', 'linux'), ('cpu', 'x64'), ('libc', 'glibc')]):
        shutil.rmtree(path.parent)
        continue
    if package.get('name') == 'node-pty':
        # node-pty ships Windows/macOS prebuilts inside its cross-platform npm
        # package. npm ci builds build/Release/pty.node for this Linux host.
        prebuilds = path.parent / 'prebuilds'
        if prebuilds.is_dir():
            for platform in prebuilds.iterdir():
                if platform.is_dir() and platform.name != 'linux-x64':
                    shutil.rmtree(platform)
        shutil.rmtree(path.parent / 'third_party' / 'conpty', ignore_errors=True)
PYTHON
    [[ -n $(find "$dest/node_modules" -type d -path '*/@img/sharp-linux-x64' -print -quit) ]] || {
      echo 'sharp-linux-x64 missing from staged runtime' >&2; exit 1;
    }
    find "$dest/node_modules" -type f \( -name '*.d.ts' -o -name '*.d.mts' -o -name '*.d.cts' -o -name '*.map' \) -delete
    ;;
  *) echo "Unknown mode: $mode" >&2; exit 1 ;;
esac
