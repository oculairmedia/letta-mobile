#!/bin/bash
# Meridian build adopter (letta-mobile-jsfrn, 2026-08-23).
#
# Agents spawn Gradle/Kotlin builds from inside the App Server, so those JVMs
# inherit meridian-appserver.service's cgroup. Observed during the incident:
#   gradle daemon   -Xmx4g   RSS 1.09GB
#   kotlin compiler -Xmx2g   RSS 1.00GB
# android-compose/gradle.properties configures 4g + 4g, so a single build can put
# ~8GB of heap in the production service's cgroup. That hurts twice:
#   1. It drives a busy 48GB host into swap. The 15:50:48 capture showed the App
#      Server blocked faulting its own pages back in (connect=3ms, upgrade never
#      ran for >10s) while CPU sat 70-83% idle -- a stall, not a busy loop.
#   2. meridian-appserver uses KillMode=control-group, so restarting the App
#      Server REAPS in-flight builds (confirmed during the 16:28 restart).
#
# Rather than patching the generated Gradle wrapper (regenerating it would silently
# drop the change) or replacing $SHELL for every agent tool call (blast radius:
# every command an agent runs), this migrates the daemons after the fact.
# cgroup v2 permits moving a running process by writing its PID to the target's
# cgroup.procs, and memory charging follows it -- verified: a 300MB allocation in
# the adopted cgroup showed up as meridian-builds.slice memory.current 0 -> 300 -> 0.
#
# Migration is one-way and safe to repeat: already-adopted processes are simply
# not found in the App Server cgroup on the next pass.

set -uo pipefail

APPSERVER_CG=/sys/fs/cgroup/system.slice/meridian-appserver.service
TARGET_SLICE=/sys/fs/cgroup/meridian.slice/meridian-builds.slice
TARGET_CG="$TARGET_SLICE/adopted"
TAG=meridian-build-adopter

log() { logger -t "$TAG" "$*"; echo "$(date -Is) [$TAG] $*"; }

[ -d "$APPSERVER_CG" ] || { echo "app server cgroup absent; nothing to do"; exit 0; }

# The slice cgroup only materializes once systemd has used it; create the leaf on
# demand so this survives a reboot without needing the slice to be started first.
if [ ! -d "$TARGET_SLICE" ]; then
    systemctl start meridian-builds.slice 2>/dev/null || true
fi
[ -d "$TARGET_SLICE" ] || { log "WARN meridian-builds.slice cgroup unavailable; skipping"; exit 0; }
mkdir -p "$TARGET_CG" 2>/dev/null || { log "WARN cannot create $TARGET_CG; skipping"; exit 0; }

# Never migrate the App Server itself, whatever else matches.
MAIN_PID="$(systemctl show meridian-appserver.service -p MainPID --value 2>/dev/null || echo 0)"

# Strict allowlist. This deliberately does NOT adopt arbitrary children of the App
# Server -- only the long-lived build JVMs that are the actual memory hogs. An
# agent's short-lived shell commands are left exactly where they are.
is_build_daemon() {
    local cmdline="$1"
    case "$cmdline" in
        *GradleDaemon*)                 return 0 ;;
        *KotlinCompileDaemon*)          return 0 ;;
        *gradle-launcher*)              return 0 ;;
        *org.gradle.launcher.daemon*)   return 0 ;;
        *) return 1 ;;
    esac
}

adopted=0
while read -r pid; do
    [ -n "$pid" ] || continue
    [ "$pid" != "$MAIN_PID" ] || continue
    [ -r "/proc/$pid/cmdline" ] || continue
    cmdline="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null)" || continue
    is_build_daemon "$cmdline" || continue

    rss_kb="$(awk '/^VmRSS:/{print $2}' "/proc/$pid/status" 2>/dev/null)"
    short="$(printf '%s' "$cmdline" | cut -c1-80)"
    if echo "$pid" > "$TARGET_CG/cgroup.procs" 2>/dev/null; then
        log "adopted pid=$pid rss=${rss_kb:-?}kB out of the App Server cgroup: $short"
        adopted=$((adopted + 1))
    else
        log "WARN failed to adopt pid=$pid ($short)"
    fi
done < "$APPSERVER_CG/cgroup.procs"

if [ "$adopted" -gt 0 ]; then
    log "adopted $adopted build process(es); slice now at $(( $(cat "$TARGET_SLICE/memory.current" 2>/dev/null || echo 0) / 1048576 ))MB"
fi
exit 0
