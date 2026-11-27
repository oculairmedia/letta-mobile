#!/usr/bin/env bash
# Regression test for scripts/lib/download-with-retry.sh, the shared
# resilient download helper of the scripts/build-android-*.sh build scripts.
#
# Proves:
#   1. every scripts/build-android-*.sh passes bash -n and downloads through
#      the shared helper only (no private curl/wget call can come back), and
#      every script pinning a sha256 fetches from a primary plus at least one
#      mirror;
#   2. an unreachable primary URL falls back to a mirror — a local
#      python3 http.server standing in for the official mirror network — and
#      the downloaded file matches the pinned sha256;
#   3. a mirror serving a corrupt file fails the sha256 gate loudly and
#      leaves no artifact behind (neither the output nor a partial);
#   4. no reachable mirror fails loudly instead of reporting success;
#   5. an already-verified file is reused without the network.
#
# Hermetic: every URL is 127.0.0.1 (a port with nothing listening as the
# dead primary, the local http.server as the mirror); no upstream is touched.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source_root="$(cd "$here/../.." && pwd)"
helper="$here/../lib/download-with-retry.sh"
work="$(mktemp -d)"
mirror_pid=""
cleanup() {
  if [ -n "$mirror_pid" ]; then kill "$mirror_pid" 2>/dev/null || true; fi
  rm -rf "$work"
}
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

# Number of "http(s):// URL arguments of each download_with_retry call.
call_url_counts() { # <script>
  awk '
    /^[[:space:]]*download_with_retry[[:space:]]/ && !in_call { in_call = 1; n = 0 }
    in_call {
      n += gsub(/"https?:\/\//, "")
      if ($0 !~ /\\$/) { print n; in_call = 0 }
    }
  ' "$1"
}

# 1. Wiring: every Android build script downloads through the shared helper.
for script in "$source_root"/scripts/build-android-*.sh; do
  name="scripts/$(basename "$script")"
  bash -n "$script" || fail "$name: bash -n failed"
  if grep -Eq '(^|[[:space:];&])(curl[[:space:]]+-|wget[[:space:]])' "$script"; then
    fail "$name must download through scripts/lib/download-with-retry.sh, not its own curl/wget call"
  fi
  if grep -Eq '^[A-Z_]+_SHA256=' "$script"; then
    grep -q 'lib/download-with-retry.sh' "$script" \
      || fail "$name pins a sha256 but does not source scripts/lib/download-with-retry.sh"
    counts="$(call_url_counts "$script")"
    [ -n "$counts" ] || fail "$name pins a sha256 but never calls download_with_retry"
    for count in $counts; do
      [ "$count" -ge 2 ] \
        || fail "$name calls download_with_retry with $count URL(s): a primary with no mirror fallback"
    done
  fi
done

# 2. The helper's resilience contract (transient errors retried, sane defaults).
[ -f "$helper" ] || fail "missing helper: scripts/lib/download-with-retry.sh"
bash -n "$helper" || fail "scripts/lib/download-with-retry.sh: bash -n failed"
grep -q -- '-fsSL' "$helper" || fail "helper lost -fsSL (fail on HTTP errors, follow mirror redirects)"
grep -q -- '--retry-all-errors' "$helper" \
  || fail "helper lost --retry-all-errors (curl error 52, empty reply, is not retried without it)"
grep -q -- '--connect-timeout' "$helper" || fail "helper lost --connect-timeout"
grep -q 'DOWNLOAD_RETRIES:-5' "$helper" || fail "helper default retries must stay 5"
grep -q 'DOWNLOAD_RETRY_DELAY:-5' "$helper" || fail "helper default retry delay must stay 5 seconds"
grep -q 'DOWNLOAD_CONNECT_TIMEOUT:-30' "$helper" || fail "helper default connect timeout must stay 30 seconds"
if grep -Eq '^[[:space:]]*set -' "$helper"; then
  fail "the helper is sourced into build scripts: it must not set shell options itself"
fi
# shellcheck source=../lib/download-with-retry.sh
source "$helper"

# Fast curl knobs for this test; the dead primary still exercises a retry.
export DOWNLOAD_RETRIES=1 DOWNLOAD_RETRY_DELAY=1 DOWNLOAD_CONNECT_TIMEOUT=2

free_port() { # prints a port number that nothing is listening on
  python3 - <<'PY'
import socket
s = socket.socket()
s.bind(("127.0.0.1", 0))
print(s.getsockname()[1])
s.close()
PY
}

# The fixture "tarball" and its pin, served by the local mirror only.
mkdir -p "$work/mirror" "$work/out"
head -c 4096 /dev/urandom > "$work/mirror/bash-5.2.37.tar.gz"
good_sha="$(sha256sum "$work/mirror/bash-5.2.37.tar.gz" | cut -d' ' -f1)"

dead_port="$(free_port)"
dead_url="http://127.0.0.1:$dead_port/gnu/bash/bash-5.2.37.tar.gz"

mirror_port="$(free_port)"
python3 -m http.server --bind 127.0.0.1 --directory "$work/mirror" "$mirror_port" \
  >"$work/mirror.log" 2>&1 &
mirror_pid=$!
mirror_up=""
for _ in $(seq 1 100); do
  if curl -fsS -o /dev/null --connect-timeout 2 "http://127.0.0.1:$mirror_port/" 2>/dev/null; then
    mirror_up=1
    break
  fi
  sleep 0.1
done
[ -n "$mirror_up" ] || { cat "$work/mirror.log" >&2; fail "local mirror did not come up"; }
mirror_url="http://127.0.0.1:$mirror_port/bash-5.2.37.tar.gz"

# 3. An unreachable primary falls back to the mirror; sha256 is verified.
out="$work/out/bash-5.2.37.tar.gz"
status=0
download_with_retry "$out" "$good_sha" "$dead_url" "$mirror_url" 2>"$work/fallback.err" || status=$?
[ "$status" -eq 0 ] || fail "dead primary did not fall back to the mirror: $(cat "$work/fallback.err")"
[ "$(sha256sum "$out" | cut -d' ' -f1)" = "$good_sha" ] || fail "fallback download failed the sha256 pin"
grep -q 'sha256 verified' "$work/fallback.err" || fail "the fallback success is not reported"
grep -q "trying the next mirror" "$work/fallback.err" || fail "the dead primary did not move on to the mirror"
[ ! -e "$out.partial" ] || fail "a partial file outlived the successful download"

# 4. A corrupt mirror file fails the sha256 gate loudly and leaves no artifact.
printf 'not the pinned tarball' > "$work/mirror/corrupt.tar.gz"
bad_out="$work/out/corrupt.tar.gz"
status=0
download_with_retry "$bad_out" "$good_sha" "http://127.0.0.1:$mirror_port/corrupt.tar.gz" \
  2>"$work/corrupt.err" || status=$?
[ "$status" -ne 0 ] || fail "a corrupt mirror file passed the sha256 gate"
grep -q 'sha256 MISMATCH' "$work/corrupt.err" \
  || fail "the sha256 failure is not loud: $(cat "$work/corrupt.err")"
[ ! -e "$bad_out" ] || fail "a corrupt artifact was left at the output path"
[ ! -e "$bad_out.partial" ] || fail "a corrupt partial was left behind"

# 5. No reachable mirror fails loudly, never a false success.
gone_out="$work/out/gone.tar.gz"
status=0
download_with_retry "$gone_out" "$good_sha" "$dead_url" \
  "http://127.0.0.1:$mirror_port/missing-on-mirror.tar.gz" 2>"$work/gone.err" || status=$?
[ "$status" -ne 0 ] || fail "reported success with an unreachable primary and a 404 mirror"
grep -q 'FAILED' "$work/gone.err" || fail "the all-mirrors-down failure is not loud"
[ ! -e "$gone_out" ] && [ ! -e "$gone_out.partial" ] \
  || fail "an artifact appeared although every mirror failed"

# 6. An already-verified file is reused without the network.
kill "$mirror_pid" 2>/dev/null || true
wait "$mirror_pid" 2>/dev/null || true
mirror_pid=""
status=0
download_with_retry "$out" "$good_sha" "$dead_url" 2>"$work/cache.err" || status=$?
[ "$status" -eq 0 ] || fail "a verified file was re-downloaded: $(cat "$work/cache.err")"
grep -q 'reusing' "$work/cache.err" || fail "verified-file reuse is not reported"

echo "download-with-retry-test: ok"
