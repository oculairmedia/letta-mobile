#!/bin/bash
# Switch the Meridian App Server to a new letta-code install (and optionally a service-only Node),
# with one restart and automatic rollback of every change if any check fails or the run is interrupted.
# Runbook: docs/deploy/letta-code-upgrade.md
#
# Usage (as root, detached so the restart cannot kill the caller):
#   systemd-run --unit=letta-code-cutover --collect --no-block \
#     /usr/local/sbin/letta-code-host-cutover.sh <old-version> <new-version> [<node-bin-dir>]
#   with EXPECTED_PATCHES set (no default): `env EXPECTED_PATCHES=<N> /usr/local/sbin/letta-code-host-cutover.sh ...`.
#   <N> is the `applied=` count `node scripts/appserver/letta-code-upgrade.mjs survey <new-version>`
#   prints on its REPO loader line (the host loader line must show the same number); the script
#   refuses to start without it. That refusal happens before the log file is opened, so under
#   systemd-run it is in `journalctl -u letta-code-cutover`, not in /var/log/letta-code-cutover-<new>.log.
#   e.g. env EXPECTED_PATCHES=40 ... letta-code-host-cutover.sh 0.34.39 0.34.70 /opt/meridian/node/v<ver>/bin
#
# Changes, all rolled back together on failure:
#   1. /root/.bun/bin/letta symlink (ExecStart runs it)       -> /root/letta-code-<new>/.../letta.js
#   2. LETTA_CLI_PATH_REAL in /etc/meridian/appserver.env     -> same letta.js (the lcp patch target)
#   3. optional: a service-only PATH line putting <node-bin-dir> first. /usr/local/bin/node is
#      shared with other services (steel-mcp) and is never swapped.
# Success requires: the staged package reports <new-version>; DISABLE_AUTOUPDATER=1 is in the env
# (a self-updated global on the service PATH is how subagents end up unpatched); the `letta` the
# service PATH resolves is the pinned bundle and, run under the service's NODE_OPTIONS +
# LETTA_CLI_PATH_REAL, reports <version> and a clean patch boot line — checked against the old pin
# before anything changes and against the new pin after the change; a NEW boot line
# `lcp-patches applied=<N> skipped=0` with N = EXPECTED_PATCHES (required, no default); the live exe is the
# expected node; the live env has the new LETTA_CLI_PATH_REAL; the symlink points at the new
# letta.js; and the 127.0.0.1:4500 listener belongs to the service's cgroup.
set -u
# Before the log redirect and before anything is read or changed: a stale or missing count would
# either roll back a healthy cutover after a restart or accept a short patch count.
case "${EXPECTED_PATCHES:-}" in
  ''|*[!0-9]*|0|0[0-9]*)
    echo "ABORT: EXPECTED_PATCHES must be a positive integer (got '${EXPECTED_PATCHES:-}'); nothing was changed." >&2
    echo "  Take it from \`node scripts/appserver/letta-code-upgrade.mjs survey <new-version>\`: the REPO loader line's applied=N." >&2
    exit 64;;
esac
OLD=${1:?old letta-code version} NEW=${2:?new letta-code version} NODE_DIR=${3:-}
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

# ---- service env readers (the parity gate must see exactly what the service sees) ----
# systemd EnvironmentFile semantics: the LAST assignment of a key wins.
svc_env() { grep "^$1=" "$ENV" | tail -1 | cut -d= -f2-; }

# The PATH a service child gets when the env file sets none: systemd's compiled default.
SYSTEMD_DEFAULT_PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

# First executable $1 on $2, exactly as a subagent's shell would resolve it (empty PATH
# elements would mean the current directory and are skipped: the service never runs one).
resolve_on_path() {
  local dir IFS=:
  for dir in $2; do
    [ -n "$dir" ] || continue
    [ -x "$dir/$1" ] || continue
    printf '%s\n' "$dir/$1"
    return 0
  done
  return 1
}

# Resolves `letta` on the service PATH from $ENV and runs it under the service's own
# NODE_OPTIONS + LETTA_CLI_PATH_REAL: the exact command a subagent child would end up running.
# Fails loudly when the resolved letta is not the pinned bundle (that child would silently run
# a different, possibly unpatched, install — the 2026-11-25 self-update incident), or when it
# does not report $1 and a clean `lcp-patches applied=... skipped=0` boot line. $2 is the
# expected applied count ("" = any count >= 1, for the pre-change bundle).
letta_parity_gate() {
  local expect_version=$1 expect_applied=${2:-}
  local svc_path svc_node svc_real svc_home resolved real pin out version boot
  svc_path=$(svc_env PATH); [ -n "$svc_path" ] || svc_path=$SYSTEMD_DEFAULT_PATH
  svc_node=$(svc_env NODE_OPTIONS)
  svc_real=$(svc_env LETTA_CLI_PATH_REAL)
  svc_home=$(svc_env HOME); [ -n "$svc_home" ] || svc_home=/root
  [ -n "$svc_real" ] || { echo "ABORT: $ENV sets no LETTA_CLI_PATH_REAL: the patch loader has no pin and every child runs unpatched"; return 1; }
  resolved=$(resolve_on_path letta "$svc_path") || { echo "ABORT: no executable letta on the service PATH '$svc_path': a subagent could not spawn the pinned CLI"; return 1; }
  real=$(readlink -f "$resolved") || { echo "ABORT: cannot resolve the service PATH letta $resolved"; return 1; }
  pin=$(readlink -f "$svc_real") || { echo "ABORT: cannot resolve LETTA_CLI_PATH_REAL=$svc_real"; return 1; }
  [ "$real" = "$pin" ] || {
    echo "ABORT: the service PATH resolves letta to $real, not the pinned $svc_real ($pin)."
    echo "  A subagent child would run that different (possibly unpatched) bundle while the server runs the pin."
    echo "  Move every PATH letta symlink with the cutover (or remove the stray install), then retry."
    return 1
  }
  # The version goes to the CLI's stdout and the patch boot line to the loader's stderr; capture
  # the streams separately so their interleave through a pipe cannot swap the two.
  local err_file out_rc probe_err
  err_file=$(mktemp) || { echo "ABORT: cannot create a temp file for the parity probe"; return 1; }
  out_rc=0
  out=$(env -i PATH="$svc_path" HOME="$svc_home" NODE_OPTIONS="$svc_node" LETTA_CLI_PATH_REAL="$svc_real" "$resolved" --version 2>"$err_file") || out_rc=$?
  probe_err=$(cat "$err_file")
  boot=$(grep -m1 'lcp-patches applied=' "$err_file" || true)
  rm -f "$err_file"
  [ "$out_rc" -eq 0 ] || { echo "ABORT: '$resolved --version' under the service env failed (rc=$out_rc): $out$probe_err"; return 1; }
  version=$(printf '%s\n' "$out" | awk 'NF {last=$0} END {print last}')
  case "$version" in "$expect_version "*|"$expect_version") ;; *)
    echo "ABORT: the service PATH letta reports '$version', expected $expect_version"; return 1;; esac
  if [ -n "$expect_applied" ]; then
    case "$boot" in *"applied=$expect_applied skipped=0"*) ;; *)
      echo "ABORT: the service PATH letta printed no 'lcp-patches applied=$expect_applied skipped=0' boot line under the service env (got: ${boot:-none})."
      echo "  Check NODE_OPTIONS in $ENV points at the staged loader and that the loader is current for this bundle."
      return 1;; esac
  else
    case "$boot" in *"applied="[1-9]*" skipped=0"*) ;; *)
      echo "ABORT: the service PATH letta printed no clean 'lcp-patches applied=<N> skipped=0' boot line under the service env (got: ${boot:-none})."
      echo "  Without it the bundle runs unpatched; check NODE_OPTIONS in $ENV points at the staged loader."
      return 1;; esac
  fi
  echo "PATH parity: letta=$resolved -> $real (pin $pin) version='$version' boot='$boot'"
}

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
# Capture the full output first so a failing --version is not masked by a pipeline (no pipefail here).
STAGED_OUT=$("$VERSION_NODE" "$NEW_JS" --version 2>&1) || { echo "ABORT: $NEW_JS --version failed: $STAGED_OUT"; exit 1; }
STAGED=$(printf '%s\n' "$STAGED_OUT" | tail -1)
case "$STAGED" in "$NEW "*|"$NEW") ;; *) echo "ABORT: staged package reports '$STAGED', expected $NEW"; exit 1;; esac
# With the updater enabled, letta-code self-updates the global install onto the service PATH and
# subagents resolve that unpatched bundle while the server runs the patched pin (2026-11-25
# incident). The env must already carry the disable; the cutover never writes policy for you.
grep -qx 'DISABLE_AUTOUPDATER=1' "$ENV" || {
  echo "ABORT: $ENV does not set DISABLE_AUTOUPDATER=1."
  echo "  letta-code would self-update the global install onto the service PATH and subagents would silently run it unpatched."
  echo "  Add DISABLE_AUTOUPDATER=1 to $ENV (see scripts/deploy/appserver.env.example), then retry."
  exit 1
}
# What a subagent child gets TODAY must already be the old pin, fully patched under the service env.
letta_parity_gate "$OLD" "" || { echo "ABORT: the preflight PATH-parity gate failed; nothing was changed"; exit 1; }
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
# The env file is now the post-cutover state, so this is exactly what a subagent child will
# resolve and run after the restart: the new pin, fully patched, reporting the expected count.
letta_parity_gate "$NEW" "$EXPECTED_PATCHES" || rollback "the post-cutover PATH-parity gate failed"

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
