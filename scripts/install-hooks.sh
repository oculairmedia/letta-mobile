#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
mkdir -p "$repo_root/.git/hooks"
cp "$repo_root/.githooks/pre-push" "$repo_root/.git/hooks/pre-push"
chmod +x "$repo_root/.git/hooks/pre-push"
printf '%s\n' "Installed .githooks/pre-push -> .git/hooks/pre-push"
