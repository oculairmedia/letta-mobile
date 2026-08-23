#!/bin/bash
# Meridian stall watchdog (added 2026-08-23).
#
# Why this exists: on 2026-08-23 ~14:00 all three Iroh peers dropped with
# closeReason=timed out AND the loopback App Server WS closed 1006, and the
# in-flight turn on local-conv-190 died with releasedWithoutTerminal. Loopback
# does not drop packets, so the shape points at the wrapper JVM stalling long
# enough that every peer's timeout fired together. The logs at the time carried
# no JVM-side evidence, so the cause was not determinable after the fact.
#
# This samples App Server round-trip latency continuously and, when a sample is
# slow or fails, captures the JVM/host state that the next incident needs:
# thread dump, GC/heap state, load, socket state, and log tails.
#
# Observability only. It never restarts or signals the services it watches.

set -uo pipefail

PROBE=/usr/local/lib/meridian/appserver-probe.cjs
JCMD=/usr/lib/jvm/java-21-openjdk-amd64/bin/jcmd
LATENCY_LOG=/var/log/meridian-probe-latency.log
STALL_DIR=/var/log/meridian-stalls
WRAPPER_LOG=/var/log/meridian-iroh-wrapper.log
GC_LOG=/var/log/meridian-iroh-wrapper-gc.log

INTERVAL_S="${INTERVAL_S:-20}"
SLOW_MS="${SLOW_MS:-2000}"        # a healthy probe is ~7ms; 2s means something is wrong
DEADLINE_MS="${DEADLINE_MS:-10000}"
CAPTURE_COOLDOWN_S="${CAPTURE_COOLDOWN_S:-300}"
KEEP_CAPTURES="${KEEP_CAPTURES:-20}"

mkdir -p "$STALL_DIR"
last_capture=0

wrapper_pid() { systemctl show meridian-iroh-wrapper.service -p MainPID --value 2>/dev/null; }
appserver_pid() { systemctl show meridian-appserver.service -p MainPID --value 2>/dev/null; }

capture() {
  local reason="$1" now dir wpid apid
  now=$(date +%s)
  if (( now - last_capture < CAPTURE_COOLDOWN_S )); then
    return 0
  fi
  last_capture=$now
  dir="$STALL_DIR/$(date +%Y%m%dT%H%M%S)"
  mkdir -p "$dir"
  wpid=$(wrapper_pid); apid=$(appserver_pid)

  {
    echo "reason:        $reason"
    echo "captured_at:   $(date -Is)"
    echo "wrapper_pid:   $wpid"
    echo "appserver_pid: $apid"
  } > "$dir/00-summary.txt"

  # JVM thread dump is the load-bearing artifact: it distinguishes a safepoint/GC
  # stall from coroutine-dispatcher starvation or a blocked Ktor thread.
  if [[ -n "$wpid" && "$wpid" != "0" ]]; then
    timeout 30 "$JCMD" "$wpid" Thread.print -l   > "$dir/10-wrapper-threads.txt" 2>&1
    timeout 20 "$JCMD" "$wpid" GC.heap_info      > "$dir/11-wrapper-heap.txt"    2>&1
    # Second dump 5s later. Diffing the two is what separates a genuinely STUCK
    # thread (identical stack in both) from one that is merely busy (stack moved)
    # -- a single dump cannot tell those apart, and that distinction is the whole
    # question when the symptom is "everything timed out at once".
    sleep 5
    timeout 30 "$JCMD" "$wpid" Thread.print -l   > "$dir/13-wrapper-threads-t+5s.txt" 2>&1
  fi

  uptime                                    > "$dir/20-load.txt"      2>&1
  free -m                                  >> "$dir/20-load.txt"      2>&1
  timeout 10 vmstat 1 3                     > "$dir/21-vmstat.txt"    2>&1
  ps -o pid,pcpu,pmem,rss,etime,stat,comm -p "${wpid:-1}" "${apid:-1}" > "$dir/22-procs.txt" 2>&1
  ss -tnp state established '( sport = :4500 or dport = :4500 )' > "$dir/23-sockets.txt" 2>&1

  tail -n 400 "$WRAPPER_LOG"  > "$dir/30-wrapper-log-tail.txt" 2>&1
  tail -n 200 "$GC_LOG"       > "$dir/31-gc-log-tail.txt"      2>&1
  tail -n 100 "$LATENCY_LOG"  > "$dir/32-latency-tail.txt"     2>&1

  logger -t meridian-stall-watchdog "captured stall evidence: $dir ($reason)"

  # / sits at ~90% use; never let captures accumulate without bound.
  ls -1dt "$STALL_DIR"/*/ 2>/dev/null | tail -n +$((KEEP_CAPTURES + 1)) \
    | xargs -r rm -rf --
}

logger -t meridian-stall-watchdog "started (interval=${INTERVAL_S}s slow=${SLOW_MS}ms)"

while true; do
  out=$(PROBE_DEADLINE_MS="$DEADLINE_MS" timeout $(( (DEADLINE_MS / 1000) + 5 )) \
        node "$PROBE" 2>&1)
  rc=$?
  ts=$(date -Is)

  # Record the App Server's RSS on every sample. Two stalls on 2026-08-23 showed
  # the SAME probe signature (connect ok, upgrade=-1) from two DIFFERENT causes,
  # and only RSS told them apart:
  #   15:50 load 30.64, si=4540 swap-in, node RSS  227MB -> host memory pressure
  #   17:45 load  3.87, si=8    no swap,  node RSS 2700MB -> V8 GC near the
  #                                                          4.05GB heap ceiling
  # Without this column the next stall is ambiguous again.
  as_pid=$(appserver_pid)
  as_rss=""
  if [[ -n "$as_pid" && "$as_pid" != "0" && -r "/proc/$as_pid/status" ]]; then
      as_rss=$(awk '/^VmRSS:/{printf "%d", $2/1024}' "/proc/$as_pid/status" 2>/dev/null)
  fi
  echo "$ts $out rss=${as_rss:-?}MB" >> "$LATENCY_LOG"

  if (( rc != 0 )); then
    capture "probe_failed: $out"
  else
    total=$(awk '{print $2}' <<< "$out")
    if [[ "$total" =~ ^[0-9]+$ ]] && (( total > SLOW_MS )); then
      capture "probe_slow: ${total}ms > ${SLOW_MS}ms"
    fi
  fi

  sleep "$INTERVAL_S"
done
