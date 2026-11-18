#!/usr/bin/env bash
# Started alongside Gradle; the caller stops it when Gradle exits normally.
#
# The diagnostics file is uploaded as an artifact, which Actions log masking
# does not cover, so it must never contain process argv: CI tools can receive
# credentials as command arguments. Process listings use `comm` (the
# executable name) only, and the Java section uses plain `jstack`, whose
# thread dump carries no VM arguments or system properties.
set -u
sleep "$1"
output="$2"
mkdir -p "$(dirname "$output")"

# pid, ppid, state, CPU, memory and executable name; never `args`/`cmd`.
ps_fields=pid,ppid,stat,%cpu,%mem,rss,comm

{
  date -u
  echo '=== Memory ==='
  free -m
  echo '=== Processes by CPU ==='
  ps -eo "$ps_fields" --sort=-%cpu | head -n 41
  echo '=== Processes by memory ==='
  ps -eo "$ps_fields" --sort=-rss | head -n 41
  echo '=== Chrome and Karma ==='
  # Karma runs under node and launches Chrome, so each browser process is
  # printed with its parent chain to tie it back to the Karma/Gradle owner.
  ps -eo "$ps_fields" | awk '
    NR == 1 { header = $0; next }
    { line[$1] = $0; parent[$1] = $2; name[$1] = tolower($7) }
    END {
      print header
      for (pid in line) {
        if (name[pid] !~ /chrome|chromium|karma/) continue
        print line[pid]
        p = parent[pid]
        depth = 0
        while (p in line && p != 0 && depth < 8) {
          print "  parent: " line[p]
          p = parent[p]
          depth++
        }
      }
    }'
  echo '=== Java thread dumps ==='
  while read -r pid; do
    echo "--- Java PID $pid ---"
    timeout 10s jstack "$pid" 2>&1 || true
  done < <(pgrep -x java || true)
} > "$output" 2>&1
