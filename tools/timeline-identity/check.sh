#!/usr/bin/env bash
# Model-check TimelineIdentity: each property of each config in its own TLC run, so one
# failing property never hides another. Usage:
#   tools/timeline-identity/check.sh                 # every *.cfg, every property
#   tools/timeline-identity/check.sh main-settled-a  # one config
#   PROPS="NoDuplicateKey" tools/timeline-identity/check.sh d1-live-b
# Env: TLA2TOOLS (default /opt/tools/tla/tla2tools-1.7.4.jar), JAVA (default java on PATH,
#      /usr/lib/jvm/jdk-26/bin/java when present), KEEP=1 also keeps the output of passing runs,
#      OUT=<dir> writes the TLC output there instead (give concurrent runs their own),
#      WORKERS=<n> TLC worker threads (default 1).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
jar="${TLA2TOOLS:-/opt/tools/tla/tla2tools-1.7.4.jar}"
java="${JAVA:-$( [ -x /usr/lib/jvm/jdk-26/bin/java ] && echo /usr/lib/jvm/jdk-26/bin/java || echo java)}"
props="${PROPS:-NoDuplicateKey NoVanishedRow StableKeys StableRowKeysAcrossRecut StableKeysAcrossRecut}"
[ -f "$jar" ] || { echo "TLC jar not found: $jar (set TLA2TOOLS)"; exit 2; }
# TLC runs from a scratch directory, so every path given relative to the caller is made absolute first.
absolute() { echo "$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"; }
jar="$(absolute "$jar")"
case "$java" in */*) java="$(absolute "$java")" ;; esac
# With no arguments every committed config is checked. A mutation config that able-to-fail.sh has
# written beside them (_break-*) is never picked up that way: it is checked only when named.
cfgs=("$@"); [ ${#cfgs[@]} -gt 0 ] || cfgs=($(cd "$here" && ls *.cfg | grep -v '^_break-' | sed 's/\.cfg$//'))
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
cp "$here"/*.tla "$work"/
out="${OUT:-$here/out}"
mkdir -p "$out"
out="$(cd "$out" && pwd)"
status=0
for cfg in "${cfgs[@]}"; do
  for prop in $props; do
    kind=INVARIANTS; [ "$prop" = NoDuplicateKey ] || kind=PROPERTIES
    # Same constants, one property.
    sed -e '/^INVARIANTS/d' -e '/^PROPERTIES/d' "$here/$cfg.cfg" > "$work/run.cfg"
    printf 'INVARIANTS TypeOK\n%s %s\n' "$kind" "$prop" >> "$work/run.cfg"
    log="$out/$cfg.$prop.out"
    (cd "$work" && "$java" -XX:+UseParallelGC -Djava.io.tmpdir="$work" -cp "$jar" tlc2.TLC -workers "${WORKERS:-1}" -deadlock \
        -metadir "$work/states" -config run.cfg MC > "$log" 2>&1) || true
    if grep -q "No error has been found" "$log"; then
      result="PASS ($(grep -o '[0-9,]* distinct states found' "$log" | tail -1))"
      # Only a passing run's log is dropped; a FAIL or ERROR log stays for the line that names it.
      [ "${KEEP:-0}" = 1 ] || rm -f "$log"
    elif grep -qE "is violated|Temporal properties were violated" "$log"; then
      result="FAIL (trace: $log)"; status=1
    else
      result="ERROR (see $log)"; status=2
    fi
    printf '%-20s %-15s %s\n' "$cfg" "$prop" "$result"
    rm -rf "$work/states"
  done
done
exit $status
