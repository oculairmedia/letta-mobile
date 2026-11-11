#!/usr/bin/env bash
# Regression test for scripts/ci/apt-install-retry.sh with a fake apt-get, resolver and hosts file.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="$here/../ci/apt-install-retry.sh"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

# Fake apt-get: fails while the mirror is not in the hosts file (when NEED_PIN is set) or for its first
# FAIL_FIRST calls; otherwise records what it was asked to do.
cat > "$work/apt" <<'FAKE'
#!/usr/bin/env bash
n=$(cat "$COUNT" 2>/dev/null || echo 0); n=$((n + 1)); echo "$n" > "$COUNT"
if [ -n "${NEED_PIN:-}" ] && ! grep -q " $NEED_PIN " "$HOSTS" 2>/dev/null; then
  echo "E: Temporary failure resolving '$NEED_PIN'" >&2; exit 100
fi
if [ "$n" -le "${FAIL_FIRST:-0}" ]; then echo "E: Temporary failure resolving 'archive.ubuntu.com'" >&2; exit 100; fi
echo "$*" >> "$CALLS"
FAKE
printf '#!/usr/bin/env bash\nexit 0\n' > "$work/resolve-ok"
printf '#!/usr/bin/env bash\nexit 2\n' > "$work/resolve-broken"
printf '#!/usr/bin/env bash\necho 203.0.113.7\n' > "$work/public-ok"
printf '#!/usr/bin/env bash\nexit 0\n' > "$work/public-silent"
printf '#!/usr/bin/env bash\necho "not an address"\n' > "$work/public-garbage"
chmod +x "$work"/apt "$work"/resolve-* "$work"/public-*
export COUNT="$work/count" CALLS="$work/calls" HOSTS="$work/hosts"
run() {
  APT_GET="$work/apt" APT_RETRY_SLEEP_SECONDS=0 APT_HOSTS_FILE="$HOSTS" APT_HOSTS_WRITE="tee -a" \
    APT_HOSTS_EDIT="sed -i" APT_MIRROR_HOSTS="mirror.test" "$@"
}
reset() { rm -f "$COUNT" "$CALLS"; : > "$HOSTS"; }

# 1. Healthy runner: one update, one install naming every package, hosts file untouched.
reset
run env APT_RESOLVE="$work/resolve-ok" bash "$script" gcc xvfb >/dev/null 2>&1 || fail "healthy run failed"
grep -q " update$" "$CALLS" || fail "update was not run"
grep -q "install -y gcc xvfb$" "$CALLS" || fail "install did not name every package"
[ ! -s "$HOSTS" ] || fail "a healthy run changed the hosts file"

# 2. Transient apt failure with a working resolver: it retries and recovers without pinning anything.
reset
run env FAIL_FIRST=2 APT_RESOLVE="$work/resolve-ok" bash "$script" gcc >/dev/null 2>&1 || fail "did not recover after transient apt failures"
grep -q "install -y gcc$" "$CALLS" || fail "install missing after recovery"
[ ! -s "$HOSTS" ] || fail "pinned a mirror although the system resolver worked"

# 3. The observed failure: the system resolver never finds the mirror. The address comes from the public
#    resolver, is pinned, and the install then succeeds.
reset
run env NEED_PIN=mirror.test APT_RESOLVE="$work/resolve-broken" APT_PUBLIC_RESOLVE="$work/public-ok" \
  bash "$script" gcc >/dev/null 2>&1 || fail "did not recover by pinning the mirror"
grep -qx "203.0.113.7 mirror.test # apt-install-retry" "$HOSTS" || fail "the mirror was not pinned to the public answer"
grep -q "install -y gcc$" "$CALLS" || fail "install missing after pinning"

# 4. No resolver answers: the step FAILS after the configured attempts, never a false success.
reset
if run env NEED_PIN=mirror.test APT_RETRY_ATTEMPTS=3 APT_RESOLVE="$work/resolve-broken" \
  APT_PUBLIC_RESOLVE="$work/public-silent" bash "$script" gcc >/dev/null 2>&1; then
  fail "reported success although apt never succeeded"
fi
[ "$(cat "$COUNT")" -eq 3 ] || fail "expected exactly 3 attempts, got $(cat "$COUNT")"
[ ! -s "$CALLS" ] || fail "an install was recorded although every attempt failed"

# 5. A public answer that is not an IPv4 address is never written to the hosts file.
reset
run env NEED_PIN=mirror.test APT_RETRY_ATTEMPTS=2 APT_RESOLVE="$work/resolve-broken" \
  APT_PUBLIC_RESOLVE="$work/public-garbage" bash "$script" gcc >/dev/null 2>&1 && fail "succeeded on a garbage answer"
[ ! -s "$HOSTS" ] || fail "wrote a non-address to the hosts file"

# 6. A pinned address that does not work must not outlive a DNS recovery: apt keeps failing while the
#    pin is present (BAD_PIN), the system resolver comes back, the pin is dropped and the install succeeds.
cat > "$work/apt-badpin" <<'FAKE'
#!/usr/bin/env bash
n=$(cat "$COUNT" 2>/dev/null || echo 0); n=$((n + 1)); echo "$n" > "$COUNT"
if grep -q "apt-install-retry" "$HOSTS" 2>/dev/null || [ "$n" -le 1 ]; then echo "E: could not connect" >&2; exit 100; fi
echo "$*" >> "$CALLS"
FAKE
cat > "$work/resolve-recovers" <<'FAKE'
#!/usr/bin/env bash
n=$(cat "$RCOUNT" 2>/dev/null || echo 0); n=$((n + 1)); echo "$n" > "$RCOUNT"
[ "$n" -gt 1 ]
FAKE
chmod +x "$work/apt-badpin" "$work/resolve-recovers"
reset; rm -f "$work/rcount"
RCOUNT="$work/rcount" APT_GET="$work/apt-badpin" APT_RETRY_SLEEP_SECONDS=0 APT_HOSTS_FILE="$HOSTS" APT_HOSTS_WRITE="tee -a" \
  APT_HOSTS_EDIT="sed -i" APT_MIRROR_HOSTS="mirror.test" APT_RESOLVE="$work/resolve-recovers" \
  APT_PUBLIC_RESOLVE="$work/public-ok" bash "$script" gcc >/dev/null 2>&1 || fail "a bad pin outlived the DNS recovery"
! grep -q "apt-install-retry" "$HOSTS" || fail "the failed pin was left in the hosts file"
grep -q "install -y gcc$" "$CALLS" || fail "install missing after the pin was dropped"

# 7. No packages is a usage error, not a silent success.
if bash "$script" >/dev/null 2>&1; then fail "accepted an empty package list"; fi

echo "apt-install-retry-test: ok"
