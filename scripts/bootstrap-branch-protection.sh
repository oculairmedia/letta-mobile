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
#   - Required status checks must pass: test, build-apk.
#   - Linear history only -- squash-merge or rebase-merge, no merge commits.
#   - Force-push and branch deletion are blocked.
#   - 0 required approving reviews (solo dev). Bump this when adding reviewers.
#   - Admin bypass is allowed (enforce_admins=false) for emergency hotfixes.
set -euo pipefail

REPO="${REPO:-oculairmedia/letta-mobile}"
BRANCH="${BRANCH:-main}"
CHECKS=(test build-apk)

contexts=$(printf '"%s",' "${CHECKS[@]}")
contexts="[${contexts%,}]"

body=$(cat <<EOF
{
  "required_status_checks": {
    "strict": true,
    "contexts": $contexts
  },
  "enforce_admins": false,
  "required_pull_request_reviews": {
    "required_approving_review_count": 0,
    "dismiss_stale_reviews": false,
    "require_code_owner_reviews": false
  },
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
