#!/usr/bin/env bash
set -euo pipefail

SOURCE_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
assert_eq() { [[ "$1" == "$2" ]] || fail "expected '$2', got '$1'"; }
assert_contains() { [[ "$1" == *"$2"* ]] || fail "expected output to contain '$2': $1"; }
assert_not_contains() { [[ "$1" != *"$2"* ]] || fail "expected output not to contain '$2': $1"; }

# Keep the required Android jobs fanned out. Reintroducing a dependency from
# build-apk to test adds the full test duration to the workflow critical path.
android_workflow="$SOURCE_ROOT/.github/workflows/android.yml"
build_apk_job="$(
  awk '
    /^  build-apk:$/ { in_job = 1; next }
    in_job && /^  [[:alnum:]_-]+:$/ { exit }
    in_job { print }
  ' "$android_workflow"
)"
assert_contains "$build_apk_job" 'strategy:'
assert_contains "$build_apk_job" 'uses: actions/cache/restore@v4'
assert_contains "$build_apk_job" 'cache-read-only: ${{ github.event_name =='
if grep -Eq '^    needs:' <<<"$build_apk_job"; then
  fail "build-apk must stay independent so APK assembly fans out with tests"
fi

test_job="$(
  awk '
    /^  test:$/ { in_job = 1; next }
    in_job && /^  [[:alnum:]_-]+:$/ { exit }
    in_job { print }
  ' "$android_workflow"
)"
assert_contains "$test_job" 'Run Android verification task graph'
assert_contains "$test_job" ':app:compileSideloadDebugKotlin'
assert_not_contains "$test_job" ':app:compileRootDebugKotlin'
assert_not_contains "$test_job" ':app:compilePlayDebugKotlin'
gradle_invocations="$(grep -Ec '^[[:space:]]*\./gradlew ' <<<"$test_job")"
assert_eq "$gradle_invocations" '1'

perf_workflow="$(<"$SOURCE_ROOT/.github/workflows/android-perf.yml")"
assert_contains "$perf_workflow" 'cache-read-only: ${{ github.event_name =='
assert_contains "$perf_workflow" '  perf-gate:'
assert_contains "$perf_workflow" 'Classify performance impact'
assert_contains "$perf_workflow" "if: steps.classify.outputs.run_benchmark == 'true'"
assert_not_contains "$perf_workflow" '  macrobenchmark:'

# The pre-push PR readiness checklist (AGENTS.md) names exactly which checks are
# required (block merge) versus advisory (never block). Lock the truth into CI
# so future workflow edits can't silently re-promote an advisory check into a
# required one without surfacing it here. `gh api ... branches/main/protection`
# is the live source; this test pins the structural contract.
branch_protection="$(
  gh api repos/oculairmedia/letta-mobile/branches/main/protection \
    --jq '.required_status_checks.contexts | sort | join(",")' \
    2>/dev/null || echo ''
)"
if [[ -n "$branch_protection" ]]; then
  assert_eq "$branch_protection" \
    'build-apk-pass,perf-gate,shared-multiplatform,test'
else
  echo "ci-policy-scripts-test: skipping branch-protection assertion (gh unavailable)" >&2
fi

shared_job="$(
  awk '
    /^  shared-multiplatform:$/ { in_job = 1; next }
    in_job && /^  [[:alnum:]_-]+:$/ { exit }
    in_job { print }
  ' "$android_workflow"
)"
assert_contains "$shared_job" 'Run shared multiplatform verification task graph'
assert_contains "$shared_job" ':sharedLogic:allTests :desktop:test :appserver-cli:test :appserver-cli:distZip :iroh-wrapper-cli:test :iroh-wrapper-cli:installDist'
shared_gradle_invocations="$(grep -Ec '^[[:space:]]*run: ./gradlew ' <<<"$shared_job")"
assert_eq "$shared_gradle_invocations" '1'

detekt_job="$(
  awk '
    /^  detekt:$/ { in_job = 1; next }
    in_job && /^  [[:alnum:]_-]+:$/ { exit }
    in_job { print }
  ' "$android_workflow"
)"
assert_contains "$detekt_job" 'needs: [test, shared-multiplatform, build-apk-pass]'

build_apk_pass_job="$(
  awk '
    /^  build-apk-pass:$/ { in_job = 1; next }
    in_job && /^  [[:alnum:]_-]+:$/ { exit }
    in_job { print }
  ' "$android_workflow"
)"
assert_contains "$build_apk_pass_job" 'needs: build-apk'

new_repo() {
  local repo="$1"
  mkdir -p "$repo/scripts/ci" "$repo/android-compose"
  cp "$SOURCE_ROOT/scripts/ci/changed-gradle-modules.sh" "$repo/scripts/ci/"
  cp "$SOURCE_ROOT/scripts/ci/agents-policy-check.sh" "$repo/scripts/ci/"
  cp "$SOURCE_ROOT/scripts/ci/stateful-mock-gate.sh" "$repo/scripts/ci/"
  git -C "$repo" init -q
  git -C "$repo" config user.name Test
  git -C "$repo" config user.email test@example.com
  # Keep fixture bytes stable when this Bash suite runs under Git for Windows.
  git -C "$repo" config core.autocrlf false
  touch "$repo/.keep"
  git -C "$repo" add .
  git -C "$repo" commit -qm base
}

repo="$TMP/mapping"
new_repo "$repo"
base="$(git -C "$repo" rev-parse HEAD)"
mkdir -p "$repo/android-compose/feature-chat/src" "$repo/android-compose/designsystem/src" \
  "$repo/android-compose/desktop/src" "$repo/android-compose/cli/src"
touch "$repo/android-compose/feature-chat/src/Chat.kt" "$repo/android-compose/designsystem/src/Theme.kt" \
  "$repo/android-compose/desktop/src/Main.kt" "$repo/android-compose/cli/src/Cli.kt"
git -C "$repo" add . && git -C "$repo" commit -qm modules
actual="$(bash "$repo/scripts/ci/changed-gradle-modules.sh" "$base")"
assert_eq "$actual" ":feature-chat:testDebugUnitTest :designsystem:testDebugUnitTest :desktop:test :cli:testDebugUnitTest"

mkdir -p "$repo/android-compose/feature-editagent/src"
git -C "$repo" mv "$repo/android-compose/feature-chat/src/Chat.kt" "$repo/android-compose/feature-editagent/src/Editor.kt"
git -C "$repo" commit -qm rename
actual="$(bash "$repo/scripts/ci/changed-gradle-modules.sh" HEAD~1)"
assert_eq "$actual" ":feature-editagent:testDebugUnitTest"

if bash "$repo/scripts/ci/changed-gradle-modules.sh" refs/heads/missing >/dev/null 2>&1; then
  fail "module resolver accepted a missing base"
fi
git -C "$repo" checkout --orphan unrelated -q
git -C "$repo" rm -rf . >/dev/null
mkdir -p "$repo/scripts/ci" "$repo/android-compose/feature-chat"
cp "$SOURCE_ROOT/scripts/ci/changed-gradle-modules.sh" "$repo/scripts/ci/"
touch "$repo/android-compose/feature-chat/New.kt"
git -C "$repo" add . && git -C "$repo" commit -qm unrelated
if bash "$repo/scripts/ci/changed-gradle-modules.sh" "$base" >/dev/null 2>&1; then
  fail "module resolver accepted a failed three-dot diff"
fi

# D-filter test: deleting a module file should still schedule that module
git -C "$repo" checkout "$base" -q
mkdir -p "$repo/android-compose/appserver-cli/src"
printf '#!/usr/bin/env bash\necho hello' > "$repo/android-compose/appserver-cli/src/appserver_cli.sh"
git -C "$repo" add . && git -C "$repo" commit -qm "add appserver-cli"
base2="$(git -C "$repo" rev-parse HEAD)"
git -C "$repo" rm "$repo/android-compose/appserver-cli/src/appserver_cli.sh" -q
git -C "$repo" commit -qm "delete appserver-cli file"
actual="$(bash "$repo/scripts/ci/changed-gradle-modules.sh" "$base2")"
assert_eq "$actual" ":appserver-cli:test"

# Additive appserver-cli test: adding a file should also schedule the module
git -C "$repo" checkout "$base2" -q
mkdir -p "$repo/android-compose/appserver-cli/src"
printf '#!/usr/bin/env bash\necho world' > "$repo/android-compose/appserver-cli/src/appserver_cli.sh"
git -C "$repo" add . && git -C "$repo" commit -qm "add appserver-cli file"
actual="$(bash "$repo/scripts/ci/changed-gradle-modules.sh" "$base2")"
assert_eq "$actual" ":appserver-cli:test"

repo="$TMP/policy"
new_repo "$repo"
mkdir -p "$repo/android-compose/app/src" "$repo/android-compose/sharedLogic/src/commonMain/kotlin" \
  "$repo/android-compose/designsystem/src"
printf '%s\n' 'val unchanged = Color(0xFF000000)' 'val changed = 1' >"$repo/android-compose/app/src/Screen.kt"
printf '%s\n' 'val safe = "ok"' >"$repo/android-compose/sharedLogic/src/commonMain/kotlin/Common.kt"
printf '%s\n' 'val allowed = Color(0xFFFFFFFF)' >"$repo/android-compose/designsystem/src/Color.kt"
git -C "$repo" add . && git -C "$repo" commit -qm fixtures
base="$(git -C "$repo" rev-parse HEAD)"
printf '%s\n' 'val unchanged = Color(0xFF000000)' 'val changed = AlertDialog()' >"$repo/android-compose/app/src/Screen.kt"
printf '%s\n' 'val unsafe = value.toByteArray()' >"$repo/android-compose/sharedLogic/src/commonMain/kotlin/Common.kt"
git -C "$repo" add . && git -C "$repo" commit -qm violations
output="$(bash "$repo/scripts/ci/agents-policy-check.sh" --diff-base "$base")"
assert_contains "$output" 'raw-alertdialog|android-compose/app/src/Screen.kt:2'
assert_contains "$output" 'sharedlogic-jvm-api|android-compose/sharedLogic/src/commonMain/kotlin/Common.kt:1'
[[ "$output" != *'no-raw-hex-color'* ]] || fail "diff scan reported an unchanged or exempt raw color"

fallback_output="$(AGENTS_POLICY_FORCE_GREP=1 bash "$repo/scripts/ci/agents-policy-check.sh" --diff-base "$base")"
assert_eq "$fallback_output" "$output"

if bash "$repo/scripts/ci/agents-policy-check.sh" --diff-base refs/heads/missing >/dev/null 2>&1; then
  fail "policy scan accepted a missing base"
fi

# Stateful repository mocks must fail before Gradle starts. Interface mocks are
# allowed, and a narrowly documented exception can use mockk-gate-allow.
repo="$TMP/stateful-mock"
new_repo "$repo"
mkdir -p "$repo/android-compose/feature/src/test"
printf '%s\n' \
  'val safe = mockk<IMessageRepository>()' \
  'val unsafe = mockk<MessageRepository>()' \
  >"$repo/android-compose/feature/src/test/RepositoryTest.kt"
if output="$(bash "$repo/scripts/ci/stateful-mock-gate.sh" 2>&1)"; then
  fail "stateful mock gate accepted a concrete MessageRepository mock"
fi
assert_contains "$output" 'RepositoryTest.kt:2'
assert_contains "$output" 'stateful-mock-gate: 1 violation(s)'

printf '%s\n' \
  'val safe = mockk<IMessageRepository>()' \
  '// mockk-gate-allow: concrete repository edge-case contract' \
  'val allowed = mockk<MessageRepository>()' \
  >"$repo/android-compose/feature/src/test/RepositoryTest.kt"
output="$(bash "$repo/scripts/ci/stateful-mock-gate.sh")"
assert_contains "$output" 'stateful-mock-gate: PASS'

# commonTest JVM API scan: adding a JVM-only API in commonTest should be reported
repo="$TMP/policy"
git -C "$repo" checkout "$base" -q
mkdir -p "$repo/android-compose/sharedLogic/src/commonTest/kotlin"
printf '%s\n' 'val safe = "ok"' >"$repo/android-compose/sharedLogic/src/commonTest/kotlin/TestUtil.kt"
git -C "$repo" add . && git -C "$repo" commit -qm "commonTest baseline"
base3="$(git -C "$repo" rev-parse HEAD)"
printf '%s\n' 'val leak = String.format("x")' >"$repo/android-compose/sharedLogic/src/commonTest/kotlin/TestUtil.kt"
git -C "$repo" add . && git -C "$repo" commit -qm "commonTest violation"
output3="$(bash "$repo/scripts/ci/agents-policy-check.sh" --diff-base "$base3")"
assert_contains "$output3" 'sharedlogic-jvm-api|android-compose/sharedLogic/src/commonTest/kotlin/TestUtil.kt:1'

echo "ci policy script tests: PASS"
