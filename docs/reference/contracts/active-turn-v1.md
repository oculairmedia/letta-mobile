# Active turn v1: contract proposal

Status: **PROPOSED for review; contract-only slice of letta-mobile-vbha8.7.1, parent letta-mobile-vbha8.7.**

This proposes one shared owner for foreground turn presence on Android, desktop and
web. This slice supplies serializable values, a pure snapshot arbitration function
and a shared fixture. It supplies no live coordinator, event adapter, persistence,
transport changes or consumer rewiring. Review precedes migration.

The problem is agreement: a terminal can close observed identity while a separately
published busy flag still shows Thinking or Stop. A local second-stop clear can also
settle one projection while another remains busy. These are source-level failure
modes to eliminate; this slice does not claim a reproduced device incident.

## Verified problem and inventory

Audit base: **ee3545123**. Shared implementation searched:
`android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/{data/presence,data/runtime/observer,data/chat/runtime,ui/chat/session}`,
`android-compose/feature-chat/src/main/java/com/letta/mobile/feature/chat/screen`,
`android-compose/desktop/src/main/kotlin/com/letta/mobile/desktop/chat`.

| Existing holder (source symbol) | Writers and readers; boundary today |
| --- | --- |
| `data/presence/ConversationRunRegistry.kt`: `ConversationRunRegistry`, `ConversationRunState` | `ConversationRunPhasePublisher` folds events through shared `RunPhaseReducer`; registry publishes conversation and agent projections. Holds phase, open tool IDs, approval ID, subagents and live detail; lacks exact run/turn identity |
| `data/runtime/observer/ObservedTurnTracker.kt`: `ObservedTurnTracker` | Passive observer records deltas, resolves terminal identity, completes on idle loop and remembers bounded completions. Run/turn identity and round continuation live separately from presence. `resolveTerminal` fills absent identity from active state; this is insufficient authority for a stale callback in the proposed contract |
| `feature-chat/.../screen/AdminChatViewModel.kt`: `interruptRun`, `replacingSendRuntime` | Marks shared presence interrupting before composer cancellation; guards sends and callbacks during runtime replacement. Session/UI streaming/thinking and send queue remain separate projections/lifecycles |
| `desktop/.../chat/DesktopChatController.kt`: `stopActiveRun`, `forceLocalStopClear` | Marks interrupting, invokes shared interrupter; local thinking/streaming conversation IDs and send job are cleared separately. `DesktopChatSessionPort` and `DesktopNowActiveBar` consume these values |
| `ui/chat/session/TimelineChatSessionMapping.kt`: `TimelineRunState`; `TimelineRunStop.kt`: `TimelineRunStopHost` | Port owns cancellation, local-clear and submitting-approval flags. Shared interrupter callbacks mutate them; mapping suppresses streaming after local clear |

Correction to the parent premise: phase reduction and stop lifecycle are already
shared (`RunPhaseReducer`, `ChatRunInterrupter`), not independent platform algorithms.
The remaining risk is independently published identity and presentation state.
The source establishes competing holders, not a reproduced device failure.
`RunPhaseReducer` resets DONE before folding subsequent frames, whereas
`ObservedTurnTracker` separately fences completed identities. Migration must preserve
that fence through every publication path instead of adding a sixth state holder.

Real action counterpart: `DesktopAppServerChatGateway.abortConversationTurn` resolves
agent ownership, looks up the run and accepts only `success && aborted` from
`AppServerTurnEngine.abort`. Its `submitApproval` resolves the captured control request,
awaits `submitApprovalResponse` acceptance and surfaces rejection. These are existing
transport semantics, not implemented by the fixture. A local clear is no proof that
the server stopped. No fake transport or backend acceptance is claimed in this slice.

## One owner and exact state

The future `ActiveTurnCoordinator` in sharedLogic is session-bound and owns one
immutable `ActiveTurnState` per resolved conversation. It is not scoped to the
currently visible chat: background turns survive focus changes. Trusted session
identity scopes all IDs; switching backend disposes the owner and observers.
A raw `default` conversation ID is ambiguous across agents. Resolve it through the
existing `ConversationReadTarget` with verified agent ownership before indexing or
dispatch. Short action signatures run on that trusted binding (or a uniquely resolved
operation/request); ambiguous ownership rejects rather than borrowing current focus.

| Field | Exact meaning |
| --- | --- |
| `conversationId`, `agentId` | Existing `ConversationId` and `AgentId`; validated ownership, never a roster/focus fallback |
| `runId`, `turnId` | Opaque transport identities, nullable only before establishment. Active candidates require both resolved; adapters must hold ambiguous frames outside presence until correlated |
| `phase` | `Idle`, `Thinking`, `ToolExecuting`, `AwaitingInput`, `Cancelling`. Thinking includes reasoning and assistant token streaming; detailed stream status remains derived from timeline data |
| `activeTool` | Optional tool display name for the turn; never the approval request identity or an authority token |
| `subagentCount` | Nonnegative foreground children of this turn; background dreams remain orthogonal |
| `startedAtEpochMs` | Start timestamp retained across progress and cancellation; null in Idle |

Idle clears tool, children and start time but retains the last closed run/turn IDs
as a fence. Errors/terminal reasons are outcomes, not additional busy phases.
The structure scanner counts exactly **19 types** in `com.letta.mobile.data.presence`
including the four new contract types (cap 50; headroom 31).

Multiple open tools and approval request bindings remain owner bookkeeping: this
snapshot is a projection of their exact set, not permission to drop that set.
A deterministic activeTool projection uses earliest still-open tool by owner event
order, then call ID as tie-breaker. An unresolved approval outranks executing tools.

## Write path and ordering

All mutations and effect reservations serialize through the one owner; publish one
complete immutable value atomically. Reducer work must be pure inside a CAS retry;
reserve effects once, execute outside publication, reconcile using captured identity.
The supplied `arbitrateActiveTurn` handles **already validated snapshots**, not raw
wire messages, timestamps, authorization or effect execution.

- Exact boundaries: only explicit Start introduces a new run/turn pair. Progress
  never starts a turn. Same-identity Start is a duplicate, including after closure.
  A terminal arriving before Start closes that identity so its delayed Start cannot
  resurrect it. A mismatched terminal cannot close the current turn.
- Same conversation and agent are required for every candidate. No fallback to
  focused conversation, most recent run, roster head or a different backend.
- Interrupt enters Cancelling once; progress cannot exit it. A second deliberate
  stop press clears locally. Repeated presses on Idle are no-ops. Only the first
  press dispatches abort; retry delivery of the *same* action is deduplicated by
  an owner operation token, distinct from a second user gesture.
- AwaitingInput outranks Thinking; same-phase progress may refresh tool/child details
  without clearing the pending approval; a validated ApprovalResolved boundary may produce
  ToolExecuting (allow) or Thinking (deny with no remaining tools). Terminal and
  deletion override tools/approvals and clear all busy detail.
- Terminal after terminal is idempotent. The owner retains retired identities and
  deletion tombstones beyond the single snapshot; it must reject retired Start
  even after another turn ran. The pure helper alone cannot detect that history.
- Delete requires verified deletion, clears the exact conversation atomically,
  retires observation and rejects all later callbacks/starts until a new trusted
  lifecycle is established. It never selects a replacement conversation.

Proposed action signatures (design only; no executable action facade in slice 1):

```kotlin
interrupt(conversationId, runId)
submitApproval(conversationId, requestId, answer)
recordTerminal(conversationId, terminalKind)
```

`interrupt` captures the matching turn and operation token before publication;
stale/missing run IDs reject without dispatch. Reuse `ChatRunInterrupter`'s
30,000 ms terminal deadline, measured from first press with an injected clock.
Timeout, unsupported abort or dispatch failure clear locally and retain an outcome;
they never roll back to an unverified Thinking state. Failure of an old attempt
cannot clear a newer turn. A second press is an intentional escape hatch, not a
second abort RPC.

`submitApproval` must resolve requestId in the exact session/conversation/turn,
validate the answer against that request, and reserve one submission. Accepted allow
moves to ToolExecuting; accepted deny recomputes remaining tools. While pending or
rejected, remain AwaitingInput and expose the error separately. Never optimistically
claim execution before transport acceptance. Owner bookkeeping deduplicates retries.

`recordTerminal` is callable only on an observer/action binding that captured the
exact run/turn and session generation. Its short public signature does not grant
permission to look up the current run at callback time. Uncorrelated terminal frames
must be reconciled through an authoritative exact-turn query; they cannot clear a
newer turn by guessing. Finished/Cancelled/Failed end that exact turn; LocalClear
and Deleted record local outcomes without inventing server acknowledgment.

## Read path and recreation

Composer busy/stop state, NowActive, timeline badges and agent presence derive from
one snapshot. Timeline content, draft/queue ownership, typing, loading and errors
remain separate concerns and cannot write phase. Tool text and streaming animation
may read timeline details but may not independently declare a turn busy.

Rotation/window resize retains the owner or restores a session-bound snapshot and
reattaches one observer per exact active turn. Process restoration is evidence to
reconcile, never a command to replay send, abort or approval. Buffer/reconcile terminal
frames across observer handoff before publishing restored busy state; stale session
callbacks are rejected. Unavailable transport is shown separately, not an invented
terminal. Persistence and device recreation acceptance are deferred to migration.

## Versioning, fixture and acceptance

The v1 fixture is
[`active-turn/v1.json`](../../../android-compose/sharedLogic/src/commonTest/resources/contracts/active-turn/v1.json).
Gradle embeds this same JSON into common tests, including targets without filesystem
resource APIs. `LettaJson.wire` decodes; `LettaJson.persisted` round trips snapshots.
Unknown phase/boundary values reject; additive keys follow the shared JSON policy.
This is a versioned fixture path, not a new transport protocol or persisted envelope.

The fixture checks normal start/stream/finish, tools plus approval, streaming stop,
second stop/local clear, terminal-before-start and late old terminal recovery,
conversation deletion, and mismatched conversation/agent/run/turn rejection. Expected
snapshots are literal independent oracles. Replacing arbitration with `current`
must fail a behavioral assertion, not compilation. Live owner history, concurrent
publication, effects, timeout clocks, approval validation and lifecycle reattachment
require slice-2 tests; this slice does not simulate them with permissive stubs.

## MIGRATION (after review; no consumer changes in this PR)

| Parent slice | Change and required evidence |
| --- | --- |
| 1 — this PR | Contract, minimal serializable types, pure arbitration, shared fixture; JVM fixture red/green/revert, strict detekt, generated API, docs and structure checks |
| 2 — shared reducer and owner | Consolidate registry/publisher and observed identity into session-bound coordinator; reuse stop seam. Validate snapshots, retain retired IDs/deletion tombstones, exact request/tool sets, monotonic sequencing, operation deduplication, timeout/effect races and restoration. Add real event/transport mapping tests |
| 3 — shared UI and composer | Project composer, controls and NowActive from owner; remove TimelineRunState cancellation/clear authority and preserve error/loading concerns. Test stop/approval and background focus changes |
| 4 — desktop migration | Controller/session port become projections, remove local busy authority; preserve gateway acceptance checks. Verify resize, reconnect and second stop through real binding |
| 5 — Android migration | ViewModel/coordinator become projections; preserve captured send queue identity. Verify rotation/process restore mid-stream and terminal during observer handoff |

## Review choices

The short terminal action requires a bound exact-identity observer as specified above.
Run/turn IDs may not exist on every transport frame; slice 2 must establish exact
correlation before publishing, rather than infer identity from the current focus.
Snapshot fences alone cannot replace retired-identity history. These are explicit
implementation prerequisites, not guarantees supplied by this contract-only slice.
