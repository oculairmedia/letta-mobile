# External transport timeline ingestion and reconcile rules

**Status:** Current implementation boundaries; audited 2026-10-18. Replaces the May 2026 admin-shim/SSE contract.

## Transport and ownership

Chat's channel is the shared `IChannelTransport`; `WsChatBridge` projects that channel into chat events and owns no timeline state. The production path uses App Server through the session channel (Iroh or a direct WS dial, including local runtime). The old admin-shim mobile WebSocket is not the current backend. `WsChatSendCoordinator` remains an Android adapter; the portable send orchestration is [ChatSendCoordinator](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/chat/send/ChatSendCoordinator.kt).

There are two timeline surfaces in the tree. Do not describe one as the universal source for every host:

| Surface | Responsibility | Current source |
|---|---|---|
| Gateway timeline | Conversation loops, optimistic locals, streaming reducer, hydration/reconcile and snapshot persistence | [TimelineRepository](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/timeline/TimelineRepository.kt), [TimelineSyncLoop](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/timeline/TimelineSyncLoop.kt) |
| Canonical paged timeline | Bounded durable settled ledger and separate live overlay, selection/generation fencing, shared presentation leases | [CanonicalTimelineEngine](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/timeline/CanonicalTimelineEngine.kt), [CanonicalTimelinePresentation](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/timeline/CanonicalTimelinePresentation.kt) |

Android and desktop bind canonical storage/runtime in [AndroidCanonicalTimelineRuntime](../../android-compose/app/src/main/java/com/letta/mobile/di/AndroidCanonicalTimelineRuntime.kt) and [DesktopCanonicalTimelineRuntime](../../android-compose/desktop/src/main/kotlin/com/letta/mobile/desktop/data/DesktopCanonicalTimelineRuntime.kt). The shared chat surface renders its supplied port; a platform's observer is not the semantic authority for the other host.

## Gateway mutations

[TimelineGatewayEvent](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/timeline/TimelineGatewayEvent.kt) declares stream messages, local send/external append, recent/post-send snapshots, delivery-state mutations, post-handler collapse and abandoned-assistant cleanup. `TimelineSyncLoop.processEventQueue()` serializes these events. Hydration and durable snapshot scheduling also have explicit collaborators; the gateway is not a claim that every initialization/write occurs through a stream event.

`TimelineExternalTransportWriter` is the boundary for optimistic external sends and message ingest. `TimelineExternalTransportAppender` and `TimelineStreamReducer` decide the local/confirmed merge. Preserve scoped agent/conversation identity, server IDs, tool call/return relationships and OTIDs; do not append assistant/tool UI rows directly as a competing canonical store.

A frame with a nonnegative `seqId` can be dropped as an exact duplicate by the loop's sequence/type/message key. Forward deltas without a sequence ID must not be deduplicated merely because text repeats: repeated characters are legitimate incremental content.

## Dual ingest and reconcile

The old document said the stream subscriber always applies duplicate frames and relies only on reducer idempotence. Current code actively suppresses dual ingest: `ingestStreamEvent` marks the WS subscription active; `submitStreamEvent` skips subscriber messages while it is active; `clearExternalTransportActive` clears that marker. Reducer identity checks remain a second boundary, not a substitute for transport lifecycle.

Recent reconcile is transport-neutral (`TimelineTransport`), not necessarily REST. [TimelineRecentMessagesReconciler](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/timeline/TimelineRecentMessagesReconciler.kt) skips ordinary refresh while the subscriber is active, single-flights compatible calls, reuses eligible recent resume results, and rejects superseded generations. `forceRefresh=true` permits the active-stream path but does not promise a network call: forced active reconciles are debounced by generation (default four seconds).

For canonical storage, `CanonicalTimelineEngine.ingest` maintains a bounded live overlay; it does not commit stream-only identities to the settled ledger. Sync/reconcile writes settled rows; the separate `publishLive` API also persists explicit terminal `block.records`. This must not be confused with `ingest`, whose streamed synthetic identities stay in the overlay. Terminal settlement remains visible until durable rows become resident; closing a presentation lease does not retire the writer.

## App Server lifecycle

The App Server transport preserves one socket's arrival sequence through `inboundFrames`. The runtime router/fanout routes by runtime scope and generation. Turn execution is in `AppServerTurnEngine`; tests cover stale generations, concurrency, reconnect, approvals and terminal handling. Do not infer wire semantics from old shim `start`/`turn_done` examples.

The [boundary test matrix](chat-boundary-regression-harness.md) and [snapshot policy](timeline-rest-snapshot-policy.md) name the current regression suites. Historical REST/POC evidence is preserved in [message-sync-migration](message-sync-migration.md) and [POC results](poc-validation-results.md); those reports do not define the current App Server contract.
