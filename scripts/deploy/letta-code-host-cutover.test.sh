#!/usr/bin/env bash
# Tests for letta-code-host-cutover.sh: the EXPECTED_PATCHES guard and the PATH-parity gates
# (DISABLE_AUTOUPDATER and the letta a subagent would resolve on the service PATH). Every path the
# script uses points at a throwaway directory; systemctl and ss are recorders/actors over that
# directory, so nothing real can be touched.
#   bash scripts/deploy/letta-code-host-cutover.test.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/letta-code-host-cutover.sh"
T="$(mktemp -d)"
trap 'kill "$(cat "$T/state/mainpid" 2>/dev/null)" 2>/dev/null || true; rm -rf "$T"' EXIT
fails=0
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fails=$((fails + 1)); fi; }

mkdir -p "$T/root" "$T/bin" "$T/nodebin" "$T/loader" "$T/state"
printf 'LETTA_CLI_PATH_REAL=%s\n' "$T/root/letta-code-1.0.0/node_modules/@letta-ai/letta-code/letta.js" > "$T/state/appserver.env"
: > "$T/state/app.log"
cat > "$T/bin/systemctl" <<STUB
#!/bin/sh
echo "\$@" >> "$T/systemctl.calls"
case "\$1" in
  restart)
    printf '[letta-code-patch] lcp-patches %s\n' "\$(cat "$T/loader/boot.txt")" >> "$T/state/app.log"
    if [ ! -s "$T/state/mainpid" ] || ! kill -0 "\$(cat "$T/state/mainpid")" 2>/dev/null; then
      # Like systemd's daemonized unit: the holder must not inherit the cutover's lock fd or
      # its log fds, or the next cutover aborts on a lock the finished one still holds.
      LETTA_CLI_PATH_REAL="\$(grep '^LETTA_CLI_PATH_REAL=' "$T/state/appserver.env" | tail -1 | cut -d= -f2-)" \
        sleep 300 9>&- >/dev/null 2>&1 &
      echo "\$!" > "$T/state/mainpid"
    fi
    ;;
  is-active) echo active ;;
  show) cat "$T/state/mainpid" 2>/dev/null ;;
esac
exit 0
STUB
cat > "$T/bin/ss" <<STUB
#!/bin/sh
# The listener check only reads the pid out of the line; report the stub service holder.
if [ -s "$T/state/mainpid" ]; then
  printf 'LISTEN 0 128 127.0.0.1:4500 0.0.0.0:* users:(("sleep",pid=%s,fd=4))\n' "\$(cat "$T/state/mainpid")"
fi
STUB
chmod +x "$T/bin/systemctl" "$T/bin/ss"
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

# ---- PATH-parity gates ------------------------------------------------------
# A miniature copy of the chain a subagent child actually walks: the service env's PATH resolves
# `letta` (a symlink, like /usr/local/bin/letta on the host), its shebang finds node on the same
# PATH, and NODE_OPTIONS loads a register+loader pair that prints the real boot-line format with
# counts staged in boot.txt. The bundle fixtures print their version like the real CLI.

NODE_BIN="$(command -v node 2>/dev/null || true)"
if [ -z "$NODE_BIN" ]; then
  echo "FAIL node is required for the PATH-parity gate tests"
  fails=$((fails + 1))
fi

OLD_JS="$T/root/letta-code-1.0.0/node_modules/@letta-ai/letta-code/letta.js"
NEW_JS="$T/root/letta-code-2.0.0/node_modules/@letta-ai/letta-code/letta.js"
STRAY_JS="$T/root/letta-code-9.9.9/node_modules/@letta-ai/letta-code/letta.js"
LOG="$T/state/cutover.log"

if [ -n "$NODE_BIN" ]; then
  make_bundle() { # $1 version: an ESM bundle whose --version prints the version, like the real CLI
    local dir="$T/root/letta-code-$1/node_modules/@letta-ai/letta-code"
    mkdir -p "$dir"
    printf '{"name":"@letta-ai/letta-code","version":"%s","type":"module"}\n' "$1" > "$dir/package.json"
    printf '#!/usr/bin/env node\nconsole.log("%s");\n' "$1" > "$dir/letta.js"
    chmod +x "$dir/letta.js"
  }
  make_bundle 1.0.0; make_bundle 2.0.0; make_bundle 9.9.9

  ln -s "$NODE_BIN" "$T/bin/node"; ln -s "$NODE_BIN" "$T/nodebin/node"

  cat > "$T/loader/register.mjs" <<'EOF'
import { register } from "node:module";
register("./loader.mjs", import.meta.url);
EOF
  cat > "$T/loader/loader.mjs" <<'EOF'
// Test double of letta-code-patch-loader.mjs: the same preconditions the real load hook checks
// (pin set, file url, resolved-path match, source present) and the same boot-line format; the
// counts come from boot.txt so each case can stage its own patch result.
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
const TARGET = process.env.LETTA_CLI_PATH_REAL ?? "";
export async function load(url, context, nextLoad) {
  const result = await nextLoad(url, context);
  if (!TARGET || !url.startsWith("file://")) return result;
  let path;
  try { path = fileURLToPath(url); } catch { return result; }
  if (resolve(path) !== resolve(TARGET)) return result;
  if (typeof result.source !== "string" && !(result.source instanceof Uint8Array)) return result;
  process.stderr.write(`[letta-code-patch] lcp-patches ${readFileSync(new URL("./boot.txt", import.meta.url), "utf8").trim()}\n`);
  return result;
}
EOF
  printf 'applied=3 skipped=0\n' > "$T/loader/boot.txt"

  write_env() { # $1 "no-autoupdater" drops the guard line; $2 is the service PATH
    {
      printf 'LETTA_CLI_PATH_REAL=%s\n' "$OLD_JS"
      printf 'NODE_OPTIONS=--import=file://%s/loader/register.mjs\n' "$T"
      printf 'PATH=%s\n' "$2"
      [ "$1" = "no-autoupdater" ] || printf 'DISABLE_AUTOUPDATER=1\n'
      printf 'HOME=/root\n'
    } > "$T/state/appserver.env"
  }
  reset_case() { # $1 "no-autoupdater"; $2 service PATH; $3 what $T/bin/letta points at
    rm -f "$T/state/mainpid" "$T/systemctl.calls" "$T/state/cutover.log" "$T/bin/letta" "$T/state/letta"
    : > "$T/state/app.log"
    printf 'applied=3 skipped=0\n' > "$T/loader/boot.txt"
    write_env "$1" "$2"
    ln -sfn "$OLD_JS" "$T/state/letta"
    [ -z "$3" ] || ln -sfn "$3" "$T/bin/letta"
  }

  # The updater left an unpatched global install on the service PATH: the preflight must refuse.
  reset_case with-autoupdater "$T/bin" "$STRAY_JS"
  rc=0; run_cutover EXPECTED_PATCHES=3 >/dev/null || rc=$?
  check "a PATH letta pointing at another bundle aborts the preflight loudly" \
    '[[ $rc == 1 ]] && grep -q "ABORT: .*service PATH resolves letta" "'"$LOG"'"'
  check "the abort names the resolved bundle and the pinned bundle" \
    'grep -q "'"$STRAY_JS"'" "'"$LOG"'" && grep -q "'"$OLD_JS"'" "'"$LOG"'"'
  check "the PATH-parity preflight abort changes nothing" \
    'grep -qx "LETTA_CLI_PATH_REAL=$OLD_JS" "$T/state/appserver.env" && [[ "$(readlink "$T/state/letta")" == "$OLD_JS" && ! -e "$T/systemctl.calls" ]]'

  # No letta on the service PATH at all: a subagent could not even spawn the pinned CLI.
  reset_case with-autoupdater "$T/nodebin" ""
  rc=0; run_cutover EXPECTED_PATCHES=3 >/dev/null || rc=$?
  check "no letta on the service PATH aborts loudly" \
    '[[ $rc == 1 ]] && grep -q "ABORT: no executable letta on the service PATH" "'"$LOG"'"'

  # Without the updater disable the service env lets letta-code self-update onto the PATH again.
  reset_case no-autoupdater "$T/bin" "$T/state/letta"
  rc=0; run_cutover EXPECTED_PATCHES=3 >/dev/null || rc=$?
  check "a service env without DISABLE_AUTOUPDATER=1 aborts loudly" \
    '[[ $rc == 1 ]] && grep -q "ABORT: .*DISABLE_AUTOUPDATER=1" "'"$LOG"'"'
  check "the DISABLE_AUTOUPDATER abort changes nothing" \
    'grep -qx "LETTA_CLI_PATH_REAL=$OLD_JS" "$T/state/appserver.env" && [[ "$(readlink "$T/state/letta")" == "$OLD_JS" && ! -e "$T/systemctl.calls" ]]'

  # A stale direct symlink (the /usr/local/bin/letta shape) survives the preflight against the old
  # pin but must fail the post-cutover gate against the new pin, rolling everything back.
  reset_case with-autoupdater "$T/bin" "$OLD_JS"
  rc=0; run_cutover EXPECTED_PATCHES=3 >/dev/null || rc=$?
  check "a PATH letta left at the old install fails the cutover" '[[ $rc == 1 ]]'
  check "the failure is the post-cutover PATH parity, loudly" \
    'grep -q "ABORT: .*service PATH resolves letta" "'"$LOG"'" && grep -q "'"$OLD_JS"'" "'"$LOG"'"'
  check "the rollback restored the env, the link and restarted the service" \
    'grep -qx "LETTA_CLI_PATH_REAL=$OLD_JS" "$T/state/appserver.env" && [[ "$(readlink "$T/state/letta")" == "$OLD_JS" ]] && grep -q "^restart" "$T/systemctl.calls" && grep -q "ROLLBACK:" "'"$LOG"'"'

  # The PATH letta follows the managed link but the loader reports the wrong patch count: the
  # expected boot line `applied=$EXPECTED_PATCHES skipped=0` is missing, so the cutover must roll back.
  reset_case with-autoupdater "$T/bin" "$T/state/letta"
  printf 'applied=2 skipped=0\n' > "$T/loader/boot.txt"
  rc=0; run_cutover EXPECTED_PATCHES=3 >/dev/null || rc=$?
  check "a PATH letta printing the wrong patch count fails the cutover" '[[ $rc == 1 ]]'
  check "the failure names the expected boot line" \
    'grep -q "ABORT: .*applied=3 skipped=0" "'"$LOG"'"'
  check "the rollback restored the env and the link" \
    'grep -qx "LETTA_CLI_PATH_REAL=$OLD_JS" "$T/state/appserver.env" && [[ "$(readlink "$T/state/letta")" == "$OLD_JS" ]]'

  # The happy path: DISABLE_AUTOUPDATER=1, the PATH letta follows the managed link onto the new
  # install, and the loader prints applied=3 skipped=0 under the service env.
  reset_case with-autoupdater "$T/bin" "$T/state/letta"
  rc=0; run_cutover EXPECTED_PATCHES=3 >/dev/null || rc=$?
  check "cutover with DISABLE_AUTOUPDATER=1 and a parity-clean PATH letta succeeds" '[[ $rc == 0 ]]'
  check "RESULT: OK is logged" 'grep -q "RESULT: OK" "'"$LOG"'"'
  check "the env pin, the managed link and the PATH letta all moved to the new install" \
    'grep -qx "LETTA_CLI_PATH_REAL=$NEW_JS" "$T/state/appserver.env" && [[ "$(readlink "$T/state/letta")" == "$NEW_JS" && "$(readlink -f "$T/bin/letta")" == "$NEW_JS" ]]'
  check "DISABLE_AUTOUPDATER=1 is still in the service env" 'grep -qx "DISABLE_AUTOUPDATER=1" "$T/state/appserver.env"'
  check "both parity gates logged their resolution" '[[ "$(grep -c "PATH parity:" "'"$LOG"'")" -eq 2 ]]'
  check "the service was restarted exactly once" '[[ "$(grep -c "^restart" "$T/systemctl.calls")" -eq 1 ]]'
fi

if ((fails)); then echo "$fails failure(s)"; exit 1; fi
echo "all passed"
