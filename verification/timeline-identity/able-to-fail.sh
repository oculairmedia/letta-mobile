#!/usr/bin/env bash
# "Able to fail": every property that passes on a config must fail on a deliberately broken
# variant of the model step it guards. Each line below is <config> <property> <Break>; the run
# copies the config with that Break and expects TLC to report a violation.
# Usage: verification/timeline-identity/able-to-fail.sh
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
matrix='
d1-settled-a   NoDuplicateKey        d1PerPageOnly
d1-settled-b   NoDuplicateKey        d1PerPageOnly
d1-settled-c   NoDuplicateKey        d1PerPageOnly
main-settled-c NoDuplicateKey        containsCompleteIgnoresBoundary
d1-settled-a   NoVanishedRow         refreshAtTail
d1-settled-b   NoVanishedRow         refreshAtTail
d1-settled-c   NoVanishedRow         refreshAtTail
main-settled-c NoVanishedRow         refreshAtTail
d1-settled-a   StableKeysAcrossRecut keyByPageEdge
main-settled-a StableKeysAcrossRecut keyByPageEdge
'
status=0
while read -r cfg prop brk; do
  [ -n "$cfg" ] || continue
  tmp="_break-$cfg-$brk"
  sed "s/^    Break = .*/    Break = \"$brk\"/" "$here/$cfg.cfg" > "$here/$tmp.cfg"
  line="$(PROPS="$prop" KEEP=1 "$here/check.sh" "$tmp" 2>&1 | tail -1)"
  rm -f "$here/$tmp.cfg"
  case "$line" in
    *FAIL*) printf '%-15s %-22s Break=%-32s caught\n' "$cfg" "$prop" "$brk" ;;
    *) printf '%-15s %-22s Break=%-32s NOT CAUGHT: %s\n' "$cfg" "$prop" "$brk" "$line"; status=1 ;;
  esac
done <<< "$matrix"
exit $status
