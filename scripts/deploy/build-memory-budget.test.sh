#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
unit="$root/scripts/deploy/meridian-builds.slice"
docs="$root/scripts/deploy/README-stall-diagnostics.md"
slice_count() {
  awk -v key="$1" '/^\[/{section=$0} section=="[Slice]" && $0==key{n++} END{print n+0}' "$unit"
}
for expected in MemoryHigh=infinity MemoryMax=24G CPUWeight=20 IOWeight=20; do
  test "$(slice_count "$expected")" -eq 1
done
grep -Fq 'systemctl set-property meridian-builds.slice MemoryHigh=infinity MemoryMax=24G' "$docs"
grep -Fq 'systemctl set-property meridian-builds.slice MemoryHigh=8G MemoryMax=12G' "$docs"
grep -Fq 'new baseline, not a measured speedup' "$docs"
echo 'build-memory-budget: passed'
