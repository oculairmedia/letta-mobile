#!/usr/bin/env bash
set -euo pipefail

SOURCE_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
assert_eq() { [[ "$1" == "$2" ]] || fail "expected '$2', got '$1'"; }
assert_contains() { [[ "$1" == *"$2"* ]] || fail "expected output to contain '$2': $1"; }

new_repo() {
  local repo="$1"
  mkdir -p "$repo/scripts/ci" "$repo/android-compose"
  cp "$SOURCE_ROOT/scripts/ci/changed-gradle-modules.sh" "$repo/scripts/ci/"
  cp "$SOURCE_ROOT/scripts/ci/agents-policy-check.sh" "$repo/scripts/ci/"
  git -C "$repo" init -q
  git -C "$repo" config user.name Test
  git -C "$repo" config user.email test@example.com
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

if bash "$repo/scripts/ci/agents-policy-check.sh" --diff-base refs/heads/missing >/dev/null 2>&1; then
  fail "policy scan accepted a missing base"
fi

echo "ci policy script tests: PASS"
