# Repository cache and paging policy

**Status:** Current policy and implementation map; audited 2026-10-18. Replaces the obsolete pre-extraction endpoint/Room inventory.

## Current implementation

| Data | Owner and persistence | Paging / transport |
|---|---|---|
| Agents | Shared `CachedAgentRepository` with injected `AgentLocalCache`; Android `AgentRepository` supplies gated Room cache. | Refresh fetches bounded pages; Iroh/channel source serves production. The old blanket `listAgents(limit=1000)` description is obsolete. |
| All conversations | Shared `CachedAllConversationsRepository` with optional `AllConversationsLocalCache`; Android binder supplies `RoomAllConversationsLocalCache`. | Shared cursor/hasMore/refresh logic; Android `ConversationPagingSource` and `Pager` binding. |
| Per-agent conversations | Android `ConversationRepository` currently owns Room `ConversationDao`, refresh metadata, optimistic cache and rollback. | Channel-backed `IrohAdminRpcConversationListSource`; refresh/cache mutexes and pushed updates. This remaining Android ownership is visible debt, not a claim that consolidation is complete. |
| Pending locals and timeline | Shared gateway/canonical contracts; platform storage bindings persist scoped pending/confirmed data. | Bounded history/reconcile and canonical paging; stream overlay is separate from settled rows. |
| Project work | Shared `CachedProjectWorkRepository`, Android binding. | Production non-App-Server route is absent; DTOs/fake-source tests do not establish a live Project Work API. See [project work contract](project-work-api-android-contract.md). |

Source authority: [CachedAgentRepository](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/repository/CachedAgentRepository.kt), [CachedAllConversationsRepository](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/repository/CachedAllConversationsRepository.kt), [ConversationRepository](../../android-compose/core/android-data/src/main/java/com/letta/mobile/data/repository/ConversationRepository.kt), [AllConversationsRepository](../../android-compose/core/android-data/src/main/java/com/letta/mobile/data/repository/AllConversationsRepository.kt) and [ConversationDao](../../android-compose/core/android-data/src/main/java/com/letta/mobile/data/local/ConversationDao.kt).

Room is an Android cache binding, not the portable persistence authority for all clients. Session cache gating/clearing must complete before reading a new session's rows; durable timeline/local-runtime state has its own scoped stores. A cache being stored in Room alone does not guarantee that it survives a backend/session change.

## Rules for new or changed repositories

- Keep portable refresh, TTL, retry, cursor, mapping and cache logic in sharedLogic. Supply platform storage/network bindings through interfaces.
- Use bounded cursor/offset pages for growing collections. Do not fetch a huge page simply to count or paint the first screen. Detect repeated cursors/no progress.
- Track initialized freshness explicitly: a successful empty response is fresh. Scope freshness to the backend and filter/agent key of its data.
- Coalesce concurrent refresh for the same scope. Preserve cancellation; keep stale cached data on network failure and expose the failure to the caller.
- Expose unknown/approximate counts when there is no exact supported count contract.
- Apply push/mutation changes without dropping loaded pages; reconcile optimistic state or roll it back on failure.
- Keep UI paging/projection separate from the canonical writer. A view disappearing must not retire durable work.

These are policy requirements, not assertions that every legacy repository already implements each one. The old tables for REST-only tools, identities, schedules and admin endpoints were removed because the REST backend hard cut made that inventory misleading.

## Verification

Inspect the exact repository and its transport source before adding an endpoint or changing a count. Tests for shared cached repositories live under [sharedLogic tests](../../android-compose/sharedLogic/src/commonTest/kotlin/com/letta/mobile/data/repository/); Android Room/session integration lives under [Android data tests](../../android-compose/core/android-data/src/test/java/com/letta/mobile/data/). For stream/history changes also use the [boundary matrix](chat-boundary-regression-harness.md).

A useful scoped audit from the repository root is:

```bash
rg -n 'limit\s*=\s*(1000|10000)|MutableStateFlow<.*List' android-compose/sharedLogic/src android-compose/core/android-data/src/main
```
