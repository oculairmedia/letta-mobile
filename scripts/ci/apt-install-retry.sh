#!/usr/bin/env bash
# apt-install-retry.sh <package...>
# Installs build prerequisites on a CI runner, surviving a runner whose resolver is not ready yet.
# A hosted runner has repeatedly started with DNS failing for the Ubuntu mirrors ("Temporary failure
# resolving 'archive.ubuntu.com'"), which failed the required `test` job on unchanged code. This waits
# for name resolution, retries the index update and the install with backoff, and fails loudly with the
# real apt error if the mirrors stay unreachable. It never skips a package and never reports success
# unless every requested package is installed.
set -euo pipefail

[ "$#" -gt 0 ] || { echo "usage: apt-install-retry.sh <package...>" >&2; exit 64; }

ATTEMPTS="${APT_RETRY_ATTEMPTS:-5}"
SLEEP_SECONDS="${APT_RETRY_SLEEP_SECONDS:-15}"
APT_GET="${APT_GET:-sudo apt-get}"
RESOLVE_HOST="${APT_RESOLVE_HOST:-archive.ubuntu.com}"
RESOLVE="${APT_RESOLVE:-getent hosts}"
APT_OPTS=(-o Acquire::Retries=3 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30)

log() { echo "[apt-install-retry] $*" >&2; }

attempt=1
while :; do
  if ! $RESOLVE "$RESOLVE_HOST" >/dev/null 2>&1; then
    log "attempt $attempt/$ATTEMPTS: cannot resolve $RESOLVE_HOST yet"
  elif $APT_GET "${APT_OPTS[@]}" update && $APT_GET "${APT_OPTS[@]}" install -y "$@"; then
    log "installed: $*"
    exit 0
  else
    log "attempt $attempt/$ATTEMPTS: apt failed"
  fi
  if [ "$attempt" -ge "$ATTEMPTS" ]; then
    log "giving up after $ATTEMPTS attempts; the runner could not reach the Ubuntu mirrors"
    exit 1
  fi
  attempt=$((attempt + 1))
  sleep "$SLEEP_SECONDS"
done
