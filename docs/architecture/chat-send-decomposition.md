# Chat send decomposition evidence

Implementation source: `70d1a0c684e6a74aed3b68df61c8311b504de665`. Original source: `b090c766bee52e8aa176249446df6ad73f7af702`. This records the shared `commonMain/data/chat/send` production family, recursively, including every new collaborator. Ownership and extension rules live in `sharedLogic/Module.md` and the owning source KDoc.

| Measurement | Original | Routing implementation |
| --- | ---: | ---: |
| Coordinator CodeScene LOC | 1285 | 498 |
| Coordinator physical lines | 1925 | 765 |
| Coordinator default Code Health | 5.58 | 9.38 |
| Full production family files | 15 | 23 |
| Full production physical lines | 3278 | 3485 |
| Scorable production family CodeScene LOC | 1820 | 1906 |
| Scorable files | 13 | 21 |
| Unscored files | 2 | 2 |

The facade shrank by 787 CodeScene LOC and 1160 physical lines; aggregate production grew by 86 CodeScene LOC and 207 physical lines. The acceptance follow-up changes KDoc and evidence, rather than runtime behavior; its physical comment-line changes are separate from these pinned implementation measurements. The target of at most 600 coordinator CodeScene LOC and health at least 8 is met. The other 19 scorable files score 10.0; the unchanged `TurnIdentityLifecycle` scores 9.68. Unscored files are not counted as 10.0.

Default CodeScene review reports eight original complex methods, led by `handleEventLocked` (CC 20), `finishActiveTurn` (15), `handleTurnDoneFrame` (14), and `deltaConversationId`/`sendInternalLocked` (11). The implementation retains one complex-method finding, send preparation/sequencing in `sendInternalLocked` (CC 10), and the existing redial conditional finding. The full per-function findings are retained in [the machine-readable evidence](evidence/chat-send-decomposition.json).

## Measurement provenance

Physical lines come from exact Git source, rather than a working-tree diff or a hand-written code-line counter. Health scores use the default CodeScene configuration. Healthy-file review omits numeric LOC; the independent read-only audit exposed CodeScene's own parser count in an isolated temporary Git repository, using exact Git bytes verified by SHA-256 and a temporary LOC warning threshold of 1. That temporary setting was used only to obtain LOC: customized probe scores are excluded, and no repository configuration, baseline, suppression or production gate was changed. The evidence records each source hash, default score, parser LOC and findings. The same two unscored files (ChatSendUiSink and the top-level LettaWireJson alias) remain outside the scorable aggregate in both snapshots.

## Behavioral and structural evidence

The baseline foreground characterization executed 12 tests: eight exposed the intended background UI failures and four preserved existing policy. The integrated pre-observer-recheck candidate `620aa9f37` passed 143 focused JVM tests, nine architecture tests, strict detekt, guardrail detekt, structure export and module boundaries. Tests reuse the existing fixtures and cover interleaved conversations, local/server aliases, same-turn run promotion, stale/replayed tails, foreign agent/generation rejection, cancellation-safe settlement, queue admission/release, suspension-time selection changes, blank new-chat adoption, preparation failures and global disconnect behavior. The final observer regression additionally exercises a route change published synchronously by the selection callback before presentation observation begins.

The existing structural contracts remain authoritative: no new package cycle, package allowance, lifecycle scope, mutex, mutable ownership index or forwarding-only interface was added. Constants-only companion namespaces were replaced by private file constants, retaining the package type cap without moving behavior into a duplicate tree. The final routing package has 50 types, within its existing cap. The acceptance follow-up lowers the coordinator raw-ID allowance from 49 to the measured 2 and removes obsolete detekt entries for displaced methods; remaining allowances retain their existing thresholds.

## Capability boundary review

The decomposition was reviewed against `letta-mobile-i5jdm`. Existing transport, timeline, runtime recording and presentation contracts remain injected capabilities. No policy helper becomes a universal plugin interface, transport-as-plugin framework, new event bus or mandatory Canvas provider. Android and Desktop retain bindings to the same shared implementation; configured KMP and host integration coverage remains with CI.

PRs remain separate and stacked by responsibility. These measurements and local tests do not imply merge authorization or post-merge verification; beads remain open until independent review, complete CI and post-merge main evidence are recorded.
