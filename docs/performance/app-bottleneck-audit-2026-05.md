# App performance bottleneck audit — 2026-05

This audit separates perceived slowness into server/network latency, client-side data loading, Compose rendering, and unnecessary repeated work. It is a pragmatic follow-up list, not a benchmark report; use the performance gate and JankStats docs for measured release tracking.

## Summary

The highest-confidence bottlenecks are not broad Compose issues. They are avoidable startup/data-loading work and large chat rendering/orchestration surfaces:

1. **Dashboard startup overfetch** — fixed in `letta-mobile-cgjj`.
2. **Chat orchestration density** — next workstream should thin `AdminChatViewModel` so stream/timeline state changes are easier to reason about and measure.
3. **Streaming chat rendering** — existing split-render/graphics-layer work is directionally correct; keep high-cadence streaming tails out of expensive markdown rendering.
4. **Network-bound screens need first-page/cached summaries** — avoid exact global counts or full-list fetches on initial screen load unless the UX truly needs exactness.

## Findings

### 1. Dashboard startup conversation count overfetch — fixed

**Before:** `DashboardViewModel.loadProgressively()` called `AllConversationsRepository.countConversations()`, which fetched `listConversations(limit = 10000)` solely to compute a stat tile.

**Impact:** Accounts with large conversation history paid a large network and parsing cost during dashboard load before the first useful page was needed.

**Fix:** `letta-mobile-cgjj` changed the dashboard to refresh one normal conversations page and display either an exact count when no more pages exist or a lower-bound count such as `50+` when pagination says more exist. The legacy count helper is now bounded to `PAGE_SIZE`.

**Follow-up:** If Letta adds a real count endpoint, route the dashboard stat to that endpoint and keep first-page refresh independent.

### 2. Chat stream latency is mostly server/gateway-bound, but client state work is dense

**Observed architecture:**

- `bot` owns gateway/WebSocket behavior.
- `sharedLogic` timeline owns timeline ingestion and reconcile.
- `app/ui/screens/chat/AdminChatViewModel.kt` still coordinates route resolution, Client Mode send orchestration, timeline observation, notification flags, composer gates, project context, reset, and search.

**Risk:** Even when the slow part is upstream model/tool execution, dense VM orchestration makes it difficult to distinguish real server latency from client-side state churn or repeated observer work.

**Safe next improvement:** Extract a timeline observer/state projector and Client Mode send/session coordinator from `AdminChatViewModel`, preserving the app↔bot↔timeline contract tests from `docs/architecture/chat-boundary-regression-harness.md`.

### 3. Streaming markdown/rendering can still become expensive under high cadence

**Existing mitigations:**

- `ChatMessageList` has comments and logic around avoiding full remeasure during streaming.
- Prior streaming-markdown work established that high-cadence active tails should avoid expensive full markdown rendering.
- `MessageContentFactory` and related tests protect stable content parsing.
- Chat pinch-to-zoom is visual-realtime but layout-deferred: a GPU layer scales during the gesture, then one real typography reflow commits on lift. See `docs/performance/chat-pinch-zoom.md`.

**Risk:** Reintroducing full markdown parsing/rendering for every tail delta can make server-side latency feel like UI jank.

**Rule:** Keep committed markdown blocks append-only and keyed; keep active streaming tails cheap until content stabilizes.

### 4. First-page and cached summaries beat exact global counts

**Pattern:** Dashboard conversations were the obvious case, but the same rule applies elsewhere:

- Prefer first-page-derived summaries for initial screen paint.
- Prefer cached names/counts when the exact value is not action-critical.
- Fetch exact totals lazily or only on screens where totals are central to the task.

**Existing good examples:**

- `DashboardViewModel` now refreshes a bounded conversations page.
- Agent/tool/block counts use dedicated repository count helpers instead of inferring from full local lists.

### 5. Large files obscure performance ownership

Large files are not automatically slow, but they make performance regressions harder to isolate:

- `AdminChatViewModel.kt` is the highest-value target because it owns chat state changes that users perceive immediately.
- Large Compose screens (`HomeScreen`, `AgentScaffold`, admin/list screens) should extract state-specific sections and dialog bodies as they are touched.

This overlaps with `letta-mobile-8e81` and should be treated as performance maintainability, not just style cleanup.

## Quick wins completed

- Dashboard conversation stat no longer performs a 10,000-row fetch on startup (`letta-mobile-cgjj`).
- Chat boundary contracts are documented and have canonical regression tripwires, making future performance-oriented refactors safer (`letta-mobile-xo1e`, `letta-mobile-azsq`, `letta-mobile-b2s4`).

## Recommended next performance work

1. **Thin `AdminChatViewModel` first**
   - Extract timeline observer/state projection.
   - Extract Client Mode send/session orchestration.
   - Keep the chat-boundary regression matrix green.

2. **Add lightweight timing around chat state projection**
   - Measure timeline emission → UI message projection → state update.
   - Use existing `Telemetry` naming conventions from `docs/observability/conventions.md`.

3. **Audit remaining startup counts/lists**
   - Search for `limit = 10000`, full-list count helpers, and dashboard/startup `refresh()` calls.
   - Replace with first-page, cached, or true count endpoints.

4. **Decompose large render surfaces when touched**
   - Prioritize sections that run during streaming or dashboard startup.

## How to verify future changes

- Startup/data-loading changes: run targeted ViewModel tests and the performance gate when startup could be affected.
- Chat rendering/state changes: run the canonical matrix in `docs/architecture/chat-boundary-regression-harness.md`.
- Compose rendering changes: add focused unit/render tests for content factories and avoid relying only on screenshots.
