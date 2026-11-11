#!/usr/bin/env bash
# Regression test for scripts/ci/apt-install-retry.sh with a fake apt-get and resolver.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="$here/../ci/apt-install-retry.sh"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

# A fake apt-get that fails its first N calls (counter file), then records what it was asked to do.
cat > "$work/apt" <<'FAKE'
#!/usr/bin/env bash
n=$(cat "$COUNT" 2>/dev/null || echo 0); n=$((n + 1)); echo "$n" > "$COUNT"
if [ "$n" -le "${FAIL_FIRST:-0}" ]; then echo "E: Temporary failure resolving 'archive.ubuntu.com'" >&2; exit 100; fi
echo "$*" >> "$CALLS"
FAKE
cat > "$work/resolve-ok" <<'FAKE'
#!/usr/bin/env bash
exit 0
FAKE
cat > "$work/resolve-after" <<'FAKE'
#!/usr/bin/env bash
n=$(cat "$RCOUNT" 2>/dev/null || echo 0); n=$((n + 1)); echo "$n" > "$RCOUNT"
[ "$n" -gt "${RESOLVE_FAIL_FIRST:-0}" ]
FAKE
chmod +x "$work/apt" "$work/resolve-ok" "$work/resolve-after"
run() { COUNT="$work/count" CALLS="$work/calls" RCOUNT="$work/rcount" APT_GET="$work/apt" APT_RETRY_SLEEP_SECONDS=0 "$@"; }
reset() { rm -f "$work/count" "$work/calls" "$work/rcount"; }

# 1. Healthy runner: one update and one install naming every package.
reset; export COUNT CALLS RCOUNT
run env APT_RESOLVE="$work/resolve-ok" bash "$script" gcc xvfb >/dev/null 2>&1 || fail "healthy run failed"
grep -q " update$" "$work/calls" || fail "update was not run"
grep -q "install -y gcc xvfb$" "$work/calls" || fail "install did not name every package"

# 2. apt fails twice (the observed DNS failure), then succeeds: the step recovers.
reset
run env FAIL_FIRST=2 APT_RESOLVE="$work/resolve-ok" bash "$script" gcc >/dev/null 2>&1 || fail "did not recover after transient apt failures"
grep -q "install -y gcc$" "$work/calls" || fail "install missing after recovery"

# 3. The resolver is not ready for the first two attempts: apt is not called until it is.
reset
run env RESOLVE_FAIL_FIRST=2 APT_RESOLVE="$work/resolve-after" bash "$script" gcc >/dev/null 2>&1 || fail "did not wait for the resolver"
[ "$(cat "$work/rcount")" -ge 3 ] || fail "resolver was not retried"
grep -q "install -y gcc$" "$work/calls" || fail "install missing after the resolver came up"

# 4. Mirrors never come back: the step FAILS (never a false success) after the configured attempts.
reset
if run env FAIL_FIRST=99 APT_RETRY_ATTEMPTS=3 APT_RESOLVE="$work/resolve-ok" bash "$script" gcc >/dev/null 2>&1; then
  fail "reported success although apt never succeeded"
fi
[ "$(cat "$work/count")" -eq 3 ] || fail "expected exactly 3 attempts, got $(cat "$work/count")"
[ ! -s "$work/calls" ] || fail "an install was recorded although every attempt failed"

# 5. No packages is a usage error, not a silent success.
if bash "$script" >/dev/null 2>&1; then fail "accepted an empty package list"; fi

echo "apt-install-retry-test: ok"
