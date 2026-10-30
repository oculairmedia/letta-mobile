#!/usr/bin/env bash
# Guardrails in seconds, without Gradle (letta-mobile-ivqlv).
#
# Runs the source gates the required `test` job runs, in parallel, and prints one PASS/FAIL
# line per gate with the fix. Run it after every edit and before a push: a guardrail failure
# found here costs seconds instead of a CI cycle. It never edits a baseline.
#
#   parity        scripts/ci/parity_gates.py (parity, platform-code, mirror-names)
#   platform-ui   scripts/ci/platform-ui-gate.sh --enforce
#   kdoc          scripts/ci/check-kdoc-hygiene.py
#   api-packages  scripts/ci/check-api-package-coverage.py
#   wire-contracts scripts/ci/check-wire-contracts.py (pairs, index, shrinking baseline)
#   ratchet       scripts/ci/baseline-ratchet-guard.py against the merge base with --base
#   structure     scripts/ci/package_headroom.py --changed: package type caps, new import
#                 cycles (CI's structure contract) and headroom of every touched package
#
# Usage: quick-check.sh [--base REF] [--verbose]
#   --base REF   compare against REF (default origin/main; fetch first when main moved)
#   --verbose    print every gate's output, not only failing ones
# Env: PYTHON overrides the interpreter. Exit status: 0 all pass, 1 any failure, 2 usage error.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BASE="origin/main"
VERBOSE=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base) BASE="$2"; shift 2 ;;
    --verbose) VERBOSE=1; shift ;;
    *) echo "quick-check: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

# Windows ships a `python3` Store stub that exits non-zero; use whichever interpreter runs.
PY="${PYTHON:-}"
if [[ -z "$PY" ]]; then
  for candidate in python3 python; do
    if "$candidate" -c 'import sys; sys.exit(sys.version_info < (3, 9))' >/dev/null 2>&1; then PY="$candidate"; break; fi
  done
fi
[[ -n "$PY" ]] || { echo "quick-check: no Python 3.9+ found (set PYTHON)" >&2; exit 2; }

MERGE_BASE="$(git -C "$ROOT" merge-base "$BASE" HEAD 2>/dev/null)" \
  || { echo "quick-check: cannot find the merge base with '$BASE' (git fetch origin?)" >&2; exit 2; }

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
cd "$ROOT" || exit 2

GATES=(parity platform-ui kdoc api-packages wire-contracts ratchet structure)
declare -A HINT=(
  [parity]="bind the shared contract on every platform, implement the shared type, or move the logic into sharedLogic/sharedUI; never add a scripts/ci/parity/ line (AGENTS.md 'No unshared frontend or logic')"
  [platform-ui]="build the @Composable in sharedUI and keep the platform file a container (PUI001), or shrink it below its cap (PUI002); delete stale allowlist lines (PUI003)"
  [kdoc]="move bead ids / CodeScene numbers out of /** KDoc */ into // comments or the commit message"
  [api-packages]="add an overview for each new production package to its module's Module.md (see the message above)"
  [wire-contracts]="add matching versioned specs/fixtures, regenerate the index, and only shrink the uncovered baseline; run :sharedLogic:jvmTest for real registry/conformance"
  [ratchet]="baselines only shrink: fix the code instead of adding or raising a baseline line"
  [structure]="split a full package or put new types in a package with headroom; drop the import that closes a cycle; lower stale lines in android-compose/architecture-tests/structure-baseline.json"
)

run_gate() {
  case "$1" in
    parity) "$PY" scripts/ci/parity_gates.py ;;
    platform-ui) bash scripts/ci/platform-ui-gate.sh --enforce ;;
    kdoc) "$PY" scripts/ci/check-kdoc-hygiene.py ;;
    api-packages) "$PY" scripts/ci/check-api-package-coverage.py ;;
    wire-contracts) "$PY" scripts/ci/check-wire-contracts.py --check --diff-base "$MERGE_BASE" ;;
    ratchet) "$PY" scripts/ci/baseline-ratchet-guard.py --diff-base "$MERGE_BASE" ;;
    structure) "$PY" scripts/ci/package_headroom.py --changed --base "$MERGE_BASE" ;;
  esac
}

started=$SECONDS
for gate in "${GATES[@]}"; do
  ( gate_start=$SECONDS; run_gate "$gate" >"$OUT/$gate.log" 2>&1; echo "$? $((SECONDS - gate_start))" >"$OUT/$gate.status" ) &
done
wait

failed=0
for gate in "${GATES[@]}"; do
  read -r status seconds <"$OUT/$gate.status"
  if [[ "$status" == 0 ]]; then
    printf 'PASS  %-13s %3ss\n' "$gate" "$seconds"
    [[ "$VERBOSE" == 1 ]] && sed 's/^/      /' "$OUT/$gate.log"
  else
    failed=1
    printf 'FAIL  %-13s %3ss\n' "$gate" "$seconds"
    grep -v '^OK|' "$OUT/$gate.log" | tail -n 40 | sed 's/^/      /'
    printf '      fix: %s\n' "${HINT[$gate]}"
  fi
done
# Headroom of the packages this change touches is useful on a pass too (a failure printed it).
read -r structure_status _ <"$OUT/structure.status"
if [[ "$VERBOSE" == 0 && "$structure_status" == 0 ]] && grep -q '^Package headroom' "$OUT/structure.log"; then
  sed -n '/^Package headroom/,/^Structure:/p' "$OUT/structure.log" | grep -v '^Structure:' | sed 's/^/      /'
fi
echo "quick-check: $([[ $failed == 0 ]] && echo 'all gates pass' || echo 'FAILED') in $((SECONDS - started))s vs ${BASE} (${MERGE_BASE:0:9})"
exit "$failed"
