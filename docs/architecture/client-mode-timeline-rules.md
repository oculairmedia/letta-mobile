# Client Mode timeline ingestion and reconcile rules

**Status:** Current contract (2026-05-10)

Client Mode uses the lettabot WebSocket gateway, but chat rendering still uses `TimelineRepository` as the source of truth. This document is the single lifecycle contract for gateway-backed USER, ASSISTANT, REASONING, TOOL_CALL, and TOOL_RESULT events. For the cross-module regression matrix that protects this contract, see [`chat-boundary-regression-harness.md`](chat-boundary-regression-harness.md).

## Layers and allowed writes

| Event kind | Allowed writer | Timeline shape |
|---|---|---|
| USER optimistic send | `AdminChatViewModel` may call `TimelineRepository.appendClientModeLocal(...)` only after a conversation id is known. Fresh routes may show a quarantined USER-only bootstrap echo until the gateway returns the id. | `TimelineEvent.Local`, `source = CLIENT_MODE_HARNESS`, `role = USER`, `deliveryState = SENT` |
| ASSISTANT stream chunk | `AdminChatViewModel` adapts `BotStreamChunk` to `ClientModeStreamChunk`; `TimelineRepository.upsertClientModeStreamChunk(...)` owns the write. | Local `messageType = ASSISTANT`, stable `otid = cm-assist-<assistantMessageId>` |
| REASONING stream chunk | Same typed stream reducer path. | Local `messageType = REASONING`, stable `otid = cm-reason-<chunk.uuid or assistantMessageId>`; text lives in `reasoningContent` |
| TOOL_CALL stream chunk | Same typed stream reducer path. | Local `messageType = TOOL_CALL`, stable batch `otid = cm-tool-<frame id>`; structured calls live in `toolCalls` |
| TOOL_RESULT stream chunk | Same typed stream reducer path; it must attach to the existing tool local when possible. | Folded into the owning TOOL_CALL local via `toolReturnContentByCallId`, `toolReturnIsErrorByCallId`, timing, and batch metadata. Standalone TOOL_RESULT locals are temporary only when a result beats its call. |

No normal-operation path may append Client Mode assistant/reasoning/tool UI messages directly to `ChatUiState.messages`. Pre-conversation assistant/reasoning/tool chunks are buffered and replayed into `TimelineRepository` once the gateway returns `conversation_id`.

## Local vs confirmed events

Client Mode locals are distinguished by:

- `TimelineEvent.Local.source == MessageSource.CLIENT_MODE_HARNESS`
- `deliveryState = SENT` because the WebSocket gateway, not the REST send queue, owns delivery
- deterministic `cm-*` otids for assistant/reasoning/tool stream upserts

Confirmed server events are `TimelineEvent.Confirmed` and are produced by `TimelineSyncLoop` from SSE or message hydration. Confirmed events can carry `source = CLIENT_MODE_HARNESS` only after they replace/collapse a Client Mode local; this preserves render-key and merge behavior for gateway-collapsed events.

## Reconcile rules by message type

| Confirmed type | Allowed reconcile behavior | Temporary heuristic? |
|---|---|---|
| USER | Fuzzy-collapse a recent Client Mode USER local with equal envelope-stripped content within `CLIENT_MODE_FUZZY_WINDOW_MS`. Preserve the local position and otid. | Yes. Replace with strict otid matching once gateway/SDK can round-trip otid. |
| ASSISTANT | Fuzzy-collapse a recent Client Mode ASSISTANT local when local and confirmed text are compatible (`equal`, `local startsWith confirmed`, or `confirmed startsWith local`). Later same-server-id deltas replace/merge in place. | Yes, scoped to `CLIENT_MODE_HARNESS`. |
| REASONING | Same as ASSISTANT, using `reasoningContent` when present. | Yes, scoped to `CLIENT_MODE_HARNESS`. |
| TOOL_CALL | Fuzzy-collapse a recent Client Mode TOOL_CALL local when any tool call id (or, as fallback, tool name) matches the confirmed tool call. Tool returns remain attached by call id. | Yes, scoped to `CLIENT_MODE_HARNESS`. |
| TOOL_RESULT | Never renders or reconciles as a standalone bubble. Server-side tool returns attach to the owning confirmed TOOL_CALL by `tool_call_id`; Client Mode tool results attach to the owning local TOOL_CALL by `toolCallId` / batch metadata. | No standalone lifecycle. |
| SYSTEM / ERROR / OTHER | Do not fuzzy-collapse Client Mode locals. SYSTEM and OTHER are dropped by chat projection; ERROR renders as system/error only when confirmed from server. | No. |

## Batch and out-of-order tool rules

- `ClientModeTimelineStreamReducer` owns batch lookup and timing metadata.
- A TOOL_CALL frame records `toolBatchIdByCallId` and `toolStartedAtByCallId` for every call in the batch.
- A TOOL_RESULT frame records `toolCompletedAtByCallId` and result/error maps keyed by `toolCallId`.
- If a result arrives before its batch call frame, the reducer may create a temporary `cm-tool-<callId>` local, then fold and remove it when the batch TOOL_CALL arrives.
- UI execution time for Client Mode locals uses per-call started/completed timestamps when available.

## Current debt

- USER/ASSISTANT/REASONING/TOOL_CALL collapse still uses bounded fuzzy matching because Client Mode cannot yet rely on strict otid round-trip through the gateway and Letta Code SDK.
- `MessageRepository` remains as a stateless HTTP helper for pagination/search/approvals/batches/reset/inspector; it is not a chat stream source of truth.
- When strict otid support lands, replace `collapseClientModeFuzzyMatch` compatibility checks with otid-by-otid replacement and keep these tests as migration safety coverage.
