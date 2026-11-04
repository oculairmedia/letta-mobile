#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
unit="$root/scripts/deploy/meridian-builds.slice"
docs="$root/scripts/deploy/README-stall-diagnostics.md"
for expected in MemoryHigh=infinity MemoryMax=24G CPUWeight=20 IOWeight=20; do
  test "$(grep -Fxc "$expected" "$unit")" -eq 1
done
grep -Fq 'systemctl set-property meridian-builds.slice MemoryHigh=8G MemoryMax=12G' "$docs"
grep -Fq 'new baseline, not a measured speedup' "$docs"
echo 'build-memory-budget: passed'
