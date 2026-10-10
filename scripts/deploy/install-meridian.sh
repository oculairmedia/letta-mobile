#!/usr/bin/env bash
# Install (or check) the Meridian host deployment from this repository.
#
# The units, drop-ins and helper scripts under scripts/deploy/ are the source of
# truth; this script is what makes that true in practice. Without it the host and
# the repo drift in BOTH directions, which is what happened before it existed:
# hand-edited live drop-ins that were never written back, and repo copies with
# improved comments that were never deployed.
#
#   ./install-meridian.sh --check    report drift, change nothing, exit 1 if any
#   ./install-meridian.sh            install, reload systemd, verify
#
# Secrets are never touched. /etc/meridian/*.env is seeded from the .example
# templates only when absent; an existing env file is compared by KEY NAME only
# and never printed, so a missing or extra variable is reported without leaking
# a value.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECK_ONLY=0
[[ "${1:-}" == "--check" ]] && CHECK_ONLY=1

UNIT_DIR=/etc/systemd/system
LIB_DIR=/usr/local/lib/meridian
ENV_DIR=/etc/meridian
drift=0

# unit file -> destination
UNITS=(
  meridian-iroh-wrapper.service
  meridian-stall-watchdog.service
  meridian-build-adopter.service
  meridian-build-adopter.timer
  meridian-cron-sensing.service
  meridian-cron-sensing.timer
  meridian-builds.slice
)
# drop-in template -> "<unit>.d/<name>.conf"
declare -A DROPINS=(
  [meridian-appserver.memory-protection.conf]="meridian-appserver.service.d/memory-protection.conf"
  [meridian-iroh-wrapper.memory-protection.conf]="meridian-iroh-wrapper.service.d/memory-protection.conf"
)
SCRIPTS=(appserver-probe.cjs stall-watchdog.sh adopt-builds.sh cron-sensing-check.sh meridian-permission-hook.cjs)

# letta-mobile-jna0o.5: the agent-side `meridian` shim (on the App Server's PATH) and the
# `meridian-canvas` letta-code skill (in the App Server user's global skills root, which letta-code
# reads from $HOME/.letta/skills). Both are inert until the wrapper runs with
# LETTA_AGENT_TOOLS_MODE=cli; the shim then reaches /run/meridian/tools.sock.
SHIM_DST=/usr/local/bin/meridian
appserver_user="$(systemctl show -p User --value meridian-appserver.service 2>/dev/null || true)"
appserver_home="$(getent passwd "${appserver_user:-root}" | cut -d: -f6)"
SKILLS_ROOT="${MERIDIAN_SKILLS_ROOT:-${appserver_home:-/root}/.letta/skills}"

say() { printf '%-58s %s\n' "$1" "$2"; }

sync_file() {  # src dst mode
  local src="$1" dst="$2" mode="$3"
  if [[ -f "$dst" ]] && diff -q "$src" "$dst" >/dev/null 2>&1; then
    say "$dst" "in sync"
    return 0
  fi
  drift=1
  if (( CHECK_ONLY )); then
    say "$dst" "DRIFTED"
    return 0
  fi
  install -D -m "$mode" "$src" "$dst"
  say "$dst" "installed"
}

for unit in "${UNITS[@]}"; do
  [[ -f "$HERE/$unit" ]] && sync_file "$HERE/$unit" "$UNIT_DIR/$unit" 644
done
for template in "${!DROPINS[@]}"; do
  [[ -f "$HERE/$template" ]] && sync_file "$HERE/$template" "$UNIT_DIR/${DROPINS[$template]}" 644
done
for script in "${SCRIPTS[@]}"; do
  [[ -f "$HERE/$script" ]] && sync_file "$HERE/$script" "$LIB_DIR/$script" 755
done

# Never replace a different `meridian` (e.g. the developer JVM CLI that --meridian-binary may point
# at for agent_message_send): only a missing file or an earlier copy of this shim is overwritten.
if [[ -e "$SHIM_DST" ]] && ! grep -q 'meridian/tools/1' "$SHIM_DST" 2>/dev/null; then
  drift=1
  say "$SHIM_DST" "IS ANOTHER PROGRAM — move it (and repoint --meridian-binary) first"
elif [[ -f "$HERE/meridian-shim.cjs" ]]; then
  sync_file "$HERE/meridian-shim.cjs" "$SHIM_DST" 755
fi
for skill in "$HERE"/skills/*/SKILL.md; do
  [[ -f "$skill" ]] || continue
  name="$(basename "$(dirname "$skill")")"
  sync_file "$skill" "$SKILLS_ROOT/$name/SKILL.md" 644
done

# jna0o.7: the letta-code PermissionRequest hook that approves allow-listed `meridian` calls in
# every permission mode (deliberately NOT a `Bash(meridian:*)` allow rule, which is a raw prefix
# match and would also approve `meridian ...; anything`). Registered in the App Server user's
# global settings, everything else in that file kept as it is.
LETTA_SETTINGS="${appserver_home:-/root}/.letta/settings.json"
if [[ -f "$HERE/meridian-permission-hook.cjs" ]]; then
  if (( CHECK_ONLY )); then
    hook_state="$(node "$HERE/meridian-permission-hook.cjs" --install "$LETTA_SETTINGS" --check 2>&1 || true)"
  else
    hook_state="$(node "$HERE/meridian-permission-hook.cjs" --install "$LETTA_SETTINGS" 2>&1 || true)"
  fi
  [[ "$hook_state" == "in sync" ]] || drift=1
  say "$LETTA_SETTINGS (meridian PermissionRequest hook)" "$hook_state"
fi

# Env files: seed when absent, otherwise compare key names only. A value never
# reaches stdout, so this is safe to run and paste anywhere.
for example in "$HERE"/*.env.example; do
  [[ -f "$example" ]] || continue
  base="$(basename "$example" .example)"
  live="$ENV_DIR/$base"
  if [[ ! -f "$live" ]]; then
    drift=1
    if (( CHECK_ONLY )); then
      say "$live" "MISSING (seed from $(basename "$example"), then fill secrets)"
    else
      install -D -m 600 "$example" "$live"
      say "$live" "seeded — FILL THE SECRETS before starting"
    fi
    continue
  fi
  missing="$(comm -23 \
    <(grep -oE '^[A-Z_]+=' "$example" | tr -d '=' | sort -u) \
    <(grep -oE '^[A-Z_]+=' "$live"    | tr -d '=' | sort -u) | tr '\n' ' ')"
  if [[ -n "${missing// }" ]]; then
    drift=1
    say "$live" "missing keys: $missing"
  else
    say "$live" "all template keys present"
  fi
done

if (( CHECK_ONLY )); then
  (( drift )) && { echo; echo "drift found — run without --check to install"; exit 1; }
  echo; echo "host matches the repository"
  exit 0
fi

systemctl daemon-reload
echo
echo "== verify"
for s in meridian-appserver meridian-iroh-wrapper meridian-stall-watchdog; do
  say "$s" "$(systemctl is-active "$s" 2>/dev/null || true)"
done
# The boot line is the only proof the runtime patches actually applied; a lower
# applied count means an anchor drifted and that patch is silently inert.
grep -a "lcp-patches applied" /var/log/meridian-appserver.log 2>/dev/null | tail -1 || true
timeout 30 node "$LIB_DIR/appserver-probe.cjs" 2>&1 | tail -1 || true
# The shim's shebang is `#!/usr/bin/env node`: node must be on the App Server's PATH.
say "node for the meridian shim" "$(command -v node || echo "NOT ON PATH — the shim cannot start")"
# jna0o.5: the endpoint exists only when agent-tools-mode puts some agent on cli. Outside an agent's shell
# call the live-call binding answers "denied" (exit 3), which still proves it is serving; exit 4
# means nothing answered.
if [[ -S /run/meridian/tools.sock ]]; then
  rc=0; timeout 10 "$SHIM_DST" --help >/dev/null 2>&1 </dev/null || rc=$?
  say "meridian tools endpoint" "$( (( rc == 0 || rc == 3 )) && echo serving || echo "socket present, no answer (exit $rc)")"
else
  say "meridian tools endpoint" "off (served only when LETTA_AGENT_TOOLS_MODE or an override is cli)"
fi
