#!/usr/bin/env bash
# Started alongside Gradle; the caller stops it when Gradle exits normally.
set -u
sleep "$1"
output="$2"
mkdir -p "$(dirname "$output")"
{
  date -u
  echo '=== Memory ==='
  free -m
  echo '=== Processes by CPU ==='
  ps -eo pid,ppid,stat,%cpu,%mem,rss,args --sort=-%cpu | head -n 41
  echo '=== Processes by memory ==='
  ps -eo pid,ppid,stat,%cpu,%mem,rss,args --sort=-rss | head -n 41
  echo '=== Chrome and Karma ==='
  ps -eo pid,ppid,stat,%cpu,%mem,rss,args | grep -Ei '[c]hrome|[c]hromium|[k]arma' || true
  echo '=== Java thread dumps ==='
  while read -r pid; do
    echo "--- Java PID $pid ---"
    timeout 10s jstack "$pid" 2>&1 || true
  done < <(pgrep -x java || true)
} > "$output" 2>&1
