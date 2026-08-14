#!/usr/bin/env bash
# Installs (copies) the frame-shape-capture mod into an agent's mod loading
# path. Mods only load from ~/.letta/mods/ (this machine) or an agent's
# $MEMORY_DIR/mods/ (travels with that agent) — there is no "load a mod
# straight out of a git repo" path, so this file must be copied there.
#
# Usage:
#   ./tools/frame-shape-capture/install.sh <target-mods-dir>
#   ./tools/frame-shape-capture/install.sh ~/.letta/mods
#   ./tools/frame-shape-capture/install.sh "$MEMORY_DIR/mods"
#
# After running this, reload the target agent's session with /reload.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$SCRIPT_DIR/frame-shape-capture.mod.ts"
TARGET_DIR="${1:-}"

if [[ -z "$TARGET_DIR" ]]; then
  echo "Usage: $0 <target-mods-dir>" >&2
  echo "  e.g. $0 ~/.letta/mods" >&2
  echo "  e.g. $0 \"\$MEMORY_DIR/mods\"" >&2
  exit 1
fi

if [[ ! -f "$SRC" ]]; then
  echo "error: source mod not found at $SRC" >&2
  exit 1
fi

mkdir -p "$TARGET_DIR"
cp "$SRC" "$TARGET_DIR/frame-shape-capture.mod.ts"

echo "Installed frame-shape-capture.mod.ts -> $TARGET_DIR/frame-shape-capture.mod.ts"
echo "Reload the target agent's session (/reload) to pick it up."
echo ""
echo "Note: this is a copy, not a symlink. If tools/frame-shape-capture/frame-shape-capture.mod.ts"
echo "changes upstream, re-run this script and /reload again to stay in sync."
