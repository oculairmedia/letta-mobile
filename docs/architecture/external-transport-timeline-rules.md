# External transport timeline ingestion and reconcile rules

**Status:** Current contract (2026-05-25). Supersedes `client-mode-timeline-rules.md`.

The admin-shim mobile WebSocket is the active external transport for chat. Chat rendering uses `TimelineRepository` as the source of truth, with `TimelineSyncLoop` linearizing all timeline mutations through a `TimelineGatewayEvent` queue. This document is the lifecycle contract for external-transport USER, ASSISTANT, REASONING, TOOL_CALL, and TOOL_RESULT events.

For the cross-module regression matrix that protects this contract, see [`chat-boundary-regression-harness.md`](chat-boundary-regression-harness.md).

For why we have this shape and where it is going, see [`architecture-review.md`](../architecture-review.md). External transport is one of the two ancestors of the planned RuntimeEvent outbox; `TimelineGatewayEvent` is the in-memory ancestor of the durable RuntimeEvent stream.

## Vocabulary

| Term | Meaning |
|---|---|
| External transport | A transport for chat that is not the Letta server's own SSE stream. Today, the admin-shim mobile WebSocket. |
| Timeline gateway | The serialized event queue inside `TimelineSyncLoop` that owns all timeline mutations for a single conversation. |
| Timeline projection | The `Flow<Timeline>` produced by reducing a stream of `TimelineGatewayEvent` plus reconcile snapshots. |
| Session graph | The per-config runtime graph owned by `SessionManager.currentGraph`. All session-scoped repositories observe the active graph. |
| Local | A `TimelineEvent.Local` produced by optimistic send / external-transport append / pre-conversation buffering. |
| Confirmed | A `TimelineEvent.Confirmed` produced by SSE or hydration; carries a server message id. |

Do not use: "Client Mode", "bot module owns the gateway", `CLIENT_MODE_HARNESS`, `appendClientModeLocal`, `upsertClientModeStreamChunk`.

## Layers and allowed writes

| Event kind | Allowed writer | Timeline shape |
|---|---|---|
| USER optimistic send | `WsChatSendCoordinator` calls `TimelineExternalTransportWriter.appendExternalTransportLocal(...)` after a conversation id is known. Fresh routes may show a quarantined USER-only bootstrap echo until the gateway returns the id. | `TimelineEvent.Local`, `source = LETTA_SERVER`, `role = USER`, `deliveryState = SENT`, stable `otid` from the WS request envelope |
| ASSISTANT / REASONING / TOOL_CALL / TOOL_RESULT stream chunks | `WsChatSendCoordinator` adapts WS frames into `LettaMessage` and calls `TimelineExternalTransportWriter.ingestExternalTransportMessage(...)`. The writer enqueues a `TimelineGatewayEvent.StreamMessage` for the conversation's `TimelineSyncLoop`. | Local or confirmed shape depends on whether the message has a server id. The unified `TimelineStreamReducer` (see `core/src/main/java/com/letta/mobile/data/timeline/TimelineStreamReducer.kt`) decides upsert vs append. |
| Pre-conversation chunks | While the WS `start` frame has not yet returned a `conversation_id`, `WsChatSendCoordinator` buffers inbound message deltas. When the id arrives, the coordinator replays buffered deltas via `ingestExternalTransportMessage(...)` in order, before allowing new deltas through. | Same as the normal stream chunk path once replayed. |

No normal-operation path may append assistant/reasoning/tool UI messages directly to `ChatUiState.messages`. All such messages enter via `TimelineExternalTransportWriter`, traverse the gateway queue, and reach the UI through the timeline projection.

## TimelineGatewayEvent — the serialization point

`TimelineSyncLoop` exposes mutations only through a `Channel<TimelineGatewayEvent>` consumed by a single `processEventQueue()` worker. The variants today:

| Event | Producer | Purpose |
|---|---|---|
| `StreamMessage` | SSE handler; external transport ingest; reconcile fan-out | Apply a single `LettaMessage` (possibly delta) to the timeline. |
| `LocalSendAppend` | REST send path (`TimelineRepository.sendMessage`) | Append an optimistic USER local before the request returns. |
| `ExternalTransportLocalAppend` | `TimelineExternalTransportWriter.appendExternalTransportLocal` | Append an optimistic USER local from the WS path. |
| `ReconcileAfterSendSnapshot` | REST send path after the synchronous response | Collapse a confirmed USER message into the matching local, attach assistant follow-ups. |
| `RecentMessagesSnapshot` | Reconcile-after-WS-send; hydration repair | Apply a window of server messages to repair gaps. |
| `PostHandlerCollapse` | SSE handler boundary | Run otid collapse after a batch of stream frames. |
| `RetrySend` / `MarkSent` / `MarkFailed` | REST send queue; WS send coordinator | Update delivery state on an existing local. |

All gateway mutations are owned by `processEventQueue()`. No collaborator may write to `Timeline` outside this path during normal operation. The migration target named in the architecture review is to bring the remaining direct writes (locally initiated REST send paths) entirely into the gateway, so the queue is the single mutation surface.

## Local vs confirmed events

Locals are produced by:

- `LocalSendAppend` (REST optimistic send)
- `ExternalTransportLocalAppend` (WS optimistic send)
- The reducer when a stream chunk arrives without a server message id

Confirmeds are produced by:

- SSE message frames with a server id
- Hydration / recent-messages snapshots
- Reconcile-after-send when the synchronous response carries the persisted user/assistant pair

`MessageSource` is currently a single-valued enum (`LETTA_SERVER`). Origin tracking on individual events is intentionally narrow — the architecture review recommends putting origin on the future `RuntimeEvent` envelope, not on `Timeline`, when we generalize. Until then, callers must not rely on `MessageSource` to discriminate transport-of-origin; use the producing call site (`appendExternalTransportLocal` vs `appendLocal`) instead.

## Reconcile rules by message type

Collapse heuristics scoped to external-transport locals:

| Confirmed type | Allowed reconcile behavior | Temporary heuristic? |
|---|---|---|
| USER | Fuzzy-collapse a recent external-transport USER local with equal envelope-stripped content within the external-transport fuzzy window. Preserve local position and otid. | Yes. The architecture review notes that this heuristic should be replaced by strict otid round-tripping once the shim/SDK guarantees otid stability. |
| ASSISTANT | Fuzzy-collapse a recent external-transport ASSISTANT local when local and confirmed text are compatible (`equal`, `local startsWith confirmed`, or `confirmed startsWith local`). Later same-server-id deltas replace/merge in place. | Yes. |
| REASONING | Same as ASSISTANT, using `reasoningContent` when present. | Yes. |
| TOOL_CALL | Fuzzy-collapse a recent external-transport TOOL_CALL local when any tool call id (or, as fallback, tool name) matches the confirmed tool call. Tool returns remain attached by call id. | Yes. |
| TOOL_RESULT | Never renders or reconciles as a standalone bubble. Server-side tool returns attach to the owning confirmed TOOL_CALL by `tool_call_id`; external-transport tool results attach to the owning local TOOL_CALL by `toolCallId` / batch metadata. | No standalone lifecycle. |
| SYSTEM / ERROR / OTHER | Do not fuzzy-collapse external-transport locals. SYSTEM and OTHER are dropped by chat projection; ERROR renders as system/error only when confirmed from the server. | No. |

The fuzzy match implementation lives in `TimelineStreamReducer` (and the `external-transport` matcher recently renamed from `fuzzy collapse stub`, see commit `b8e5c324`).

## Batch and out-of-order tool rules

- `TimelineStreamReducer` owns batch lookup and timing metadata.
- A TOOL_CALL frame records per-call batch identity and start time.
- A TOOL_RESULT frame records completion time and result/error maps keyed by `toolCallId`.
- If a result arrives before its batch call frame, the reducer may create a temporary local keyed on the call id, then fold and remove it when the batch TOOL_CALL arrives.
- UI execution time uses per-call started/completed timestamps when available.

## Turn lifecycle and SSE suppression

External-transport turns advance through:

1. `WsChatSendCoordinator.send(...)` enqueues an `ExternalTransportLocalAppend` and dispatches the WS request.
2. The gateway returns `conversation_id` if this was a fresh route; pre-conversation deltas are replayed via `ingestExternalTransportMessage`.
3. Stream frames arrive (`message`, `tool_call`, `tool_return`, `reasoning`, `approval_request`, `usage_statistics`, `stop_reason`) and the coordinator routes each into the timeline gateway via the writer.
4. `turn_done` (or `error` + `turn_done(status="failed")`) clears the active turn. `WsChatSendCoordinator.clearExternalTransportActive(...)` marks the conversation no longer in an active WS turn so SSE can resume normal duties.
5. `usage_statistics` and `stop_reason` are first-wins per turn on the shim. The coordinator captures once and drops later duplicates with telemetry rather than overwriting.

While a WS turn is active, the SSE stream for that conversation must not double-apply the assistant/tool messages the WS path is already streaming. The current strategy is: SSE handlers downstream of the gateway are idempotent through the reducer's same-server-id upsert path, so a duplicate confirmed frame from SSE while a WS turn is active collapses into the existing entry. Tests for this contract live in the regression harness.

## Current debt

- Fuzzy collapse for USER / ASSISTANT / REASONING / TOOL_CALL still exists because external-transport cannot yet rely on strict otid round-trip through the admin-shim and the Letta Code SDK. When strict otid support lands, replace fuzzy matching with otid-by-otid replacement and keep these tests as migration safety coverage.
- `MessageRepository` remains a stateless HTTP helper for pagination / search / approvals / batches / reset / inspector. It is not a chat stream source of truth.
- The local REST send path still writes to the gateway via direct queue sends rather than through a uniform writer interface like `TimelineExternalTransportWriter`. The architecture review identifies converging the remaining mutation paths into a single named gateway surface as the next consolidation step before the RuntimeEvent outbox lands.
- `ConversationStateHolder` exists as a shadow projection (pure-fold over `Flow<LettaMessage>` using `scan` + `reduceStreamFrame`). It is not authoritative yet. Promotion happens only after replay parity proves byte-equivalent timelines across live and replay paths.

## Forward link to RuntimeEvent

The gateway today is in-memory and per-conversation. The planned RuntimeEvent outbox is the durable, portable, cross-conversation form of this same idea:

```
TimelineGatewayEvent (today, in-memory, per-conv)
  -> RuntimeEvent (planned, durable, outbox-backed)
     -> sharedLogic KMP module
        -> Android + Desktop + future iOS consume the same projection
```

The first proof for RuntimeEvent will not be Koog. It will be: record current `TimelineGatewayEvent` inputs as fixtures, replay through the reducer, assert the same Timeline as the live path. Koog becomes another `RuntimeEvent` producer after that proof lands.
