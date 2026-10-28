#!/usr/bin/env bash
# Regression tests for pagination-replay-fuzz workflow and campaign param resolver.
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
WORKFLOW="$ROOT/.github/workflows/pagination-replay-fuzz.yml"
RESOLVER="$ROOT/scripts/fuzz/resolve-pagination-replay-campaign-params.py"
CAMPAIGN_DIR="$ROOT/campaign"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

[[ -f "$WORKFLOW" ]] || fail "missing workflow $WORKFLOW"
[[ -f "$RESOLVER" ]] || fail "missing resolver $RESOLVER"

# Scan every run: block (single-line and multi-line `run: |`) for workflow_dispatch input
# expansions; inputs may only reach the shell through env: variables.
python3 - "$WORKFLOW" <<'PYCHECK' || fail "workflow must not interpolate workflow_dispatch inputs into run: blocks"
import re, sys
lines = open(sys.argv[1]).read().splitlines()
bad = []
i = 0
while i < len(lines):
    m = re.match(r"^(\s*)(?:-\s+)?run:\s*(.*)$", lines[i])
    if not m:
        i += 1
        continue
    indent = len(m.group(1))
    body = [m.group(2)]
    i += 1
    while i < len(lines) and (not lines[i].strip() or len(lines[i]) - len(lines[i].lstrip()) > indent):
        body.append(lines[i])
        i += 1
    bad += [line for line in body if re.search(r"\$\{\{[^}]*inputs\.", line)]
sys.exit(1 if bad else 0)
PYCHECK

grep -q 'permissions:' "$WORKFLOW" && grep -A2 'permissions:' "$WORKFLOW" | grep -q 'contents: read' \
  || fail "workflow must use contents: read"
grep -q 'pull_request_target' "$WORKFLOW" && fail "pull_request_target is forbidden"

grep -q '23 6 \* \* \*' "$WORKFLOW" || fail "expected UTC schedule 23 6 * * *"
grep -q 'paginationReplayFuzzCampaign' "$WORKFLOW" || fail "workflow must invoke paginationReplayFuzzCampaign"
grep -q 'test -s campaign/fuzz-manifest.json' "$WORKFLOW" || fail "workflow must fail closed when manifest missing"
grep -q '\-\-out' "$WORKFLOW" && fail "workflow must not pass arbitrary resolver output paths"

BUILD_GRADLE="$ROOT/android-compose/sharedLogic/build.gradle.kts"
grep -q 'jvmArgumentProviders' "$BUILD_GRADLE" || fail "sharedLogic build must wire fuzz campaign JVM args via jvmArgumentProviders"
grep -q 'FuzzCampaignJvmArguments' "$BUILD_GRADLE" || fail "sharedLogic build must declare FuzzCampaignJvmArguments provider"
if grep -q 'notCompatibleWithConfigurationCache' "$BUILD_GRADLE"; then
  fail "sharedLogic build must not disable configuration cache for paginationReplayFuzzCampaign"
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

python3 "$RESOLVER" --kind pr-smoke
# shellcheck disable=SC1090
source "$CAMPAIGN_DIR/fuzz-params.env"
[[ "$FUZZ_CAMPAIGN_SEED_COUNT" == "2" ]] || fail "pr-smoke seed count"
[[ "$FUZZ_CAMPAIGN_SCENARIOS_PER_SEED" == "3" ]] || fail "pr-smoke scenarios"
[[ "$FUZZ_CAMPAIGN_EXPECTED_TOTAL" == "24" ]] || fail "pr-smoke total cases"
[[ -s "$CAMPAIGN_DIR/fuzz-params.json" ]] || fail "pr-smoke json summary"

python3 "$RESOLVER" --kind nightly --utc-date 2026-10-27
# shellcheck disable=SC1090
source "$CAMPAIGN_DIR/fuzz-params.env"
[[ "$FUZZ_CAMPAIGN_SEED_COUNT" == "32" ]] || fail "nightly seed count"
[[ "$FUZZ_CAMPAIGN_SCENARIOS_PER_SEED" == "200" ]] || fail "nightly scenarios"
[[ "$FUZZ_CAMPAIGN_EXPECTED_TOTAL" == "25600" ]] || fail "nightly total cases"

if python3 "$RESOLVER" --kind manual --seed-base 1 --seed-count 0 --scenarios-per-seed 1 2>/dev/null; then
  fail "manual resolver must reject seed_count=0"
fi

if python3 "$RESOLVER" --kind pr-smoke --out "$TMP/evil.env" 2>/dev/null; then
  fail "resolver must reject untrusted --out paths"
fi

if command -v actionlint >/dev/null 2>&1; then
  actionlint "$WORKFLOW"
fi

echo "PASS pagination-replay-fuzz-workflow-test"
