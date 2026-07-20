#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd -P)"
failures=0

pass() { printf 'ok - %s\n' "$1"; }
fail() { printf 'not ok - %s\n' "$1" >&2; failures=$((failures + 1)); }
assert_file() { [[ -f "$REPO_ROOT/$1" ]] && pass "$1 exists" || fail "$1 exists"; }
assert_contains() {
  if rg -q --fixed-strings -- "$2" "$REPO_ROOT/$1"; then pass "$1 contains $2"; else fail "$1 contains $2"; fi
}

for file in \
  .serena/project.yml \
  config/mcp/serena.env \
  config/mcp/serena-config.yml \
  config/mcp/README.md \
  config/scip-java/pilot.env \
  scripts/mcp/serena.sh \
  scripts/scip/scip-java-pilot.sh \
  scripts/scip/extract-scip-graph.py \
  docs/tooling/serena-scip-pilot.md; do
  assert_file "$file"
done

bash -n "$REPO_ROOT/scripts/mcp/serena.sh" && pass 'Serena shell syntax' || fail 'Serena shell syntax'
bash -n "$REPO_ROOT/scripts/scip/scip-java-pilot.sh" && pass 'SCIP shell syntax' || fail 'SCIP shell syntax'
PYTHONPYCACHEPREFIX="$REPO_ROOT/.local/python-cache" \
  python3 -m py_compile "$REPO_ROOT/scripts/scip/extract-scip-graph.py" && pass 'SCIP extractor syntax' || fail 'SCIP extractor syntax'

assert_contains config/mcp/serena.env 'SERENA_VERSION=1.6.0'
assert_contains config/mcp/serena.env 'SERENA_GIT_REVISION=93b9544ea9def8e93cb6a90f8ea67befe3c8fee4'
assert_contains config/scip-java/pilot.env 'SCIP_JAVA_VERSION=0.13.1'
assert_contains config/scip-java/pilot.env 'SCIP_JAVA_SHA256=a694cae143c32c5b6226362fb4bd268a8d13d3cd9b482819b3b0029a9a97b8fe'
assert_contains .serena/project.yml 'ls_workspace_folders:'
assert_contains .serena/project.yml '  - "android-compose"'
assert_contains config/mcp/serena-config.yml 'project_serena_folder_location: "$projectDir/.serena"'
assert_contains scripts/mcp/serena.sh 'UV_PYTHON_INSTALL_DIR="$LOCAL_ROOT/uv/python"'
assert_contains scripts/mcp/serena.sh 'ulimit -u "$SERENA_PROCESS_LIMIT"'
assert_contains scripts/scip/scip-java-pilot.sh 'sha256sum --check --status'
assert_contains scripts/scip/scip-java-pilot.sh 'ulimit -u "$SCIP_PROCESS_LIMIT"'
assert_contains .gitignore '.local/'
assert_contains docs/tooling/serena-scip-pilot.md 'Android Gradle integration as unsupported'

paths="$($REPO_ROOT/scripts/mcp/serena.sh paths)"
[[ "$paths" == *"SERENA_HOME=$REPO_ROOT/.local/serena/home"* ]] && pass 'Serena paths are project-local' || fail 'Serena paths are project-local'
[[ "$paths" == *"UV_PYTHON_INSTALL_DIR=$REPO_ROOT/.local/uv/python"* ]] && pass 'uv Python installs are project-local' || fail 'uv Python installs are project-local'

set +e
pilot_output="$($REPO_ROOT/scripts/scip/scip-java-pilot.sh index 2>&1)"
pilot_status=$?
set -e
[[ $pilot_status -eq 78 ]] && pass 'SCIP pilot is opt-in and non-blocking' || fail 'SCIP pilot is opt-in and non-blocking'
[[ "$pilot_output" == *'pilot disabled'* ]] && pass 'SCIP pilot explains disabled state' || fail 'SCIP pilot explains disabled state'

fixture_root="$(mktemp -d "$REPO_ROOT/.local/tooling-smoke.XXXXXX")"
trap 'rm -rf -- "$fixture_root"' EXIT
: >"$fixture_root/index.scip"
cat >"$fixture_root/scip" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' '{"documents":[{"relativePath":"src/Foo.kt","symbols":[{"symbol":"test Foo#","displayName":"Foo"}],"occurrences":[{"symbol":"test Bar#","symbolRoles":0,"range":[0,0,0,3]}]}]}'
EOF
chmod +x "$fixture_root/scip"
if "$REPO_ROOT/scripts/scip/extract-scip-graph.py" "$fixture_root/index.scip" "$fixture_root/graph.jsonl" \
  --scip-command "$fixture_root/scip" --max-records 5 >/dev/null; then
  [[ $(wc -l <"$fixture_root/graph.jsonl") -eq 5 ]] && pass 'SCIP extraction emits bounded graph schema' || fail 'SCIP extraction emits bounded graph schema'
else
  fail 'SCIP extraction emits bounded graph schema'
fi
printf 'preserve\n' >"$fixture_root/limited.jsonl"
set +e
"$REPO_ROOT/scripts/scip/extract-scip-graph.py" "$fixture_root/index.scip" "$fixture_root/limited.jsonl" \
  --scip-command "$fixture_root/scip" --max-records 2 >/dev/null 2>&1
extract_status=$?
set -e
[[ $extract_status -eq 75 ]] && pass 'SCIP extraction enforces record limit' || fail 'SCIP extraction enforces record limit'
[[ "$(<"$fixture_root/limited.jsonl")" == preserve ]] && pass 'SCIP extraction failure is atomic' || fail 'SCIP extraction failure is atomic'
cat >"$fixture_root/invalid-scip" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' '{"documents":{}}'
EOF
chmod +x "$fixture_root/invalid-scip"
set +e
invalid_output="$("$REPO_ROOT/scripts/scip/extract-scip-graph.py" "$fixture_root/index.scip" \
  "$fixture_root/invalid.jsonl" --scip-command "$fixture_root/invalid-scip" 2>&1)"
invalid_status=$?
set -e
[[ $invalid_status -eq 65 ]] && pass 'SCIP extraction rejects malformed document containers' || fail 'SCIP extraction rejects malformed document containers'
[[ "$invalid_output" == *'documents must be an array'* ]] && pass 'SCIP extraction explains malformed input' || fail 'SCIP extraction explains malformed input'
[[ ! -e "$fixture_root/invalid.jsonl" ]] && pass 'SCIP validation does not create output' || fail 'SCIP validation does not create output'

if rg -q -S '(token|secret|password)["[:space:]]*[:=]["[:space:]]*[^$<{[:space:]]+' \
  "$REPO_ROOT/config/mcp" "$REPO_ROOT/config/scip-java"; then
  fail 'config examples contain no apparent literal secrets'
else
  pass 'config examples contain no apparent literal secrets'
fi

base_ref=""
if [[ -n "${GITHUB_BASE_REF:-}" ]]; then
  # Fetch into a dedicated remote-tracking ref. A refspec such as
  # `main:origin/main` creates an ambiguous local branch named origin/main,
  # and depth=1 can omit the PR merge base entirely.
  base_ref="refs/remotes/origin/$GITHUB_BASE_REF"
  git -C "$REPO_ROOT" fetch --no-tags origin "+refs/heads/$GITHUB_BASE_REF:$base_ref"
fi
if [[ -n "$base_ref" ]]; then
  changed_files="$(git -C "$REPO_ROOT" diff --name-only "$base_ref...HEAD")"
else
  changed_files="$(git -C "$REPO_ROOT" diff --name-only HEAD)"
fi

# Isolation only applies when this change set actually touches the
# code-intelligence stack. App-only PRs still run architecture export, but
# must not fail this gate just because they change Kotlin outside the stack.
stack_touch_pattern='^(\.github/workflows/architecture-graph\.yml|\.serena/.*|config/mcp/.*|config/scip-java/.*|docs/tooling/.*|scripts/mcp/.*|scripts/scip/.*|scripts/tests/tooling-smoke\.sh|tools/architecture_query/.*)$'
stack_allowlist_pattern='^(\.github/workflows/architecture-graph\.yml|\.github/workflows/qodana_code_quality\.yml|\.gitignore|\.serena/.*|android-compose/architecture-tests/.*|android-compose/build-logic/.*|android-compose/build\.gradle\.kts|android-compose/settings\.gradle\.kts|config/mcp/.*|config/scip-java/.*|docs/tooling/.*|scripts/mcp/.*|scripts/scip/.*|scripts/tests/tooling-smoke\.sh|tools/architecture_query/.*)$'
if ! printf '%s\n' "$changed_files" | rg -q "$stack_touch_pattern"; then
  pass 'Code-intelligence stack changes stay within declared boundaries'
elif printf '%s\n' "$changed_files" | rg -v "$stack_allowlist_pattern" | rg -q .; then
  fail 'Code-intelligence stack changes stay within declared boundaries'
else
  pass 'Code-intelligence stack changes stay within declared boundaries'
fi

(( failures == 0 )) || exit 1
printf 'tooling smoke tests passed\n'
