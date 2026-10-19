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
# Required Android workflows (and the perf-scripts self-test) must report on
# Cloud Agent stacked feat/** and cursor/** PRs, not only main and letta/**.
# pull_request.branches filters the PR base. Missing these patterns is why
# stacked reviews never got test / build-apk-pass / shared-multiplatform.
android_workflow_text="$(<"$android_workflow")"
assert_contains "$android_workflow_text" '- "feat/**"'
assert_contains "$android_workflow_text" '- "cursor/**"'
assert_contains "$android_workflow_text" '- "codex/**"'
push_triggers="$(awk '/^  push:$/ { active=1; next } active && /^  [[:alnum:]_]+:$/ { exit } active { print }' "$android_workflow")"
pr_triggers="$(awk '/^  pull_request:$/ { active=1; next } active && /^  [[:alnum:]_]+:$/ { exit } active { print }' "$android_workflow")"
for triggers in "$push_triggers" "$pr_triggers"; do
  assert_contains "$triggers" '- "docs/**"'
  assert_contains "$triggers" '- "scripts/docs/**"'
  assert_contains "$triggers" '- "scripts/appserver/**"'
done
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
assert_contains "$test_job" 'name: test-workload'
required_test_job="$(
  awk '/^  required-test:$/ { active=1; next }
       active && /^  [[:alnum:]_-]+:$/ { exit }
       active { print }' "$android_workflow"
)"
assert_contains "$required_test_job" 'name: test'
assert_contains "$required_test_job" 'needs: [test, api-docs, api-contract-windows]'
assert_contains "$required_test_job" 'if: always()'
assert_contains "$required_test_job" 'TEST_RESULT: ${{ needs.test.result }}'
assert_contains "$required_test_job" 'DOCS_RESULT: ${{ needs.api-docs.result }}'
assert_contains "$required_test_job" 'WINDOWS_API_RESULT: ${{ needs.api-contract-windows.result }}'
required_test_script="$(sed -n '/run: |/,$p' <<<"$required_test_job" | tail -n +2)"
for test_result in success failure cancelled skipped; do
  for docs_result in success failure cancelled skipped; do
    for windows_api_result in success failure cancelled skipped; do
    if TEST_RESULT="$test_result" DOCS_RESULT="$docs_result" WINDOWS_API_RESULT="$windows_api_result" bash -e -c "$required_test_script"; then
      [[ "$test_result/$docs_result/$windows_api_result" == success/success/success ]] || fail "required test accepted $test_result/$docs_result/$windows_api_result"
    else
      [[ "$test_result/$docs_result/$windows_api_result" != success/success/success ]] || fail "required test rejected successful jobs"
    fi
    done
  done
done
assert_contains "$test_job" ':app:compileSideloadDebugKotlin'
assert_not_contains "$test_job" ':app:compileRootDebugKotlin'
assert_not_contains "$test_job" ':app:compilePlayDebugKotlin'
# letta-mobile-o4ygk.1: the module-boundary gate and its synthetic-graph tests
# run inside the REQUIRED test job, not only in the advisory graph workflow.
assert_contains "$test_job" ':build-logic:test'
assert_contains "$test_job" 'checkArchitectureBoundaries'
assert_contains "$test_job" 'test_documentation_index.py'
assert_contains "$test_job" 'scripts/docs/documentation-index.py --check'
assert_contains "$test_job" 'scripts/appserver/generate-protocol-reference.test.mjs'
assert_contains "$test_job" 'scripts/appserver/verify-v2-audit-doc.mjs'
# Stacked PRs must diff additive modules against the GitHub PR base. Diffing
# vs origin/main re-runs lower-stack modules and lets unrelated flakes fail
# required `test` (StreamingMarkdownRecompositionGateTest on #1556).
assert_contains "$test_job" 'github.event.pull_request.base.sha'
assert_not_contains "$test_job" 'changed-gradle-modules.sh origin/main'
gradle_invocations="$(grep -Ec '^[[:space:]]*\./gradlew ' <<<"$test_job")"
assert_eq "$gradle_invocations" '1'
assert_contains "$test_job" 'apiCheck'
assert_not_contains "$test_job" '--continue'
assert_contains "$(cat "$android_workflow")" 'api-contract-windows:'
assert_contains "$(cat "$android_workflow")" 'Check Windows public API contracts'

# One Pages deployment must contain both the app and generated API reference.
# Uploading each site independently would let one replace the other.
assemble_pages="$(
  awk '/^  assemble-web-pages:$/ { active=1; next }
       active && /^  [[:alnum:]_-]+:$/ { exit }
       active { print }' "$android_workflow"
)"
assert_contains "$assemble_pages" 'needs: [web-wasm, api-docs, test]'
assert_contains "$assemble_pages" 'name: wasm-site'
assert_contains "$assemble_pages" 'name: kotlin-api-docs'
assert_contains "$assemble_pages" 'name: kotlin-structure-report'
assert_contains "$assemble_pages" 'path: public/structure'
assert_contains "$test_job" 'Enforce structural documentation limits'
assert_contains "$test_job" ':architecture-tests:structureReport'
assert_contains "$test_job" 'tools/structure_report/structure_report.py --base-ref "$base"'
assert_contains "$test_job" 'STRUCTURE_BASE: ${{ github.event.pull_request.base.sha || github.event.before }}'
assert_contains "$assemble_pages" 'path: public/api'
assert_contains "$assemble_pages" 'test -s public/index.html && test -s public/api/index.html'
assert_contains "$assemble_pages" "github.event_name == 'push' && github.ref == 'refs/heads/main'"
assert_eq "$(grep -c 'uses: actions/upload-pages-artifact@' "$android_workflow")" '1'
deploy_pages="$(
  awk '/^  deploy-web-pages:$/ { active=1; next }
       active && /^  [[:alnum:]_-]+:$/ { exit }
       active { print }' "$android_workflow"
)"
assert_contains "$deploy_pages" 'needs: assemble-web-pages'
assert_contains "$deploy_pages" "github.event_name == 'push' && github.ref == 'refs/heads/main'"

perf_workflow="$(<"$SOURCE_ROOT/.github/workflows/android-perf.yml")"
assert_contains "$perf_workflow" '- "feat/**"'
assert_contains "$perf_workflow" '- "cursor/**"'
assert_contains "$perf_workflow" '- "codex/**"'
assert_contains "$perf_workflow" 'cache-read-only: ${{ github.event_name =='
assert_contains "$perf_workflow" '  perf-gate:'
assert_contains "$perf_workflow" 'Classify performance impact'
assert_not_contains "$perf_workflow" '  macrobenchmark:'
# The ~30 min emulator macrobenchmark is nightly + main, not a PR gate. PRs run
# only the cheap perf-scripts self-test unless they edit the gate itself or ask
# for [perf:rebaseline]; a red nightly/main run files "perf-gate regression".
assert_contains "$perf_workflow" '  perf-scripts:'
assert_contains "$perf_workflow" '    - cron: '
assert_contains "$perf_workflow" "if: needs.perf-scripts.outputs.run_benchmark == 'true'"
assert_contains "$perf_workflow" 'run_benchmark=false'
assert_contains "$perf_workflow" 'TITLE: perf-gate regression'
assert_contains "$perf_workflow" 'issues: write'

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
# perf-gate left the required set when it moved to nightly + main. Until the
# live protection rule is updated, tolerate the old set with a loud note so this
# suite does not go red on a repo-admin setting it cannot change.
if [[ "$branch_protection" == 'build-apk-pass,perf-gate,shared-multiplatform,test' ]]; then
  echo "ci-policy-scripts-test: branch protection still requires perf-gate; remove it (perf-gate is nightly + main now)" >&2
elif [[ -n "$branch_protection" ]]; then
  assert_eq "$branch_protection" \
    'build-apk-pass,shared-multiplatform,test'
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
assert_contains "$shared_job" 'Run bounded sharedLogic JVM tests'
assert_contains "$shared_job" 'timeout --preserve-status 12m ./gradlew :sharedLogic:jvmTest --info'
assert_contains "$shared_job" 'Upload sharedLogic JVM test diagnostics'
assert_contains "$shared_job" 'sharedlogic-jvmtest-diagnostics-${{ github.run_id }}'
assert_contains "$shared_job" 'Fail when bounded sharedLogic JVM tests fail or hang'
assert_contains "$shared_job" 'Run remaining shared multiplatform verification task graph'
# allTests / hostNativeTest hang; :desktop:test also hung on follow-up PRs
# even before Konan. JVM + CLI tests first; native compile second.
assert_contains "$shared_job" ':appserver-cli:test :appserver-cli:distZip :iroh-wrapper-cli:test :iroh-wrapper-cli:installDist'
assert_contains "$shared_job" ':sharedLogic:compileKotlinHostNative :sharedLogic:compileTestKotlinHostNative'
# letta-mobile-o4ygk.2: wasmJs compiles stay in the REQUIRED shared job.
assert_contains "$shared_job" ':sharedLogic:compileKotlinWasmJs :sharedLogic:compileTestKotlinWasmJs'
# letta-mobile-o4ygk.4: the shared Compose UI compiles for wasm in the same required job.
assert_contains "$shared_job" ':sharedUI:compileKotlinWasmJs :sharedUI:compileTestKotlinWasmJs'
shared_gradle_invocations="$(grep -Ec '^[[:space:]]*\./gradlew ' <<<"$shared_job")"
assert_eq "$shared_gradle_invocations" '2'

detekt_job="$(
  awk '
    /^  detekt:$/ { in_job = 1; next }
    in_job && /^  [[:alnum:]_-]+:$/ { exit }
    in_job { print }
  ' "$android_workflow"
)"
assert_contains "$detekt_job" 'needs: [test, shared-multiplatform, build-apk-pass]'
# letta-mobile-by4vm.32: honest gates. detekt fails on findings outside the
# per-module baselines; main pushes always run sharedUI/desktop tests; the perf
# gate measures once (no automatic rerun) and the Qodana workflow stays gone.
assert_contains "$detekt_job" '-Parchitecture.strict=true'
assert_not_contains "$detekt_job" 'continue-on-error'
main_module_job="$(
  awk '
    /^  main-module-tests:$/ { in_job = 1; next }
    in_job && /^  [[:alnum:]_-]+:$/ { exit }
    in_job { print }
  ' "$android_workflow"
)"
assert_contains "$main_module_job" "if: github.event_name != 'pull_request'"
assert_contains "$main_module_job" './gradlew :sharedUI:jvmTest :desktop:test'
assert_not_contains "$perf_workflow" 'attempt 2'
assert_not_contains "$perf_workflow" '--retryable-single-startup-exit-code'
[[ ! -e "$SOURCE_ROOT/.github/workflows/qodana_code_quality.yml" ]] || fail "Qodana workflow is back; see letta-mobile-by4vm.32"
platform_ui_job="$(
  awk '
    /^  platform-ui-gate:$/ { in_job = 1; next }
    in_job && /^  [[:alnum:]_-]+:$/ { exit }
    in_job { print }
  ' "$SOURCE_ROOT/.github/workflows/agents-policy.yml"
)"
assert_contains "$platform_ui_job" '--enforce'
assert_not_contains "$platform_ui_job" 'continue-on-error'

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
  "$repo/android-compose/sharedUI/src" "$repo/android-compose/desktop/src" "$repo/android-compose/cli/src"
touch "$repo/android-compose/feature-chat/src/Chat.kt" "$repo/android-compose/designsystem/src/Theme.kt" \
  "$repo/android-compose/sharedUI/src/Surface.kt" \
  "$repo/android-compose/desktop/src/Main.kt" "$repo/android-compose/cli/src/Cli.kt"
git -C "$repo" add . && git -C "$repo" commit -qm modules
actual="$(bash "$repo/scripts/ci/changed-gradle-modules.sh" "$base")"
assert_eq "$actual" ":feature-chat:testDebugUnitTest :designsystem:testDebugUnitTest :sharedUI:jvmTest :desktop:test :cli:test"

# Stacked PR: designsystem landed on the lower branch; this commit only
# touches app/. Diff vs the stack base must not schedule designsystem.
mkdir -p "$repo/android-compose/app/src"
stack_base="$(git -C "$repo" rev-parse HEAD)"
touch "$repo/android-compose/app/src/Conversations.kt"
git -C "$repo" add . && git -C "$repo" commit -qm stacked-app-only
actual="$(bash "$repo/scripts/ci/changed-gradle-modules.sh" "$stack_base")"
assert_eq "$actual" ""
git -C "$repo" reset --hard "$stack_base" -q

mkdir -p "$repo/android-compose/designsystem/src"
git -C "$repo" mv "$repo/android-compose/feature-chat/src/Chat.kt" "$repo/android-compose/designsystem/src/Editor.kt"
git -C "$repo" commit -qm rename
actual="$(bash "$repo/scripts/ci/changed-gradle-modules.sh" HEAD~1)"
assert_eq "$actual" ":designsystem:testDebugUnitTest"

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

# An SPI change re-runs the conformance kit; a kit change runs only the kit.
mkdir -p "$repo/android-compose/plugin-api/src" "$repo/android-compose/plugin-api-testkit/src"
touch "$repo/android-compose/plugin-api/src/CanvasPlugin.kt"
git -C "$repo" add . && git -C "$repo" commit -qm "touch plugin-api"
actual="$(bash "$repo/scripts/ci/changed-gradle-modules.sh" HEAD~1)"
assert_eq "$actual" ":plugin-api:jvmTest :plugin-api-testkit:jvmTest"
touch "$repo/android-compose/plugin-api-testkit/src/PluginConformance.kt"
git -C "$repo" add . && git -C "$repo" commit -qm "touch plugin-api-testkit"
actual="$(bash "$repo/scripts/ci/changed-gradle-modules.sh" HEAD~1)"
assert_eq "$actual" ":plugin-api-testkit:jvmTest"

# A DrawBox change runs its own tests and sharedUI's, which render through it.
mkdir -p "$repo/android-compose/drawbox/src"
touch "$repo/android-compose/drawbox/src/ShapeLabel.kt"
git -C "$repo" add . && git -C "$repo" commit -qm "touch drawbox"
actual="$(bash "$repo/scripts/ci/changed-gradle-modules.sh" HEAD~1)"
assert_eq "$actual" ":drawbox:jvmTest :sharedUI:jvmTest"

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

# Release documentation contract: README claims APK + Windows EXE/MSI assets,
# and release.yml must attach matching patterns so the docs stay true.
readme="$(<"$SOURCE_ROOT/README.md")"
assert_contains "$readme" 'signed Android APK'
assert_contains "$readme" '.exe'
assert_contains "$readme" '.msi'
release_workflow="$(<"$SOURCE_ROOT/.github/workflows/release.yml")"
assert_contains "$release_workflow" 'release-assets/*.apk'
assert_contains "$release_workflow" 'release-assets/exe/*.exe'
assert_contains "$release_workflow" 'release-assets/msi/*.msi'

# Docs freshness: the real docs pass, and each source of truth the check reads
# (settings.gradle.kts, branch-protection CHECKS, links) fails it when it drifts.
docs_check="$SOURCE_ROOT/scripts/ci/docs-freshness-check.py"
python3 "$docs_check" "$SOURCE_ROOT" >/dev/null || fail "docs-freshness-check must pass on the repo docs"
# The fixture holds only the docs and the sources of truth, so unrelated links may
# also report; each case asserts its own message rather than a clean baseline.
docs_fixture() {
  rm -rf "$TMP/docs" && mkdir -p "$TMP/docs"
  # Portable copy (BSD cp has no --parents / -t).
  (cd "$SOURCE_ROOT" && for f in README.md CONTRIBUTING.md CLAUDE.md AGENTS.md \
    android-compose/README.md android-compose/settings.gradle.kts \
    android-compose/gradle/wrapper/gradle-wrapper.properties \
    android-compose/appserver-cli/src/test/resources/appserver/restart-replay-evidence.json \
    scripts/bootstrap-branch-protection.sh .github/workflows/*.yml; do
      mkdir -p "$TMP/docs/$(dirname "$f")" && cp "$f" "$TMP/docs/$f"
    done)
}
docs_fixture
printf '%s\n' 'include(":brand-new-module")' >>"$TMP/docs/android-compose/settings.gradle.kts"
mkdir -p "$TMP/docs/android-compose/brand-new-module"
if output="$(python3 "$docs_check" "$TMP/docs" 2>&1)"; then fail "unlisted module must fail docs-freshness"; fi
assert_contains "$output" 'module :brand-new-module is not named'
docs_fixture
sed -i.bak 's/^CHECKS=(\(.*\))/CHECKS=(\1 codecov)/' "$TMP/docs/scripts/bootstrap-branch-protection.sh"
if output="$(python3 "$docs_check" "$TMP/docs" 2>&1)"; then fail "gate drift must fail docs-freshness"; fi
assert_contains "$output" 'Required CI gates lists'
docs_fixture
printf '\nSee [gone](docs/does-not-exist.md) and `:nope:assembleDebug`.\n' >>"$TMP/docs/README.md"
if output="$(python3 "$docs_check" "$TMP/docs" 2>&1)"; then fail "broken link must fail docs-freshness"; fi
assert_contains "$output" 'broken link (docs/does-not-exist.md)'
assert_contains "$output" '`:nope:assembleDebug` does not start with a declared Gradle module'

# Generated navigation percent-encodes filenames; the freshness gate must decode
# the URL path and ignore query/fragment components before resolving it.
docs_fixture
mkdir -p "$TMP/docs/docs"
printf '# Spaced title\n' >"$TMP/docs/docs/space name.md"
printf '\n[valid](docs/space%%20name.md?view=source#heading)\n' >>"$TMP/docs/README.md"
output="$(python3 "$docs_check" "$TMP/docs" 2>&1 || true)"
assert_not_contains "$output" 'broken link (docs/space%20name.md?view=source#heading)'
printf '\n[missing](docs/missing%%20name.md)\n' >>"$TMP/docs/README.md"
output="$(python3 "$docs_check" "$TMP/docs" 2>&1 || true)"
assert_contains "$output" 'broken link (docs/missing%20name.md)'
# An includeBuild(...) build is a valid task prefix (`:build-logic:test` runs in `test`);
# dropping the includeBuild makes the same reference fail.
docs_fixture
printf '\nRun `:build-logic:test`.\n' >>"$TMP/docs/README.md"
output="$(python3 "$docs_check" "$TMP/docs" 2>&1 || true)"
assert_not_contains "$output" '`:build-logic:test` does not start'
sed -i.bak '/includeBuild("build-logic")/d' "$TMP/docs/android-compose/settings.gradle.kts"
if output="$(python3 "$docs_check" "$TMP/docs" 2>&1)"; then fail "unknown build prefix must fail docs-freshness"; fi
assert_contains "$output" '`:build-logic:test` does not start with a declared Gradle module'

docs_fixture
mkdir -p "$TMP/docs/docs/architecture"
printf '# A design\n\nNo lifecycle declared.\n' >"$TMP/docs/docs/architecture/example.md"
output="$(python3 "$docs_check" "$TMP/docs" 2>&1 || true)"
assert_contains "$output" 'example.md: missing Status header'
printf '\n**Status:** Historical design.\n<!-- live-path: missing/source.kt -->\n' >>"$TMP/docs/docs/architecture/example.md"
output="$(python3 "$docs_check" "$TMP/docs" 2>&1 || true)"
assert_not_contains "$output" 'example.md: missing Status header'
assert_contains "$output" 'missing live path (missing/source.kt)'
mkdir -p "$TMP/docs/missing"
touch "$TMP/docs/missing/source.kt"
output="$(python3 "$docs_check" "$TMP/docs" 2>&1 || true)"
assert_not_contains "$output" 'missing live path (missing/source.kt)'

docs_fixture
sed -i.bak 's/three clients/two clients/' "$TMP/docs/CLAUDE.md"
output="$(python3 "$docs_check" "$TMP/docs" 2>&1 || true)"
assert_contains "$output" 'says two clients/apps but Gradle declares three client hosts'
sed -i.bak '/Browser WASM host/d' "$TMP/docs/CLAUDE.md"
output="$(python3 "$docs_check" "$TMP/docs" 2>&1 || true)"
assert_contains "$output" 'missing client host :web in module map'

docs_fixture
printf '\nThe restart-replay evidence stays at 0.0.1 until re-captured live.\n' >>"$TMP/docs/CLAUDE.md"
output="$(python3 "$docs_check" "$TMP/docs" 2>&1 || true)"
assert_contains "$output" 'restart-replay claim 0.0.1 differs from captured evidence'

echo "ci policy script tests: PASS"
