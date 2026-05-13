#!/usr/bin/env bash
# One-time setup: point this clone's git hooks at the checked-in .githooks/.
# Idempotent -- safe to re-run.
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

git config core.hooksPath .githooks
chmod +x .githooks/* 2>/dev/null || true

printf 'Hooks activated: %s\n' "$(git config core.hooksPath)"
printf 'Available hooks:\n'
for hook in .githooks/*; do
  [ -f "$hook" ] || continue
  printf '  %s\n' "$(basename "$hook")"
done
