#!/usr/bin/env bash
# Cron / scheduler sensing check — letta-mobile-lgns8.24.2 / .24.3 mitigation.
#
# WHY THIS EXISTS
# The App Server's own cron surfaces cannot tell a live scheduler from a dead
# one. Measured 2026-08-01 (see docs/testing/lgns8-acceptance-evidence-ledger.md
# section 8): every run-log entry reads status:"ok", outcome:"queued",
# reason:"scheduled_time_matched" whether the fired turn EXECUTED or was dark,
# and crons.json's last_fired_at / fire_count are written at ENQUEUE time, so
# they advance through a total outage too. On top of that:
#
#   * a tick with no attached WS client enqueues and never runs (dark);
#   * the drain on reconnect is LOSSY — 3 buffered fires produced 1 turn;
#   * a restart silently skips ticks with no missed accounting at all;
#   * the lease loser gives up permanently after 3x30s with only a
#     console.error — no structured signal anywhere (letta-mobile-lgns8.24.3).
#
# The only in-band signal that discriminates is the CONVERSATION TRANSCRIPT.
# This script is the external sensor the runbook mandates: it verifies the
# scheduler lease is held by a live process that really is the process it
# claims to be, and that every enabled recurring task has actually produced a
# cron fire in its conversation transcript within ~2x its cadence.
#
# It is strictly READ-ONLY on the store. It never writes, moves, or repairs
# anything. Nonzero exit == a human must look.
#
# USAGE
#   scripts/deploy/cron-sensing-check.sh [--quiet]
#
# ENVIRONMENT (all optional)
#   LETTA_HOME                 default /root/.letta
#   LETTA_CRONS_JSON           default $LETTA_HOME/crons.json
#   LETTA_LOCAL_BACKEND_DIR    default $LETTA_HOME/lc-local-backend
#   CRON_SENSING_TAIL_BYTES    transcript tail scanned first, default 4194304
#   CRON_SENSING_GRACE_FACTOR  cadence multiplier before failing, default 2
#   CRON_SENSING_GRACE_FLOOR_S extra slack in seconds, default 300
#
# EXIT CODES
#   0  all checks passed
#   1  at least one check FAILED
#   2  the check could not run (missing dependency / unreadable input)

set -uo pipefail

QUIET=0
for arg in "$@"; do
  case "$arg" in
    --quiet) QUIET=1 ;;
    -h | --help)
      sed -n '1,50p' "$0"
      exit 0
      ;;
    *)
      echo "unknown argument: $arg" >&2
      exit 2
      ;;
  esac
done

LETTA_HOME="${LETTA_HOME:-/root/.letta}"
CRONS_JSON="${LETTA_CRONS_JSON:-$LETTA_HOME/crons.json}"
BACKEND_DIR="${LETTA_LOCAL_BACKEND_DIR:-$LETTA_HOME/lc-local-backend}"
TAIL_BYTES="${CRON_SENSING_TAIL_BYTES:-4194304}"
GRACE_FACTOR="${CRON_SENSING_GRACE_FACTOR:-2}"
GRACE_FLOOR_S="${CRON_SENSING_GRACE_FLOOR_S:-300}"

FAILURES=0
WARNINGS=0

log() { [ "$QUIET" -eq 1 ] || printf '%s\n' "$*"; }
ok() { log "OK    $*"; }
warn() {
  WARNINGS=$((WARNINGS + 1))
  printf 'WARN  %s\n' "$*"
}
fail() {
  FAILURES=$((FAILURES + 1))
  printf 'FAIL  %s\n' "$*" >&2
}
die() {
  printf 'ERROR %s\n' "$*" >&2
  exit 2
}

command -v jq >/dev/null 2>&1 || die "jq is required"
[ -r "$CRONS_JSON" ] || die "cannot read $CRONS_JSON"
jq -e . "$CRONS_JSON" >/dev/null 2>&1 || die "$CRONS_JSON is not valid JSON"

NOW_EPOCH="$(date -u +%s)"

log "cron sensing check — $(date -u +%Y-%m-%dT%H:%M:%SZ)"
log "  lease file : $CRONS_JSON"
log "  store      : $BACKEND_DIR"

# ---------------------------------------------------------------- lease owner

OWNER_PID="$(jq -r '.scheduler_owner.pid // empty' "$CRONS_JSON")"
OWNER_TICKS="$(jq -r '.scheduler_owner.process_start_ticks // empty' "$CRONS_JSON")"
OWNER_BOOT="$(jq -r '.scheduler_owner.boot_id // empty' "$CRONS_JSON")"
OWNER_STARTED="$(jq -r '.scheduler_owner.started_at // empty' "$CRONS_JSON")"

if [ -z "$OWNER_PID" ]; then
  # This is exactly what a released lease looks like, and also exactly what a
  # scheduler that never claimed one looks like. Either way nothing fires.
  fail "no scheduler lease holder (scheduler_owner is null) — NO cron will fire"
else
  if [ -d "/proc/$OWNER_PID" ]; then
    ok "lease holder pid $OWNER_PID is alive"

    # Identity, not just liveness: a recycled pid would pass a bare kill -0.
    if [ -n "$OWNER_TICKS" ] && [ -r "/proc/$OWNER_PID/stat" ]; then
      LIVE_TICKS="$(awk '{print $22}' "/proc/$OWNER_PID/stat" 2>/dev/null)"
      if [ "$LIVE_TICKS" = "$OWNER_TICKS" ]; then
        ok "lease holder start-ticks match ($OWNER_TICKS) — not a recycled pid"
      else
        fail "lease holder pid $OWNER_PID start-ticks $LIVE_TICKS != recorded $OWNER_TICKS — STALE lease, pid was recycled"
      fi
    else
      warn "lease record carries no process_start_ticks — cannot rule out a recycled pid"
    fi

    CMD="$(tr '\0' ' ' <"/proc/$OWNER_PID/cmdline" 2>/dev/null)"
    case "$CMD" in
      *"app-server"*) ok "lease holder is an app-server process" ;;
      "") warn "cannot read the lease holder's cmdline" ;;
      *) warn "lease holder does not look like an app-server: $CMD" ;;
    esac
  else
    fail "lease holder pid $OWNER_PID is DEAD — stale lease, no cron will fire"
  fi

  if [ -n "$OWNER_BOOT" ] && [ -r /proc/sys/kernel/random/boot_id ]; then
    LIVE_BOOT="$(cat /proc/sys/kernel/random/boot_id)"
    if [ "$LIVE_BOOT" = "$OWNER_BOOT" ]; then
      ok "lease boot_id matches the running boot"
    else
      fail "lease boot_id $OWNER_BOOT is from a previous boot (now $LIVE_BOOT) — STALE lease"
    fi
  fi

  if [ -n "$OWNER_STARTED" ]; then
    if STARTED_EPOCH="$(date -u -d "$OWNER_STARTED" +%s 2>/dev/null)"; then
      AGE=$((NOW_EPOCH - STARTED_EPOCH))
      if [ "$AGE" -lt -60 ]; then
        fail "lease started_at $OWNER_STARTED is in the future — clock skew or a corrupt lease"
      else
        ok "lease claimed $OWNER_STARTED (held ${AGE}s)"
      fi
    else
      warn "lease started_at is unparseable: $OWNER_STARTED"
    fi
  fi
fi

# ------------------------------------------------------------ cadence parsing
#
# Deliberately minimal. Returns the cadence in seconds, or empty when the
# expression is anything more interesting than N-minutes / hourly / daily.
# An unparsed cadence is a WARN (unsensed), never a silent pass.

cron_cadence_seconds() {
  local expr="$1"
  local -a f=()
  # read -a, not word splitting: an unquoted split would glob '*' against cwd.
  read -r -a f <<<"$expr"
  [ "${#f[@]}" -eq 5 ] || return 1
  local min="${f[0]}" hour="${f[1]}" dom="${f[2]}" mon="${f[3]}" dow="${f[4]}"
  [ "$dom" = "*" ] && [ "$mon" = "*" ] && [ "$dow" = "*" ] || return 1

  case "$min:$hour" in
    '*:*')
      echo 60
      return 0
      ;;
  esac
  case "$min" in
    '*/'[0-9]*)
      if [ "$hour" = "*" ]; then
        echo $((${min#*/} * 60))
        return 0
      fi
      return 1
      ;;
    [0-9]*)
      case "$hour" in
        '*')
          echo 3600
          return 0
          ;; # hourly at minute N
        '*/'[0-9]*)
          echo $((${hour#*/} * 3600))
          return 0
          ;; # every N hours
        [0-9]*)
          case "$hour" in
            *,*) return 1 ;;
          esac
          echo 86400
          return 0
          ;; # daily
      esac
      ;;
  esac
  return 1
}

# ------------------------------------------------------- transcript sensing

# base64 of "conversation:<conv_id>" is the on-disk conversation directory name.
conversation_dir() {
  local conv_id="$1"
  local key
  key="$(printf 'conversation:%s' "$conv_id" | base64 -w0)"
  local d="$BACKEND_DIR/conversations/$key"
  [ -d "$d" ] && {
    printf '%s' "$d"
    return 0
  }
  # Tolerate a padding-stripped variant.
  d="$BACKEND_DIR/conversations/${key%%=*}"
  [ -d "$d" ] && {
    printf '%s' "$d"
    return 0
  }
  return 1
}

# Newest cron-fire timestamp for a task, read from the committed transcript.
# The fire prompt upstream writes is: Scheduled task "<name>" is firing.
latest_fire_iso() {
  local file="$1" name="$2" marker line
  marker="Scheduled task \\\"$name\\\" is firing"
  line="$(tail -c "$TAIL_BYTES" "$file" 2>/dev/null | grep -F "$marker" | tail -n 1)"
  if [ -z "$line" ]; then
    # Long cadences legitimately fall outside the tail window; pay for the
    # full scan rather than reporting a false outage.
    line="$(grep -F "$marker" "$file" 2>/dev/null | tail -n 1)"
  fi
  [ -n "$line" ] || return 1
  printf '%s' "$line" | grep -o '"timestamp":"[^"]*"' | head -n 1 |
    sed 's/.*"timestamp":"//; s/"$//'
}

TASK_COUNT="$(jq '.tasks | length' "$CRONS_JSON")"
log "  tasks      : $TASK_COUNT"

SENSED=0
while IFS=$'\t' read -r TID TNAME TSTATUS TRECUR TCRON TCONV; do
  [ -n "$TID" ] || continue
  case "$TSTATUS" in
    active | enabled) ;;
    *)
      log "SKIP  task $TID ($TNAME): status=$TSTATUS"
      continue
      ;;
  esac
  if [ "$TRECUR" != "true" ]; then
    log "SKIP  task $TID ($TNAME): one-shot"
    continue
  fi

  CADENCE="$(cron_cadence_seconds "$TCRON")" || CADENCE=""
  if [ -z "$CADENCE" ]; then
    warn "task $TID ($TNAME): cron '$TCRON' not parseable as N-minutes/hourly/daily — UNSENSED"
    continue
  fi

  if [ -z "$TCONV" ] || [ "$TCONV" = "null" ]; then
    fail "task $TID ($TNAME): no conversation_id — cannot sense execution"
    continue
  fi

  CDIR="$(conversation_dir "$TCONV")" || {
    fail "task $TID ($TNAME): no conversation directory for $TCONV under $BACKEND_DIR/conversations"
    continue
  }
  TRANSCRIPT="$CDIR/messages.jsonl"
  [ -r "$TRANSCRIPT" ] || {
    fail "task $TID ($TNAME): transcript unreadable ($TRANSCRIPT)"
    continue
  }

  FIRE_ISO="$(latest_fire_iso "$TRANSCRIPT" "$TNAME")" || {
    fail "task $TID ($TNAME): no cron fire found anywhere in the transcript — never executed"
    continue
  }
  FIRE_EPOCH="$(date -u -d "$FIRE_ISO" +%s 2>/dev/null)" || {
    fail "task $TID ($TNAME): unparseable fire timestamp '$FIRE_ISO'"
    continue
  }

  AGE=$((NOW_EPOCH - FIRE_EPOCH))
  BUDGET=$((CADENCE * GRACE_FACTOR + GRACE_FLOOR_S))
  SENSED=$((SENSED + 1))
  if [ "$AGE" -gt "$BUDGET" ]; then
    fail "task $TID ($TNAME): last transcript fire $FIRE_ISO is ${AGE}s old, budget ${BUDGET}s (cron '$TCRON') — cron is DARK or dead"
  else
    ok "task $TID ($TNAME): fired $FIRE_ISO (${AGE}s ago, budget ${BUDGET}s, cron '$TCRON')"
  fi
done < <(jq -r '.tasks[] | [
    (.id // ""),
    (.name // ""),
    (.status // ""),
    ((.recurring // false) | tostring),
    (.cron // ""),
    (.conversation_id // "")
  ] | @tsv' "$CRONS_JSON")

log "sensed $SENSED recurring task(s); $WARNINGS warning(s); $FAILURES failure(s)"

if [ "$FAILURES" -gt 0 ]; then
  printf 'FAIL  cron sensing check FAILED (%d) — see docs/architecture/lettashim-retirement-deployment-runbook.md "Cron sensing check"\n' "$FAILURES" >&2
  exit 1
fi
exit 0
