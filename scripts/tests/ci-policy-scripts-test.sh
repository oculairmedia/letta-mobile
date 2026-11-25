#!/usr/bin/env bash
set -euo pipefail

SOURCE_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
assert_eq() { [[ "$1" == "$2" ]] || fail "expected '$2', got '$1'"; }
assert_contains() { [[ "$1" == *"$2"* ]] || fail "expected output to contain '$2': $1"; }
assert_not_contains() { [[ "$1" != *"$2"* ]] || fail "expected output not to contain '$2': $1"; }

# AGP 9 may omit detekt's Android variant tasks. The aggregate must scan
# every source set instead of reverting Android modules to the plugin defaults.
detekt_build="$(<"$SOURCE_ROOT/android-compose/build.gradle.kts")"
assert_contains "$detekt_build" 'files(allSourceSetDirs(layout.projectDirectory.dir("src").asFile))'
assert_not_contains "$detekt_build" 'files(pluginDefaultSource)'

# Artifact-only Ivy repositories must never probe Maven libraries. Coordinates
# match the setup tasks in KGP 2.4.10, including Binaryen's lowercase group.
python3 - "$SOURCE_ROOT/android-compose/settings.gradle.kts" <<'PY'
import json
from pathlib import Path
import re
import sys


def tokens(text):
    # Ignore comments, but preserve strings (URLs contain //) and block nesting.
    return [m.group() for m in re.finditer(
        r'"(?:\\.|[^"\\])*"|//[^\n]*|/\*.*?\*/|[A-Za-z_][A-Za-z_0-9]*|[^\s]',
        text, re.S,
    ) if not m.group().startswith(('//', '/*'))]


def blocks(ts, prefix):
    found = []
    depth = 0
    for i, token in enumerate(ts):
        if depth == 0 and ts[i:i + len(prefix)] == prefix:
            start = i + len(prefix)
            level = 1
            end = start
            while end < len(ts) and level:
                level += (ts[end] == '{') - (ts[end] == '}')
                end += 1
            assert level == 0, 'unbalanced repository block'
            found.append(ts[start:end - 1])
        depth += (token == '{') - (token == '}')
    return found


def only_block(ts, prefix):
    matches = blocks(ts, prefix)
    assert len(matches) == 1, f'expected one block: {prefix}'
    return matches[0]


tools = (
    ('https://nodejs.org/dist/', 'org.nodejs', 'node'),
    ('https://github.com/yarnpkg/yarn/releases/download', 'com.yarnpkg', 'yarn'),
    ('https://github.com/WebAssembly/binaryen/releases/download', 'com.github.webassembly', 'binaryen'),
)


def check(text):
    management = only_block(tokens(text), ['dependencyResolutionManagement', '{'])
    repos = only_block(management, ['repositories', '{'])
    for url, group, module in tools:
        repo = only_block(repos, ['ivy', '(', json.dumps(url), ')', '{'])
        content = only_block(repo, ['content', '{'])
        assert content == tokens(f'includeModule("{group}", "{module}")'), (
            f'{module}: allow only the exact tool module, not broad includes'
        )


settings = Path(sys.argv[1]).read_text()
check(settings)  # Positive: all three normal tool coordinates remain permitted.
for _, group, module in tools:
    include = f'includeModule("{group}", "{module}")'
    filter_block = re.search(r'content\s*\{\s*' + re.escape(include) + r'\s*\}', settings)
    assert filter_block, f'{module}: missing filter fixture'
    for replacement in (
        '',  # Missing filter, even with a valid filter elsewhere in settings.
        'content { // ' + include + '\n}',
        'content { includeModule("wrong.group", "wrong-module") }',
        'content { ' + include + ' includeGroupByRegex(".*") }',
        'content { ' + include + f' includeGroup("{group}")' + ' }',
    ):
        invalid = settings[:filter_block.start()] + replacement + settings[filter_block.end():]
        invalid += '\ncontent { ' + include + ' }\n'
        try:
            check(invalid)
        except AssertionError:
            continue
        raise AssertionError(f'{module}: accepted missing, misplaced, commented or broad filter')
print('Distribution repository scope checks passed (3 positive modules, 15 negative mutations)')
PY

# Probe assertions moved with the probe helpers into the JVM CLI. Keep the
# workflow pointed at that owner: the old sharedLogic filter selects no tests
# and prevents the hermetic/red-path jobs from running.
probe_workflow="$(<"$SOURCE_ROOT/.github/workflows/iroh-probe.yml")"
probe_unit_job="$(awk '/^  probe-unit:$/ { active=1; next }
  active && /^  [[:alnum:]_-]+:$/ { exit }
  active { print }' <<<"$probe_workflow")"
assert_contains "$probe_unit_job" './gradlew :cli:test'
assert_not_contains "$probe_unit_job" ':sharedLogic:jvmTest'
[[ -f "$SOURCE_ROOT/android-compose/cli/src/test/java/com/letta/mobile/data/transport/iroh/IrohProbeAssertionsTest.kt" ]] ||
  fail 'probe assertion suite must exist in the CLI test source set'

# Keep the required Android jobs fanned out. Reintroducing a dependency from
# build-apk to test adds the full test duration to the workflow critical path.
android_workflow="$SOURCE_ROOT/.github/workflows/android.yml"
# Required Android workflows (and the perf-scripts self-test) must report on
# Cloud Agent stacked feat/** and cursor/** PRs, not only main and letta/**.
# pull_request.branches filters the PR base. Missing these patterns is why
# stacked reviews never got test / build-apk-pass / shared-multiplatform.
android_workflow_text="$(<"$android_workflow")"
assert_contains "$android_workflow_text" $'      - name: Upload sharedUI JVM test reports\n        if: always()\n'
assert_contains "$android_workflow_text" '- "feat/**"'
assert_contains "$android_workflow_text" '- "cursor/**"'
assert_contains "$android_workflow_text" '- "codex/**"'
push_triggers="$(awk '/^  push:$/ { active=1; next } active && /^  [[:alnum:]_]+:$/ { exit } active { print }' "$android_workflow")"
pr_triggers="$(awk '/^  pull_request:$/ { active=1; next } active && /^  [[:alnum:]_]+:$/ { exit } active { print }' "$android_workflow")"
assert_contains "$push_triggers" '- "docs/**"'
assert_contains "$push_triggers" '- "scripts/docs/**"'
assert_contains "$push_triggers" '- "scripts/appserver/**"'
# Tree hygiene must run for additions in any directory, including unknown ones.
assert_not_contains "$pr_triggers" '    paths:'
assert_not_contains "$pr_triggers" '    paths-ignore:'
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
shared_ui_job="$(
  awk '
    /^  shared-ui-desktop-tests:$/ { in_job = 1; next }
    in_job && /^  [[:alnum:]_-]+:$/ { exit }
    in_job { print }
  ' "$android_workflow"
)"
# ci-speedup-pr2: the additive :sharedUI:jvmTest / :desktop:test suites run in a
# parallel job, not serially on the Android test runner. It is part of required
# `test`: green-skip semantics only, never a job-level `if:`.
assert_contains "$shared_ui_job" 'name: shared-ui-desktop-tests'
assert_not_contains "$shared_ui_job" $'\n    if:'
assert_contains "$shared_ui_job" $'\n    timeout-minutes: '
assert_contains "$shared_ui_job" 'java-version: 26'
# Every heavy step rides the resolve step's output; a PR that touches neither
# sharedUI nor desktop skips them with a step summary and still reports green.
assert_contains "$shared_ui_job" '- name: Resolve shared UI and desktop tests'
assert_contains "$shared_ui_job" "if: contains(steps.jvm_ui_scope.outputs.tasks, ':desktop:')"
assert_contains "$shared_ui_job" "if: steps.jvm_ui_scope.outputs.tasks != ''"
assert_contains "$shared_ui_job" 'changed-gradle-modules.sh --all --group jvm-ui'
assert_contains "$shared_ui_job" 'changed-gradle-modules.sh --group jvm-ui "$base"'
assert_contains "$shared_ui_job" 'Shared UI + desktop JVM tests: skipped on this PR.'
# The desktop suite pulls the Rust pen bridge (native/tablet_input) and the
# Mermaid renderer through processResources; their cargo state is cached.
assert_contains "$shared_ui_job" 'android-compose/native/tablet_input/target'
assert_contains "$shared_ui_job" "hashFiles('android-compose/native/tablet_input/Cargo.lock'"
# The reports moved with the suites: the sharedUI upload must not be in test-workload.
assert_contains "$shared_ui_job" 'Upload sharedUI JVM test reports'
assert_contains "$shared_ui_job" 'sharedui-jvmtest-reports-${{ github.run_id }}'
assert_contains "$shared_ui_job" 'Upload desktop JVM test reports'
assert_not_contains "$test_job" 'Upload sharedUI JVM test reports'
# The desktop prerequisites install moved along with :desktop:test.
assert_contains "$shared_ui_job" 'bash "$GITHUB_WORKSPACE/scripts/ci/apt-install-retry.sh" gcc pkg-config patch libx11-dev libglib2.0-dev xvfb xauth'
assert_not_contains "$test_job" 'Install Linux hotkey build prerequisites'
shared_gradle_ui_invocations="$(grep -Ec '^[[:space:]]*\./gradlew ' <<<"$shared_ui_job")"
assert_eq "$shared_gradle_ui_invocations" '1'
required_test_job="$(
  awk '/^  required-test:$/ { active=1; next }
       active && /^  [[:alnum:]_-]+:$/ { exit }
       active { print }' "$android_workflow"
)"
assert_contains "$required_test_job" 'name: test'
# letta-mobile-l8lcn: web tests are part of the required set too.
# PR-description checks (pr-body-gate) are part of the same aggregate: a missing body line blocks merge.
# ci-speedup-pr2: so is the parallel sharedUI/desktop suite job.
assert_contains "$required_test_job" 'needs: [test, api-docs, api-contract-windows, web-wasm, pr-body-gate, shared-ui-desktop-tests]'
assert_contains "$required_test_job" 'if: always()'
assert_contains "$required_test_job" 'TEST_RESULT: ${{ needs.test.result }}'
assert_contains "$required_test_job" 'DOCS_RESULT: ${{ needs.api-docs.result }}'
assert_contains "$required_test_job" 'WINDOWS_API_RESULT: ${{ needs.api-contract-windows.result }}'
assert_contains "$required_test_job" 'WEB_RESULT: ${{ needs.web-wasm.result }}'
assert_contains "$required_test_job" 'SHARED_UI_DESKTOP_RESULT: ${{ needs.shared-ui-desktop-tests.result }}'
assert_contains "$required_test_job" 'BODY_RESULT: ${{ needs.pr-body-gate.result }}'
assert_contains "$required_test_job" 'EVENT_NAME: ${{ github.event_name }}'
api_docs_job="$(awk '/^  api-docs:$/ { active=1; next }
  active && /^  [[:alnum:]_-]+:$/ { exit }
  active { print }' "$android_workflow")"
# ci-speedup-pr1: the 18m Dokka HTML graph is path-gated. The fixture carries the
# gate condition too, so an edit that un-conditions the precompile (or reorders
# its `if:` behind `run:`) fails the endswith match below, exactly like a removal
# or a duplicate did before.
docs_precompile_step='      - name: Precompile shared Android API dependency
        if: steps.dokka_gate.outputs.run == '\''true'\''
        working-directory: android-compose
        run: ./gradlew --no-daemon --no-parallel --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process --console=plain :sharedLogic:compileAndroidMain'

docs_compiles_in_separate_process() {
  local job="$1"
  local before_generation="${job%%      - name: Generate shared API reference*}"
  [[ "$before_generation" != "$job" && "$before_generation" == *"$docs_precompile_step"$'\n' ]] || return 1
  local generation="${job#*      - name: Generate shared API reference}"
  [[ "$generation" == *'set -o pipefail'* ]] || return 1
  [[ "$generation" == *'./gradlew --no-daemon --no-parallel --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process -PdokkaSourceRef=${{ github.sha }} --console=plain :dokkaGeneratePublicationHtml 2>&1 | tee dokka-diagnostics.log'* ]]
}

docs_compiles_in_separate_process "$api_docs_job" || fail 'API documentation must precompile Android in a separate single-use JVM'
# The fast tooling checks stay on every PR — no gate may attach to them — and the
# heavy Gradle steps may only run behind the gate. The gate opens on non-PR events
# and on exactly the public-API/docs paths that can change the rendered reference.
assert_contains "$api_docs_job" $'- name: Verify documentation tooling\n        env:'
assert_contains "$api_docs_job" "if: steps.dokka_gate.outputs.run == 'true'"
assert_not_contains "$api_docs_job" $'- name: Verify documentation tooling\n        if:'
assert_not_contains "$api_docs_job" $'- name: Setup Java 26\n        uses:'
assert_contains "$api_docs_job" "- name: Setup Java 26
        if: steps.dokka_gate.outputs.run == 'true'
"
assert_contains "$api_docs_job" "grep -Eq '^android-compose/(sharedLogic|sharedUI|core/ids|core/runtime|plugin-api)/|^docs/|(^|/)Module\\.md$|^scripts/ci/api-doc-undocumented-baseline\\.txt$|^\\.github/workflows/android\\.yml$'"
for invalid_docs_job in \
  "${api_docs_job/"$docs_precompile_step"/}" \
  "${api_docs_job/"$docs_precompile_step"/}"$'\n'"$docs_precompile_step" \
  "${api_docs_job/:sharedLogic:compileAndroidMain/:sharedLogic:compileAndroidMain :dokkaGeneratePublicationHtml}" \
  "${api_docs_job/"$docs_precompile_step"/"$docs_precompile_step"$'\n        if: false'}"; do
  if docs_compiles_in_separate_process "$invalid_docs_job"; then
    fail 'API documentation isolation check accepted a missing, reordered, combined or disabled compiler invocation'
  fi
done
required_test_script="$(sed -n '/run: |/,$p' <<<"$required_test_job" | tail -n +2)"
# The required-test script is a flat conjunction: each conjunct reads exactly one
# result variable. The cross-product below holds the shared-ui result at success
# while covering every verdict of the other variables; the compact loop after it
# covers every verdict of the shared-ui result with the others held at success.
for test_result in success failure cancelled skipped; do
  for docs_result in success failure cancelled skipped; do
    for windows_api_result in success failure cancelled skipped; do
      for web_result in success failure skipped; do
        for event_name in pull_request push; do
          for body_result in success failure cancelled skipped; do
            combo="$test_result/$docs_result/$windows_api_result/$web_result/$event_name/$body_result"
            # A PR needs the description checks to succeed; a push has none, so they must be skipped.
            body_ok=no
            if [[ "$event_name/$body_result" == pull_request/success || "$event_name/$body_result" == push/skipped ]]; then
              body_ok=yes
            fi
            if TEST_RESULT="$test_result" DOCS_RESULT="$docs_result" WINDOWS_API_RESULT="$windows_api_result" \
              WEB_RESULT="$web_result" SHARED_UI_DESKTOP_RESULT=success \
              EVENT_NAME="$event_name" BODY_RESULT="$body_result" bash -e -c "$required_test_script"; then
              [[ "$test_result/$docs_result/$windows_api_result/$web_result/$body_ok" == success/success/success/success/yes ]] \
                || fail "required test accepted $combo"
            else
              [[ "$test_result/$docs_result/$windows_api_result/$web_result/$body_ok" != success/success/success/success/yes ]] \
                || fail "required test rejected successful jobs ($combo)"
            fi
          done
        done
      done
    done
  done
done
# ci-speedup-pr2: the parallel sharedUI/desktop job gates like any other needs
# dependency — anything but success must redden the required `test` aggregate.
for shared_ui_desktop_result in success failure cancelled skipped; do
  if TEST_RESULT=success DOCS_RESULT=success WINDOWS_API_RESULT=success WEB_RESULT=success \
    SHARED_UI_DESKTOP_RESULT="$shared_ui_desktop_result" EVENT_NAME=pull_request BODY_RESULT=success \
    bash -e -c "$required_test_script"; then
    [[ "$shared_ui_desktop_result" == success ]] \
      || fail "required test accepted shared-ui-desktop-tests result $shared_ui_desktop_result"
  else
    [[ "$shared_ui_desktop_result" != success ]] \
      || fail "required test rejected a successful shared-ui-desktop-tests job"
  fi
done
# letta-mobile-ewtaq: the parity guardrails run in the REQUIRED test job, never advisory.
assert_contains "$test_job" 'python3 scripts/ci/parity_gates.py'
assert_contains "$test_job" 'bash scripts/ci/platform-ui-gate.sh --enforce'
assert_contains "$test_job" 'python3 scripts/ci/baseline-ratchet-guard.py --diff-base "$BASE_SHA"'
assert_contains "$test_job" 'python3 scripts/ci/tree-hygiene.py --diff-base "$BASE_SHA"'
assert_contains "$test_job" 'test_gate_hygiene.py'
# The PR-description checks (shared-search line, web-bundle approval, test-weakening reason) moved out of
# test-workload into pr-body-policy.sh, run by the pr-body-gate job (needed by required `test`) and by
# pr-body-policy.yml on every description edit. They must stay out of the heavy graph: an edit must
# never re-run it.
body_script="$(<"$SOURCE_ROOT/scripts/ci/pr-body-policy.sh")"
assert_contains "$body_script" 'bash scripts/ci/agents-policy-check.sh --require-shared-search'
assert_contains "$body_script" 'python3 scripts/ci/web-bundle-raise-guard.py --diff-base "$BASE_SHA"'
assert_contains "$body_script" 'python3 scripts/ci/test-weakening-guard.py --diff-base "$BASE_SHA"'
assert_contains "$body_script" "gh api \"repos/\${GITHUB_REPOSITORY}/pulls/\${PR_NUMBER}\" --jq '.body // \"\"'"
assert_not_contains "$test_job" 'agents-policy-check.sh --require-shared-search'
assert_not_contains "$test_job" 'web-bundle-raise-guard.py --diff-base'
assert_not_contains "$test_job" 'test-weakening-guard.py --diff-base'
assert_not_contains "$test_job" 'PR_BODY'
pr_body_gate_job="$(
  awk '/^  pr-body-gate:$/ { active=1; next }
       active && /^  [[:alnum:]_-]+:$/ { exit }
       active { print }' "$android_workflow"
)"
assert_contains "$pr_body_gate_job" "if: github.event_name == 'pull_request'"
assert_contains "$pr_body_gate_job" 'run: bash scripts/ci/pr-body-policy.sh'
body_workflow="$(<"$SOURCE_ROOT/.github/workflows/pr-body-policy.yml")"
assert_contains "$body_workflow" 'types: [opened, edited, synchronize, reopened]'
assert_contains "$body_workflow" 'run: bash scripts/ci/pr-body-policy.sh'
assert_not_contains "$body_workflow" 'gradlew'
# An edit that invalidates the body must turn the required `test` red: the refresh re-runs pr-body-gate
# whatever its earlier result, and still runs after a failed policy job.
refresh_job="$(awk '/^  refresh-android-gate:$/ { active=1; next }
  active && /^  [[:alnum:]_-]+:$/ { exit }
  active { print }' <<<"$body_workflow")"
assert_contains "$refresh_job" "if: always() && github.event.action == 'edited'"
assert_contains "$refresh_job" '/jobs/${job_id}/rerun'
assert_not_contains "$refresh_job" 'conclusion == "failure"'
# The refresh must never re-run an OLD head's android.yml run: that attempt enters the shared
# concurrency group and cancels the current head's run (CodeRabbit rewrites the body after every
# push, so an `edited` event can carry a stale head). The live head is read at the start and again
# right before the rerun call (a push can land during the wait loop).
assert_contains "$refresh_job" 'PR_NUMBER: ${{ github.event.pull_request.number }}'
assert_contains "$refresh_job" 'pulls/${PR_NUMBER}" --jq .head.sha'
refresh_script="$(awk '/^        run: \|$/ { active=1; next } active { sub(/^          /, ""); print }' <<<"$refresh_job")"
[[ "$(grep -c 'pulls/${PR_NUMBER}' <<<"$refresh_script")" -ge 2 ]] || fail 'refresh step must read the live PR head before the lookup and again before the rerun'
rerun_line="$(grep -n -- '-X POST' <<<"$refresh_script" | head -1 | cut -d: -f1)"
last_head_check="$(grep -n 'pulls/${PR_NUMBER}' <<<"$refresh_script" | tail -1 | cut -d: -f1)"
[[ -n "$rerun_line" && -n "$last_head_check" && "$last_head_check" -lt "$rerun_line" ]] || fail 'the live-head re-check must come before the rerun call'
# Behavioural check with a fake gh/sleep: the rerun POST happens only when the live head still equals
# HEAD_SHA at the time of the call.
refresh_bin="$TMP/refresh-bin"
mkdir -p "$refresh_bin"
printf '#!/usr/bin/env bash\nexit 0\n' >"$refresh_bin/sleep"
cat >"$refresh_bin/gh" <<'GH'
#!/usr/bin/env bash
# Fake gh. Each pulls/ read consumes the next live head from REFRESH_HEADS_FILE (the last repeats).
args="$*"
case "$args" in
  *-X\ POST*) echo POST >>"$REFRESH_LOG" ;;
  *pulls/*)
    read -r first rest <"$REFRESH_HEADS_FILE"
    echo "$first"
    if [[ -n "$rest" ]]; then echo "$rest" >"$REFRESH_HEADS_FILE"; fi
    ;;
  *workflows/android.yml/runs*) echo 111 ;;
  *jobs?per_page*) echo 222 ;;
  *actions/runs/*) echo completed ;;
esac
GH
chmod +x "$refresh_bin/gh" "$refresh_bin/sleep"
run_refresh() { # live-head sequence (space separated); prints POST log
  local heads="$1"
  : >"$TMP/refresh.log"
  printf '%s\n' "$heads" >"$TMP/refresh.heads"
  (
    export PATH="$refresh_bin:$PATH" GITHUB_REPOSITORY=o/r PR_NUMBER=7 HEAD_SHA=newsha \
      REFRESH_LOG="$TMP/refresh.log" REFRESH_HEADS_FILE="$TMP/refresh.heads"
    bash -c "$refresh_script" >"$TMP/refresh.out" 2>&1 || fail "refresh step failed: $(<"$TMP/refresh.out")"
  )
  cat "$TMP/refresh.log"
}
assert_eq "$(run_refresh 'newsha newsha')" 'POST'
assert_eq "$(run_refresh 'oldsha')" ''
assert_eq "$(run_refresh 'newsha newersha')" ''
# The live body read must fail the job when it fails (errexit) and be the only body source: an empty
# live body is a missing line, never a reason to fall back to the (stale) event payload.
assert_contains "$body_script" 'set -euo pipefail'
assert_contains "$body_script" 'unset GITHUB_EVENT_PATH'
body_repo="$TMP/body-policy"
mkdir -p "$body_repo/bin" "$body_repo/event"
echo '{"pull_request":{"body":"Shared implementation searched: android-compose/sharedUI/"}}' > "$body_repo/event/event.json"
run_body_policy() {  # $1 = fake gh stdout, $2 = fake gh exit code
  printf '#!/usr/bin/env bash\nprintf "%%s" "$FAKE_GH_BODY"\nexit "$FAKE_GH_EXIT"\n' > "$body_repo/bin/gh"
  chmod +x "$body_repo/bin/gh"
  PATH="$body_repo/bin:$PATH" FAKE_GH_BODY="$1" FAKE_GH_EXIT="$2" GITHUB_REPOSITORY=o/r PR_NUMBER=1 PR_AUTHOR=a \
    BASE_SHA="$(git -C "$SOURCE_ROOT" rev-parse HEAD)" BASE_REF=main GITHUB_EVENT_PATH="$body_repo/event/event.json" \
    bash "$SOURCE_ROOT/scripts/ci/pr-body-policy.sh" 2>&1
}
run_body_policy 'Shared implementation searched: android-compose/sharedUI/' 0 >/dev/null || fail 'pr-body-policy.sh rejected a valid live body'
if run_body_policy '' 0 >/dev/null; then fail 'pr-body-policy.sh fell back to the event payload on an empty live body'; fi
if run_body_policy 'Shared implementation searched: android-compose/sharedUI/' 1 >/dev/null; then fail 'pr-body-policy.sh passed although the gh api read failed'; fi
# A body edit must not start (or cancel) the heavy Android graph, and a main run must never be cancelled.
assert_not_contains "$pr_triggers" 'edited'
assert_contains "$pr_triggers" 'types: [opened, synchronize, reopened]'
assert_contains "$android_workflow_text" "cancel-in-progress: \${{ github.ref != 'refs/heads/main' }}"
# Every android.yml job carries its own timeout-minutes (the default is 360).
while IFS= read -r job_name; do
  job_block="$(awk -v job="  ${job_name}:" '$0 == job { active=1; next }
    active && /^  [[:alnum:]_-]+:$/ { exit }
    active { print }' "$android_workflow")"
  assert_contains "$job_block" $'\n    timeout-minutes: '
done < <(awk '/^jobs:$/ { active=1; next } active && /^  [[:alnum:]_-]+:$/ { sub(/:$/, "", $1); print $1 }' "$android_workflow")
assert_contains "$test_job" 'test_parity_gates.py'
assert_contains "$test_job" "python3 -m unittest discover -s scripts/ci -p 'test_baseline_ratchet_guard.py' -v"
assert_contains "$test_job" 'scripts/tests/platform-ui-gate-test.sh'
# A runner whose resolver is not ready must not fail the required job on unchanged code.
assert_contains "$test_job" 'scripts/tests/apt-install-retry-test.sh'
# The Linux job must catch a stale mingw API dump: only api-contract-windows regenerates it.
assert_contains "$test_job" 'bash scripts/ci/api-dump-mirror-check.sh'
# Workspace-rooted: these jobs default to working-directory android-compose, where a relative
# scripts/ path does not exist (the first version of this step failed with "No such file").
# The desktop-prerequisite install itself moved to shared-ui-desktop-tests with :desktop:test.
for workflow in .github/workflows/android.yml .github/workflows/desktop.yml .github/workflows/release.yml; do
  [ -f "$SOURCE_ROOT/$workflow" ] || { echo "FAIL: missing $workflow" >&2; exit 1; }
  if grep -n 'apt-install-retry.sh' "$SOURCE_ROOT/$workflow" | grep -v 'apt-install-retry-test' | grep -qv 'GITHUB_WORKSPACE/scripts/ci/apt-install-retry.sh'; then
    echo "FAIL: $workflow calls apt-install-retry.sh by a path relative to the job's working directory" >&2
    exit 1
  fi
done
assert_not_contains "$test_job" 'sudo apt-get install'
assert_not_contains "$test_job" 'continue-on-error'
# letta-mobile-l8lcn: every push to main runs the full additive test set.
# ci-speedup-pr2: the additive set splits at the group boundary — test-workload
# resolves the android half, shared-ui-desktop-tests the jvm-ui half.
assert_contains "$test_job" 'changed-gradle-modules.sh --all --group android'
assert_contains "$test_job" 'changed-gradle-modules.sh --group android "$base"'

assert_contains "$test_job" ':app:compileSideloadDebugKotlin'
assert_not_contains "$test_job" ':app:compileRootDebugKotlin'
assert_not_contains "$test_job" ':app:compilePlayDebugKotlin'
# ci-speedup-pr2: :app:testScreenshot left the verification graph. It selects zero
# test classes (PR #2661: app/src/test has no *ScreenshotTest); the real screenshot
# suites (:designsystem, :feature-chat) still run. Line-precise in the task array.
assert_not_contains "$test_job" $'\n            :app:testScreenshot\n'
assert_not_contains "$shared_ui_job" ':app:testScreenshot'
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
# ci-speedup-pr1: apiCheck moved off the Android test runner (it dragged
# :sharedLogic:compileKotlinWasmJs/compileKotlinHostNative, +3m09s, onto it) into
# required `shared-multiplatform`, which compiles those targets anyway; the
# test workload must not re-import it. Line-precise: the explanation comment in
# the job may mention apiCheck, the verification task graph may not list it again.
assert_not_contains "$test_job" $'\n            apiCheck\n'
assert_not_contains "$test_job" '--continue'
assert_contains "$(cat "$android_workflow")" 'api-contract-windows:'
assert_contains "$(cat "$android_workflow")" 'Check Windows public API contracts'
# ci-speedup-pr1: the 21m44s single-threaded Windows apiCheck runs on non-PR
# events and only when a PR changes a public-API surface (shared modules, an
# *.api dump, gradle/build-logic, this workflow). Skipping is reported in the
# step summary and every heavy step — including the Konan save, which must not
# persist a toolchain an apiCheck never verified — rides the same gate output.
win_api_job="$(
  awk '/^  api-contract-windows:$/ { active=1; next }
    active && /^  [[:alnum:]_-]+:$/ { exit }
    active { print }' "$android_workflow"
)"
assert_contains "$win_api_job" '- name: Resolve whether the Windows API contract must run'
assert_contains "$win_api_job" "if: steps.win_api.outputs.run == 'true'"
assert_contains "$win_api_job" "grep -Eq '^android-compose/(sharedLogic|sharedUI|core/ids|core/runtime|plugin-api|gradle|build-logic)/|\\.api$|^\\.github/workflows/android\\.yml$'"
assert_not_contains "$win_api_job" $'- name: Check Windows public API contracts\n        run:'
assert_contains "$win_api_job" "- name: Check Windows public API contracts
        if: steps.win_api.outputs.run == 'true'
"
# The job itself must stay unconditional: required-test reads its result and
# rejects `skipped`, so the skip rides green steps, never a skipped job.
assert_not_contains "$win_api_job" $'\n    if:'

# ci-speedup-pr1: web-wasm splits in two scopes. cargo fmt/test/clippy on
# native/iroh-wasm run on non-PR events or when the crate changed; the heavy
# :web:wasmJsBrowserDistribution + :web:wasmJsBrowserTest + bundle-budget
# group run on non-PR events or when the web client surface it compiles
# changed (web, sharedUI, sharedLogic, plugin-api, core/runtime, gradle,
# build-logic, iroh-wasm, this workflow). The wasm-bindgen CLI comes from the
# pinned prebuilt release tarball with a cargo-install fallback, and cargo
# state is cached off Cargo.lock. The fast identity-store node test stays on
# every run.
web_wasm_job="$(
  awk '/^  web-wasm:$/ { active=1; next }
    active && /^  [[:alnum:]_-]+:$/ { exit }
    active { print }' "$android_workflow"
)"
assert_contains "$web_wasm_job" '- name: Resolve WASM verification scope'
assert_contains "$web_wasm_job" "if: steps.wasm_gates.outputs.heavy == 'true'"
assert_contains "$web_wasm_job" "if: steps.wasm_gates.outputs.rust == 'true'"
assert_contains "$web_wasm_job" "if: steps.wasm_gates.outputs.rust == 'true' || steps.wasm_gates.outputs.heavy == 'true'"
assert_contains "$web_wasm_job" 'wasm-bindgen-0.2.127-x86_64-unknown-linux-musl.tar.gz'
assert_contains "$web_wasm_job" 'cargo install wasm-bindgen-cli --version 0.2.127 --locked'
assert_contains "$web_wasm_job" "grep -Eq '^android-compose/(web|sharedUI|sharedLogic|plugin-api|core/runtime|gradle|build-logic|native/iroh-wasm)/|^\\.github/workflows/android\\.yml$'"
assert_contains "$web_wasm_job" 'cargo-iroh-wasm-v1-${{ hashFiles('
assert_not_contains "$web_wasm_job" $'- name: Test browser Iroh identity store\n        if:'
assert_not_contains "$web_wasm_job" $'- name: Build and test Compose web client\n        timeout-minutes:'
assert_not_contains "$web_wasm_job" $'\n    if:'

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
assert_contains "$test_job" 'scripts/ci/package_headroom.py --compare android-compose/build/reports/structure/report.json'
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
assert_contains "$perf_workflow" 'cache-read-only: true'
# A main run is never cancelled and keeps its own group per commit, so a burst of merges cannot
# replace a pending main run (queue: single).
assert_contains "$perf_workflow" "cancel-in-progress: \${{ github.ref != 'refs/heads/main' }}"
assert_contains "$perf_workflow" "github.ref == 'refs/heads/main' && format('-{0}', github.sha)"

# Required Gradle caches belong to main, never ephemeral PR merge refs.
# Keep job-scoped snapshots: setup-gradle deduplicates their shared artifacts.
while IFS= read -r setup_block; do
  assert_contains "$setup_block" 'cache-read-only: ${{ github.event_name == '\''pull_request'\'' || github.ref != '\''refs/heads/main'\'' }}'
  assert_contains "$setup_block" 'cache-cleanup: on-success'
done < <(awk '/uses: gradle\/actions\/setup-gradle@/ { block=$0; active=1; next }
  active && /^      - / { gsub(/\n/, "|", block); print block; active=0 }
  active { block=block "\n" $0 }
  END { if (active) { gsub(/\n/, "|", block); print block } }' "$android_workflow")

for optional_workflow in desktop android-perf iroh-probe pagination-replay-fuzz architecture-graph codecov api-contract-capture release iroh-mdns-binding; do
  optional_text="$(<"$SOURCE_ROOT/.github/workflows/$optional_workflow.yml")"
  setup_count="$(grep -c 'uses: gradle/actions/setup-gradle@' <<<"$optional_text")"
  readonly_count="$(grep -c 'cache-read-only: true' <<<"$optional_text")"
  assert_eq "$readonly_count" "$setup_count"
  # ci-speedup-pr2: one Gradle cache family repo-wide. Without the gh-v3 key
  # prefix (android.yml's), each workflow restored AND wrote its own parallel
  # default-prefix gradle-home tree (200-400 MB) under GitHub's 10 GB budget.
  [[ "$setup_count" -ge 1 ]] || fail "$optional_workflow.yml lacks setup-gradle"
  assert_contains "$optional_text" 'GRADLE_BUILD_ACTION_CACHE_KEY_PREFIX: gh-v3'
done
embedded_workflow="$(<"$SOURCE_ROOT/.github/workflows/embedded-runtime-node18.yml")"
assert_contains "$embedded_workflow" 'cache-read-only: ${{ github.event_name == '\''pull_request'\'' || github.ref != '\''refs/heads/main'\'' }}'
# The embedded runtime job writes Gradle caches on main pushes, so it must be on
# the same key prefix as android.yml and ask for stale-entry cleanup
# (`cache-cleanup` is the v4 input; `gradle-home-cache-cleanup` is the v3 alias).
assert_contains "$embedded_workflow" 'GRADLE_BUILD_ACTION_CACHE_KEY_PREFIX: gh-v3'
assert_contains "$embedded_workflow" 'cache-cleanup: on-success'
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
# Exact match, no tolerated legacy sets: a tolerance branch would let this
# suite pass while main silently lacks a required gate (detekt was promoted on
# 2026-10-20; perf-gate left when it moved to nightly + main).
if [[ -n "$branch_protection" ]]; then
  assert_eq "$branch_protection" \
    'build-apk-pass,detekt,shared-multiplatform,test'
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
assert_contains "$shared_job" 'timeout --preserve-status 16m ./gradlew :sharedLogic:jvmTest --info'
assert_contains "$shared_job" 'Upload sharedLogic JVM test diagnostics'
assert_contains "$shared_job" 'sharedlogic-jvmtest-diagnostics-${{ github.run_id }}'
assert_contains "$shared_job" 'Fail when bounded sharedLogic JVM tests fail or hang'
assert_contains "$shared_job" 'Run remaining shared multiplatform verification task graph'
# allTests / hostNativeTest hang; :desktop:test belongs to shared-ui-desktop-tests,
# never here. ci-speedup-pr2: the two sequential invocations became one — the two
# task batches have no ordering requirement, so Gradle fuses them into one DAG and
# the job drops one startup + configuration round (~4m43s job before).
assert_contains "$shared_job" ':appserver-cli:test :appserver-cli:distZip :iroh-wrapper-cli:test :iroh-wrapper-cli:installDist'
assert_contains "$shared_job" ':sharedLogic:compileKotlinHostNative :sharedLogic:compileTestKotlinHostNative'
# letta-mobile-o4ygk.2: wasmJs compiles stay in the REQUIRED shared job.
assert_contains "$shared_job" ':sharedLogic:compileKotlinWasmJs :sharedLogic:compileTestKotlinWasmJs'
# letta-mobile-o4ygk.4: the shared Compose UI compiles for wasm in the same required job.
assert_contains "$shared_job" ':sharedUI:compileKotlinWasmJs :sharedUI:compileTestKotlinWasmJs'
shared_gradle_invocations="$(grep -Ec '^[[:space:]]*\./gradlew ' <<<"$shared_job")"
assert_eq "$shared_gradle_invocations" '1'
# ci-speedup-pr1: apiCheck moved here from test-workload. This job already compiles
# the shared wasmJs/hostNative targets the ABI comparison needs, and it is the
# required `shared-multiplatform` check, so API drift still blocks every merge.
# Pin it onto the single shared-target invocation, which ends with the comparison,
# exactly as the bounded :sharedLogic:jvmTest phase stays its own invocation.
assert_contains "$shared_job" ':sharedUI:compileTestKotlinWasmJs apiCheck'

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
# letta-mobile-l8lcn: the push-only main-module-tests job is gone; the test job runs the
# full set on push instead, so no module's tests depend on its own files changing.
assert_not_contains "$(cat "$android_workflow")" 'main-module-tests:'
assert_not_contains "$perf_workflow" 'attempt 2'
assert_not_contains "$perf_workflow" '--retryable-single-startup-exit-code'
[[ ! -e "$SOURCE_ROOT/.github/workflows/qodana_code_quality.yml" ]] || fail "Qodana workflow is back; see letta-mobile-by4vm.32"
assert_not_contains "$(cat "$SOURCE_ROOT/.github/workflows/agents-policy.yml")" 'platform-ui-gate:'

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
# ci-speedup-pr2: the additive set partitions at the group boundary. The parallel
# shared-ui-desktop-tests job resolves the jvm-ui half (always both suites on
# --all); test-workload resolves every other task under --group android.
assert_eq "$(bash "$repo/scripts/ci/changed-gradle-modules.sh" --group jvm-ui "$base")" ":sharedUI:jvmTest :desktop:test"
assert_eq "$(bash "$repo/scripts/ci/changed-gradle-modules.sh" --group android "$base")" ":feature-chat:testDebugUnitTest :designsystem:testDebugUnitTest :cli:test"
assert_eq "$(bash "$repo/scripts/ci/changed-gradle-modules.sh" --all --group jvm-ui)" ":sharedUI:jvmTest :desktop:test"
all_android="$(bash "$repo/scripts/ci/changed-gradle-modules.sh" --all --group android)"
assert_not_contains "$all_android" ':sharedUI:jvmTest'
assert_not_contains "$all_android" ':desktop:test'
assert_contains "$all_android" ':core:android-data:testDebugUnitTest'
assert_contains "$all_android" ':plugin-api-testkit:jvmTest'
if bash "$repo/scripts/ci/changed-gradle-modules.sh" --group bogus "$base" >/dev/null 2>&1; then
  fail "module resolver accepted an unknown --group"
fi

# Stacked PR: designsystem landed on the lower branch; this commit only
# touches app/. Diff vs the stack base must not schedule designsystem.
mkdir -p "$repo/android-compose/app/src"
stack_base="$(git -C "$repo" rev-parse HEAD)"
touch "$repo/android-compose/app/src/Conversations.kt"
git -C "$repo" add . && git -C "$repo" commit -qm stacked-app-only
actual="$(bash "$repo/scripts/ci/changed-gradle-modules.sh" "$stack_base")"
assert_eq "$actual" ""
# An app-only diff selects nothing in the jvm-ui half either: the split job must
# skip its Gradle steps and still report green.
assert_eq "$(bash "$repo/scripts/ci/changed-gradle-modules.sh" --group jvm-ui "$stack_base")" ""
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

# A testkit change must compile its Android instrumentation consumer as well as JVM callers.
mkdir -p "$repo/android-compose/appserver-testkit/src"
touch "$repo/android-compose/appserver-testkit/src/Smoke.kt"
git -C "$repo" add . && git -C "$repo" commit -qm "add appserver testkit"
actual="$(cd "$repo" && bash scripts/ci/changed-gradle-modules.sh HEAD~1)"
assert_eq "$actual" ":app:compileRootDebugAndroidTestKotlin :cli:test :appserver-cli:test"

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

# Shared Room contracts must exercise their JVM driver and the Android consumers.
mkdir -p "$repo/android-compose/core/data/src"
touch "$repo/android-compose/core/data/src/Entity.kt"
git -C "$repo" add . && git -C "$repo" commit -qm "touch shared Room data"
actual="$(bash "$repo/scripts/ci/changed-gradle-modules.sh" HEAD~1)"
assert_eq "$actual" ":core:android-data:testDebugUnitTest :core:data:jvmTest"

# letta-mobile-l8lcn: dependency-aware. A sharedLogic change runs the tests of every module that
# depends on it, transitively (sharedUI via its project() edge, desktop and feature-chat via sharedUI).
mkdir -p "$repo/android-compose/sharedUI" "$repo/android-compose/desktop" "$repo/android-compose/feature-chat" \
  "$repo/android-compose/sharedLogic/src"
echo 'dependencies { api(project(":sharedLogic")) }' >"$repo/android-compose/sharedUI/build.gradle.kts"
echo 'dependencies { implementation(project(":sharedUI")) }' >"$repo/android-compose/desktop/build.gradle.kts"
echo 'dependencies { implementation(project(":sharedUI")) }' >"$repo/android-compose/feature-chat/build.gradle.kts"
echo 'plugins { }' >"$repo/android-compose/sharedLogic/build.gradle.kts"
git -C "$repo" add . && git -C "$repo" commit -qm "module graph"
touch "$repo/android-compose/sharedLogic/src/Turn.kt"
git -C "$repo" add . && git -C "$repo" commit -qm "touch sharedLogic"
actual="$(bash "$repo/scripts/ci/changed-gradle-modules.sh" HEAD~1)"
assert_eq "$actual" ":feature-chat:testDebugUnitTest :sharedUI:jvmTest :desktop:test"
# A version-catalog change can affect any module: everything runs.
mkdir -p "$repo/android-compose/gradle"
touch "$repo/android-compose/gradle/libs.versions.toml"
git -C "$repo" add . && git -C "$repo" commit -qm "touch catalog"
actual="$(bash "$repo/scripts/ci/changed-gradle-modules.sh" HEAD~1)"
assert_eq "$actual" "$(bash "$repo/scripts/ci/changed-gradle-modules.sh" --all)"
assert_contains "$actual" ":feature-chat:testDebugUnitTest"
assert_contains "$actual" ":plugin-api-testkit:jvmTest"

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

# The pre-push hook runs the fast CI checks when the push changes their inputs (.kt
# for KDoc hygiene, KMP common leaks, API package coverage and package headroom;
# *.api for the mingw dump mirror), skips them otherwise, and blocks the push on
# failure: each fix takes seconds, the CI runs they burn take half an hour.
# Behavioural: the real hook, the real check scripts, a fixture repo whose push
# ranges are faked on stdin.
pre_push="$(<"$SOURCE_ROOT/.githooks/pre-push")"
assert_contains "$pre_push" 'scripts/ci/check-kdoc-hygiene.py'
assert_contains "$pre_push" 'scripts/ci/check-kmp-common-leaks.py'
assert_contains "$pre_push" 'scripts/ci/check-api-package-coverage.py'
assert_contains "$pre_push" 'run_guard headroom "package_headroom.py" --changed --base'
assert_contains "$pre_push" 'scripts/ci/api-dump-mirror-check.sh'

repo="$TMP/pre-push"
new_repo "$repo"
cp "$SOURCE_ROOT/scripts/ci/check-kdoc-hygiene.py" "$repo/scripts/ci/"
cp "$SOURCE_ROOT/scripts/ci/check-kmp-common-leaks.py" "$repo/scripts/ci/"
cp "$SOURCE_ROOT/scripts/ci/check-api-package-coverage.py" "$repo/scripts/ci/"
cp "$SOURCE_ROOT/scripts/ci/api-dump-mirror-check.sh" "$repo/scripts/ci/"
mkdir -p "$repo/scripts/codescene" "$repo/.githooks"
cp "$SOURCE_ROOT/scripts/codescene/cs-delta.sh" "$repo/scripts/codescene/"
cp "$SOURCE_ROOT/.githooks/pre-push" "$repo/.githooks/"
# The hygiene check discovers published modules through the dokka aggregate, so the
# fixture declares exactly one module with one package and matching overviews.
printf 'dependencies {\n    add("dokka", project(":fake"))\n}\n' >"$repo/android-compose/build.gradle.kts"
mkdir -p "$repo/android-compose/fake/src/main/kotlin" "$repo/android-compose/fake/api/linux_x64" \
  "$repo/android-compose/fake/api/mingw_x64"
printf '# Module Letta Mobile API\n\nOverview words that are long enough to count as an introduction here.\n' \
  >"$repo/android-compose/Module.md"
printf '# Module :fake\n\nOverview words that are long enough to count as an introduction here.\n\n# Package com.example.fake\n\nMore overview words that are long enough to count as a paragraph.\n' \
  >"$repo/android-compose/fake/Module.md"
printf 'package com.example.fake\n\nval answer = 42\n' >"$repo/android-compose/fake/src/main/kotlin/Fake.kt"
printf '// api\n' >"$repo/android-compose/fake/api/linux_x64/fake.api"
printf '// api\n' >"$repo/android-compose/fake/api/mingw_x64/fake.api"
git -C "$repo" add . && git -C "$repo" commit -qm fixtures
zero_sha="0000000000000000000000000000000000000000"
run_pre_push() { # $1 = local_sha, $2 = remote_sha; prints combined hook output
  # git runs pre-push from the repo root, and the hook resolves its scripts from
  # `git rev-parse --show-toplevel`, so the fake invocation must run there too.
  (
    cd "$repo" || exit 2
    printf 'refs/heads/feat %s refs/heads/feat %s\n' "$1" "$2" \
      | JAVA_HOME="${JAVA_HOME:-/usr}" bash .githooks/pre-push 2>&1
  )
}

# A clean .kt change runs the four hygiene/leak/coverage/headroom checks (and passes);
# the fixture has no structure-baseline.json, so headroom skips rather than failing.
# The mirror check is skipped because no *.api file changed.
printf 'package com.example.fake\n\nval other = 7\n' >"$repo/android-compose/fake/src/main/kotlin/Fake.kt"
git -C "$repo" add . && git -C "$repo" commit -qm clean-kt
base="$(git -C "$repo" rev-parse HEAD~1)"
clean_kt_sha="$(git -C "$repo" rev-parse HEAD)"
out="$(run_pre_push "$clean_kt_sha" "$base")" || fail "pre-push rejected a clean .kt push: $out"
assert_contains "$out" 'check-kdoc-hygiene.py: clean'
assert_contains "$out" 'check-kmp-common-leaks.py: clean'
assert_contains "$out" 'check-api-package-coverage.py: clean'
assert_contains "$out" 'skipping scripts/ci/package_headroom.py --changed'
assert_contains "$out" 'skipping scripts/ci/api-dump-mirror-check.sh'

# A bead id in a pushed .kt file's KDoc blocks the push outright.
printf 'package com.example.fake\n\n/** Documents the fake. See bead abcde.1 for the story. */\nval answer = 42\n' \
  >"$repo/android-compose/fake/src/main/kotlin/Fake.kt"
git -C "$repo" add . && git -C "$repo" commit -qm bead-kdoc
bead_kt_sha="$(git -C "$repo" rev-parse HEAD)"
if out="$(run_pre_push "$bead_kt_sha" "$clean_kt_sha")"; then
  fail "pre-push allowed a .kt push whose KDoc cites a bead id"
fi
assert_contains "$out" 'describe intent instead of abcde.1'

# A push with no .kt and no .api change runs none of the four checks.
git -C "$repo" checkout -q -b notes "$base"
printf 'notes\n' >"$repo/NOTES.md"
git -C "$repo" add . && git -C "$repo" commit -qm notes
notes_sha="$(git -C "$repo" rev-parse HEAD)"
out="$(run_pre_push "$notes_sha" "$base")" || fail "pre-push rejected a non-Kotlin push: $out"
assert_contains "$out" 'skipping scripts/ci/check-kdoc-hygiene.py'
assert_contains "$out" 'skipping scripts/ci/check-kmp-common-leaks.py'
assert_contains "$out" 'skipping scripts/ci/api-dump-mirror-check.sh'

# An .api change with a stale mingw mirror blocks the push, while a KDoc-free push
# proves the mirror gate runs on .api changes alone.
git -C "$repo" checkout -q -b api "$base"
printf '// api v2\n' >"$repo/android-compose/fake/api/linux_x64/fake.api"
git -C "$repo" add . && git -C "$repo" commit -qm stale-mirror
stale_api_sha="$(git -C "$repo" rev-parse HEAD)"
if out="$(run_pre_push "$stale_api_sha" "$base")"; then
  fail "pre-push allowed an .api push with a stale mingw mirror"
fi
assert_contains "$out" 'api-dump-mirror'
assert_contains "$out" 'skipping scripts/ci/check-kdoc-hygiene.py'

# A brand-new branch (zero remote sha) still finds its changes via merge-base
# with origin/main and blocks a violating push.
git -C "$repo" checkout -q -b newbranch "$base"
git -C "$repo" update-ref "refs/remotes/origin/main" "$base"
printf 'package com.example.fake\n\n/** Fake. See bead abcde.1 again. */\nval answer = 42\n' \
  >"$repo/android-compose/fake/src/main/kotlin/Fake.kt"
git -C "$repo" add . && git -C "$repo" commit -qm new-branch-bead
new_branch_sha="$(git -C "$repo" rev-parse HEAD)"
if out="$(run_pre_push "$new_branch_sha" "$zero_sha")"; then
  fail "pre-push allowed a first push of a branch with a KDoc bead id"
fi
assert_contains "$out" 'describe intent instead of abcde.1'

# A JVM-only import in commonMain is the exact leak that breaks the required
# hostNative/wasmJs compiles of shared-multiplatform: the hook blocks the push.
git -C "$repo" checkout -q -b kmp "$base"
mkdir -p "$repo/android-compose/fake/src/commonMain/kotlin"
printf 'package com.example.fake\n\nimport java.io.File\n\nval leak = File("x")\n' \
  >"$repo/android-compose/fake/src/commonMain/kotlin/Leak.kt"
git -C "$repo" add . && git -C "$repo" commit -qm jvm-leak
kmp_sha="$(git -C "$repo" rev-parse HEAD)"
if out="$(run_pre_push "$kmp_sha" "$base")"; then
  fail "pre-push allowed a JVM-only import java.io.File in commonMain"
fi
assert_contains "$out" 'Leak.kt:3: import java.io.File is JVM-only'
assert_contains "$out" 'KMP leak check failed'

# The same import is legal in a platform source set: jvmMain only compiles on the
# JVM, so its push passes the leak gate (and the other three).
git -C "$repo" checkout -q -b jvm "$base"
mkdir -p "$repo/android-compose/fake/src/jvmMain/kotlin"
printf 'package com.example.fake\n\nimport java.io.File\n\nval useJdk = File("x")\n' \
  >"$repo/android-compose/fake/src/jvmMain/kotlin/Jvm.kt"
git -C "$repo" add . && git -C "$repo" commit -qm jvm-api
jvm_sha="$(git -C "$repo" rev-parse HEAD)"
out="$(run_pre_push "$jvm_sha" "$base")" || fail "pre-push rejected a jvmMain file using a JVM API: $out"
assert_contains "$out" 'check-kmp-common-leaks.py: clean'

# A new production package missing from its module's Module.md (the Slice 2 #2656
# class of failure) blocks the push in seconds instead of a full CI run.
git -C "$repo" checkout -q -b pkg "$base"
mkdir -p "$repo/android-compose/fake/src/main/kotlin/other"
printf 'package com.example.other\n\nval text = 1\n' \
  >"$repo/android-compose/fake/src/main/kotlin/other/Other.kt"
git -C "$repo" add . && git -C "$repo" commit -qm undocumented-package
pkg_sha="$(git -C "$repo" rev-parse HEAD)"
if out="$(run_pre_push "$pkg_sha" "$base")"; then
  fail "pre-push allowed a new package with no Module.md overview"
fi
assert_contains "$out" 'missing # Package com.example.other'
assert_contains "$out" 'API package coverage failed'

# Delete-only pushes exit before any check, as before.
out="$(run_pre_push "$zero_sha" "$base")" || fail "pre-push rejected a delete-only push: $out"
assert_eq "$out" ""

echo "ci policy script tests: PASS"
