# Timeline REST Snapshot Policy

`TimelineSyncLoop` has two REST snapshot paths: full hydrate and recent-message reconcile. Neither path should compete with a healthy live stream.

## Call-Site Audit

| Call site | Trigger | Policy |
| --- | --- | --- |
| `TimelineRepository.getOrCreate()` -> `TimelineSyncLoop.hydrate()` | Cold-start creation of a conversation loop. Used when the app has no in-memory timeline for that conversation. | Allowed. This is the initial history load before the loop is useful to UI callers. |
| `TimelineRepository.repairExpiredConversationCursor()` -> `TimelineSyncLoop.hydrate(recordConversationCursor = true)` | Shim reports `cursor_expired` for a persisted resume cursor. | Allowed. This is the repair fallback that clears the stale cursor, loads a bounded recent window, then re-establishes the cursor at the hydrate end. |
| `TimelineRepository.reconcileRecentMessages(..., forceRefresh = false)` -> `TimelineSyncLoop.reconcileRecentMessages()` | Open/reopen catch-up and external-run fallback. | Skipped while `streamSubscriberActive` is true so SSE/WS remains the only live writer. |
| `TimelineRepository.reconcileRecentMessages(..., forceRefresh = true)` -> `TimelineSyncLoop.reconcileRecentMessages(forceRefresh = true)` | User-initiated refresh or explicit repair. | Allowed exactly once per call, even if the stream subscriber is active. |
| `TimelineSyncLoop.reconcileAfterSend()` | Locally-initiated REST send completed. | Allowed. This swaps the optimistic local user event for the authoritative server echo by OTID. |
| `TimelineSyncLoop.reconcileExternalTransportSend()` | Admin-shim WS turn completed. | Allowed. This confirms the externally appended local user event after `turn_done`; live WS frame ingest already handled assistant/tool output. |

## Rule

Open/reopen catch-up must not fetch REST snapshots while the stream subscriber is healthy and current. The only live-stream bypass is an explicit user/repair refresh, represented by `forceRefresh = true`.
