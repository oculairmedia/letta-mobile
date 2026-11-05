#!/bin/bash
# Switch the Meridian App Server to a new letta-code install (and optionally a service-only Node),
# with one restart and automatic rollback of every change if the boot check fails.
# Runbook: docs/deploy/letta-code-upgrade.md
#
# Usage (as root, detached so the restart cannot kill the caller):
#   systemd-run --unit=letta-code-cutover --collect --no-block \
#     /path/to/letta-code-host-cutover.sh <old-version> <new-version> [<node-bin-dir>]
#   e.g. ... letta-code-host-cutover.sh 0.34.14 0.34.39 /opt/meridian/node/v22.23.4/bin
#
# Changes, all rolled back together on failure:
#   1. /root/.bun/bin/letta symlink (ExecStart runs it)       -> /root/letta-code-<new>/.../letta.js
#   2. LETTA_CLI_PATH_REAL in /etc/meridian/appserver.env     -> same letta.js (the lcp patch target)
#   3. optional: a service-only PATH line putting <node-bin-dir> first. /usr/local/bin/node is
#      shared with other services (steel-mcp) and is never swapped.
# Success requires: boot line `lcp-patches applied=<N> skipped=0` with N = EXPECTED_PATCHES
# (default 28), live process exe == the expected node, live env has the new LETTA_CLI_PATH_REAL,
# and 127.0.0.1:4500 listening.
set -u
OLD=${1:?old letta-code version} NEW=${2:?new letta-code version} NODE_DIR=${3:-}
EXPECTED_PATCHES=${EXPECTED_PATCHES:-28}
LOG=${LOG:-/var/log/letta-code-cutover-$NEW.log}; exec >>"$LOG" 2>&1
ENV=${ENV_FILE:-/etc/meridian/appserver.env}
LINK=${LETTA_LINK:-/root/.bun/bin/letta}
APP_LOG=${APP_LOG:-/var/log/meridian-appserver.log}
INSTALL_ROOT=${INSTALL_ROOT:-/root}
OLD_JS=$INSTALL_ROOT/letta-code-$OLD/node_modules/@letta-ai/letta-code/letta.js
NEW_JS=$INSTALL_ROOT/letta-code-$NEW/node_modules/@letta-ai/letta-code/letta.js
TS=$(date -u +%Y%m%dT%H%M%SZ)
BAK=$ENV.pre-letta-code-$NEW-$TS

echo "=== $(date -u +%FT%TZ) cutover $OLD -> $NEW node=${NODE_DIR:-unchanged}"
[ -f "$NEW_JS" ] || { echo "ABORT: $NEW_JS missing (install it first)"; exit 1; }
[ -z "$NODE_DIR" ] || [ -x "$NODE_DIR/node" ] || { echo "ABORT: $NODE_DIR/node missing"; exit 1; }
[ "$(readlink "$LINK")" = "$OLD_JS" ] || { echo "ABORT: $LINK is $(readlink "$LINK"), expected $OLD_JS"; exit 1; }
grep -qx "LETTA_CLI_PATH_REAL=$OLD_JS" "$ENV" || { echo "ABORT: LETTA_CLI_PATH_REAL is not $OLD_JS"; exit 1; }
if [ -n "$NODE_DIR" ] && grep -q '^PATH=' "$ENV" && ! grep -q '^# letta-code-host-cutover: service-only Node' "$ENV"; then
  echo "ABORT: $ENV already sets a PATH this script did not write; refusing to guess"; exit 1
fi
cp -a "$ENV" "$BAK"; echo "env backup: $BAK"

rollback() {
  echo "ROLLBACK: $1"
  cp -a "$BAK" "$ENV"; ln -sfn "$OLD_JS" "$LINK"
  systemctl restart meridian-appserver; sleep "${ROLLBACK_SETTLE_SECONDS:-25}"
  echo "rollback active=$(systemctl is-active meridian-appserver) pid=$(systemctl show meridian-appserver -p MainPID --value) link=$(readlink "$LINK")"
  tail -n 400 "$APP_LOG" | grep 'lcp-patches applied=' | tail -1
  exit 1
}

sed -i "s|^LETTA_CLI_PATH_REAL=$OLD_JS\$|LETTA_CLI_PATH_REAL=$NEW_JS|" "$ENV"
if [ -n "$NODE_DIR" ]; then
  # Replace any earlier service-only PATH from a previous cutover (foreign PATH refused above).
  sed -i '/^# letta-code-host-cutover: service-only Node/,+1d' "$ENV"
  printf '# letta-code-host-cutover: service-only Node (%s, letta-code %s); /usr/local/bin/node stays shared.\nPATH=%s:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n' \
    "$TS" "$NEW" "$NODE_DIR" >> "$ENV"
fi
ln -sfn "$NEW_JS" "$LINK"
grep -nE '^(PATH|LETTA_CLI_PATH_REAL|NODE_OPTIONS)=' "$ENV"; echo "link -> $(readlink "$LINK")"

LINES=$(wc -l < "$APP_LOG")
systemctl restart meridian-appserver
boot=""
for _ in $(seq 1 90); do
  sleep "${POLL_SECONDS:-2}"
  boot=$(tail -n +$((LINES + 1)) "$APP_LOG" | grep -m1 'lcp-patches applied=' || true)
  [ -n "$boot" ] && break
done
echo "boot: ${boot:-<none>}"
case "$boot" in *"applied=$EXPECTED_PATCHES skipped=0"*) ;; *) rollback "boot line not applied=$EXPECTED_PATCHES skipped=0";; esac
systemctl is-active -q meridian-appserver || rollback "service not active"
P=$(systemctl show meridian-appserver -p MainPID --value)
EXE=$(readlink "/proc/$P/exe"); echo "pid=$P exe=$EXE"
[ -z "$NODE_DIR" ] || [ "$EXE" = "$NODE_DIR/node" ] || rollback "live node is $EXE, not $NODE_DIR/node"
tr '\0' '\n' < "/proc/$P/environ" | grep -qx "LETTA_CLI_PATH_REAL=$NEW_JS" || rollback "live env missing new LETTA_CLI_PATH_REAL"
for _ in $(seq 1 30); do ss -ltn | grep -q '127.0.0.1:4500 ' && break; sleep 2; done
ss -ltn | grep -q '127.0.0.1:4500 ' || rollback ":4500 not listening"
echo "RESULT: OK pid=$P node=$("$EXE" --version) letta=$("$EXE" "$NEW_JS" --version 2>&1 | tail -1)"
echo "rollback: cp -a $BAK $ENV && ln -sfn $OLD_JS $LINK && systemctl restart meridian-appserver"
