#!/bin/bash
# SessionStart: in Claude Code cloud sessions, install bd, write the Dolt credentials and sync the
# beads database; then print `bd prime` (everywhere) so the tracker context loads after the sync.
# Never runs `bd migrate`: Emmanuel is the only schema migrator (AGENTS.md → beads).
# Setup output goes to stderr; stdout is reserved for `bd prime`, which becomes session context.
set -euo pipefail

BD_VERSION=1.3.2
DOLT_CREDS_ID=5c1trdut2vlj0his4je1fljdoaqv4i1o44mjntf1n1m10

cloud_setup() {
  if ! bd version 2>/dev/null | grep -q "bd version $BD_VERSION "; then
    echo "session-start: installing bd $BD_VERSION" >&2
    curl -fsSL "https://github.com/gastownhall/beads/releases/download/v$BD_VERSION/beads_${BD_VERSION}_linux_amd64.tar.gz" \
      | tar -xz -C /usr/local/bin bd
  fi

  # Setup scripts can't see environment secrets; session hooks can.
  if [ -z "${DOLT_CREDS_JWK_B64:-}" ]; then
    echo "session-start: DOLT_CREDS_JWK_B64 is not set; skipping beads sync" >&2
    return 0
  fi
  mkdir -p "$HOME/.dolt/creds"
  echo "$DOLT_CREDS_JWK_B64" | base64 -d > "$HOME/.dolt/creds/$DOLT_CREDS_ID.jwk"
  chmod 600 "$HOME/.dolt/creds/$DOLT_CREDS_ID.jwk"
  printf '{"user.creds":"%s","user.name":"oulair","user.email":"emanuvaderland@gmail.com"}\n' \
    "$DOLT_CREDS_ID" > "$HOME/.dolt/config_global.json"

  chmod 700 .beads
  git config beads.role maintainer

  # Bootstrap once per container; resume/clear/compact reuse the synced database.
  if [ ! -d .beads/embeddeddolt ]; then
    # bd rewrites tracked .beads files on bootstrap; put them back if they were clean before.
    local beads_clean=false
    git diff --quiet -- .beads && beads_clean=true
    bd bootstrap --yes >&2
    [ "$beads_clean" = true ] && git checkout -- .beads
  fi
  bd ping >&2
}

cd "${CLAUDE_PROJECT_DIR:-.}"

if [ "${CLAUDE_CODE_REMOTE:-}" = "true" ]; then
  cloud_setup
fi

bd prime
