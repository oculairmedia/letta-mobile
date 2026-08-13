# Data-Efficiency Audit — LettaMobile Android

**Date:** 2026-08-12 (Validated & Updated)
**Scope:** `C:/letta-mobile/android-compose`
**Stacks reviewed:** Ktor `HttpClient` (REST), App Server WebSocket transport, Room cache, repositories, background sync warmup paths.

> **Verification & Audit Summary:**
> - `ContentEncoding` (Q1 & Q4): **False Positives / Invalid**. OkHttp engine's default `BridgeInterceptor` automatically appends `Accept-Encoding: gzip` to HTTP requests and transparently decompresses gzipped response bodies before handing them to Ktor. Standard raster images (PNG, JPG, WebP) are already compressed binary data where gzip adds overhead rather than savings.
> - WebSocket Compression (Q2): **Valid Finding (Syntax & Scope Updated)**. WebSocket compression is omitted (`WebSocketDeflateExtension` appears 0 times). Note that `install(WebSocketDeflateExtension)` is the actual Ktor API and is JVM-only (not usable in KMP `commonMain` code).
> - `limit = 1000` (Q3): **Valid Finding (Count Corrected)**. `limit = 1000` appears across **7 repository files** (14 call sites total), plus 1 ViewModel (`UsageViewModel.kt`) and 1 test file (`ProjectChatCoordinatorTest.kt`) — totaling 9 `.kt` files overall.
> - High & Medium Severity Findings (**H1–H5**, **M1–M4**, **M6**): **Verified & Valid**.
> - Stream Jitter (**M5**): **Valid Finding (File Location Corrected)**. Located at `sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/timeline/TimelineSyncStreamSubscriber.kt:193-199`.
> - Config Dedup (**L3**): **Overstated / Invalid**. `SettingsRepository.kt:85-88` already deduplicates emissions by config `id` via `distinctUntilChanged`.

---

## Quick wins (small diff, large impact)

### Q1. Enable HTTP compression on the admin API client [INVALID / FALSE POSITIVE]
**File:** `core/data/src/main/java/com/letta/mobile/data/api/LettaApiClient.kt:165-220`
*Audit Note:* **False Positive**. `HttpClient(OkHttp)` delegates header and stream decompression to OkHttp's `BridgeInterceptor`. OkHttp automatically adds `Accept-Encoding: gzip` to outgoing requests by default and transparently decompresses gzipped responses before passing the body to Ktor. Manually installing Ktor's `ContentEncoding` plugin on an OkHttp client engine is redundant and can conflict with OkHttp's transparent gzip mechanism.

### Q2. Enable `permessage-deflate` on the App Server WebSocket [VALID]
**File:** `sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/transport/appserver/AppServerWebSocketLimits.kt:42` (`applyAppServerFrameLimits`)
Sets `maxFrameSize` but never installs WebSocket deflate extensions (`WebSocketDeflateExtension` appears 0 times across the repo). Ktor's `HttpClient(CIO)` engine defaults to uncompressed frames — every `stream_delta`/`update_queue`/`agent_list_response` frame over the App Server `/ws` socket is uncompressed text.
**Fix.** On JVM/Desktop `HttpClient(CIO)` call sites:
```kotlin
install(WebSockets) {
    applyAppServerFrameLimits()
    extensions {
        install(WebSocketDeflateExtension)
    }
}
```
*Note:* `WebSocketDeflateExtension` is JVM-specific and cannot be called directly inside KMP `commonMain` code without expect/actual wiring or host-level engine configuration.

### Q3. Replace hardcoded `limit = 1000` with paginated requests [VALID]
Affected repository files (14 total call sites using `limit = 1000` as a "fetch everything" hack):
- `core/data/src/main/java/com/letta/mobile/data/repository/ArchiveRepository.kt:28, 55`
- `core/data/src/main/java/com/letta/mobile/data/repository/BlockRepository.kt:111, 115`
- `core/data/src/main/java/com/letta/mobile/data/repository/FolderRepository.kt:31, 73, 77, 81`
- `core/data/src/main/java/com/letta/mobile/data/repository/GroupRepository.kt:33, 72`
- `core/data/src/main/java/com/letta/mobile/data/repository/IdentityRepository.kt:85, 89`
- `core/data/src/main/java/com/letta/mobile/data/repository/MessageRepository.kt:134, 138`
- `core/data/src/main/java/com/letta/mobile/data/repository/ProviderRepository.kt:28`
*(Also appears in `UsageViewModel.kt:70` and `ProjectChatCoordinatorTest.kt:115`)*

Each fetches up to 1000 records in a single HTTP round-trip. The `*Api` methods already accept `limit`/`offset`, and the project already has `Paging3` infrastructure (`AgentPagingSource`, `ConversationPagingSource`) plus `repeat(MAX_PAGES) { … }` patterns in `IrohAdminRpcAgentSource.listAgents` and `IrohAdminRpcBlockSource.listAllBlocks`. The 1000-limit hack is the most pervasive single source of avoidable bandwidth.

### Q4. Coil `imageHttpClient` is missing compression [INVALID / FALSE POSITIVE]
**File:** `app/src/main/java/com/letta/mobile/LettaApplication.kt:43-66`
*Audit Note:* **False Positive**. OkHttp transparently adds `Accept-Encoding: gzip` for `HttpClient(OkHttp)`. Furthermore, standard image formats (PNG, JPEG, WebP) are already binary compressed; HTTP gzip compression offers no bandwidth reduction for raster images.

---

## High-severity findings

### H1. Conversation list refresh decodes full `Conversation` DTOs [VALID]
`ConversationApi.listConversations` returns the full `Conversation` DTO (incl. `inContextMessageIds`/`isolatedBlockIds` arrays). The conversation-picker UI only needs `id`, `summary`, `lastMessageAt`, `agentId`. The server already supports `?slim=true` for `/v1/agents` (see `AgentApi.listAgentsSlim`), but **no equivalent for `/v1/conversations`**. Add `listConversationsSlim`, or at minimum decode only the picker fields via `JsonObject`. Same JSON bytes, ~80% smaller decoded objects.

### H2. `MessageApi.fetchRecentMessages` over-fetches runs and slices client-side [VALID / CLARIFIED]
**File:** `core/data/src/main/java/com/letta/mobile/data/api/MessageApi.kt:36, 112` (`RUN_TO_MESSAGE_MULTIPLIER = 5`)
The comment admits it: "The Letta API's `limit` counts runs/steps, not messages." The client over-fetches `runLimit = ((messageLimit * 5) / 4).coerceIn(messageLimit, 200)`, then slices the most recent `messageLimit` messages off the front. For a default request of 20 messages, it requests 25 runs (up to a max cap of 200 runs). Each run can contain multiple large messages with tool calls/attachments. Either: request a server-side `message_count` parameter, or ask for a `?fields=id,date,otid,…` projection.

### H3. Agent list refresh fetches the full ~621 KB payload even for picker UIs [VALID]
**File:** `core/data/src/main/java/com/letta/mobile/data/repository/AgentRepository.kt:214-245, 363` (`refreshAgentsLocked`, `observeReconnects`)
The `refreshAgentsLocked()` path always asks the server for the **full** `Agent` DTO (42 fields including `tools`, `sources`, `tool_rules`, `blocks`, `memory`, `secrets`, `message_ids`, `compaction_settings`, `identities`, etc.). The slim `AgentSummary` projection (`AgentApi.kt:57`) is only used by `listAgentSummaries()`. The reconnect observer at line 363 calls `refreshAgents()` on every WS reconnect, re-downloading the full payload. Route the reconnect-driven agent refresh through a tiered strategy: slim summaries first, lazy-load full `Agent` on demand.

### H4. No background-stream budget enforcement [VALID]
**File:** `app/src/main/java/com/letta/mobile/channel/ChatPushService.kt:283-321`
`MAX_BACKGROUND_PERSISTENT_STREAMS = 5` proactively warms 5 persistent SSE stream subscribers (each an unbounded `while (isActive)` loop at `TimelineSyncStreamSubscriber.kt:45`). The idle-404 backoff caps at `STREAM_IDLE_BACKOFF_MAX_MS` (32s), but each stream is still a long-lived connection holding an OkHttp dispatcher slot (`LettaApiClient.kt:164`). On a 4G connection the foreground user's send can still queue behind these 5 streams. Reduce the warmup budget to `currentConversationId + top-2 by recency` (3 slots), and on WS reconnect re-warm in priority order so the visible conversation always wins.

### H5. `AgentRepository.getAgent` always re-fetches remote on cache miss [VALID]
**File:** `core/data/src/main/java/com/letta/mobile/data/repository/AgentRepository.kt:286-306`
The flow emits the cached hit, then unconditionally calls `fetchAgentRemote(id)` even when the cached value was fresh. The fresh-window check that exists on the agent list (`hasFreshAgents`/`lastRefreshAtMillis`, line 267) is not consulted here — every chat open re-issues `GET /v1/agents/{id}` even if we just refreshed the same id 30 seconds ago. Gate the network fetch on the same `hasFreshAgents(maxAgeMs)` check the list path uses.

---

## Medium-severity findings

### M1. Duplicate background work: `ChatPushService` warmup + `ChannelHeartbeatSync` [VALID / CLARIFIED]
**Files:** `app/src/main/java/com/letta/mobile/channel/ChatPushService.kt:283-321`, `app/src/main/java/com/letta/mobile/channel/ChannelHeartbeatScheduler.kt:31` (15-min periodic WorkManager via `ChannelHeartbeatSync.kt:33`)
`ChatPushService` opens the WebSocket and proactively hydrates 5 conversations every time the service starts. Independently, the WorkManager periodic heartbeat fires every 15 minutes and lists the latest 100 conversations via REST (`ChannelHeartbeatSync.kt:33`). (*Note: `ChatPushAlarmReceiver.kt` only triggers `ChatPushService.start()` and does not issue HTTP requests itself*). Route the WorkManager heartbeat through `AllConversationsRepository`'s cached list (`getConversationsPaged`) instead of an independent `conversationApi.listConversations`.

### M2. Block library pages return full `Block.value` text [VALID]
**File:** `core/data/src/main/java/com/letta/mobile/data/repository/IrohAdminRpcBlockSource.kt:131-167`
Paginates `limit=BLOCK_LIST_PAGE_SIZE` until `has_more` is false, but each page returns `Block` objects with `value: String` (the block contents) plus metadata. For 1439 blocks / 2.4 MB total, every library browse potentially transfers many MB of value text when the user only sees labels. Introduce `BlockSummary` / `listBlocksSlim` for the list view; lazy-load full `Block.value` only when the user expands a row.

### M3. `GroupRepository.listGroupMessages` uses `limit = 1000` [VALID]
**File:** `core/data/src/main/java/com/letta/mobile/data/repository/GroupRepository.kt:72`
Same over-fetch pattern as H2. The `MessageApi` has `before`/`after` cursor support — let the UI page.

### M4. `Agent` DTO has 42 fields decoded on every JSON response [VALID]
**File:** `sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/model/Agent.kt:68-117`
The `Agent` data class declares 42 fields including `system`, `message_ids`, `tool_rules`, `secrets`, `managed_group`, `multi_agent_group`, `compaction_settings`, `last_run_completion`, `last_run_duration_ms`, `hidden`, etc. Many are populated by the server on every `/v1/agents` response but only used by the edit-agent screen. With `ignoreUnknownKeys = true` (`LettaApiClient.kt:55`), unknown server-side additions are silently ignored — but known fields are still decoded. A server-side `?fields=` projection or client-side `Json.decodeFromJsonElement` over a smaller `AgentCore` class would shrink both wire bytes and deserialization time.

### M5. `TimelineSyncStreamSubscriber` reconnect storm on network error [VALID]
**File:** `sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/timeline/TimelineSyncStreamSubscriber.kt:193-199`
On any non-idle exception, `runStreamSubscriber` delays fixed `STREAM_BACKOFF_MAX_MS` (8000ms) with no jitter. With 5 persistent streams each on its own loop, when the network drops or server restarts, all streams retry at the exact same millisecond — a thundering-herd reconnect. Add per-conversation jitter (`Random.nextLong(0, STREAM_BACKOFF_MAX_MS)`) to the error catch block, mirroring the silence-timeout path at line 153.

### M6. `AllConversationsRepository` has inconsistent pagination race [VALID]
**File:** `core/data/src/main/java/com/letta/mobile/data/repository/AllConversationsRepository.kt:132-141`
The repo exposes both `getConversationsPaged` (Paging3 with cursor-based `after`) and a manual `loadNextPage()` (offset-based via the same API). `loadNextPage()` is NOT protected by `refreshMutex`, while `refresh()` and `refreshIfStale()` are mutex-protected — concurrent calls to `loadNextPage()` and `refresh()` can race on `currentCursor` and `_conversations`.

---

## Low-severity findings

### L1. `MessageApi` over-fetches regardless of context window state [VALID]
**File:** `core/data/src/main/java/com/letta/mobile/data/api/MessageApi.kt:104-148`
Even when the client only needs 5 recent messages, the over-fetch formula requests `runLimit = 20` (minimum coerced limit), downloading extra runs.

### L2. `AgentDao.getAllOnce` returns the full agent list on cold start [VALID / CLARIFIED]
**File:** `core/data/src/main/java/com/letta/mobile/data/local/AgentDao.kt:14-15` & `AgentRepository.kt:70-79`
On Room init, `AgentRepository.init` launches a coroutine on `repositoryScope` calling `agentDao.getAllOnce()` to load all cached agent entities into memory. A `LIMIT 200` query or lazy loading for the picker would scale better for large agent DBs.

### L3. `SettingsRepository.activeConfigChanges` field gating [INVALID / OVERSTATED]
*Audit Note:* **Overstated**. `SettingsRepository.kt:85-88` already applies `.distinctUntilChanged { old, new -> old.id == new.id }`. Downstream subscribers only receive updates when the active server configuration identity actually changes (not on UI theme or unrelated preference updates).

---

## Architectural concerns

1. **Transport-by-transport payload strategies**: The codebase has three fetch paths (REST `LettaApiClient`, Iroh `admin_rpc`, and App Server WebSocket frames). A single shared projection convention (e.g., `{entity}Slim` DTOs for list views, full `{entity}` only on detail screens) would resolve H1, H3, and M2 systematically.

2. **The `limit=1000` pattern**: 7 repositories use `limit=1000` calls. Standardizing on `cursor`/`after` pagination or shared `Paging3` infrastructure avoids unexpected mega-payload transfers.

3. **Cache-vs-network consistency**: Read-side cache decisions vary across repositories. Introducing a uniform caching policy helper with `hasFresh(maxAgeMs)` would resolve H5 and M1 cleanly.

4. **Background stream budget**: Centralize the 5-stream budget into a single `BackgroundStreamBudget` policy object so warmup, heartbeat, and reconnect paths stay aligned.

---

## Validated Status of Disclaimed Items

- **Image/asset loading (Coil)**: Validated. OkHttp engine automatically negotiates gzip header and decompression. No action needed for Q4.
- **WorkManager constraints**: Validated in `ChannelHeartbeatScheduler.kt:22`. Set to `NetworkType.CONNECTED` (runs on any network including cellular) and `setRequiresBatteryNotLow(true)`.

---

## Action Plan

### Phase 1 — Surgical fixes (1–2 days, no API changes, no new DTOs)

| # | Finding | Task | Files to Change | Effort | Risk |
|---|---------|------|-----------------|--------|------|
| 1.1 | **H5** | Gate `getAgent` network fetch on cache freshness | `AgentRepository.kt:286-306` | S | Low |
| 1.2 | **M6** | Wrap `loadNextPage()` with `refreshMutex` | `AllConversationsRepository.kt:132-137` | S | Low |
| 1.3 | **M5** | Add random jitter to error-path backoff | `TimelineSyncStreamSubscriber.kt:193-199` (sharedLogic commonMain) | S | Low |

#### 1.1 — Fresh-window gate on `AgentRepository.getAgent` (H5)

**Problem:** Every chat open unconditionally calls `fetchAgentRemote(id)` even when the agent was refreshed seconds ago.

**Implementation:**
```kotlin
// AgentRepository.kt — getAgent flow
override fun getAgent(id: AgentId): Flow<Agent> = flow {
    val cached = _agents.value.find { it.id == id }
    if (cached != null) {
        emit(cached)
        // Skip network if the agent list was refreshed recently
        if (hasFreshAgents(SINGLE_AGENT_FRESH_WINDOW_MS)) return@flow
    }
    // ... existing local-source and remote-fetch paths unchanged
}

companion object {
    /** 30 seconds — suppress per-agent GET when the bulk list was just refreshed. */
    private const val SINGLE_AGENT_FRESH_WINDOW_MS = 30_000L
}
```

**Verification:** Unit test: call `getAgent` twice within 30s after a `refreshAgents()`; assert only one remote fetch. Run `:app:testRootDebugUnitTest`.

---

#### 1.2 — Mutex-protect `loadNextPage()` (M6)

**Problem:** `loadNextPage()` reads/writes `currentCursor` and `_hasMore` without locking, racing with `refresh()`.

**Implementation:**
```kotlin
// AllConversationsRepository.kt
override suspend fun loadNextPage() = refreshMutex.withLock {
    if (!_hasMore.value) return@withLock
    val newConversations = fetchPage(after = currentCursor)
    applyLoadedPage(newConversations)
}
```

**Verification:** Existing `AllConversationsRepositoryTest` should continue to pass. Add a concurrent-call test launching `refresh()` + `loadNextPage()` simultaneously and assert no crash or duplicated entries. Run `:app:testRootDebugUnitTest`.

---

#### 1.3 — Add jitter to stream error backoff (M5)

**Problem:** 5 streams all delay a fixed 8000ms on error → thundering-herd reconnect.

**Implementation:**
```kotlin
// TimelineSyncStreamSubscriber.kt — error catch block (line ~198)
} catch (t: Throwable) {
    Telemetry.error(
        "TimelineSync", "streamSubscriber.networkError", t,
        "conversationId" to conversationId,
    )
    val jitteredDelay = STREAM_BACKOFF_MAX_MS + Random.nextLong(STREAM_BACKOFF_MAX_MS)
    delay(jitteredDelay.milliseconds)
}
```

**Verification:** `TimelineSyncStreamSubscriberPolicyTest` — assert that the delay range is `[8000, 16000)` ms. Run `:sharedLogic:allTests`.

---

### Phase 2 — Compression & pagination (3–5 days)

| # | Finding | Task | Files to Change | Effort | Risk |
|---|---------|------|-----------------|--------|------|
| 2.1 | **Q2** | Install `WebSocketDeflateExtension` on CIO WS clients | 8 `install(WebSockets)` call sites (see list below) | M | Medium |
| 2.2 | **Q3** | Replace `limit = 1000` with proper pagination | 7 repository files, 14 call sites | L | Medium |

#### 2.1 — WebSocket frame compression (Q2)

**Problem:** All 8 production `install(WebSockets)` sites run with uncompressed frames.

**Call sites to update (JVM-only):**
1. `appserver-cli/.../AppServerRestartReplayProbe.kt:169`
2. `desktop/.../DesktopChatGateway.kt:82`
3. `desktop/.../DesktopWsChannelTransport.kt:285`
4. `iroh-wrapper-cli/.../AppServerServeIrohCommand.kt:561, 733`
5. `sharedLogic/.../AppServerSmokeRunner.kt:67` (the `:141` site is a test-only raw socket — skip)

**Implementation approach:** Create a JVM-only helper alongside `applyAppServerFrameLimits()`:
```kotlin
// New file or extension in a jvmMain source set
fun WebSockets.Config.applyAppServerDefaults() {
    applyAppServerFrameLimits()
    extensions {
        install(WebSocketDeflateExtension)
    }
}
```
Then replace `applyAppServerFrameLimits()` calls with `applyAppServerDefaults()` at each JVM site.

**Prerequisite:** Verify the Letta App Server backend negotiates `permessage-deflate`. If it doesn't, the extension silently falls back to uncompressed — no harm, but no benefit either.

**Verification:** `AppServerWebSocketClientFrameLimitTest` + integration test with a local App Server. Run `:sharedLogic:allTests` + `:desktop:test`.

---

#### 2.2 — Replace `limit = 1000` across repositories (Q3)

**Problem:** 14 call sites use `limit = 1000` as a "fetch everything" hack.

**Strategy:** Two patterns depending on the use case:

| Pattern | Use When | Example |
|---------|----------|---------|
| **Exhaust-loop** | Admin list that truly needs all records (e.g. archive list for a dropdown) | `repeat(MAX_PAGES) { page -> ... if (page.size < PAGE_SIZE) break }` |
| **Paging3** | UI list with scroll (block library, messages, agents-for-folder) | Existing `ConversationPagingSource` / `AgentPagingSource` pattern |

**Per-file plan:**

| Repository | Call Sites | Recommended Pattern |
|------------|------------|---------------------|
| `ArchiveRepository.kt:28, 55` | `refreshArchives`, `listAgentsForArchive` | Exhaust-loop (admin, typically <100 items) |
| `BlockRepository.kt:111, 115` | `listAllBlocks`, `listAgentsForBlock` | Exhaust-loop (already has Iroh paged path) |
| `FolderRepository.kt:31, 73, 77, 81` | `refreshFolders`, `listAgentsForFolder`, `listFolderPassages`, `listFolderFiles` | Exhaust-loop / Paging3 for passages |
| `GroupRepository.kt:33, 72` | `refreshGroups`, `listGroupMessages` | Exhaust-loop for groups; Paging3 for messages |
| `IdentityRepository.kt:85, 89` | `listAgentsForIdentity`, `listBlocksForIdentity` | Exhaust-loop (admin, small counts) |
| `MessageRepository.kt:134, 138` | `listBatches`, `listBatchMessages` | Exhaust-loop (batch jobs, bounded) |
| `ProviderRepository.kt:28` | `refreshProviders` | Exhaust-loop (admin, <50 providers typical) |

**Shared helper:**
```kotlin
/** Exhaust all pages from a paginated API. */
suspend fun <T> exhaustPages(
    pageSize: Int = 50,
    maxPages: Int = 100,
    fetch: suspend (limit: Int, offset: Int) -> List<T>,
): List<T> {
    val merged = mutableListOf<T>()
    repeat(maxPages) { i ->
        val page = fetch(pageSize, i * pageSize)
        merged += page
        if (page.size < pageSize) return merged
    }
    return merged
}
```

**Verification:** Add a unit test per repository that mocks the API to return exactly 2 pages and asserts both pages are fetched. Run `:app:testRootDebugUnitTest`.

---

### Phase 3 — Slim projections (5–8 days, requires server-side coordination)

| # | Finding | Task | Files to Change | Effort | Risk |
|---|---------|------|-----------------|--------|------|
| 3.1 | **H1** | Add `listConversationsSlim` | `ConversationApi.kt`, `AllConversationsRepository.kt`, server | L | Medium |
| 3.2 | **H3** | Route reconnect refresh through `AgentSummary` | `AgentRepository.kt:353-365` | M | Medium |
| 3.3 | **M2** | Add `BlockSummary` / `listBlocksSlim` | `IrohAdminRpcBlockSource.kt`, `BlockApi.kt`, server | L | Medium |

#### 3.1 — Conversation slim projection (H1)

**Depends on:** Server-side `?slim=true` support on `GET /v1/conversations` (or a `?fields=` param).

**Client-side plan:**
1. Add `ConversationSummary` data class in `sharedLogic/commonMain/.../model/` with `id`, `summary`, `lastMessageAt`, `agentId`.
2. Add `ConversationApi.listConversationsSlim()` calling `GET /v1/conversations?slim=true`.
3. Update `ChannelHeartbeatSync.doWork()` (line 33) to use the slim projection.
4. Update `ChatPushService.warmupSubscribers()` (line 309) to use the slim projection.
5. Keep full `listConversations()` for detail screens.

**Verification:** Measure response body size before/after with OkHttp logging interceptor.

---

#### 3.2 — Reconnect agent refresh via slim projection (H3)

**Problem:** `observeReconnects` at line 363 calls `refreshAgents()` → full 621 KB agent payload on every WS reconnect.

**Implementation:**
```kotlin
// AgentRepository.kt — observeReconnects
private suspend fun observeReconnects(channelTransport: IChannelTransport) {
    var wasConnected: Boolean? = null
    channelTransport.state.collect { state ->
        val nowConnected = state is ChannelTransportState.Connected
        if (wasConnected == false && nowConnected) {
            // Slim refresh: update names/descriptions for picker UIs.
            // Full agent details are lazy-loaded by getAgent() on demand.
            runCatching { refreshAgentSummariesOnReconnect() }
                .onFailure { e -> Log.w("AgentRepository", "reconnect slim refresh failed: ${e.message}") }
        }
        wasConnected = nowConnected
    }
}

private suspend fun refreshAgentSummariesOnReconnect() {
    val summaries = listAgentSummaries()
    // Merge name/description updates into cached full agents without
    // discarding their tools/blocks/memory (which getAgent will lazy-load).
    _agents.update { current ->
        val summaryMap = summaries.associateBy { it.id }
        current.map { agent ->
            summaryMap[agent.id]?.let { slim ->
                agent.copy(name = slim.name, description = slim.description)
            } ?: agent
        }
    }
}
```

**Verification:** Mock the transport to emit a reconnect event. Assert `listAgentsSlim` is called (not `listAgents`). Run `:app:testRootDebugUnitTest`.

---

#### 3.3 — Block slim projection (M2)

**Depends on:** Server-side `?fields=` or `?slim=true` on `GET /v1/blocks` (or Iroh `block.list` response trimming).

**Client-side plan:**
1. Add `BlockSummary` data class with `id`, `label`, `isTemplate`, `createdAt` (omit `value`).
2. Add `BlockApi.listBlocksSlim()` / update `IrohAdminRpcBlockSource` to request slim.
3. Block library list screen uses `BlockSummary`; expand/detail loads full `Block`.

**Verification:** Measure total bytes transferred for a 1000+ block library before/after.

---

### Phase 4 — Background budget & dedup (3–5 days)

| # | Finding | Task | Files to Change | Effort | Risk |
|---|---------|------|-----------------|--------|------|
| 4.1 | **H4** | Reduce stream warmup budget from 5 → 3 | `ChatPushService.kt:384` | S | Medium |
| 4.2 | **M1** | Route heartbeat through cached conversation list | `ChannelHeartbeatSync.kt:33` | M | Low |
| 4.3 | **H4** | Extract `BackgroundStreamBudget` policy object | New file + `ChatPushService.kt` | M | Low |

#### 4.1 — Reduce warmup budget (H4)

**Implementation:**
```kotlin
// ChatPushService.kt
private const val MAX_BACKGROUND_PERSISTENT_STREAMS = 3
```
Budget: current conversation (slot 1) + top 2 by recency (slots 2–3). The current code already prioritizes `currentConversationId` at line 315.

**Verification:** Telemetry: monitor `warmup.plan.budget` metric drop from 5 → 3. Verify foreground send latency does not regress.

---

#### 4.2 — Deduplicate heartbeat conversation fetch (M1)

**Problem:** `ChannelHeartbeatSync.doWork()` calls `conversationApi.listConversations(limit=100)` independently of what `ChatPushService` already fetched.

**Implementation:** Inject `AllConversationsRepository` into `ChannelHeartbeatSync` and use its cached list:
```kotlin
// ChannelHeartbeatSync.kt
val conversations = if (allConversationsRepository.hasFreshConversations(maxAgeMs = 60_000)) {
    allConversationsRepository.conversations.value
} else {
    allConversationsRepository.refresh()
    allConversationsRepository.conversations.value
}
```

**Verification:** Assert no direct `conversationApi.listConversations` call in `ChannelHeartbeatSync` after refactor. Run `ChannelHeartbeatSyncTest`.

---

#### 4.3 — Extract `BackgroundStreamBudget` (H4)

**Implementation:** Single source-of-truth policy object:
```kotlin
// New: sharedLogic/.../BackgroundStreamBudget.kt
object BackgroundStreamBudget {
    const val MAX_WARM_STREAMS = 3
    const val PRIORITY_CURRENT = 0
    const val PRIORITY_RECENT = 1

    fun allocate(
        currentConversationId: String?,
        recentConversationIds: List<String>,
    ): List<String> = buildList {
        currentConversationId?.let { add(it) }
        recentConversationIds.forEach { id ->
            if (id !in this) add(id)
        }
    }.take(MAX_WARM_STREAMS)
}
```

Replace `ChatPushService.warmupSubscribers()` and any future callers to use `BackgroundStreamBudget.allocate()`.

**Verification:** Unit test the allocation logic. Run `:sharedLogic:allTests`.

---

### Phase Summary

| Phase | Scope | Effort | Dependencies |
|-------|-------|--------|--------------|
| **1 — Surgical fixes** | H5, M5, M6 | 1–2 days | None |
| **2 — Compression & pagination** | Q2, Q3 | 3–5 days | Phase 1 (recommended, not required) |
| **3 — Slim projections** | H1, H3, M2 | 5–8 days | Server-side slim endpoint support |
| **4 — Background budget** | H4, M1 | 3–5 days | Phase 1 (recommended, not required) |

**Total estimated effort:** 12–20 engineering days

**Items requiring no action (closed):**
- ~~Q1~~ — OkHttp handles gzip transparently
- ~~Q4~~ — OkHttp handles gzip transparently; images are pre-compressed
- ~~L3~~ — `activeConfigChanges` already deduplicates by config identity

---

## Phase 1 — Landed Findings

The following Phase 1 items have been implemented (see branch
`data-efficiency/phase-1-surgical-fixes`):

| # | Finding | Commit | What changed |
|---|---------|--------|--------------|
| **1.1** | **H5** | (Phase 1 PR) | `AgentRepository.getAgent` skips `fetchAgentRemote` when `hasFreshAgents(SINGLE_AGENT_FRESH_WINDOW_MS = 30_000L)` is true. Added a `@VisibleForTesting setLastRefreshForTest` seam + 2 unit tests. |
| **1.2** | **M6** | (Phase 1 PR) | `AllConversationsRepository.loadNextPage` now wraps its body in `refreshMutex.withLock { … }`, matching the existing `refresh()` lock discipline. Added 1 concurrent-call unit test. |
| **1.3** | **M5** | (Phase 1 PR) | `TimelineSyncStreamSubscriber` error-path backoff now uses a new `streamErrorBackoffMs()` helper that returns `[STREAM_BACKOFF_MAX_MS, 2 * STREAM_BACKOFF_MAX_MS)`. Added `TimelineSyncStreamSubscriberBackoffTest` with 3 jitter-range assertions. |

**Phase 1 table update** (rows marked complete above the action plan):

| # | Finding | Task | Status |
|---|---------|------|--------|
| 1.1 | **H5** | Gate `getAgent` network fetch on cache freshness | ✅ Landed |
| 1.2 | **M6** | Wrap `loadNextPage()` with `refreshMutex` | ✅ Landed |
| 1.3 | **M5** | Add random jitter to error-path backoff | ✅ Landed |

**Items still open in Phases 2–4:** Q2, Q3, H1, H3, M2, H4, M1.
