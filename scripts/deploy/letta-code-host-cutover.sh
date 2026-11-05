#!/bin/bash
# Switch the Meridian App Server to a new letta-code install (and optionally a service-only Node),
# with one restart and automatic rollback of every change if any check fails or the run is interrupted.
# Runbook: docs/deploy/letta-code-upgrade.md
#
# Usage (as root, detached so the restart cannot kill the caller):
#   systemd-run --unit=letta-code-cutover --collect --no-block \
#     /usr/local/sbin/letta-code-host-cutover.sh <old-version> <new-version> [<node-bin-dir>]
#   e.g. ... letta-code-host-cutover.sh 0.34.14 0.34.39 /opt/meridian/node/v22.23.4/bin
#
# Changes, all rolled back together on failure:
#   1. /root/.bun/bin/letta symlink (ExecStart runs it)       -> /root/letta-code-<new>/.../letta.js
#   2. LETTA_CLI_PATH_REAL in /etc/meridian/appserver.env     -> same letta.js (the lcp patch target)
#   3. optional: a service-only PATH line putting <node-bin-dir> first. /usr/local/bin/node is
#      shared with other services (steel-mcp) and is never swapped.
# Success requires: the staged package reports <new-version>; a NEW boot line
# `lcp-patches applied=<N> skipped=0` with N = EXPECTED_PATCHES (default 28); the live exe is the
# expected node; the live env has the new LETTA_CLI_PATH_REAL; the symlink points at the new
# letta.js; and the 127.0.0.1:4500 listener belongs to the service's cgroup.
set -u
OLD=${1:?old letta-code version} NEW=${2:?new letta-code version} NODE_DIR=${3:-}
EXPECTED_PATCHES=${EXPECTED_PATCHES:-28}
LOG=${LOG:-/var/log/letta-code-cutover-$NEW.log}; exec >>"$LOG" 2>&1
ENV=${ENV_FILE:-/etc/meridian/appserver.env}
LINK=${LETTA_LINK:-/root/.bun/bin/letta}
APP_LOG=${APP_LOG:-/var/log/meridian-appserver.log}
UNIT=${APP_UNIT:-meridian-appserver}
LOCK=${CUTOVER_LOCK:-/run/letta-code-cutover.lock}
INSTALL_ROOT=${INSTALL_ROOT:-/root}
OLD_JS=$INSTALL_ROOT/letta-code-$OLD/node_modules/@letta-ai/letta-code/letta.js
NEW_JS=$INSTALL_ROOT/letta-code-$NEW/node_modules/@letta-ai/letta-code/letta.js
PATH_MARKER='# letta-code-host-cutover: service-only Node'
TS=$(date -u +%Y%m%dT%H%M%SZ)
BAK=$ENV.pre-letta-code-$NEW-$TS

echo "=== $(date -u +%FT%TZ) cutover $OLD -> $NEW node=${NODE_DIR:-unchanged}"

# One cutover at a time, even when run directly rather than through the fixed systemd unit.
exec 9>"$LOCK" || { echo "ABORT: cannot open lock $LOCK"; exit 1; }
flock -n 9 || { echo "ABORT: another cutover holds $LOCK"; exit 1; }

# ---- preflight: nothing is changed until all of these pass ----
[ -f "$NEW_JS" ] || { echo "ABORT: $NEW_JS missing (install it first)"; exit 1; }
if [ -n "$NODE_DIR" ]; then
  NODE_BIN=$(readlink -f "$NODE_DIR/node") && [ -x "$NODE_BIN" ] || { echo "ABORT: $NODE_DIR/node missing"; exit 1; }
fi
[ "$(readlink "$LINK")" = "$OLD_JS" ] || { echo "ABORT: $LINK is $(readlink "$LINK"), expected $OLD_JS"; exit 1; }
grep -qx "LETTA_CLI_PATH_REAL=$OLD_JS" "$ENV" || { echo "ABORT: LETTA_CLI_PATH_REAL is not $OLD_JS"; exit 1; }
if [ -n "$NODE_DIR" ] && grep -q '^PATH=' "$ENV" && ! grep -qF "$PATH_MARKER" "$ENV"; then
  echo "ABORT: $ENV already sets a PATH this script did not write; refusing to guess"; exit 1
fi
VERSION_NODE=${NODE_BIN:-node}
STAGED=$("$VERSION_NODE" "$NEW_JS" --version 2>&1 | tail -1) || { echo "ABORT: $NEW_JS --version failed: $STAGED"; exit 1; }
case "$STAGED" in "$NEW "*|"$NEW") ;; *) echo "ABORT: staged package reports '$STAGED', expected $NEW"; exit 1;; esac
cp -a "$ENV" "$BAK" && cmp -s "$ENV" "$BAK" || { echo "ABORT: env backup to $BAK failed; nothing changed"; exit 1; }
echo "env backup: $BAK"

rollback() {
  trap - TERM INT HUP
  echo "ROLLBACK: $1"
  if ! { cp -a "$BAK" "$ENV" && cmp -s "$BAK" "$ENV"; }; then
    # Leave the link and the service alone: a relinked old install with a half-restored env is worse.
    echo "ROLLBACK FAILED: could not restore $ENV from $BAK; link and service NOT touched. Restore by hand:"
    echo "  cp -a $BAK $ENV && ln -sfn $OLD_JS $LINK && systemctl restart $UNIT"
    exit 2
  fi
  ln -sfn "$OLD_JS" "$LINK" || echo "ROLLBACK WARNING: relink of $LINK to $OLD_JS failed"
  systemctl restart "$UNIT"; sleep "${ROLLBACK_SETTLE_SECONDS:-25}"
  echo "rollback env=restored active=$(systemctl is-active "$UNIT") pid=$(systemctl show "$UNIT" -p MainPID --value) link=$(readlink "$LINK")"
  tail -n 400 "$APP_LOG" | grep 'lcp-patches applied=' | tail -1
  exit 1
}
trap 'rollback "interrupted by signal"' TERM INT HUP

# ---- change ----
sed -i "s|^LETTA_CLI_PATH_REAL=$OLD_JS\$|LETTA_CLI_PATH_REAL=$NEW_JS|" "$ENV" || rollback "env edit failed"
grep -qx "LETTA_CLI_PATH_REAL=$NEW_JS" "$ENV" || rollback "env edit did not take"
if [ -n "$NODE_DIR" ]; then
  # Replace any earlier service-only PATH from a previous cutover (a foreign PATH was refused above).
  sed -i "/^$PATH_MARKER/,+1d" "$ENV" || rollback "removing the previous service-only PATH failed"
  printf '%s (%s, letta-code %s); /usr/local/bin/node stays shared.\nPATH=%s:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n' \
    "$PATH_MARKER" "$TS" "$NEW" "$NODE_DIR" >> "$ENV" || rollback "writing the service-only PATH failed"
fi
ln -sfn "$NEW_JS" "$LINK" || rollback "symlink update failed"
[ "$(readlink "$LINK")" = "$NEW_JS" ] || rollback "symlink does not point at $NEW_JS"
grep -nE '^(PATH|LETTA_CLI_PATH_REAL|NODE_OPTIONS)=' "$ENV"; echo "link -> $(readlink "$LINK")"

# Only a boot line written after this point counts. Remember the log's inode and size: if the log is
# rotated (new inode) or truncated (smaller), read the new file from the start instead of the offset.
LOG_INODE=$(stat -c %i "$APP_LOG" 2>/dev/null || echo 0)
LOG_SIZE=$(stat -c %s "$APP_LOG" 2>/dev/null || echo 0)
new_log_bytes() {
  local inode size
  inode=$(stat -c %i "$APP_LOG" 2>/dev/null || echo 0)
  size=$(stat -c %s "$APP_LOG" 2>/dev/null || echo 0)
  if [ "$inode" = "$LOG_INODE" ] && [ "$size" -ge "$LOG_SIZE" ]; then
    tail -c +$((LOG_SIZE + 1)) "$APP_LOG"
  else
    cat "$APP_LOG"
  fi
}
systemctl restart "$UNIT" || rollback "systemctl restart failed"
boot=""
for _ in $(seq 1 90); do
  sleep "${POLL_SECONDS:-2}"
  boot=$(new_log_bytes | grep -m1 'lcp-patches applied=' || true)
  [ -n "$boot" ] && break
done
echo "boot: ${boot:-<none>}"
case "$boot" in *"applied=$EXPECTED_PATCHES skipped=0"*) ;; *) rollback "no new boot line with applied=$EXPECTED_PATCHES skipped=0 (got: ${boot:-none})";; esac

# ---- verify from the live process ----
systemctl is-active -q "$UNIT" || rollback "service not active"
P=$(systemctl show "$UNIT" -p MainPID --value)
[ -n "$P" ] && [ "$P" != 0 ] || rollback "no MainPID"
EXE=$(readlink -f "/proc/$P/exe") || rollback "cannot read /proc/$P/exe"
echo "pid=$P exe=$EXE"
[ -z "$NODE_DIR" ] || [ "$EXE" = "$NODE_BIN" ] || rollback "live node is $EXE, not $NODE_BIN"
tr '\0' '\n' < "/proc/$P/environ" | grep -qx "LETTA_CLI_PATH_REAL=$NEW_JS" || rollback "live env missing new LETTA_CLI_PATH_REAL"
[ "$(readlink "$LINK")" = "$NEW_JS" ] || rollback "symlink changed under us: $(readlink "$LINK")"

# The listener must belong to this service's cgroup (the App Server may listen from a child process).
SVC_CG=$(cut -d: -f3- "/proc/$P/cgroup" | head -1) && [ -n "$SVC_CG" ] || rollback "cannot read service cgroup"
listener_ok=""
for _ in $(seq 1 30); do
  for lp in $(ss -ltnpH 'sport = :4500' 2>/dev/null | grep -F '127.0.0.1:4500' | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u); do
    [ "$(cut -d: -f3- "/proc/$lp/cgroup" 2>/dev/null | head -1)" = "$SVC_CG" ] && listener_ok=$lp
  done
  [ -n "$listener_ok" ] && break
  sleep "${POLL_SECONDS:-2}"
done
[ -n "$listener_ok" ] || rollback "127.0.0.1:4500 is not held by a process in $SVC_CG"

trap - TERM INT HUP
echo "RESULT: OK pid=$P listener=$listener_ok node=$("$EXE" --version) letta=$STAGED"
echo "rollback: cp -a $BAK $ENV && ln -sfn $OLD_JS $LINK && systemctl restart $UNIT"
