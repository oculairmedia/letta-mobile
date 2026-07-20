#!/usr/bin/env bash
# Map git changes vs a base ref to additive Gradle unit-test tasks.
#
# Usage: changed-gradle-modules.sh [BASE_REF]
#   BASE_REF defaults to origin/main
#
# Prints a space-separated list of Gradle tasks to stdout (may be empty).
# This script only SUGGESTS additive module tests for PR speed — it never
# recommends skipping :sharedLogic:allTests (that stays a required CI job).
#
# Mapping (android-compose/ prefixes):
#   feature-chat/**       → :feature-chat:testDebugUnitTest
#   feature-editagent/**  → :feature-editagent:testDebugUnitTest
#   designsystem/**       → :designsystem:testDebugUnitTest
#   core/data/**          → (skip; covered by base test job)
#   desktop/**            → :desktop:test
#   cli/**                → :cli:testDebugUnitTest
set -euo pipefail

BASE_REF="${1:-origin/main}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

# Fetch-depth-safe resolution: shallow clones may lack BASE_REF.
resolve_base() {
  local ref="$1"
  if git rev-parse --verify -q "${ref}^{commit}" >/dev/null 2>&1; then
    printf '%s\n' "$ref"
    return 0
  fi
  # Try fetching the ref (best-effort; may no-op offline)
  if git fetch --no-tags --depth=1 origin \
    "+refs/heads/${ref#origin/}:refs/remotes/origin/${ref#origin/}" \
    >/dev/null 2>&1; then
    if git rev-parse --verify -q "${ref}^{commit}" >/dev/null 2>&1; then
      printf '%s\n' "$ref"
      return 0
    fi
  fi
  # Fall back to merge-base with HEAD's upstream or first parent
  if git rev-parse --verify -q "origin/main^{commit}" >/dev/null 2>&1; then
    printf '%s\n' "origin/main"
    return 0
  fi
  if git rev-parse --verify -q "HEAD~1^{commit}" >/dev/null 2>&1; then
    printf '%s\n' "HEAD~1"
    return 0
  fi
  # No usable base — report no additive tasks
  return 1
}

if ! RESOLVED_BASE="$(resolve_base "$BASE_REF")"; then
  # Empty output is valid (no additive tasks)
  exit 0
fi

# Prefer three-dot (merge-base) range; fall back to two-dot if needed.
DIFF_FILES=""
if DIFF_FILES="$(git diff --name-only "${RESOLVED_BASE}...HEAD" 2>/dev/null)"; then
  :
elif DIFF_FILES="$(git diff --name-only "${RESOLVED_BASE}" HEAD 2>/dev/null)"; then
  :
else
  exit 0
fi

declare -A TASKS=()

while IFS= read -r file; do
  [[ -z "$file" ]] && continue
  case "$file" in
    android-compose/feature-chat/*)
      TASKS[":feature-chat:testDebugUnitTest"]=1
      ;;
    android-compose/feature-editagent/*)
      TASKS[":feature-editagent:testDebugUnitTest"]=1
      ;;
    android-compose/designsystem/*)
      TASKS[":designsystem:testDebugUnitTest"]=1
      ;;
    android-compose/core/data/*)
      # Covered by the base :core:data:testDebugUnitTest job — skip.
      ;;
    android-compose/desktop/*)
      TASKS[":desktop:test"]=1
      ;;
    android-compose/cli/*)
      TASKS[":cli:testDebugUnitTest"]=1
      ;;
  esac
done <<<"$DIFF_FILES"

# Stable order for reproducible CI logs
ORDERED=(
  ":feature-chat:testDebugUnitTest"
  ":feature-editagent:testDebugUnitTest"
  ":designsystem:testDebugUnitTest"
  ":desktop:test"
  ":cli:testDebugUnitTest"
)

OUT=()
for t in "${ORDERED[@]}"; do
  if [[ -n "${TASKS[$t]+x}" ]]; then
    OUT+=("$t")
  fi
done

# Space-separated (may be empty)
if ((${#OUT[@]})); then
  printf '%s\n' "${OUT[*]}"
else
  printf '\n'
fi
