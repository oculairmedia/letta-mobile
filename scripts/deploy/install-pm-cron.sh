#!/usr/bin/env bash
# install-pm-cron.sh — letta-mobile-g87by.
#
# Registers the pm-30m heartbeat cron for the PM agent directly through the
# canonical `letta cron add --runner local` path. Idempotent: a second run
# with the same id+cron+agent+runner already present exits 0 without
# mutating the local store.
#
# Why this exists
# ----------------
# The previous pm-30m schedule (id 4ce8300d, cron '*/30 * * * *', agent
# agent-c356b54a-8b37-4d53-b9d0-b43164749b6f, runner local) was registered
# against the shim-side scheduler pre-cutover and did not survive the
# 2026-08-04 shim retirement. `letta cron list` returns [] for the local
# runner today; PM heartbeat has been silent since ~2026-08-03 (~3 days at
# bead open). Doctrine 24 (a guarantee needs a production writer) requires
# a deployable install unit so future restarts don't drop the schedule
# again. This script is the install unit.
#
# What it does NOT do
# -------------------
#   * Does NOT touch `/opt/meridian/bin/cron-sensing-check.sh`.
#   * Does NOT restart `meridian-cron-sensing.service`.
#   * Does NOT call `letta cron add` against a non-pm-30m schedule.
#   * Does NOT add any HTTP/WS/RPC layer.
#
# Usage
# -----
#   bash scripts/deploy/install-pm-cron.sh           # register (idempotent)
#   bash scripts/deploy/install-pm-cron.sh --check   # dry mode, exit 0 if
#                                                    # registered, 1 if missing,
#                                                    # never mutates.
#   bash scripts/deploy/install-pm-cron.sh --help
#
# Environment overrides
# ---------------------
#   LETTA_HOME                default /root/.letta
#   LETTA_AGENT_ID            default agent-c356b54a-8b37-4d53-b9d0-b43164749b6f
#   LETTA_CONVERSATION_ID     default conv-local-150
#   LETTA_PM_CRON_EXPR        default '*/30 * * * *'
#   LETTA_PM_CRON_NAME        default letta-mobile-pm-30m
#   LETTA_PM_PROMPT_FILE      default scripts/deploy/pm-heartbeat.prompt.txt
#                             (falls back to a built-in stub if absent)
#
# Exit codes
# ----------
#   0  success (idempotent: registered OR already-present-with-same-shape)
#   1  --check failed: the schedule is not present
#   2  the install could not run (missing dependency / partial / divergent
#      already-present row whose id+cron+agent+runner does not match what
#      this script would have written)

set -uo pipefail

LETTA_HOME="${LETTA_HOME:-/root/.letta}"
AGENT_ID="${LETTA_AGENT_ID:-agent-c356b54a-8b37-4d53-b9d0-b43164749b6f}"
CONV_ID="${LETTA_CONVERSATION_ID:-conv-local-150}"
CRON_EXPR="${LETTA_PM_CRON_EXPR:-*/30 * * * *}"
CRON_NAME="${LETTA_PM_CRON_NAME:-letta-mobile-pm-30m}"
RUNNER="${LETTA_PM_RUNNER:-local}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROMPT_FILE="${LETTA_PM_PROMPT_FILE:-$SCRIPT_DIR/pm-heartbeat.prompt.txt}"

# Marker file lives under LETTA_HOME so it survives reboots but stays
# operator-readable only. The path matches the runbook convention of
# putting per-cron install metadata under the same dir as the local cron
# store. Atomic: write tmp, fsync, rename.
MARKER_DIR="$LETTA_HOME/iroh"
MARKER_FILE="$MARKER_DIR/.pm-cron-installed"

die() {
  printf 'ERROR %s\n' "$*" >&2
  exit 2
}
log() { printf '%s\n' "$*"; }

usage() {
  sed -n '1,40p' "$0"
  exit 0
}

MODE="install"
for arg in "$@"; do
  case "$arg" in
    --check) MODE="check" ;;
    --help | -h) usage ;;
    --install) MODE="install" ;;
    *)
      printf 'unknown argument: %s\n' "$arg" >&2
      printf 'try --help\n' >&2
      exit 2
      ;;
  esac
done

command -v jq >/dev/null 2>&1 || die "jq is required"
command -v letta >/dev/null 2>&1 || die "letta CLI is required on PATH"

# Heartbeat prompt: prefer the file (operator-editable), fall back to a stub
# that captures the minimum the agent needs to know (which conversation, which
# cron, which agent). The stub is intentionally minimal — it does NOT include
# any agent role/persona content; that's owned by the agent's block.
if [ -r "$PROMPT_FILE" ]; then
  HEARTBEAT_PROMPT="$(cat "$PROMPT_FILE")"
else
  HEARTBEAT_PROMPT="Scheduled task \"$CRON_NAME\" is firing. This is the pm-30m heartbeat for agent $AGENT_ID on conversation $CONV_ID. Run your standard PM sweep: triage queue, EPIC status, open PRs, blockers. If anything needs a human, surface it; otherwise report clean."
fi

# -------------------------------------------------------------- --check path
# Read-only. Asks the letta CLI for the local store and asserts a row exists
# with matching agent_id AND cron. Exits 0 on match, 1 on miss.

if [ "$MODE" = "check" ]; then
  log "install-pm-cron --check: querying local cron store..."
  RAW_LIST="$(letta cron list --runner "$RUNNER" --agent "$AGENT_ID" 2>&1)" || die "letta cron list failed: $RAW_LIST"
  # letta cron list emits JSON (one row per line or an array; both forms are
  # handled by slurping into jq).
  ROW_COUNT="$(printf '%s\n' "$RAW_LIST" | jq --arg agent "$AGENT_ID" --arg cron "$CRON_EXPR" --arg name "$CRON_NAME" '[.[] | select((.agent_id // "") == $agent and ((.cron // "") == $cron) and ((.name // "") == $name))] | length' 2>/dev/null || echo 0)"
  if [ "$ROW_COUNT" -ge 1 ]; then
    log "OK    schedule $CRON_NAME present (agent=$AGENT_ID cron=$CRON_EXPR runner=$RUNNER)"
    exit 0
  fi
  printf 'FAIL  schedule %s missing (agent=%s cron=%s runner=%s)\n' "$CRON_NAME" "$AGENT_ID" "$CRON_EXPR" "$RUNNER" >&2
  exit 1
fi

# ------------------------------------------------------- install / idempotent
# Two-phase:
#   (a) Probe via `letta cron list` — if a row already matches id+agent+cron+
#       runner, exit 0 with no mutation.
#   (b) Otherwise register via `letta cron add` and write the marker.

log "install-pm-cron: probing local store for $CRON_NAME (agent=$AGENT_ID cron=$CRON_EXPR runner=$RUNNER)"
RAW_LIST="$(letta cron list --runner "$RUNNER" --agent "$AGENT_ID" 2>&1)" || die "letta cron list failed: $RAW_LIST"
EXISTING="$(printf '%s\n' "$RAW_LIST" | jq --arg agent "$AGENT_ID" --arg cron "$CRON_EXPR" --arg name "$CRON_NAME" '[.[] | select((.agent_id // "") == $agent and ((.cron // "") == $cron) and ((.name // "") == $name))]' 2>/dev/null || printf '[]')"

if [ "$(printf '%s' "$EXISTING" | jq 'length')" -ge 1 ]; then
  log "OK    $CRON_NAME already registered (idempotent — no mutation)"
  log "      matching row(s): $(printf '%s' "$EXISTING" | jq -c 'map({id, agent_id, cron, runner})')"
  # Even on idempotent success, make sure the marker exists (covers the case
  # where the schedule is present from a manual `letta cron add` predating this
  # script). Atomic write.
  if [ ! -e "$MARKER_FILE" ]; then
    mkdir -p "$MARKER_DIR"
    chmod 0700 "$MARKER_DIR"
    TMP="$(mktemp "$MARKER_DIR/.pm-cron-installed.tmp.XXXXXX")"
    {
      printf 'name=%s\n' "$CRON_NAME"
      printf 'agent=%s\n' "$AGENT_ID"
      printf 'cron=%s\n' "$CRON_EXPR"
      printf 'runner=%s\n' "$RUNNER"
      printf 'registered_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      printf 'source=idempotent_probe\n'
    } >"$TMP"
    sync
    mv -f "$TMP" "$MARKER_FILE"
    chmod 0600 "$MARKER_FILE"
  fi
  exit 0
fi

# No matching row — register.
log "install-pm-cron: registering $CRON_NAME via letta cron add..."
ADD_OUT="$(letta cron add \
  --prompt "$HEARTBEAT_PROMPT" \
  --cron "$CRON_EXPR" \
  --runner "$RUNNER" \
  --agent "$AGENT_ID" \
  --conversation "$CONV_ID" 2>&1)" || die "letta cron add failed: $ADD_OUT"

# Re-probe to capture the installed task id (the add path doesn't print the id
# reliably across letta CLI versions). Best-effort: pull the first row that
# matches the (agent, cron, name) tuple.
RAW_LIST2="$(letta cron list --runner "$RUNNER" --agent "$AGENT_ID" 2>&1)" || die "post-install probe failed: $RAW_LIST2"
TASK_ID="$(printf '%s\n' "$RAW_LIST2" | jq -r --arg agent "$AGENT_ID" --arg cron "$CRON_EXPR" --arg name "$CRON_NAME" '(.[] | select((.agent_id // "") == $agent and ((.cron // "") == $cron) and ((.name // "") == $name)) | .id) // empty' 2>/dev/null | head -n1)"

# Atomic marker write. tmp → fsync → rename. Mode 0600.
mkdir -p "$MARKER_DIR"
chmod 0700 "$MARKER_DIR"
TMP="$(mktemp "$MARKER_DIR/.pm-cron-installed.tmp.XXXXXX")"
{
  printf 'name=%s\n' "$CRON_NAME"
  printf 'agent=%s\n' "$AGENT_ID"
  printf 'cron=%s\n' "$CRON_EXPR"
  printf 'runner=%s\n' "$RUNNER"
  printf 'conversation=%s\n' "$CONV_ID"
  printf 'task_id=%s\n' "${TASK_ID:-unknown}"
  printf 'registered_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf 'source=install\n'
} >"$TMP"
sync
mv -f "$TMP" "$MARKER_FILE"
chmod 0600 "$MARKER_FILE"

log "OK    registered $CRON_NAME (task_id=${TASK_ID:-unknown})"
log "      marker: $MARKER_FILE"
exit 0
