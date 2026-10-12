#!/usr/bin/env bash
# Self-test for scripts/ci/platform-ui-gate.sh (letta-mobile-ckmub.7).
# Proves: unlisted new composable file -> fail; listed legacy file growing
# past its limit -> fail; Platform-UI-Reason: present -> pass with warning.
set -euo pipefail

GATE="$(cd "$(dirname "$0")/../ci" && pwd)/platform-ui-gate.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
assert_contains() { [[ "$1" == *"$2"* ]] || fail "expected output to contain '$2': $1"; }
assert_not_contains() { [[ "$1" != *"$2"* ]] || fail "expected output not to contain '$2': $1"; }

UI="$TMP/android-compose/desktop/src/main/kotlin"
mkdir -p "$UI" "$TMP/android-compose/sharedUI/src/commonMain/kotlin"
ALLOW="$TMP/allowlist.txt"
composable() { # file lines
  { echo "@Composable"; echo "fun X() {"; for ((i = 3; i < $2; i++)); do echo "  // $i"; done; echo "}"; } >"$1"
}
gate() { # runs the gate with --enforce; prints output, then "exit=N"
  local status=0 out
  out="$(env -u PR_BODY -u GITHUB_EVENT_PATH "$@" bash "$GATE" --root "$TMP" --allowlist "$ALLOW" --enforce 2>&1)" || status=$?
  printf '%s\nexit=%s' "$out" "$status"
}

composable "$UI/Legacy.kt" 20
echo "fun notUi() = 1" >"$UI/Plain.kt"
composable "$TMP/android-compose/sharedUI/src/commonMain/kotlin/Shared.kt" 10

# --update seeds the allowlist; a clean tree passes.
PLATFORM_UI_REASON= bash "$GATE" --root "$TMP" --allowlist "$ALLOW" --update 2>/dev/null
grep -q '^android-compose/desktop/src/main/kotlin/Legacy.kt 20 legacy-pending(ckmub.3)$' "$ALLOW" \
  || fail "allowlist not seeded: $(cat "$ALLOW")"
grep -q 'Plain.kt\|Shared.kt' "$ALLOW" && fail "non-composable or sharedUI file was allowlisted"
out="$(gate PLATFORM_UI_REASON=)"
assert_contains "$out" "exit=0"
assert_contains "$out" "platform UI files: 1 (legacy-pending 1, containers 0)"

# PUI001: a new unlisted composable file fails.
composable "$UI/NewScreen.kt" 10
out="$(gate PLATFORM_UI_REASON=)"
assert_contains "$out" "ERROR|PUI001|android-compose/desktop/src/main/kotlin/NewScreen.kt"
assert_contains "$out" "exit=1"
rm "$UI/NewScreen.kt"

# PUI002: a listed legacy file may grow within the +5 slack, not beyond.
composable "$UI/Legacy.kt" 25
assert_contains "$(gate PLATFORM_UI_REASON=)" "exit=0"
composable "$UI/Legacy.kt" 26
out="$(gate PLATFORM_UI_REASON=)"
assert_contains "$out" "ERROR|PUI002|android-compose/desktop/src/main/kotlin/Legacy.kt"
assert_contains "$out" "exit=1"

# Platform-UI-Reason: in the PR body (or env locally) -> pass with warning.
composable "$UI/NewScreen.kt" 10
out="$(gate PLATFORM_UI_REASON= PR_BODY=$'Summary\nPlatform-UI-Reason: camera host needs a platform view\n')"
assert_contains "$out" "WARN|PUI001|android-compose/desktop/src/main/kotlin/NewScreen.kt"
assert_contains "$out" "WARN|PUI002|"
assert_contains "$out" "overridden by Platform-UI-Reason: camera host needs a platform view"
assert_not_contains "$out" "ERROR|"
assert_contains "$out" "exit=0"
out="$(gate PLATFORM_UI_REASON='local override')"
assert_contains "$out" "exit=0"
assert_contains "$out" "overridden by Platform-UI-Reason: local override"

# PUI003: a deleted allowlisted file asks for its line to be removed (warn only).
rm "$UI/Legacy.kt" "$UI/NewScreen.kt"
out="$(gate PLATFORM_UI_REASON=)"
assert_contains "$out" "WARN|PUI003|android-compose/desktop/src/main/kotlin/Legacy.kt"
assert_contains "$out" "exit=0"

# PUI004 (PR mode): raising an allowlist line vs the base needs a reason.
composable "$UI/Legacy.kt" 20
cp "$ALLOW" "$TMP/scripts-allow.txt"
ALLOW="$TMP/scripts-allow.txt"
g() { git -C "$TMP" -c user.name=t -c user.email=t@t -c commit.gpgsign=false -c core.autocrlf=false "$@" >/dev/null 2>&1; }
g init -q
g add -A
g commit -q -m base
sed -i 's/Legacy.kt 20 /Legacy.kt 40 /' "$ALLOW"
g commit -q -am raise
out="$(env -u PR_BODY -u GITHUB_EVENT_PATH PLATFORM_UI_REASON= bash "$GATE" --root "$TMP" --allowlist "$ALLOW" --diff-base HEAD~1 --enforce 2>&1)" \
  && fail "raised allowlist line without a reason passed: $out"
assert_contains "$out" "ERROR|PUI004|android-compose/desktop/src/main/kotlin/Legacy.kt|allowlist maxLines raised 20 -> 40"

echo "platform-ui-gate-test: ok"
