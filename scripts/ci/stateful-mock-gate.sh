#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCAN_ROOT="$ROOT/android-compose"

# Stateful concrete collaborators retain flows, caches, transports, database
# handles, or coroutine scopes. Mocking them makes tests order-dependent and
# blocks safe Gradle project parallelism. Mock their interface or use the
# corresponding Fake* from :core:testutil instead.
STATEFUL_TYPES='TimelineRepository|SettingsRepository|AgentRepository|ConversationRepository|ChannelTransport|NotificationReplyHandler|MessageRepository|ClientModeController|NotificationDelivery'
PATTERN="mockk[[:space:]]*<[[:space:]]*([[:alnum:]_]+\\.)*(${STATEFUL_TYPES})([[:space:]>?,]|$)"

matches="$(
  cd "$ROOT"
  grep -rEnH --include='*Test.kt' "$PATTERN" android-compose 2>/dev/null || true
)"

violations=0
while IFS=: read -r file line source; do
  [[ -n "$file" ]] || continue
  file="${file//\\//}"
  file_path="$ROOT/$file"

  # File/class suppression is for tests intentionally exercising a concrete
  # implementation. Prefer a nearby reason comment for isolated exceptions.
  if grep -Eq '@Suppress\([^)]*"StatefulMockGate"' "$file_path"; then
    continue
  fi

  start=$((line > 2 ? line - 2 : 1))
  if sed -n "${start},${line}p" "$file_path" |
      grep -Eq 'mockk-gate-allow:[[:space:]]*[^[:space:]]'; then
    continue
  fi

  if ((violations == 0)); then
    echo "Stateful mock isolation violations:" >&2
  fi
  echo "  ${file}:${line}: ${source}" >&2
  echo "    Use the repository interface or a Fake* test double; see letta-mobile-0dnn.8." >&2
  violations=$((violations + 1))
done <<<"$matches"

if ((violations > 0)); then
  echo "stateful-mock-gate: ${violations} violation(s)" >&2
  exit 1
fi

echo "stateful-mock-gate: PASS"
