#!/usr/bin/env bash
# Map git changes vs a base ref to additive Gradle unit-test tasks.
#
# Usage: changed-gradle-modules.sh [BASE_REF]
#   BASE_REF defaults to origin/main
#
# Prints a space-separated list of Gradle tasks to stdout (may be empty).
# Resolver or diff failures are fatal so CI never silently skips applicable tests.
set -euo pipefail

BASE_REF="${1:-origin/main}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

resolve_base() {
  local ref="$1"
  if git rev-parse --verify -q "${ref}^{commit}" >/dev/null 2>&1; then
    printf '%s\n' "$ref"
    return
  fi

  if [[ "$ref" == origin/* ]]; then
    local branch="${ref#origin/}"
    git fetch --no-tags --depth=1 origin \
      "+refs/heads/${branch}:refs/remotes/origin/${branch}" >/dev/null 2>&1 || {
        echo "changed-gradle-modules: failed to fetch missing base '$ref'" >&2
        return 1
      }
    if git rev-parse --verify -q "${ref}^{commit}" >/dev/null 2>&1; then
      printf '%s\n' "$ref"
      return
    fi
  fi

  echo "changed-gradle-modules: base '$ref' is not a commit" >&2
  return 1
}

RESOLVED_BASE="$(resolve_base "$BASE_REF")"
if ! DIFF_FILES="$(git diff --name-only --diff-filter=ACMR "${RESOLVED_BASE}...HEAD")"; then
  echo "changed-gradle-modules: failed to diff '$RESOLVED_BASE...HEAD'" >&2
  exit 1
fi

declare -A TASKS=()
while IFS= read -r file; do
  [[ -z "$file" ]] && continue
  case "$file" in
    android-compose/feature-chat/*) TASKS[":feature-chat:testDebugUnitTest"]=1 ;;
    android-compose/feature-editagent/*) TASKS[":feature-editagent:testDebugUnitTest"]=1 ;;
    android-compose/designsystem/*) TASKS[":designsystem:testDebugUnitTest"]=1 ;;
    android-compose/core/data/*) ;;
    android-compose/desktop/*) TASKS[":desktop:test"]=1 ;;
    android-compose/cli/*) TASKS[":cli:testDebugUnitTest"]=1 ;;
  esac
done <<<"$DIFF_FILES"

ORDERED=(
  ":feature-chat:testDebugUnitTest"
  ":feature-editagent:testDebugUnitTest"
  ":designsystem:testDebugUnitTest"
  ":desktop:test"
  ":cli:testDebugUnitTest"
)
OUT=()
for task in "${ORDERED[@]}"; do
  [[ -n "${TASKS[$task]+x}" ]] && OUT+=("$task")
done

if ((${#OUT[@]})); then
  printf '%s\n' "${OUT[*]}"
else
  printf '\n'
fi
