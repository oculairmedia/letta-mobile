# Mobile Chat Render / Streaming Hot-Path Audit

**Scope:** `android-compose/feature-chat` + `android-compose/designsystem` chat render & streaming pipeline.
**Date:** 2026-06-05
**Auditor:** Hephaestus (subagent)
**Device:** Pixel 9 Pro (caiman), app `0.1.0-285-g174107c6-sideload`
**Prior art:** `IncrementalChatRenderItemsCache` in `ChatRenderModelBuilder.kt` (commit `d07ce5a5`, bead `rmzmo`) — eliminated the O(n)-per-token whole-conversation render-model rebuild (O(n²)/turn). Validated earlier with gfxinfo (janky 13.39%->3.34%, p90/p95 150ms->16-19ms). This audit hunts for the **remaining** members of that class.

## Goal

Find work in HOT PATHS (per streamed token / per frame / per recompose) that scales with **total conversation size** instead of with new/visible data, plus recompose/measure smells.

## Streaming invariant (from rmzmo)

> Per-frame / per-token work should be O(1) or O(new data). Full-history scans belong only in rare reconcile/backfill or one-time index construction.

---

## Measured baseline (this audit)

All captures on Pixel 9 Pro, `dumpsys gfxinfo com.letta.mobile` with `reset` before each window. The device had a real, long, tool-heavy conversation loaded with an active run.

### Scroll (12 synthetic swipes up/down over the conversation)
| metric | value |
|---|---|
| Total frames | 979 |
| Janky (modern) | 5 (0.51%) |
| Janky (legacy) | 500 (51.07%) |
| p50 / p90 / p95 / p99 | 12 / 20 / 22 / 38 ms |
| Slow UI thread | 4 |
| Missed Vsync | 0 |

### Active streaming (passive, 6s window, ~371 frames @ ~62fps)
| metric | value |
|---|---|
| Janky (modern) | 0 (0.00%) |
| Janky (legacy) | 140 (37.74%) |
| p50 / p90 / p95 / p99 | 14 / 16 / 17 / 18 ms |
| Slow UI thread | 0 |
| Missed Vsync | 0 |

### Active streaming (passive, 10s window, ~614 frames)
| metric | value |
|---|---|
| Janky (modern) | 1 (0.16%) |
| p50 / p90 / p95 / p99 | 14 / 16 / 17 / 18 ms |
| Slow UI thread | 0 |
| Frame deadline missed | 1 |

**Interpretation.** The rmzmo-line of fixes (PR #328 render-item reuse, PR #330 tail-only markdown reparse / plain-prose fast path / graphicsLayer rasterization) has **landed and is working**: steady-state streaming and scrolling are now essentially jank-free on this device (p99 18ms streaming, 38ms scroll), a large improvement over the rmzmo.4 baseline (13.39% janky, p90/p95 150ms). The high "legacy janky %" is the legacy 16.67ms-deadline metric on a high-refresh panel and is expected; the modern jank metric is the reliable signal.

Choreographer logged intermittent `Skipped N frames` spikes (418/580/1171/186) tied to navigation/idle transitions, **not** to steady streaming — a fresh 12s streaming-only window logged zero skips. So the remaining risk is not continuous per-token jank but (a) latent O(history) work that has not yet been profiled at extreme history sizes, and (b) eager per-tick scans that are currently cheap only because the cache upstream bounds them.

> NOTE: a true two-finger pinch could not be injected via `adb input` (no multitouch), so `ChatPinch frameBudget` telemetry was not captured live in this session. The pinch-path findings below are from code reading + the existing rmzmo.4 evidence.

---

## Findings

Severity scale: **P1** (active per-token O(history) in a hot path) · **P2** (per-tick scan currently bounded but fragile / latent O(history)) · **P3** (recompose/stability smell, bounded cost).

### F1 — `loadPressureSummary` runs an O(total-conversation) scan on every streaming tick (FIXED in this PR)

- **Location:** `feature-chat/.../screen/ChatMessageList.kt:398-414` (`remember(state.messages, …, renderItems){ … renderItems.pinchVisibleContentSummary().toolCards }`), scan body at `ChatMessageList.kt:98-130`.
- **What scales with what:** `pinchVisibleContentSummary()` walks **every** `ChatRenderItem` and **every** message inside every `RunBlock` — O(total render items + total grouped messages) = O(total conversation).
- **When it fires:** the `remember` is keyed on `state.messages` and `renderItems`, **both of which get a new identity on every streamed token/tick**. So the full-history scan re-runs **per token** during streaming. This is precisely the rmzmo class (per-token work scaling with total conversation).
- **Why it's currently survivable:** the result (`currentLoadPressureSummary`) is consumed in exactly ONE place — `ChatMessageList.kt:554`, inside the **pinch-begin** gesture handler — purely as Telemetry payload. It is never read on the steady-state render path, so the work is pure waste every tick.
- **Measured evidence:** not isolated in this run's percentiles (cost is small at current history length and overlaps the cheap streaming window), but it is unbounded: the same scan was the shape of the original rmzmo O(n²) regression. At the 5k-message history that surfaced the original shim regression this is a per-token full-list walk.
- **Severity:** P1 (matches rmzmo invariant violation: O(history) per token).
- **Fix (applied):** stop computing `toolCardCount` eagerly every tick. Compute the tool-card count **lazily at pinch-begin** (where it is actually consumed) by reusing the already-O(visible) `visibleRenderItems` path, and drop `renderItems` + `state.messages` from the `remember` key so the per-tick summary is O(1). Net: zero per-token full-history work; the count is produced once per pinch gesture instead of once per token.

### F2 — `ChatRenderItem` data classes are not `@Immutable`/`@Stable`, defeating Compose skipping

- **Location:** `feature-chat/.../coordination/MessageGrouping.kt:62` (`Single`) and `:95` (`RunBlock`). `RunBlock.messages: List<Pair<UiMessage, GroupPosition>>` is an unstable type (raw `List`, `Pair`, and `GroupPosition` may be unstable).
- **What scales with what:** these are the item params for every LazyColumn row. Because the classes are inferred **unstable** by the Compose compiler, the per-item composables (`MeasuredChatRenderItem`, `RunBlock`, `RenderChatMessage`) cannot skip on recompose even when the item is unchanged — so every recompose pass re-executes each visible item's body. Cost scales with **visible** items per recompose (bounded), but the recompose frequency is high during streaming.
- **When it fires:** every chat recompose (every streaming tick, every state change).
- **Measured evidence:** steady-state streaming is currently clean (p99 18ms), so the marginal cost is presently absorbed; this is a latent amplifier that makes every other hot-path regression worse and removes the compiler's ability to prune untouched visible rows.
- **Severity:** P3 (stability smell; bounded but broad).
- **Fix (applied, low-risk subset):** annotate the `ChatRenderItem` sealed interface + `Single`/`RunBlock` with `@Immutable`. They are constructed once per render-model build and never mutated; `UiMessage`/`GroupPosition` are value types, so the @Immutable promise holds. This lets Compose skip unchanged visible rows. (Converting `RunBlock.messages` to `ImmutableList` is a larger ripple — left as a follow-up bead.)

### F3 — Whole-`ChatUiState` passed into every LazyColumn item ⇒ wide recomposition on any field change

- **Location:** `feature-chat/.../screen/ChatMessageList.kt` — `state: ChatUiState` is threaded into `MeasuredChatRenderItem` content, `RenderChatMessage` (`:761,:776,:810`), and read inside item bodies (e.g. `state.messages.lastOrNull()?.id` at `:1077`, `state.collapsedRunIds`, `state.expandedReasoningMessageIds`, `state.activeApprovalRequestId`, `state.isStreaming`).
- **What scales with what:** `ChatUiState` is one `@Immutable` object that bundles high-churn fields (`messages`, `isStreaming`, `messageListChange`, `promptTokens`/`completionTokens`/`totalTokens`, `a2uiFrameCount`) with per-item fields. Any change to **any** field yields a new `state` identity, so every visible item that captured `state` recomposes — even when only the token counter changed. Recomposition breadth is O(visible items) but is triggered by unrelated high-frequency fields.
- **When it fires:** every streaming tick and every token/usage update.
- **Measured evidence:** currently masked by the cheap streaming window; this is the residual "reading a frequently-changing State high in the tree causes wide recomposition" smell called out in the audit brief and in rmzmo.4.
- **Severity:** P2 (latent wide recompose; correct fix is a refactor).
- **Fix:** **bead only** (too large for this PR). Pass only the narrow slices each row needs (e.g. `collapsedRunIds`, `expandedReasoningMessageIds`, `activeApprovalRequestId`, a single `isStreamingTail: Boolean`) instead of the whole `state`, or split `ChatUiState` so volatile telemetry/usage fields live in a separate state object that the item tree does not read.

### F4 — `geometryContentFingerprint` hashes full message content for the streaming-bucket set each relevant change

- **Location:** `feature-chat/.../render/ChatMessageGeometry.kt:103-155` (`geometryContentFingerprint`), invoked from `ChatMessageList.kt:587-618` (`activeStreamingGeometryBuckets`) and per-item at `:686-693`.
- **What scales with what:** `includeMessage` computes `message.content.hashCode()` (O(content length)) plus folds over all attachments. For the `activeStreamingGeometryBuckets` memo it runs over every render item that `containsMessageId(newestMessageId)`.
- **When it fires:** the memo is keyed on `renderItems`, `state.isStreaming`, geometry inputs, `collapsedRunIds`, etc. It is **filtered to the streaming tail** (`filter { it.containsMessageId(newestMessageId) }`), so it is O(active tail content), not O(history) — **bounded and acceptable**. The per-item signature at `:686` runs only for visible items.
- **Measured evidence:** consistent with the clean streaming numbers; no regression observed.
- **Severity:** P3 (documented non-issue — bounded to the active tail). Recorded here so a future change that broadens the filter is flagged against this invariant.
- **Fix:** none required. Keep the `containsMessageId(newestMessageId)` filter; do **not** let this fingerprint run over the whole list.

### F5 — `chatTextContentKey()` / geometry cache key recomputes full-string `hashCode()` per measure

- **Location:** `designsystem/.../text/ChatTextGeometry.kt:38-44` and `:123-133`.
- **What scales with what:** the cache key embeds `hashCode()` of the full text (O(text length)). For the streaming active block this is recomputed at paint cadence as the block grows.
- **When it fires:** per `geometryMeasurer.measure(...)` call on the active block (StreamingMarkdownText `:224`). Bounded by **active block** length, not history — the committed prefix blocks keep stable keys/identity and are not re-measured.
- **Severity:** P3 (bounded to active block; acceptable).
- **Fix:** none required now; if active blocks ever get very large, switch the content key to length+prefix+suffix only (already captured in `ChatTextContentKey`) and drop the full-string hash.

### F6 — `containsMarkdownTable()` full-text scan on assistant text

- **Location:** `feature-chat/.../render/MessageContentFactory.kt:67` + `:143-166`.
- **What scales with what:** scans every line of the message looking for a table separator — O(message content), and is `remember(text)`-keyed.
- **When it fires:** once per text change of the **active** assistant message (per paint tick during streaming). It is per-message, not per-history, and re-runs only when that one message's text changes. Bounded.
- **Severity:** P3 (per active message; acceptable).
- **Fix:** none required.

---

## Summary table

| ID | Location | Scales with | Fires | Severity | Disposition |
|----|----------|-------------|-------|----------|-------------|
| F1 | ChatMessageList.kt:398-414 / :98 | total conversation | per token | P1 | **Fixed in PR** |
| F2 | MessageGrouping.kt:62,95 | visible items × recompose freq | per recompose | P3 | **@Immutable added in PR** |
| F3 | ChatMessageList.kt (state threading) | visible items, any state field | per tick | P2 | Bead (refactor) |
| F4 | ChatMessageGeometry.kt:103 | active tail content | per relevant change | P3 | Non-issue (bounded) |
| F5 | ChatTextGeometry.kt:38,123 | active block length | per measure | P3 | Non-issue (bounded) |
| F6 | MessageContentFactory.kt:67,143 | active message length | per active-msg tick | P3 | Non-issue (bounded) |

## Do-not-regress invariants

- **rmzmo:** never rebuild the whole conversation render model per token. `IncrementalChatRenderItemsCache` must keep reusing `committedRenderItems`; only the active tail is rebuilt per tick.
- **F1 fix:** no per-token full-history scan may be re-introduced for telemetry. The `loadPressureSummary` `remember` must not key on `state.messages`/`renderItems`.
- **F4:** the streaming-bucket fingerprint must stay filtered to `containsMessageId(newestMessageId)`.
