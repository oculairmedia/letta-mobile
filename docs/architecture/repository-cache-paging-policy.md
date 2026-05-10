# Repository cache and paging policy

This policy is the baseline for `letta-mobile-yoic.3` follow-up work. It is intentionally incremental: do not block urgent bug fixes, but do not add new unbounded list fetches or process-only caches for data that must survive cold start.

## Principles

1. **Room is the source for cross-screen and cold-start data.** If a value is shown by more than one screen, is needed for route/title hydration, or should be visible before the network returns, persist it in Room and expose a Room-backed flow.
2. **Paging is required for high-cardinality collections.** Any collection that can plausibly exceed one screenful or grow with usage must be represented by a `PagingSource`/`Pager` or a bounded cursor flow. Avoid `limit = 1000`/`limit = 10000` fetch-all shortcuts.
3. **Memory state is only for ephemeral state.** `MutableStateFlow` process caches are acceptable for screen UI state, live stream state, transient monitor pages, and data already persisted elsewhere. They are not sufficient as the only source for agents, conversations, titles, or other route-critical metadata.
4. **Counts must be explicit or approximate.** Use real count endpoints when available. If an endpoint lacks count support, expose `unknown`, a loaded-page estimate, or `pageSize+` metadata instead of fetching a huge page just to count.
5. **A cached empty response is still fresh.** Freshness must be based on `lastRefreshAt`/initialized metadata, not `list.isNotEmpty()`. This prevents empty organizations, empty filters, and deleted collections from repeatedly re-fetching.

## Current Room-backed data

| Data | Current state | Policy |
| --- | --- | --- |
| Agents | `AgentEntity`/`AgentDao` exists; `AgentRepository` seeds `_agents` from Room, then refreshes via `listAgents(limit = 1000)` | Keep Room for cold-start agent names and route hydration. Replace fetch-all refresh with paged/offset sync or bounded initial page plus count. |
| Pending local chat messages | `PendingLocalEntity`/`PendingLocalDao` via `RoomPendingLocalStore` | Keep persisted; migration tests must preserve optimistic user content. |
| Bug reports | `BugReportEntity`/`BugReportDao` | Keep persisted; not high-cardinality UI-critical. |
| Conversations | Process-memory only in `ConversationRepository` and `AllConversationsRepository` | Add `ConversationEntity`/`ConversationDao` for list rows, summaries/titles, archive state, agent mapping, updated timestamps, and cold-start route title hydration. |

## Endpoint paging/count audit

| Area | API support | Current repository behavior | Required direction |
| --- | --- | --- | --- |
| Agents | `listAgents(limit, offset, tags)`, `countAgents()` | `AgentPagingSource` exists; `refreshAgents()` still fetches `limit = 1000` into memory/Room | Keep `AgentPagingSource`; replace full refresh with paged sync or bounded first-page cache refresh. |
| Conversations | `listConversations(agentId, limit, after, archiveStatus, summarySearch, order, orderBy)`; no count endpoint in current client | Per-agent `ConversationRepository` calls `listConversations(agentId)` without explicit limit; `AllConversationsRepository` pages `PAGE_SIZE = 50` and `countConversations()` is bounded to one page | Add Room cache and cursor paging. Exact count must remain unavailable/approximate unless the API gains a count endpoint. |
| Messages | Agent/conversation message APIs support `limit`, `before`, `after`, `order`; `MessagePagingSource` exists | `MessageRepository` is stateless for normal operations; inspector/batches use bounded helper calls | Keep stateless/paged. Large inspector or batch views should use cursors before increasing limits. |
| Tools | `listTools(limit, offset)`, `countTools()` | Memory cache plus `listTools(limit, offset)` helper; full refresh currently calls `listTools()` | Add a `ToolPagingSource` before any large all-tools surfaces require full result sets. Memory cache may remain only as a short-lived summary cache. |
| Projects | `listProjects()` catalog | Memory cache with `refreshProjectsIfStale()` | Accept memory cache unless project catalog becomes large or route-critical offline data is needed. Use initialized freshness semantics. |
| Admin collections: archives, folders, providers, groups | Most list APIs support `before/after/limit/order`; folders/groups also have count endpoints | Many admin repositories fetch `limit = 1000` into `MutableStateFlow` | Convert touched high-volume admin screens to cursor paging. Use count endpoints where present; otherwise approximate. |
| Identities | `listIdentities()` has no paging params; count endpoint exists; relationship lists support cursor params | Memory cache for identities; relationship lists use `limit = 1000` | Keep bounded memory only if API truly returns a small organization-scoped set. Use paged relationship lists where screens expose large attachments. |
| Jobs, runs, steps | Params include `limit`, `before`, `after`, ordering/filtering | Monitor repositories keep one process-memory list | Accept memory for monitor pages only when callers pass explicit small limits. Add paging if monitors become scrollable histories. |
| MCP servers/tools | Servers support `limit/offset`; server tools currently no paging params | Memory caches; `fetchAllMcpTools()` loops all servers and fetches every tool | Keep server list bounded/paged. Avoid global `fetchAllMcpTools()` on startup; load per server or background-index with user-visible progress. |
| Passages/folder files/group messages/batch messages | Cursor or limit params exist in most APIs | Several helpers use fixed `limit = 1000` | Treat as detail-only bounded calls today; convert to paging before exposing large scrollable lists or aggregate dashboards. |

## Freshness and refresh semantics

- Repository APIs that expose `refreshIfStale(maxAgeMs)` must be **single-flight**: concurrent callers for the same key share one in-flight refresh or are serialized behind a mutex.
- Freshness metadata should be keyed to the same scope as the data: global lists use one timestamp; per-agent conversation lists use `agentId`; filtered lists include filter keys.
- A refresh completes by atomically writing persisted rows first when possible, then updating process projections. On failure, keep existing cache and surface the error to the initiating caller.
- Standard caller TTLs already in use are 30 seconds for list screens and 60 seconds for chat/project background hydration. Repositories should not hardcode screen-specific TTLs; callers pass TTLs, repositories enforce initialized/stale checks.
- Mutations (`create`, `update`, `archive`, `delete`, `fork`) should update the Room cache and process projection optimistically when safe, then reconcile with the server response. Failed optimistic mutations must roll back to the previous local snapshot.
- Paging refresh should invalidate `PagingSource`s instead of mutating every loaded page manually. Summary/header caches may be updated separately for immediate UI feedback.

## Fetch-all guardrails

New code must not add:

- `limit = 1000` or larger for list screens, counts, dashboard stats, or startup hydration.
- Unparameterized list calls on endpoints that accept `limit`, `after`, `before`, or `offset`.
- Process-only caches for data required during cold start, deep link resolution, or chat route title rendering.

Acceptable exceptions must be documented at the call site and fit one of these categories:

- API contract is known to return a tiny bounded set, and no paging params exist.
- Detail screen requests a deliberately bounded first page with clear truncation/"load more" affordance.
- Test fixture or migration utility code that is not production startup/UI behavior.

Useful local audit command:

```bash
rg -n "limit\s*=\s*(1000|10000)|list[A-Z][A-Za-z]*\([^\n]*\)|MutableStateFlow<.*List" android-compose/core/src/main/java/com/letta/mobile/data android-compose/app/src/main/java/com/letta/mobile/ui
```

## Follow-up test plan

### `letta-mobile-yoic.3.2` — conversation Room cache

- DAO migration test adds `ConversationEntity` without destructive fallback.
- Repository fake-API test emits cached conversations/titles before network refresh completes.
- Empty remote list is persisted with a fresh timestamp and does not loop refreshes.
- Mutations update Room and process flows, then roll back on API failure.
- Chat/session resolver test proves a cold-start conversation title can render from Room before network.

### `letta-mobile-yoic.3.3` — count strategy

- Fake API test proves dashboard/stat count paths do not call `listConversations(limit = 1000/10000)`.
- UI state test covers `unknown`, `loading`, and `loaded estimate` count states.
- If a future API count endpoint is added, add a contract test for that endpoint and keep fallback approximate.

### `letta-mobile-yoic.3.4` / `3.5` — high-volume paging and perf gates

- PagingSource tests cover offset (`Agent`, `Tool`) and cursor (`Conversation`, monitor/history endpoints) next-key behavior, end-of-list detection, filters, and errors.
- Repository TTL tests cover cached-before-network, stale refresh, single-flight concurrent refresh, cached empty responses, and failed refresh preserving cache.
- Performance gate opens chat and conversation list against 1k+ fake conversations and verifies no startup fetch-all and no main-thread list construction bottleneck. If macrobenchmark setup is unavailable, add a repeatable JVM/perf smoke and file a benchmark follow-up.
