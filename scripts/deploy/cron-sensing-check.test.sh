#!/usr/bin/env bash
# Regression test for cron-sensing-check.sh — letta-mobile-g87by.
#
# Why this exists
# ----------------
# Pre-g87by the validator silently exited 0 against an empty .tasks registry
# (doctrine 24 — validator with zero production writers passing silently). After
# g87by, the script MUST exit 2 against an empty registry with a `FAIL  expected
# >= 1 recurring task(s); sensed 0` line. This test pins that contract.
#
# Coverage
# --------
#   1. Empty registry  -> exit 2 + FAIL line present.
#   2. Populated registry with a syntactically valid cron (`*/30 * * * *`) and a
#      present transcript fire marker -> exit 0, OK line present.
#   3. Populated registry with an out-of-range cron (`*/0 * * * *`) -> exit 0
#      (WARN, not silent pass), WARN line present.
#   4. install-pm-cron.sh idempotence: two consecutive runs of a SANDBOXED
#      install (HOME redirected to a temp dir) exit 0 each; the second run must
#      NOT register a duplicate task.
#
# Usage
# -----
#   bash scripts/deploy/cron-sensing-check.test.sh
#
# Exit codes
# ----------
#   0  all assertions passed
#   1  at least one assertion FAILED
#   2  a precondition (jq, mktemp) could not be satisfied

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SENSING="$SCRIPT_DIR/cron-sensing-check.sh"
INSTALL="$SCRIPT_DIR/install-pm-cron.sh"

PASS=0
FAIL=0
ASSERTIONS=0

assert_eq() {
  ASSERTIONS=$((ASSERTIONS + 1))
  local label="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    PASS=$((PASS + 1))
    printf '  PASS  %s\n' "$label"
  else
    FAIL=$((FAIL + 1))
    printf '  FAIL  %s: expected=%q actual=%q\n' "$label" "$expected" "$actual" >&2
  fi
}

assert_contains() {
  ASSERTIONS=$((ASSERTIONS + 1))
  local label="$1" needle="$2" haystack="$3"
  if printf '%s' "$haystack" | grep -qF -- "$needle"; then
    PASS=$((PASS + 1))
    printf '  PASS  %s (contains: %s)\n' "$label" "$needle"
  else
    FAIL=$((FAIL + 1))
    printf '  FAIL  %s: missing %q in output:\n%s\n' "$label" "$needle" "$haystack" >&2
  fi
}

# Preconditions
command -v jq >/dev/null 2>&1 || {
  printf 'ERROR jq is required\n' >&2
  exit 2
}
# Runnability check: shebang-explicit (git does not preserve xbit reliably).
runnable() { [ -r "$1" ] && head -1 "$1" | grep -q '^#!'; }
runnable "$SENSING" || {
  printf 'ERROR %s is missing or not shebang-explicit\n' "$SENSING" >&2
  exit 2
}

# Build a writable scratch dir so we never touch the live ledger at
# /root/.letta/crons.json. All CRONS_JSON / LETTA_HOME overrides below route
# reads into this tree.
WORK="$(mktemp -d -t g87by-test-XXXXXX)"
trap 'if [ -f "$WORK/.lease-helper.pid" ]; then kill "$(cat "$WORK/.lease-helper.pid")" 2>/dev/null || true; fi; rm -rf "$WORK"' EXIT

mkdir -p "$WORK/.letta" "$WORK/lc-local-backend"
chmod 0700 "$WORK/.letta"

# Lease stub: a live-looking lease so the lease checks don't poison the empty
# test case. The lease is only read by the validator when .tasks[] is non-empty
# in the populated cases; the empty case exercises the count>=1 gate before any
# task iteration.
LIVE_BOOT="$(cat /proc/sys/kernel/random/boot_id 2>/dev/null || echo 'unknown-boot')"
# Use a long-lived helper as the "lease holder" so the start-ticks assertion
# inside cron-sensing-check.sh is stable across invocations. $$ would tick
# forward between when SELF_TICKS is captured and when the script reads
# /proc/$$/stat under bash -x, breaking the lease match.
LEASE_HELPER_PIDFILE="$WORK/.lease-helper.pid"
sleep 86400 &
LEASE_HELPER_PID=$!
printf '%s\n' "$LEASE_HELPER_PID" >"$LEASE_HELPER_PIDFILE"
SELF_PID="$LEASE_HELPER_PID"
SELF_TICKS="$(awk '{print $22}' "/proc/$SELF_PID/stat" 2>/dev/null || echo 1)"
LEASE_JSON=$(jq -n \
  --arg pid "$SELF_PID" \
  --arg boot "$LIVE_BOOT" \
  --arg started "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg ticks "$SELF_TICKS" \
  '{
    version: 1,
    scheduler_owner: {
      pid: ($pid | tonumber),
      token: "test-token",
      started_at: $started,
      process_start_ticks: $ticks,
      boot_id: $boot
    },
    tasks: []
  }')
printf '%s\n' "$LEASE_JSON" >"$WORK/.letta/crons.json"

# ------------------------------------------------------------- case 1: empty
echo "=== case 1: empty registry must exit 2 with FAIL line ==="
EMPTY_OUT="$(LETTA_CRONS_JSON="$WORK/.letta/crons.json" LETTA_LOCAL_BACKEND_DIR="$WORK/lc-local-backend" bash "$SENSING" 2>&1)"
EMPTY_EXIT=$?
assert_eq "empty registry exit code" "2" "$EMPTY_EXIT"
assert_contains "empty registry FAIL line" "FAIL  expected >= 1 recurring task(s); sensed 0" "$EMPTY_OUT"

# --------------------------------------------- case 2: populated, valid cron
echo "=== case 2: populated registry with valid */30 cron + present fire ==="
# A task row that the cron parser will accept AND a transcript with a fresh
# "Scheduled task" fire line so the per-task OK path runs.
TASK_ID="t-populated"
TASK_NAME="letta-mobile-pm-30m-test"
CONV_ID="conv-test-populated"
B64="$(printf 'conversation:%s' "$CONV_ID" | base64 -w0)"
CONV_DIR="$WORK/lc-local-backend/conversations/$B64"
mkdir -p "$CONV_DIR"
NOW_EPOCH="$(date -u +%s)"
FIRE_TS="$(date -u -d "@$NOW_EPOCH" +%Y-%m-%dT%H:%M:%S.%3NZ)"
printf '{"role":"user","timestamp":"%s","content":"Scheduled task \\"%s\\" is firing"}\n' "$FIRE_TS" "$TASK_NAME" >"$CONV_DIR/messages.jsonl"

# Build a fresh crons.json with this task + a still-live-looking lease (we are
# our own pid in this test, so start_ticks = 1 matches /proc/$$/stat field 22
# by construction -- set it from /proc so we don't get a recycled-pid WARN).
SELF_TICKS="$(awk '{print $22}' "/proc/$SELF_PID/stat" 2>/dev/null || echo 1)"
POPULATED=$(jq -n \
  --arg pid "$SELF_PID" \
  --arg boot "$LIVE_BOOT" \
  --arg started "$(date -u +%Y-%m-%dT%Y-%m-%dT%H:%M:%SZ)" \
  --arg ticks "$SELF_TICKS" \
  --arg tid "$TASK_ID" \
  --arg tname "$TASK_NAME" \
  --arg conv "$CONV_ID" \
  '{
    version: 1,
    scheduler_owner: {
      pid: ($pid | tonumber),
      token: "test-token",
      started_at: $started,
      process_start_ticks: $ticks,
      boot_id: $boot
    },
    tasks: [{
      id: $tid,
      name: $tname,
      status: "active",
      recurring: true,
      cron: "*/30 * * * *",
      runner: "local",
      conversation_id: $conv
    }]
  }')
printf '%s\n' "$POPULATED" >"$WORK/.letta/crons.json"

POP_OUT="$(LETTA_CRONS_JSON="$WORK/.letta/crons.json" LETTA_LOCAL_BACKEND_DIR="$WORK/lc-local-backend" bash "$SENSING" 2>&1)"
POP_EXIT=$?
assert_eq "populated registry exit code" "0" "$POP_EXIT"
assert_contains "populated registry OK line" "lease holder pid $SELF_PID is alive" "$POP_OUT"

# ----------------------------------------- case 3: populated, */0 cron -> WARN
echo "=== case 3: */0 cron must WARN (not silent-pass) ==="
# Same task row but with `*/0` — the cadence parser will reject this shape
# (N out of range [1..59]) and the new lint must surface it as a WARN.
BAD_CRON=$(jq -n \
  --arg pid "$SELF_PID" \
  --arg boot "$LIVE_BOOT" \
  --arg started "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg ticks "$SELF_TICKS" \
  --arg tid "$TASK_ID" \
  --arg tname "$TASK_NAME" \
  --arg conv "$CONV_ID" \
  '{
    version: 1,
    scheduler_owner: {
      pid: ($pid | tonumber),
      token: "test-token",
      started_at: $started,
      process_start_ticks: $ticks,
      boot_id: $boot
    },
    tasks: [{
      id: $tid,
      name: $tname,
      status: "active",
      recurring: true,
      cron: "*/0 * * * *",
      runner: "local",
      conversation_id: $conv
    }]
  }')
printf '%s\n' "$BAD_CRON" >"$WORK/.letta/crons.json"

BAD_OUT="$(LETTA_CRONS_JSON="$WORK/.letta/crons.json" LETTA_LOCAL_BACKEND_DIR="$WORK/lc-local-backend" bash "$SENSING" 2>&1)"
BAD_EXIT=$?
# WARN, not silent pass, not FAIL: exit 0 but a WARN line referencing the bad
# cron must appear.
assert_eq "*/0 cron exit code" "0" "$BAD_EXIT"
assert_contains "*/0 cron WARN line" "WARN  task $TASK_ID ($TASK_NAME): cron '*/0 * * * *'" "$BAD_OUT"

# ------------------------------------------ case 4: install-pm-cron idempotence
echo "=== case 4: install-pm-cron.sh idempotence under sandbox ==="
# We DO NOT exercise the live `letta cron add` path here — instead we stub the
# `letta` binary on PATH so the script thinks it just registered a task.
# The contract under test is: same id+cron+agent+runner in the local store ->
# exit 0 without further mutation.
# Set the executable bit on the install script for this run.
# (git may not preserve the bit across clones; the script is bash-explicit.)
chmod +x "$INSTALL" 2>/dev/null || true
if [ -x "$INSTALL" ]; then
  STUB_BIN="$WORK/stub-bin"
  mkdir -p "$STUB_BIN"
  cat >"$STUB_BIN/letta" <<'STUB'
#!/usr/bin/env bash
# Stub `letta cron add` / `letta cron list` for sandboxed idempotence test.
# Writes a deterministic task row into $LETTA_HOME/crons.json on `add`,
# reads it back on `list`.
case "$1" in
  cron)
    case "$2" in
      add)
        # Parse --cron / --agent / --conversation / --runner / --prompt flags.
        CRON_EXPR=""
        AGENT_ID=""
        CONV_ID=""
        RUNNER=""
        PROMPT=""
        shift 2
        while [ $# -gt 0 ]; do
          case "$1" in
            --cron) CRON_EXPR="$2"; shift 2 ;;
            --agent) AGENT_ID="$2"; shift 2 ;;
            --conversation) CONV_ID="$2"; shift 2 ;;
            --runner) RUNNER="$2"; shift 2 ;;
            --prompt) PROMPT="$2"; shift 2 ;;
            *) shift ;;
          esac
        done
        HOME_DIR="${LETTA_HOME:-/root/.letta}"
        CRONS_FILE="$HOME_DIR/crons.json"
        mkdir -p "$HOME_DIR"
        if [ ! -f "$CRONS_FILE" ]; then
          printf '{"version":1,"scheduler_owner":null,"tasks":[]}\n' >"$CRONS_FILE"
        fi
        # Append a task (the stub mirrors the live add path: no dedup).
        TASK_ID="stub-task-$(date +%s%N)"
        TNAME="letta-mobile-pm-30m"
        jq --arg tid "$TASK_ID" \
           --arg tname "$TNAME" \
           --arg cron "$CRON_EXPR" \
           --arg agent "$AGENT_ID" \
           --arg conv "$CONV_ID" \
           --arg runner "$RUNNER" \
           --arg prompt "$PROMPT" \
           '.tasks += [{
             id: $tid, name: $tname, status: "active", recurring: true,
             cron: $cron, agent_id: $agent, conversation_id: $conv,
             runner: $runner, prompt: $prompt
           }]' "$CRONS_FILE" >"$CRONS_FILE.tmp" && mv "$CRONS_FILE.tmp" "$CRONS_FILE"
        printf 'registered task %s\n' "$TASK_ID"
        exit 0
        ;;
      list)
        HOME_DIR="${LETTA_HOME:-/root/.letta}"
        CRONS_FILE="$HOME_DIR/crons.json"
        if [ -f "$CRONS_FILE" ]; then
          AGENT_FILTER=""
          shift 2
          while [ $# -gt 0 ]; do
            case "$1" in
              --agent) AGENT_FILTER="$2"; shift 2 ;;
              *) shift ;;
            esac
          done
          if [ -n "$AGENT_FILTER" ]; then
            jq --arg a "$AGENT_FILTER" '.tasks | map(select(.agent_id == $a))' "$CRONS_FILE"
          else
            jq '.tasks' "$CRONS_FILE"
          fi
        else
          printf '[]\n'
        fi
        exit 0
        ;;
    esac
    ;;
esac
printf 'unknown stub invocation: %s\n' "$*" >&2
exit 99
STUB
  chmod 0755 "$STUB_BIN/letta"

  # First run — should register.
  FIRST_OUT="$(env -i PATH="$STUB_BIN:/usr/bin:/bin" HOME="$WORK" LETTA_HOME="$WORK/.letta" \
    bash "$INSTALL" 2>&1)"
  FIRST_EXIT=$?
  assert_eq "install-pm-cron first run exit code" "0" "$FIRST_EXIT"
  FIRST_COUNT="$(jq '.tasks | length' "$WORK/.letta/crons.json" 2>/dev/null || echo 0)"

  # Second run — must exit 0 without further mutation (count unchanged).
  SECOND_OUT="$(env -i PATH="$STUB_BIN:/usr/bin:/bin" HOME="$WORK" LETTA_HOME="$WORK/.letta" \
    bash "$INSTALL" 2>&1)"
  SECOND_EXIT=$?
  SECOND_COUNT="$(jq '.tasks | length' "$WORK/.letta/crons.json" 2>/dev/null || echo 0)"
  assert_eq "install-pm-cron second run exit code" "0" "$SECOND_EXIT"
  assert_eq "install-pm-cron idempotence (task count unchanged)" "$FIRST_COUNT" "$SECOND_COUNT"
  assert_contains "install-pm-cron second run is idempotent" "already registered" "$SECOND_OUT"

  # --check flag — after the first run the row exists; --check exits 0.
  CHECK_OUT="$(env -i PATH="$STUB_BIN:/usr/bin:/bin" HOME="$WORK" LETTA_HOME="$WORK/.letta" \
    bash "$INSTALL" --check 2>&1)"
  CHECK_EXIT=$?
  assert_eq "install-pm-cron --check after install exit code" "0" "$CHECK_EXIT"

  # --check flag — against an empty registry it exits 1, NOT 0.
  printf '{"version":1,"scheduler_owner":null,"tasks":[]}\n' >"$WORK/.letta/crons.json"
  CHECK_EMPTY_OUT="$(env -i PATH="$STUB_BIN:/usr/bin:/bin" HOME="$WORK" LETTA_HOME="$WORK/.letta" \
    bash "$INSTALL" --check 2>&1)"
  CHECK_EMPTY_EXIT=$?
  assert_eq "install-pm-cron --check against empty registry exit code" "1" "$CHECK_EMPTY_EXIT"
else
  printf '  SKIP  install-pm-cron.sh not present in this worktree (case 4 deferred to PR commit 1)\n'
fi

# -------------------------------------------------------------- summary
echo ""
echo "=== summary ==="
echo "assertions: $ASSERTIONS  pass: $PASS  fail: $FAIL"

if [ "$FAIL" -gt 0 ]; then
  exit 1
fi
exit 0
