#!/usr/bin/env bash
# "Able to fail": every property that passes on a config must fail on a deliberately broken
# variant of the model step it guards. Each line below is <config> <property> <guards>; the run
# copies the config with Break set to those guards (comma-separated) and expects TLC to report a
# violation. S3 keeps row keys stable by two guards at once (keys from a row's own records, and the
# key a row was first drawn with is remembered), so its key properties need both broken.
# Usage: verification/timeline-identity/able-to-fail.sh [config-prefix]
#   (a prefix such as s3-settled runs only the matching lines)
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
only="${1:-}"
matrix='
s3-settled-a   NoDuplicateKey           bareRunKey,reuseTakenKey
s3-settled-b   NoDuplicateKey           bareRunKey,reuseTakenKey
s3-settled-c   NoDuplicateKey           containsCompleteIgnoresBoundary,bareRunKey,reuseTakenKey
s3-settled-a   NoVanishedRow            refreshAtTail
s3-settled-b   NoVanishedRow            refreshAtTail
s3-settled-c   NoVanishedRow            refreshAtTail
s3-settled-a   StableKeys               keyByReader,forgetKeys
s3-settled-b   StableKeys               keyByReader,forgetKeys
s3-settled-c   StableKeys               keyByReader,forgetKeys
s3-settled-a   StableRowKeysAcrossRecut keyByPageEdge,forgetKeys
s3-settled-b   StableRowKeysAcrossRecut keyByPageEdge,forgetKeys
s3-settled-c   StableRowKeysAcrossRecut keyByPageEdge,forgetKeys
s3-settled-a   StableKeysAcrossRecut    keyByPageEdge,forgetKeys
s3-live-a      NoDuplicateKey           bareRunKey,reuseTakenKey
s3-live-b      NoDuplicateKey           bareRunKey,reuseTakenKey
s3-live-a      NoVanishedRow            hideByKeyOnly
s3-live-b      NoVanishedRow            hideByKeyOnly
s3-live-a      StableKeys               keyByReader,forgetKeys
s3-live-b      StableKeys               keyByReader,forgetKeys
s3-live-a      StableRowKeysAcrossRecut keyByPageEdge,forgetKeys
s3-live-b      StableRowKeysAcrossRecut keyByPageEdge,forgetKeys
'
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
status=0
while read -r cfg prop brk; do
  [ -n "$cfg" ] || continue
  case "$cfg" in "$only"*) ;; *) continue ;; esac
  tmp="_break-$cfg-${brk//,/-}"
  set_of="{\"${brk//,/\", \"}\"}"
  sed "s/^    Break = .*/    Break = $set_of/" "$here/$cfg.cfg" > "$here/$tmp.cfg"
  line="$(OUT="$work/out" PROPS="$prop" KEEP=1 "$here/check.sh" "$tmp" 2>&1 | tail -1)"
  rm -f "$here/$tmp.cfg"
  case "$line" in
    *FAIL*) printf '%-15s %-25s Break=%-32s caught\n' "$cfg" "$prop" "$brk" ;;
    *) printf '%-15s %-25s Break=%-32s NOT CAUGHT: %s\n' "$cfg" "$prop" "$brk" "$line"; status=1 ;;
  esac
done <<< "$matrix"
exit $status
