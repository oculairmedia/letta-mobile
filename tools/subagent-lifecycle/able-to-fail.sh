#!/usr/bin/env bash
# Every property has a passing baseline and a surgical broken action.
# Disciplined is explicitly a delivery restriction, not a claimed Kotlin fix.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"; mine="_break-$$"
trap 'rm -rf "$work"; rm -f "$here/$mine"-*.cfg' EXIT
status=0
while read -r prop brk; do
  cfg="$mine-$brk"
  # A comma-separated break names several surgical mutations applied together.
  sed "s/Break = {}/Break = {\"${brk//,/\\\",\\\"}\"}/" "$here/fixed-disciplined.cfg" > "$here/$cfg.cfg"
  rc=0
  RAW=1 KEEP=1 OUT="$work/out" PROPS="$prop" "$here/check.sh" "$cfg" > "$work/result" 2>&1 || rc=$?
  # check.sh fails closed on parse/type errors; require a genuine named violation.
  if [[ "$rc" = 1 ]] && grep -q "$prop.*FAIL (expected PASS" "$work/result"; then
    printf '%-26s Break=%-14s caught\n' "$prop" "$brk"
  else
    printf '%-26s Break=%-14s NOT CAUGHT\n' "$prop" "$brk"
    cat "$work/result"
    status=1
  fi
  rm -f "$here/$cfg.cfg"
done <<'MATRIX'
EventuallyTerminal skipExpiry,forgetEarly
NoResurrection resurrect
RetainedTerminalNeverRuns resurrect
ParentIsolation leak
NoPendingRow pending
NoPendingChildRow leak,pending
MATRIX
exit "$status"
