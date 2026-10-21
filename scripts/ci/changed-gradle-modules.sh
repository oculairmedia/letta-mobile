#!/usr/bin/env bash
# Map git changes vs a base ref to the additive Gradle unit-test tasks they need.
#
# Usage: changed-gradle-modules.sh [BASE_REF]
#        changed-gradle-modules.sh --all
#   BASE_REF defaults to origin/main. CI PRs pass github.event.pull_request.base.sha
#   so stacked reviews only schedule modules that changed vs their GitHub base.
#   --all prints every additive task (every push to main runs the full set).
#
# Dependency-aware (letta-mobile-l8lcn): a changed module schedules its own tests AND the tests of
# every module that depends on it, transitively, read from the `project(":x")` edges in the build
# scripts. A sharedLogic change therefore runs sharedUI, feature-chat, designsystem, desktop, cli,
# ... tests. A change to the root build, settings, gradle/ or build-logic schedules everything.
# Only files outside every module (docs, top-level READMEs) schedule nothing.
#
# Prints a space-separated list of Gradle tasks to stdout (may be empty).
# Resolver or diff failures are fatal so CI never silently skips applicable tests.
set -euo pipefail

ALL=0
if [[ "${1:-}" == "--all" ]]; then
  ALL=1
  shift
fi
BASE_REF="${1:-origin/main}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

ORDERED=(
  ":app:compileRootDebugAndroidTestKotlin"
  ":feature-chat:testDebugUnitTest"
  ":designsystem:testDebugUnitTest"
  ":drawbox:jvmTest"
  ":sharedUI:jvmTest"
  ":plugin-view:jvmTest"
  ":plugin-view:testAndroidHostTest"
  ":core:android-data:testDebugUnitTest"
  ":core:data:jvmTest"
  ":core:ids:jvmTest"
  ":core:runtime:jvmTest"
  ":desktop:test"
  ":cli:test"
  ":appserver-cli:test"
  ":iroh-wrapper-cli:test"
  ":plugin-api:jvmTest"
  ":plugin-api-testkit:jvmTest"
)

# Gradle module -> its additive test tasks. Modules not listed have none here (:app's run in the
# base task set, :sharedLogic's in shared-multiplatform, :web's in web-wasm) but still pass changes
# on to their dependents.
declare -A MODULE_TASKS=(
  [":feature-chat"]=":feature-chat:testDebugUnitTest"
  [":designsystem"]=":designsystem:testDebugUnitTest"
  [":sharedUI"]=":sharedUI:jvmTest"
  [":drawbox"]=":drawbox:jvmTest"
  [":plugin-view"]=":plugin-view:jvmTest :plugin-view:testAndroidHostTest"
  [":core:android-data"]=":core:android-data:testDebugUnitTest"
  [":core:data"]=":core:data:jvmTest"
  [":core:ids"]=":core:ids:jvmTest"
  [":core:runtime"]=":core:runtime:jvmTest"
  [":desktop"]=":desktop:test"
  [":cli"]=":cli:test"
  [":appserver-cli"]=":appserver-cli:test"
  [":appserver-testkit"]=":app:compileRootDebugAndroidTestKotlin"
  [":iroh-wrapper-cli"]=":iroh-wrapper-cli:test"
  [":plugin-api"]=":plugin-api:jvmTest"
  [":plugin-api-testkit"]=":plugin-api-testkit:jvmTest"
)
# Edges the build scripts do not spell as project(":x") dependencies: sharedUI checks DrawBox's
# pixels, the conformance kit is the SPI's contract, the Room contracts' Android consumers and the
# testkit's JVM callers.
declare -A EXTRA_DEPENDENTS=(
  [":drawbox"]=":sharedUI"
  [":core:data"]=":core:android-data"
  [":plugin-api"]=":plugin-api-testkit"
  [":appserver-testkit"]=":cli :appserver-cli"
)

print_tasks() { # task... -> ORDERED subset on one line
  declare -A want=()
  local task
  for task in "$@"; do want["$task"]=1; done
  local out=()
  for task in "${ORDERED[@]}"; do
    [[ -n "${want[$task]+x}" ]] && out+=("$task")
  done
  if ((${#out[@]})); then
    printf '%s\n' "${out[*]}"
  else
    printf '\n'
  fi
}

if ((ALL)); then
  print_tasks "${ORDERED[@]}"
  exit 0
fi


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
if ! DIFF_FILES="$(git diff --name-only --diff-filter=ACMRD "${RESOLVED_BASE}...HEAD")"; then
  echo "changed-gradle-modules: failed to diff '$RESOLVED_BASE...HEAD'" >&2
  exit 1
fi

# --- module graph -------------------------------------------------------------------------
declare -A MODULE_DIRS=()   # android-compose/<dir> -> :module
declare -A DEPENDENTS=()    # :module -> " :dependent ..."
for module in "${!MODULE_TASKS[@]}" "${!EXTRA_DEPENDENTS[@]}"; do
  dir="${module#:}"
  MODULE_DIRS["android-compose/${dir//://}"]="$module"
done
for module in "${!EXTRA_DEPENDENTS[@]}"; do
  DEPENDENTS["$module"]+=" ${EXTRA_DEPENDENTS[$module]}"
done
while IFS= read -r build; do
  [[ -z "$build" ]] && continue
  dir="${build%/build.gradle.kts}"
  [[ "$dir" == android-compose || "$dir" == android-compose/build-logic* ]] && continue
  module=":${dir#android-compose/}"
  module="${module//\//:}"
  MODULE_DIRS["$dir"]="$module"
  while IFS= read -r dep; do
    [[ -n "$dep" && "$dep" != "$module" ]] && DEPENDENTS["$dep"]+=" $module"
  done < <(grep -o 'project("[^"]*")' "$build" | sed 's/^project("\(.*\)")$/\1/' | sort -u)
done < <(find android-compose -name build.gradle.kts -not -path '*/build/*' -not -path 'android-compose/build-logic/*' 2>/dev/null | sort)

module_of() { # repo-relative path -> :module (longest directory match), or empty
  local dir="${1%/*}"
  while [[ "$dir" == android-compose/* ]]; do
    if [[ -n "${MODULE_DIRS[$dir]+x}" ]]; then
      printf '%s\n' "${MODULE_DIRS[$dir]}"
      return
    fi
    dir="${dir%/*}"
  done
}

declare -A AFFECTED=()
queue=()
mark() {
  [[ -n "${AFFECTED[$1]+x}" ]] && return
  AFFECTED["$1"]=1
  queue+=("$1")
}

while IFS= read -r file; do
  [[ -z "$file" ]] && continue
  case "$file" in
    android-compose/build.gradle.kts | android-compose/settings.gradle.kts | android-compose/gradle.properties |       android-compose/gradle/* | android-compose/build-logic/*)
      print_tasks "${ORDERED[@]}"
      exit 0
      ;;
    android-compose/*)
      module="$(module_of "$file")"
      [[ -n "$module" ]] && mark "$module"
      ;;
  esac
done <<<"$DIFF_FILES"

while ((${#queue[@]})); do
  module="${queue[0]}"
  queue=("${queue[@]:1}")
  for dependent in ${DEPENDENTS[$module]:-}; do mark "$dependent"; done
done

tasks=()
for module in "${!AFFECTED[@]}"; do
  [[ -n "${MODULE_TASKS[$module]+x}" ]] && read -r -a more <<<"${MODULE_TASKS[$module]}" && tasks+=("${more[@]}")
done
print_tasks "${tasks[@]}"
