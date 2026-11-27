#!/usr/bin/env bash
# Shared resilient download helper for the scripts/build-android-*.sh build
# scripts. Source this file, then call:
#
#   download_with_retry <output-path> <pinned-sha256> <primary-url> [mirror-url...]
#
# A transient upstream outage must not fail an Android build: ftp.gnu.org has
# answered a signed release build's tarball request with an empty reply
# (curl error 52), which curl does not retry by default. The helper:
#   - fetches each URL in order with curl's own transient-error retries
#     (--retry-all-errors covers the empty-reply case);
#   - verifies the pinned sha256 after every download, before the file ever
#     reaches its output path;
#   - removes every partial download, so a corrupt file is never left behind;
#   - reuses an already-verified file as-is and re-fetches a corrupt cache;
#   - fails loudly (non-zero, on stderr) when no mirror serves the pinned file.
#
# Env overrides (tests set these so curl is fast):
#   DOWNLOAD_RETRIES          curl --retry (default 5)
#   DOWNLOAD_RETRY_DELAY      curl --retry-delay seconds (default 5)
#   DOWNLOAD_CONNECT_TIMEOUT  curl --connect-timeout seconds (default 30)
#
# This file is sourced into build scripts that set their own shell options:
# it must never set them itself.

DOWNLOAD_RETRIES="${DOWNLOAD_RETRIES:-5}"
DOWNLOAD_RETRY_DELAY="${DOWNLOAD_RETRY_DELAY:-5}"
DOWNLOAD_CONNECT_TIMEOUT="${DOWNLOAD_CONNECT_TIMEOUT:-30}"

dw_log() { echo "[download-with-retry] $*" >&2; }

dw_sha256_matches() { # <pinned-sha256> <file>: 0 when the file matches the pin
  printf '%s  %s\n' "$1" "$2" | sha256sum -c --status
}

download_with_retry() { # <output-path> <pinned-sha256> <primary-url> [mirror-url...]
  if [ "$#" -lt 3 ]; then
    dw_log "usage: download_with_retry <output-path> <pinned-sha256> <primary-url> [mirror-url...]"
    return 64
  fi
  local out="$1" sha256="$2" url tmp
  [[ "$sha256" =~ ^[0-9a-fA-F]{64}$ ]] || { dw_log "invalid pinned sha256: $sha256"; return 64; }
  shift 2

  # A previously verified file is reused as-is; a corrupt cache is re-fetched.
  if [ -f "$out" ] && dw_sha256_matches "$sha256" "$out"; then
    dw_log "$out already present and sha256-verified — reusing"
    return 0
  fi

  mkdir -p "$(dirname "$out")"
  tmp="$out.partial"
  for url in "$@"; do
    rm -f "$tmp"
    dw_log "fetching $url"
    if curl -fsSL --retry "$DOWNLOAD_RETRIES" --retry-all-errors \
        --retry-delay "$DOWNLOAD_RETRY_DELAY" --connect-timeout "$DOWNLOAD_CONNECT_TIMEOUT" \
        -o "$tmp" "$url"; then
      if dw_sha256_matches "$sha256" "$tmp"; then
        mv -f "$tmp" "$out"
        dw_log "sha256 verified for $out (from $url)"
        return 0
      fi
      dw_log "sha256 MISMATCH for $url: pinned $sha256, served $(sha256sum "$tmp" | cut -d' ' -f1) — trying the next mirror"
    else
      dw_log "download failed: $url — trying the next mirror"
    fi
  done
  rm -f "$tmp"
  dw_log "FAILED: no mirror served $out with pinned sha256 $sha256; partial downloads were removed"
  return 1
}
