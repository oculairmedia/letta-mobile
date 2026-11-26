#!/usr/bin/env bash
# One property per TLC run; expected witnesses are checked, never hidden by || true.
# RAW=1 disables expected-failure handling (used by able-to-fail.sh).
# TLA2TOOLS, JAVA, OUT, KEEP, WORKERS and PROPS mirror timeline-identity/check.sh.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
jar="${TLA2TOOLS:-/opt/tools/tla/tla2tools-1.7.4.jar}"
java="${JAVA:-$(if [[ -x /usr/lib/jvm/jdk-26/bin/java ]]; then echo /usr/lib/jvm/jdk-26/bin/java; else echo java; fi)}"
[[ -f "$jar" ]] || { echo "TLC jar not found: $jar" >&2; exit 2; }
jar="$(cd "$(dirname "$jar")" && pwd)/$(basename "$jar")"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
cp "$here"/*.tla "$work/"
out="${OUT:-$here/out}"; mkdir -p "$out"; out="$(cd "$out" && pwd)"
cfgs=("$@"); [[ ${#cfgs[@]} -gt 0 ]] || cfgs=(main fixed fixed-disciplined)
status=0
for cfg in "${cfgs[@]}"; do
  for prop in ${PROPS:-EventuallyTerminal NoResurrection RetainedTerminalNeverRuns ParentIsolation NoPendingRow NoPendingChildRow}; do
    sed -e '/^INVARIANTS/d' -e '/^PROPERTIES/d' "$here/$cfg.cfg" > "$work/run.cfg"
    printf 'INVARIANTS TypeOK\nPROPERTIES %s\n' "$prop" >> "$work/run.cfg"
    log="$out/$cfg.$prop.out"
    rc=0
    (cd "$work" && "$java" -XX:+UseParallelGC -cp "$jar" tlc2.TLC -seed 1 -fp 0 -lncheck final -workers "${WORKERS:-1}" -deadlock \
      -metadir "$work/states" -config run.cfg MC > "$log" 2>&1) || rc=$?
    expected=PASS
    if [[ "${RAW:-0}" != 1 ]]; then
      case "$cfg:$prop" in
        main:EventuallyTerminal|fixed:EventuallyTerminal|main:NoResurrection|fixed:NoResurrection|main:ParentIsolation|main:NoPendingRow|fixed:NoPendingRow|main:NoPendingChildRow) expected=FAIL ;;
      esac
    fi
    actual=ERROR
    if [[ "$rc" = 0 ]] && grep -q 'No error has been found' "$log"; then actual=PASS
    elif grep -qE "Temporal properties were violated|(Property|Invariant|Action property) $prop is violated" "$log" && ! grep -q 'Invariant TypeOK is violated' "$log"; then actual=FAIL
    fi
    stats="$( { grep -E 'distinct states found|Finished in' "$log" || true; } | tr '\n' ' ')"
    if [[ "$actual" = "$expected" ]]; then
      printf '%-20s %-26s %s (expected %s) %s\n' "$cfg" "$prop" "$actual" "$expected" "$stats"
      if [[ "$actual" = PASS ]] && [[ "${KEEP:-0}" != 1 ]]; then rm -f "$log"; fi
    else
      printf '%-20s %-26s %s (expected %s; log: %s)\n' "$cfg" "$prop" "$actual" "$expected" "$log"
      # CI deletes OUT on exit and uploads no artifact, so the log is the only triage evidence.
      cat "$log"
      status=1
      [[ "$actual" != ERROR ]] || status=2
    fi
    rm -rf "$work/states"
  done
done
exit "$status"
