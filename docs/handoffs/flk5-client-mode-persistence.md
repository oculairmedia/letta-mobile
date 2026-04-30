# flk.5 — Client-Mode Timeline Persistence

**Date:** 2026-04-27
**Branch:** `feat/flk1-keyed-prefix-blocks`
**Reporter:** Emmanuel
**Symptom:** In Client Mode (chat with Meridian via lettabot WS gateway), navigating away from the chat screen and coming back shows an empty conversation. Messages sent/received in this session are lost.

## Diagnosis (confirmed)

`TimelineSyncLoop._state` is the **only store** for Client Mode messages.

- `appendClientModeLocal` (user bubbles) — writes only to `_state.value`. Does NOT call `pendingLocalStore.save(...)` (only image-attached sends do).
- `upsertClientModeLocalAssistantChunk` (assistant bubbles) — writes only to `_state.value`.
- The Letta server is NOT a fallback source for client-mode messages because:
  - The user request goes via WS gateway → Meridian's agent run, not into the user-perspective Letta conversation. So `GET /v1/conversations/<conv>/messages` returns nothing the user sent through client mode (or returns Meridian's run history but not aligned with mobile's "current conversation" id from a client-perspective).
- `TimelineRepository` is `@Singleton`, so within a single process lifetime the cache survives ViewModel destruction. **But process death wipes everything.**

### Logcat evidence (2026-04-27)

Pixel 2 XL `711KPAE0914240`, app pid 23628:
- 6 conversations stuck in `streamSubscriber.idle404` loop (90s backoff).
- Zero `hydrate`, `eventReceived`, `Local`, `appendClientMode` events in 798 lines of recent log.
- Server `POST /v1/conversations/<id>/stream` returns `{"detail":"INVALID_ARGUMENT: No active runs found for this conversation."}` — correctly classified as idle by `streamSubscriber`. Not the bug.
- Server `GET /v1/conversations/<id>/messages?limit=3` returns full message history including Meridian's reasoning, assistant_message, tool_return — but client-mode user requests are not present (they never went through this conv).

### Why native Letta chats are unaffected

For agents the user chats AS (not WITH via gateway), `send()` goes through Letta REST. The user bubble is echoed by the server, `reconcileAfterSend` upgrades the Local to Confirmed, and `hydrate()` on cold start rebuilds the timeline from `GET /messages`. **Server is source of truth.**

For Client Mode, **there is no source of truth**. The mobile in-memory `_state` IS the source of truth, and it dies with the process.

## Fix plan: Path C — snapshot/restore client-mode state

Treat in-memory `_state` as authoritative during process lifetime; snapshot to disk on background, restore on `getOrCreate` cold path.

### Components

1. **New Room entity** `client_mode_timeline_snapshot` keyed by `conversationId`:
   - `conversationId TEXT PRIMARY KEY`
   - `eventsJson TEXT` — serialized `List<TimelineEvent>` (includes both Local and Confirmed; Confirmed retained so we don't lose ones already merged)
   - `liveCursor TEXT NULL`
   - `updatedAt INTEGER`
2. **DAO**: `upsert(conv, json, cursor, ts)`, `load(conv): Snapshot?`, `delete(conv)`.
3. **Snapshot writer hook** in `TimelineSyncLoop`:
   - Write debounced (~500ms) on every `_state` mutation that includes a `CLIENT_MODE_HARNESS` Local. (Skip for purely native conv timelines — they reconcile from server.)
   - Synchronous flush on `ProcessLifecycleOwner` `ON_STOP`.
4. **Restore hook** in `TimelineRepository.getOrCreate`:
   - After `loop.hydrate()` (which may have fetched 0 server msgs for client mode), call `loop.restoreSnapshotIfClientMode()`.
   - Method merges disk snapshot Locals (and Confirmeds the snapshot saw) into `_state`, taking the snapshot as authoritative for CLIENT_MODE_HARNESS-tagged events.
5. **Eviction**: snapshot is cleared once the conv has a confirmed reconcile path (Confirmed events older than 24h, or > 200 events). Stop disk from growing unbounded.

### Out of scope (deferred to flk.6)

- Bidirectional sync between mobile snapshot and a future server-side Client Mode message store.
- Cross-device replay of client-mode history.

## Acceptance criteria

- After force-stopping the app mid-conversation in client mode and reopening, the chat is restored exactly as it appeared before stop (user bubbles + assistant streamed bubbles).
- After `adb shell am force-stop com.letta.mobile && adb shell am start -n com.letta.mobile/.MainActivity`, navigate to the same conv → messages visible.
- No regression in native Letta chat hydration (still uses `GET /messages`).
- No DB growth past 200 events per conv or 24h-old Confirmeds.

## Estimated diff

~150–200 LOC across:
- `core/src/main/java/com/letta/mobile/data/local/ClientModeTimelineSnapshotDao.kt` (new, ~30)
- `core/src/main/java/com/letta/mobile/data/local/ClientModeTimelineSnapshotEntity.kt` (new, ~15)
- `core/src/main/java/com/letta/mobile/data/local/AppDatabase.kt` (migration, +10)
- `core/src/main/java/com/letta/mobile/data/timeline/ClientModeSnapshotStore.kt` (new, ~50)
- `core/src/main/java/com/letta/mobile/data/timeline/TimelineSyncLoop.kt` (snapshot hook + restore, ~30)
- `core/src/main/java/com/letta/mobile/data/timeline/TimelineRepository.kt` (call restore, ~5)
- DI module update (~10)
- Tests (~50)
