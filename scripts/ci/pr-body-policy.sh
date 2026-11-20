#!/usr/bin/env bash
# The PR-description policy checks, run by .github/workflows/pr-body-policy.yml (fast feedback
# on every description edit) and by the required `test` aggregate in android.yml (the merge gate).
# One script so the two can never drift apart. Every check reads the description live through the
# API, never the event payload, so a re-run after an edit sees the new text.
#
# Env: GH_TOKEN, GITHUB_REPOSITORY, PR_NUMBER, PR_AUTHOR, BASE_SHA, BASE_REF.
# Runs every check even after one fails, so a single run lists every missing line.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

if ! git rev-parse --verify -q "${BASE_SHA}^{commit}" >/dev/null; then
  git fetch --no-tags origin "${BASE_REF}:refs/remotes/origin/${BASE_REF}" 2>/dev/null \
    || git fetch --no-tags origin "${BASE_REF}"
fi

PR_BODY="$(gh api "repos/${GITHUB_REPOSITORY}/pulls/${PR_NUMBER}" --jq '.body // ""')"
export PR_BODY

status=0
echo "::group::Shared implementation searched"
bash scripts/ci/agents-policy-check.sh --require-shared-search || status=1
echo "::endgroup::"
echo "::group::Web bundle ceiling raises"
python3 scripts/ci/web-bundle-raise-guard.py --diff-base "$BASE_SHA" || status=1
echo "::endgroup::"
echo "::group::Test weakening"
python3 scripts/ci/test-weakening-guard.py --diff-base "$BASE_SHA" || status=1
echo "::endgroup::"
exit "$status"
