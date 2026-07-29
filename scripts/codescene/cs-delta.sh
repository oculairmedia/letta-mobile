#!/usr/bin/env bash
# CodeScene delta analysis against the merge base — catches the code-health
# findings the "CodeScene Code Health Review" PR check would report, but
# locally, before the push. Mirrors what codescene.io runs on the PR.
#
# Requirements (skips gracefully when missing):
#   - the `cs` CLI: curl https://downloads.codescene.io/enterprise/cli/install-cs-tool.sh | sh
#   - CS_ACCESS_TOKEN: mint a PAT at https://codescene.io/users/me/pat
#
# Behavior:
#   - advisory by default: prints findings, always exits 0
#   - CS_DELTA_GATE=1 makes degradations fail the script (for use as a gate)
#   - CS_DELTA_BASE overrides the comparison base (default: merge-base with
#     origin/main, falling back to HEAD~1)
set -uo pipefail

if ! command -v cs >/dev/null 2>&1; then
  # Also probe the default install location before giving up.
  if [ -x "$HOME/.local/bin/cs" ]; then
    PATH="$PATH:$HOME/.local/bin"
  else
    echo "[codescene] 'cs' CLI not installed — skipping delta analysis." >&2
    echo "[codescene] install: curl https://downloads.codescene.io/enterprise/cli/install-cs-tool.sh | sh" >&2
    exit 0
  fi
fi

if [ -z "${CS_ACCESS_TOKEN:-}" ]; then
  echo "[codescene] CS_ACCESS_TOKEN not set — skipping delta analysis." >&2
  echo "[codescene] mint a PAT at https://codescene.io/users/me/pat and export CS_ACCESS_TOKEN=<pat>" >&2
  exit 0
fi

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

base="${CS_DELTA_BASE:-}"
if [ -z "$base" ]; then
  base="$(git merge-base HEAD origin/main 2>/dev/null || true)"
fi
if [ -z "$base" ]; then
  base="HEAD~1"
fi

echo "[codescene] cs delta vs ${base}" >&2
if [ "${CS_DELTA_GATE:-0}" = "1" ]; then
  exec cs delta --error-on-warnings "$base" HEAD
fi

# Advisory mode: cs delta exits 0 unconditionally without --error-on-warnings;
# the findings themselves are the value.
cs delta "$base" HEAD || {
  echo "[codescene] cs delta itself failed (advisory — not blocking)." >&2
  exit 0
}
