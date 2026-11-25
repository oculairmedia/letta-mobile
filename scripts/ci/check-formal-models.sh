#!/usr/bin/env bash
# Advisory, zero-Gradle budget: shipped S3 settled fixtures, not the hours-long
# historical/live matrix. The complete matrix remains available locally.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
for cfg in s3-settled-a s3-settled-b s3-settled-c; do
  for prop in NoDuplicateKey NoVanishedRow StableKeys StableRowKeysAcrossRecut StableKeysAcrossRecut; do
    expected=PASS
    case "$cfg:$prop" in s3-settled-[bc]:StableKeysAcrossRecut) expected=FAIL ;; esac
    rc=0
    KEEP=1 OUT="$work/timeline" PROPS="$prop" "$root/tools/timeline-identity/check.sh" "$cfg" > "$work/result" 2>&1 || rc=$?
    log="$work/timeline/$cfg.$prop.out"
    actual=ERROR
    if [ "$rc" = 0 ] && grep -q 'No error has been found' "$log"; then actual=PASS
    elif [ "$rc" = 1 ] && grep -qE "Temporal properties were violated|(Invariant|Property|Action property) $prop is violated" "$log" && ! grep -q 'Invariant TypeOK is violated' "$log"; then actual=FAIL
    fi
    cat "$work/result"
    if [ "$actual" != "$expected" ]; then
      echo "Unexpected timeline result: $cfg $prop $actual (expected $expected)" >&2
      cat "$log"
      exit 1
    fi
  done
done
OUT="$work/subagent" "$root/tools/subagent-lifecycle/check.sh"
