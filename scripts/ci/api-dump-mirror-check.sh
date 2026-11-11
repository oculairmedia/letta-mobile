#!/usr/bin/env bash
# api-dump-mirror-check.sh
# The public API dumps are kept per host: api/linux_x64 and api/mingw_x64. Only a Windows runner
# regenerates and checks the mingw copy, so a change that updates the linux dump and forgets its mirror
# passes every Linux check and turns main red on api-contract-windows (it did on 2026-11-11, PR #2427).
# This runs anywhere: for every module with both directories, the JVM dumps must be byte-identical and
# the klib dumps may differ only in their "// Targets:" header lines.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
base="${API_DUMP_ROOT:-$root/android-compose}"
status=0
found=0
while IFS= read -r linux_dir; do
  module_api="$(dirname "$linux_dir")"
  mingw_dir="$module_api/mingw_x64"
  [ -d "$mingw_dir" ] || continue
  while IFS= read -r linux_file; do
    rel="${linux_file#"$linux_dir"/}"
    mingw_file="$mingw_dir/$rel"
    found=$((found + 1))
    if [ ! -f "$mingw_file" ]; then
      echo "::error title=api-dump-mirror::${mingw_file#"$root"/} is missing (mirror of ${linux_file#"$root"/})"
      status=1
      continue
    fi
    if ! diff -q <(grep -v '^// Targets:' "$linux_file") <(grep -v '^// Targets:' "$mingw_file") >/dev/null; then
      echo "::error title=api-dump-mirror::${mingw_file#"$root"/} does not match ${linux_file#"$root"/}"
      diff <(grep -v '^// Targets:' "$linux_file") <(grep -v '^// Targets:' "$mingw_file") | head -n 12 >&2 || true
      echo "  What to do: run apiDump, then copy the linux_x64 dump over the mingw_x64 one (keep the klib file's '// Targets:' lines)." >&2
      status=1
    fi
  done < <(find "$linux_dir" -type f -name '*.api' | sort)
done < <(find "$base" -type d -path '*/api/linux_x64' -not -path '*/build/*' | sort)
if [ "$found" -eq 0 ]; then
  echo "::error title=api-dump-mirror::no api/linux_x64 dumps found under $base; the check would verify nothing"
  exit 1
fi
[ "$status" -eq 0 ] && echo "api-dump-mirror: ok ($found dump files)"
exit "$status"
