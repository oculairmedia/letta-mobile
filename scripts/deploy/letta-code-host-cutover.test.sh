#!/usr/bin/env bash
# Tests for the EXPECTED_PATCHES guard of letta-code-host-cutover.sh. Every path the script uses points at a
# throwaway directory and systemctl is a recorder, so nothing real can be touched.
#   bash scripts/deploy/letta-code-host-cutover.test.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/letta-code-host-cutover.sh"
T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
fails=0
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fails=$((fails + 1)); fi; }

mkdir -p "$T/root" "$T/bin" "$T/state"
printf 'LETTA_CLI_PATH_REAL=%s\n' "$T/root/letta-code-1.0.0/node_modules/@letta-ai/letta-code/letta.js" > "$T/state/appserver.env"
: > "$T/state/app.log"
printf '#!/bin/sh\necho "$@" >> "%s/systemctl.calls"\n' "$T" > "$T/bin/systemctl"
chmod +x "$T/bin/systemctl"
snapshot() { (cd "$T" && find . -printf '%p %y %s %l\n' | sort; cat state/appserver.env state/app.log); }

# Runs the cutover with every path variable inside $T; extra arguments are env assignments.
run_cutover() {
  env -u EXPECTED_PATCHES PATH="$T/bin:$PATH" LOG="$T/state/cutover.log" ENV_FILE="$T/state/appserver.env" \
    LETTA_LINK="$T/state/letta" APP_UNIT=dummy-cutover-test-unit APP_LOG="$T/state/app.log" CUTOVER_LOCK="$T/state/cutover.lock" \
    INSTALL_ROOT="$T/root" ROLLBACK_SETTLE_SECONDS=0 POLL_SECONDS=0 "$@" \
    bash "$SCRIPT" 1.0.0 2.0.0 2>&1
}

before="$(snapshot)"
out="$(run_cutover || true)"; rc=0; run_cutover >/dev/null || rc=$?
check "unset EXPECTED_PATCHES exits 64" '[[ $rc == 64 ]]'
check "unset EXPECTED_PATCHES names the survey command" 'grep -q "ABORT: EXPECTED_PATCHES must be a positive integer" <<<"$out" && grep -q "letta-code-upgrade.mjs survey" <<<"$out"'
check "unset EXPECTED_PATCHES changes nothing (no log, lock, backup, link or service call)" '[[ "$(snapshot)" == "$before" && ! -e "$T/systemctl.calls" ]]'

for bad in "" 0 00 -3 36x abc 3.6 " 36"; do
  rc=0; out="$(run_cutover EXPECTED_PATCHES="$bad")" || rc=$?
  check "EXPECTED_PATCHES='$bad' is refused before any change" '[[ $rc == 64 && "$(snapshot)" == "$before" && ! -e "$T/systemctl.calls" ]]'
done

# A valid count passes the guard and stops at the first preflight check (the new install is not staged).
rc=0; run_cutover EXPECTED_PATCHES=36 >/dev/null || rc=$?
check "a valid count gets past the guard to the preflight" '[[ $rc == 1 ]] && grep -q "ABORT: .*letta.js missing" "$T/state/cutover.log"'
check "the preflight abort still left the env and service alone" '[[ ! -e "$T/systemctl.calls" && "$(cat "$T/state/appserver.env")" == "LETTA_CLI_PATH_REAL=$T/root/letta-code-1.0.0/node_modules/@letta-ai/letta-code/letta.js" ]]'

if ((fails)); then echo "$fails failure(s)"; exit 1; fi
echo "all passed"
