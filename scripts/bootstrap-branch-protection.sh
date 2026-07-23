#!/usr/bin/env bash
# Apply branch protection rules to main on the GitHub remote.
# Idempotent -- safe to re-run; the API call replaces the existing rule.
#
# Requires: gh CLI authenticated with admin:repo on the target repo.
#
# Usage:
#   ./scripts/bootstrap-branch-protection.sh                 # uses defaults
#   REPO=other/fork ./scripts/bootstrap-branch-protection.sh
#   BRANCH=develop ./scripts/bootstrap-branch-protection.sh
#
# What this enforces on `main`:
#   - Direct pushes are rejected; changes must arrive via a PR.
#   - PRs must be up-to-date with main before merging (strict).
#   - Required status checks must pass: test, build-apk-pass,
#     shared-multiplatform (KMP all-target gate, see letta-mobile-kx1r3),
#     and perf-gate.
#   - Linear history only -- squash-merge or rebase-merge, no merge commits.
#   - Force-push and branch deletion are blocked.
#   - No approving-review requirement (solo dev). When a second contributor
#     joins, switch `required_pull_request_reviews` to a non-null block with
#     `required_approving_review_count` set to >= 1.
#   - Admin bypass is allowed (enforce_admins=false) for emergency hotfixes.
#
# History notes:
#   - `required_pull_request_reviews` MUST be `null` for true "no review"
#     semantics. Setting `{required_approving_review_count: 0}` STILL
#     requires an approving review (count is a minimum, not an
#     "if-required" toggle) and creates a solo-dev deadlock since GitHub
#     forbids self-approval. See the first run of bootstrap on 2026-05-13:
#     PR #42 couldn't merge until the rule was corrected.
#   - The required status check is `build-apk-pass` (a single aggregator
#     job in .github/workflows/android.yml), NOT the matrix-expanded
#     `build-apk (variant, ...)` contexts. The matrix names truncate at
#     100 chars and change shape if the matrix definition is edited.
set -euo pipefail

REPO="${REPO:-oculairmedia/letta-mobile}"
BRANCH="${BRANCH:-main}"
CHECKS=(test build-apk-pass shared-multiplatform perf-gate)

contexts=$(printf '"%s",' "${CHECKS[@]}")
contexts="[${contexts%,}]"

body=$(cat <<EOF
{
  "required_status_checks": {
    "strict": true,
    "contexts": $contexts
  },
  "enforce_admins": false,
  "required_pull_request_reviews": null,
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "required_linear_history": true
}
EOF
)

printf 'Applying branch protection to %s:%s\n' "$REPO" "$BRANCH"
printf 'Required status checks: %s\n' "${CHECKS[*]}"

printf '%s' "$body" | gh api \
  --method PUT \
  -H "Accept: application/vnd.github+json" \
  "/repos/$REPO/branches/$BRANCH/protection" \
  --input - >/dev/null

printf 'Done. Verify in the UI: https://github.com/%s/settings/branches\n' "$REPO"
