# Chat send decomposition evidence

**Status:** Reviewed implementation evidence; source-audited and ratchet-validated 2026-10-21. PR CI and post-merge main evidence are tracked in Beads (`letta-mobile-gjcbd.7`).

Implementation source: `b860a7f686d30e951c316e6bfc51a5c8a9a33dc4` (final #2148 head). Original source: `b090c766bee52e8aa176249446df6ad73f7af702`. This records the shared `commonMain/data/chat/send` production family, recursively, including every new collaborator. Ownership and extension rules live in `sharedLogic/Module.md` and the owning source KDoc.

| Measurement | Original | Final implementation |
| --- | ---: | ---: |
| Coordinator CodeScene LOC | 1285 | 473 |
| Coordinator physical lines | 1925 | 738 |
| Coordinator default Code Health | 5.58 | 9.3 |
| Full production family files | 15 | 23 |
| Full production physical lines | 3278 | 3441 |
| Scorable production family CodeScene LOC | 1820 | 1857 |
| Scorable files | 13 | 21 |
| Unscored files | 2 | 2 |

The facade shrank by 812 CodeScene LOC and 1187 physical lines; aggregate production grew by 37 CodeScene LOC and 163 physical lines. That growth comes from the registry, terminal-policy and scheduling extractions that precede routing (#2144 to #2147: 1858 CodeScene LOC / 3426 physical at #2147's head). The routing change itself (#2148) leaves the family at 1857 CodeScene LOC, one below its base (and 15 physical lines above it), after deleting duplicated helpers it had initially added (an earlier routing candidate measured 1906 / 3485). The acceptance follow-up changes KDoc and evidence, not runtime behavior; its comment-line changes are separate from these pinned implementation measurements. The target of at most 600 coordinator CodeScene LOC and health at least 8 is met. The other 19 scorable files score 10.0; the unchanged `TurnIdentityLifecycle` scores 9.68. Unscored files are not counted as 10.0.

Local default CodeScene review reports eight original complex methods, led by `handleEventLocked` (CC 20), `finishActiveTurn` (15), `handleTurnDoneFrame` (14), and `deltaConversationId`/`sendInternalLocked` (11). The implementation retains one complex-method finding, send preparation/sequencing in `sendInternalLocked` (CC 10), and the existing redial conditional finding. The full per-function findings are retained in [the machine-readable evidence](evidence/chat-send-decomposition.json).

## Measurement provenance

Physical lines come from exact Git source, not a working-tree diff or a hand-written code-line counter. Health scores use local cs 1.0.41 (build 341169b662167b7a7f07c2ecc06fb560ef96c71a, Windows amd64) with the default CodeScene configuration; these are distinct from GitHub/cloud analysis results. Healthy-file review omits numeric LOC, so CodeScene's own parser count is read in an isolated temporary Git repository, using exact Git bytes verified by SHA-256 and a temporary LOC warning threshold of 1. That temporary setting is used only to obtain LOC: customized probe scores are excluded, and no repository configuration, baseline, suppression or production gate was changed. The evidence records each source hash, default score, parser LOC and findings. The same two unscored files (ChatSendUiSink and the top-level LettaWireJson alias) remain outside the scorable aggregate in both snapshots.

## Behavioral and structural evidence

The baseline foreground characterization executed 12 tests: eight exposed the intended background UI failures and four preserved existing policy. Tests reuse the existing fixtures and cover interleaved conversations, local/server aliases, same-turn run promotion, stale/replayed tails, foreign agent/generation rejection, cancellation-safe settlement, queue admission/release, suspension-time and reentrant selection changes, blank new-chat adoption, preparation failures before adoption and after a selection switch, background deltas and global disconnect behavior. The final routing head passed 164 focused `chat.send` and observer JVM tests, strict detekt, guardrail detekt, architecture tests, structure export and module boundaries.

The existing structural contracts remain authoritative: no new package cycle, package allowance, lifecycle scope, mutex, mutable ownership index or forwarding-only interface was added. Constants-only companion namespaces were replaced by private file constants, keeping the package at its existing 50-type cap without moving behavior into a duplicate tree. The acceptance follow-up lowers the coordinator raw-ID allowance from 49 to the measured 1 and removes nine detekt baseline entries for displaced methods; strict detekt and the architecture ratchets pass with the lowered allowances. Remaining allowances keep their existing thresholds.

## Capability boundary review

The decomposition was reviewed against `letta-mobile-i5jdm`. Existing transport, timeline, runtime recording and presentation contracts remain injected capabilities. No policy helper becomes a universal plugin interface, transport-as-plugin framework, new event bus or mandatory Canvas provider. Android and Desktop retain bindings to the same shared implementation; configured KMP and host integration coverage remains with CI.

PRs remain separate and stacked by responsibility. These measurements and local tests do not imply merge authorization or post-merge verification; beads remain open until independent review, complete CI and post-merge main evidence are recorded.
