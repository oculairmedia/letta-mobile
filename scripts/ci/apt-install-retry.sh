#!/usr/bin/env bash
# apt-install-retry.sh <package...>
# Installs build prerequisites on a CI runner whose resolver cannot find the Ubuntu mirrors.
# Hosted runners have failed the required `test` job on unchanged code with "Temporary failure resolving
# 'archive.ubuntu.com'" for every Ubuntu mirror while other hosts resolved, for hours and across reruns.
# So waiting is not enough: when the system resolver fails for a mirror, its address is looked up over
# DNS-over-HTTPS by IP (no DNS needed) and pinned in /etc/hosts for this job, then apt is retried.
# It never skips a package and never reports success unless apt installed every requested package.
set -euo pipefail

[ "$#" -gt 0 ] || { echo "usage: apt-install-retry.sh <package...>" >&2; exit 64; }

ATTEMPTS="${APT_RETRY_ATTEMPTS:-5}"
SLEEP_SECONDS="${APT_RETRY_SLEEP_SECONDS:-10}"
APT_GET="${APT_GET:-sudo apt-get}"
RESOLVE="${APT_RESOLVE:-getent hosts}"
PUBLIC_RESOLVE="${APT_PUBLIC_RESOLVE:-}"
HOSTS_FILE="${APT_HOSTS_FILE:-/etc/hosts}"
HOSTS_WRITE="${APT_HOSTS_WRITE:-sudo tee -a}"
HOSTS_EDIT="${APT_HOSTS_EDIT:-sudo sed -i}"
PIN_MARK="# apt-install-retry"
MIRRORS="${APT_MIRROR_HOSTS:-azure.archive.ubuntu.com archive.ubuntu.com security.ubuntu.com}"
APT_OPTS=(-o Acquire::Retries=3 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30)

log() { echo "[apt-install-retry] $*" >&2; }

# One IPv4 address for $1 from a public resolver reached by IP, or nothing.
public_address() {
  if [ -n "$PUBLIC_RESOLVE" ]; then $PUBLIC_RESOLVE "$1"; return; fi
  python3 - "$1" <<'PY'
import json, sys, urllib.request
name = sys.argv[1]
for server in ("1.1.1.1", "8.8.8.8"):
    path = "dns-query" if server == "1.1.1.1" else "resolve"
    request = urllib.request.Request(
        f"https://{server}/{path}?name={name}&type=A", headers={"accept": "application/dns-json"})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            answers = json.load(response).get("Answer", [])
    except Exception:
        continue
    addresses = [a["data"] for a in answers if a.get("type") == 1]
    if addresses:
        print(addresses[0])
        break
PY
}

# Pins every mirror the system resolver cannot find. Returns 0 when at least one was pinned.
pin_unresolved_mirrors() {
  local pinned=1 host address
  for host in $MIRRORS; do
    $RESOLVE "$host" >/dev/null 2>&1 && continue
    address="$(public_address "$host" 2>/dev/null | head -n 1 || true)"
    if [[ "$address" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
      echo "$address $host $PIN_MARK" | $HOSTS_WRITE "$HOSTS_FILE" >/dev/null
      log "system resolver cannot find $host; pinned it to $address for this job"
      pinned=0
    else
      log "system resolver cannot find $host and no public resolver answered"
    fi
  done
  return $pinned
}

# Drops this script's pins, so a pinned address that does not work cannot outlive a DNS recovery:
# each attempt asks the system resolver first and pins again only what it still cannot find.
unpin_mirrors() {
  grep -q "$PIN_MARK\$" "$HOSTS_FILE" 2>/dev/null || return 0
  $HOSTS_EDIT "/$PIN_MARK\$/d" "$HOSTS_FILE"
}

attempt=1
while :; do
  if $APT_GET "${APT_OPTS[@]}" update && $APT_GET "${APT_OPTS[@]}" install -y "$@"; then
    log "installed: $*"
    exit 0
  fi
  log "attempt $attempt/$ATTEMPTS: apt failed"
  if [ "$attempt" -ge "$ATTEMPTS" ]; then
    log "giving up after $ATTEMPTS attempts; the runner could not reach the Ubuntu mirrors"
    exit 1
  fi
  unpin_mirrors
  pin_unresolved_mirrors || sleep "$SLEEP_SECONDS"
  attempt=$((attempt + 1))
done
