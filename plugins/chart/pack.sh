#!/usr/bin/env bash
# Packs letta.chart into a deterministic .lcp (a zip with letta-plugin.json at its root):
# the same sources always give the same bytes, so the printed sha256 can pin the package.
#   ./pack.sh [out-dir]     (default: ./dist)
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out_dir="$(mkdir -p "${1:-$here/dist}" && cd "${1:-$here/dist}" && pwd)"
id="$(node -p 'require(process.argv[1]).id' "$here/letta-plugin.json")"
version="$(node -p 'require(process.argv[1]).version' "$here/letta-plugin.json")"
out="$out_dir/$id-$version.lcp"

# What the package carries: the manifest, the runtime's files, the pages. Tests and docs stay out.
files=(letta-plugin.json server.mjs png.mjs pages/chart.html)

stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
for f in "${files[@]}"; do
  mkdir -p "$stage/$(dirname "$f")"
  cp "$here/$f" "$stage/$f"
done
# Fixed modes and timestamps, sorted entries, no extra fields (-X), no directory entries (-D).
find "$stage" -type f -exec chmod 0644 {} +
find "$stage" -exec touch -h -d '2000-01-01T00:00:00Z' {} +
rm -f "$out"
(cd "$stage" && printf '%s\n' "${files[@]}" | LC_ALL=C sort | TZ=UTC zip -q -X -D -9 "$out" -@)

sha="$(sha256sum "$out" | cut -d' ' -f1)"
echo "$out"
echo "sha256:$sha"
