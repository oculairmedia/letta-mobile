#!/usr/bin/env bash
# Apply repo-level GitHub settings that aren't checked into files:
#   1. Enable secret scanning + push protection (free for public repos).
#      Blocks pushes containing known token formats (GitHub PATs, AWS keys,
#      Stripe keys, etc.) before they reach the remote.
#   2. Enable auto-merge so squash-merge fires automatically once required
#      status checks go green. Pairs with branch protection from
#      bootstrap-branch-protection.sh.
#   3. Enable auto-delete of head branches on merge -- keeps origin tidy.
#
# Idempotent -- safe to re-run.
#
# Requires: gh CLI authenticated with admin:repo on the target repo.
#
# Usage:
#   ./scripts/bootstrap-repo-settings.sh
#   REPO=other/fork ./scripts/bootstrap-repo-settings.sh
set -euo pipefail

REPO="${REPO:-oculairmedia/letta-mobile}"

printf 'Applying repo-level settings to %s\n' "$REPO"

# 1. Auto-merge + auto-delete branches.
gh api \
  --method PATCH \
  -H "Accept: application/vnd.github+json" \
  "/repos/$REPO" \
  -F allow_auto_merge=true \
  -F delete_branch_on_merge=true \
  -F allow_squash_merge=true \
  -F allow_merge_commit=false \
  -F allow_rebase_merge=true \
  >/dev/null
printf '  ✓ auto-merge + delete-on-merge enabled (squash + rebase merge only)\n'

# 2. Secret scanning. Public repos get this free; private repos need
#    GitHub Advanced Security (paid) -- the call will fail with 403 there.
if gh api \
  --method PATCH \
  -H "Accept: application/vnd.github+json" \
  "/repos/$REPO" \
  -F 'security_and_analysis[secret_scanning][status]=enabled' \
  -F 'security_and_analysis[secret_scanning_push_protection][status]=enabled' \
  >/dev/null 2>&1
then
  printf '  ✓ secret scanning + push protection enabled\n'
else
  printf '  ! secret scanning could not be enabled (private repo without GHAS, or already locked)\n'
fi

printf '\nVerify in the UI:\n'
printf '  https://github.com/%s/settings\n' "$REPO"
printf '  https://github.com/%s/settings/security_analysis\n' "$REPO"
