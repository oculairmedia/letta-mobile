# Timeline history snapshot and reconcile policy

**Status:** Current implementation reference; audited 2026-10-18. The historical filename is retained for existing links; snapshots now use a transport-neutral API.

`TimelineSyncLoop` receives a `TimelineTransport`. The backing history request can be App Server/admin RPC; a snapshot must not be called REST merely because it originated in a REST-era helper.

| Path | Current policy |
|---|---|
| `TimelineRepository.getOrCreate` / `hydrateSingleFlight` | Creates/reuses a scoped loop and shares an in-flight hydrate. One caller performs the hydrate while concurrent callers await the same loop-specific flight; cancelling the owner cancels that flight. This is single-flight coordination, not an independent loop-owned background job. |
| `repairExpiredConversationCursorScoped` | Repairs an expired resume cursor using `hydrate(recordConversationCursor=true, fallbackCursorSeq=...)`. Preserve backend/agent/conversation scope. |
| `reconcileRecentMessages(forceRefresh=false)` | Skips while `streamSubscriberActive` is true. Eligible resume requests may reuse a recent successful result. Compatible concurrent calls share a flight. |
| `reconcileRecentMessages(forceRefresh=true)` | Allows a request while the subscriber is active; it is still coalesced/debounced and generation-fenced. It is not guaranteed to fetch exactly once per call. |
| Gateway post-send/external-send helpers | Reconcile authoritative user/assistant history through the timeline transport; retain optimistic identity and do not replace fresh live state with a stale generation's snapshot. |
| Canonical paged timeline | Shared semantic writer merges sync/reconcile records into the durable settled ledger; streamed live overlay is separate and bounded. |

`TimelineRecentMessagesReconciler` defaults the forced-active debounce to 4,000 ms and rejects older/superseded positive connection generations. Its freshness sequence invalidates otherwise reusable resume results. Outcome is `Applied`, `Skipped` or `Failed`; callers must preserve that distinction.

The implementation authority is [TimelineRepository](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/timeline/TimelineRepository.kt), [TimelineRecentMessagesReconciler](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/timeline/TimelineRecentMessagesReconciler.kt), [TimelineExternalTransportAppender](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/timeline/TimelineExternalTransportAppender.kt) and [CanonicalTimelineEngine](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/timeline/CanonicalTimelineEngine.kt). See the [boundary suites](chat-boundary-regression-harness.md) for test selection.
