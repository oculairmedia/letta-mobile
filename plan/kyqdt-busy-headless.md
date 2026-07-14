# kyqdt-busy-mode: headless two-client gate for the kyqdt live bug

## Goal
Make the kyqdt busy-engine failure reproducible HEADLESSLY (no device, no human)
and produce a structured verdict that names the owner + last-terminal + release-reason,
so we can distinguish the 3 hypotheses (missing-terminal / contention / stale-watchdog)
WITHOUT re-running the failed live gate by hand.

## Background
- PR #875 merged: `activeTurn` now carries `acquiredBy {runId, agentId, conversationId, acquiredAtMs}`,
  `lastTerminal`, and `released {reason, at}`. This is the missing observability.
- Existing headless two-client harness: `scripts/iroh_two_client_hermetic.sh` +
  `app-server-iroh-two-client-probe` CLI command (already covers dial + A->B + B->A + B redial).
- Missing: a "kyqdt-busy" mode that asserts the second-send busy-rejection + captures the new
  telemetry fields from the wrapper log. This is what the headless gate needs.

## Hypotheses the captured telemetry distinguishes
- H1 missing-terminal: acquiredBy.runId == first-sender, lastTerminal == null
  (the engine's view of owner is correct, but the terminal never arrived to release it).
  Fix: release on authoritative terminal from any source.
- H2 contention: acquiredBy.runId == a DIFFERENT runId (a different client/conversation
  holds the slot). Fix: per-conversation keyed mutex.
- H3 stale-watchdog: acquiredBy.runId is from an OLDER session, no release ever fired.
  Fix: same as H1 (release-on-authoritative-terminal subsumes H3).

## Implementation plan (broken into 4 beads, ordered)
1. 0lktu (add flag): --mode=kyqdt-busy on the probe CLI. New round: A sends, BEFORE
   asserting A's terminal, attempt a second send from B against the same conversation.
   Default mode unchanged.
2. 2baxn (assertion): assert the second send (B) is rejected with iroh_turn_engine_busy
   code + 'Iroh App Server turn engine is already busy.' message; assert acquiredBy.runId
   matches the FIRST sender (A); assert lastTerminal is null for a stuck-active case OR set
   for a successful release.
3. qatoy (telemetry capture): --capture-wrapper-logs=PATH flag on the probe + the hermetic
   shell script reads the wrapper log (or stub log in hermetic) and extracts the new #875 fields
   (acquiredBy / lastTerminal / released) into the JSON output. Assert them against the expected
   sequence. This is the core of the headless gate.
4. r0nr1 (runbook): scripts/kyqdt_busy_live_gate.sh (wrapper variant) +
   scripts/kyqdt_busy_hermetic.sh (hermetic variant). Runbook covers the 3 hypotheses,
   pass/fail criteria (release reason = normal_completion; acquiredBy matches first sender;
   lastTerminal set; subsequent send rejected with iroh_turn_engine_busy), how to read the
   JSON + the wrapper-log-captured fields, and the merge gate
   (only merge #866 after this script prints 'kyqdt-busy: PASS' twice -- hermetic + live).

## Worktree
- .letta/worktrees/kyqdt-busy-headless at fe43e5c8c (current main, post-#896).
- Branch: plan/kyqdt-busy-headless.

## Sub-agent guidance
- Use sonnet-4.5 for the implementation; not opus.
- Keep the diff tight: only the probe CLI + the hermetic shell + the runbook + a test.
  Do NOT change the App Server or the wrapper.
- Preserve all existing assertion behavior in the default mode.
- Add a clear, greppable [kyqdt-busy] log prefix for new lines.
