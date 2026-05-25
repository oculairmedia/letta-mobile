# Chat app-transport-timeline boundary regression harness

**Status:** Canonical test matrix (2026-05-25). Supersedes the prior Client Mode regression harness.

This is the regression harness for the seam between:

1. **App / session selection** — `AdminChatViewModel`, `ChatConversationCoordinator`, `SessionManager.currentGraph`
2. **External transport** — `WsChatBridge`, `WsChatSendCoordinator`, `TimelineExternalTransportWriter`
3. **Timeline ingestion and reconcile** — `TimelineRepository`, `TimelineSyncLoop` (with its `TimelineGatewayEvent` queue and `processEventQueue()` worker), `TimelineStreamReducer`

When changing any of those layers, run the relevant matrix row instead of relying on one module's tests alone.

For the lifecycle contract these tests protect, see [`external-transport-timeline-rules.md`](external-transport-timeline-rules.md). For the architectural picture and the future RuntimeEvent direction, see [`../architecture-review.md`](../architecture-review.md).

## Canonical tripwires

| Failure mode / contract | Canonical tests | Command |
|---|---|---|
| External-transport sends route through `WsChatSendCoordinator` + `WsChatBridge` and append an optimistic local with a stable android otid through `TimelineExternalTransportWriter`. | `WsChatSendCoordinatorTest.send dispatches through ws bridge and appends optimistic local with android otid`; `WsChatSendCoordinatorTest.send with image attachments passes them through to the bridge (lcp-dlj)` | `./gradlew :feature-chat:testRootDebugUnitTest --tests 'com.letta.mobile.feature.chat.WsChatSendCoordinatorTest'` |
| Fresh-route sends must not pre-hydrate a most-recent conversation; first WS send on a fresh route must use the resolved conversation rather than create a new one. | `ChatConversationCoordinatorTest.fresh client mode route does not hydrate most recent conversation`; `ChatConversationCoordinatorTest.first ws send after client mode open uses resolved conversation without creating one`; `ChatConversationCoordinatorTest.client mode open without route arg exposes resolved most recent conversation as active`; `ChatConversationCoordinatorTest.client mode open with cached clientModeConversationId mirrors it into the unified route key` | `./gradlew :feature-chat:testRootDebugUnitTest --tests 'com.letta.mobile.feature.chat.ChatConversationCoordinatorTest'` |
| Pre-conversation chunks are buffered and replayed through the timeline gateway in order once the WS `start` frame returns the conversation id. | `WsChatSendCoordinatorTest.message deltas before conversation id are buffered and replayed through timeline` | `./gradlew :feature-chat:testRootDebugUnitTest --tests 'com.letta.mobile.feature.chat.WsChatSendCoordinatorTest'` |
| Stream chunks are deltas, not cumulative snapshots; same-server-id frames merge into the existing confirmed event rather than appending duplicates; cumulative frames must not double existing text. | `TimelineStreamReducerTest.server id match merges stream deltas into existing confirmed event`; `TimelineStreamReducerTest.cumulative frames do not double existing text`; `TimelineStreamReducerTest.otid match dedupes duplicate stream frame`; `TimelineStreamReducerTest.seqId dedup skips already-ingested stream frame` | `./gradlew :core:testDebugUnitTest --tests 'com.letta.mobile.data.timeline.TimelineStreamReducerTest'` |
| Tool returns attach to the matching tool call; returns arriving before their call are buffered without rendering until the call lands. | `TimelineStreamReducerTest.tool return attaches to matching tool call and emits notification`; `TimelineStreamReducerTest.tool return without matching call is buffered without rendering` | `./gradlew :core:testDebugUnitTest --tests 'com.letta.mobile.data.timeline.TimelineStreamReducerTest'` |
| Approval responses mark the matching approval request decided in-place. | `TimelineStreamReducerTest.approval response marks matching approval request decided` | `./gradlew :core:testDebugUnitTest --tests 'com.letta.mobile.data.timeline.TimelineStreamReducerTest'` |
| Turn lifecycle: `usage_statistics` is first-wins per turn; `turn started` resets first-wins state; `turn_done(failed)` surfaces the buffered error; `turn_done(cancelled)` must not raise an error banner; lossy turn done triggers a reconcile while clean turn done skips it. | `WsChatSendCoordinatorTest.usage statistics writes tokens to ui state once per turn`; `WsChatSendCoordinatorTest.turn started resets per turn first wins state`; `WsChatSendCoordinatorTest.turn done failed surfaces an error message`; `WsChatSendCoordinatorTest.turn done cancelled does not set error banner`; `WsChatSendCoordinatorTest.failed turn surfaces buffered error message from preceding error frame`; `WsChatSendCoordinatorTest.clean turn done skips reconcile when shim reports lossy false`; `WsChatSendCoordinatorTest.lossy turn done forces a reconcile against external default conversation id` | `./gradlew :feature-chat:testRootDebugUnitTest --tests 'com.letta.mobile.feature.chat.WsChatSendCoordinatorTest'` |
| WS busy-queue behavior: sends issued while a turn is active are queued with an optimistic local and drained on `turn_done`; queue overflow does not append an orphan local; disconnects clear queued sends and mark locals failed. | `WsChatSendCoordinatorTest.busy send is queued with optimistic local and drains on turn done`; `WsChatSendCoordinatorTest.busy send queue drops overflow without appending optimistic local`; `WsChatSendCoordinatorTest.disconnect clears queued sends and marks optimistic locals failed` | `./gradlew :feature-chat:testRootDebugUnitTest --tests 'com.letta.mobile.feature.chat.WsChatSendCoordinatorTest'` |
| `TimelineRepository` owns the process registry of `TimelineSyncLoop` instances; cache eviction, LRU access, and clear-on-session-change preserve loop integrity and cancel cached loop subscribers cleanly. | `TimelineRepositoryTest.clear cancels cached loop stream subscriber before removing it`; `TimelineRepositoryTest.cache evicts least recently used loop and keeps recently accessed loop active`; `TimelineRepositoryTest.post handler collapse cache hit is synchronized and refreshes access order`; `TimelineRepositoryTest.getOrCreate hydrates on background dispatcher even when caller is main-like` | `./gradlew :core:testDebugUnitTest --tests 'com.letta.mobile.data.timeline.TimelineRepositoryTest'` |
| Chat-screen observer rebinds correctly on conversation switch; identical-conversation starts are idempotent; older page prefixes are merged into live emissions; in-flight active reply streams keep typing flags coherent; A2UI "thinking" states clear at first assistant response. | `ChatTimelineObserverTest.same conversation start is idempotent while observer is active`; `ChatTimelineObserverTest.switching conversations rebinds observer and tracker`; `ChatTimelineObserverTest.older page prefix is prepended to subsequent live timeline emissions`; `ChatTimelineObserverTest.active reply stream keeps streaming and typing flags true`; `ChatTimelineObserverTest.a2ui thinking stays active until first assistant response`; `ChatTimelineObserverTest.confirmed assistant tail clears duplicate initial message in flight` | `./gradlew :feature-chat:testRootDebugUnitTest --tests 'com.letta.mobile.feature.chat.ChatTimelineObserverTest'` |
| Conversation hydration runs `reconcileRecentMessages` on open to repair gaps; explicit timeline routes hydrate the requested conversation and start the observer. | `ChatConversationCoordinatorTest.loadMessages reconciles recent messages on conversation open (letta-mobile-ork1)`; `ChatConversationCoordinatorTest.explicit timeline route hydrates requested conversation and starts observer` | `./gradlew :feature-chat:testRootDebugUnitTest --tests 'com.letta.mobile.feature.chat.ChatConversationCoordinatorTest'` |

## Reusable fixtures and helpers

- **`WsChatSendCoordinatorTest`** owns the WS send + turn lifecycle fixture. Use it as the canonical starting point for any new contract on the `WsChatSendCoordinator` ↔ `WsChatBridge` ↔ `TimelineExternalTransportWriter` boundary. The test uses a `FakeWsChatBridge` and a `FakeTimelineExternalTransportWriter` (`core/src/testFixtures/java/com/letta/mobile/testutil/FakeTimelineExternalTransportWriter.kt`) — extend these before reaching for full repository fakes.
- **`ChatConversationCoordinatorTest`** is the app-level fixture for fresh-vs-existing route resolution, hydration timing, and most-recent-conversation behavior. It uses shared fake repositories and SessionGraph wiring.
- **`TimelineStreamReducerTest`** is the pure-reducer fixture for stream-frame-to-Timeline contracts — no transport dependencies, JVM-fast. Add reducer-level invariants here first; only escalate to coordinator-level tests when the bug crosses layers.
- **`TimelineRepositoryTest`** is the loop-cache / lifecycle fixture for `TimelineRepository` itself (cache policy, clear semantics, post-handler collapse).
- **`ChatTimelineObserverTest`** covers the observer layer that turns `StateFlow<Timeline>` into UI message state, including in-flight active reply tracking.
- **`ConversationStateHolderTest`** (in `core/src/test/java/com/letta/mobile/data/timeline/experimental/`) covers the shadow pure-fold projection. It is NOT yet authoritative — add tests here when working on the RuntimeEvent path, but do not gate production behavior on these.

## Notes for future changes

- Add tests to the smallest layer that owns the behavior. Only add a cross-layer tripwire when the bug historically escaped that layer.
- Test names should describe the contract (`turn done cancelled does not set error banner`) rather than the implementation method.
- Keep terminal stream frames payload-free. If a transport intentionally emits a terminal content snapshot, update the transport contract and reducer tests in the same PR.
- Reconcile heuristics scoped to external-transport locals (fuzzy collapse on USER / ASSISTANT / REASONING / TOOL_CALL) should be replaced by strict otid round-tripping when the admin-shim and Letta Code SDK can guarantee otid stability. Do this without weakening these tests.
- All timeline mutations must traverse `TimelineSyncLoop`'s `TimelineGatewayEvent` queue. Any new producer (REST send path, hydration, future RuntimeEvent outbox, future Koog local turns) must enqueue a gateway event rather than mutate `Timeline` directly. Add a test at the producing layer's seam that proves the event reaches the queue.
- `ConversationStateHolder` is shadow-only. Do not promote it to authoritative production behavior until RuntimeEvent fixture replay can prove byte-equivalent timelines across live and replay paths.

## Stale references (do not cite)

The following identifiers were used by the prior Client Mode harness and no longer exist in production code. Do not add tests under these names; do not link to docs that cite them:

- `ClientModeChatSender` / `ClientModeChatSenderTest`
- `ClientModeTimelineStreamReducer`
- `WsBotClient` / `WsBotClientLifecycleTest`
- `RemoteBotSession` / `RemoteBotSessionTest`
- `AdminChatViewModelTest` (the file was deleted during the chat refactor; equivalent coverage now lives in `WsChatSendCoordinatorTest`, `ChatConversationCoordinatorTest`, and `ChatTimelineObserverTest`)
- `MessageSource.CLIENT_MODE_HARNESS` (the production enum currently has only `LETTA_SERVER`)
- `appendClientModeLocal` / `upsertClientModeStreamChunk` (replaced by `appendExternalTransportLocal` + `ingestExternalTransportMessage` on `TimelineExternalTransportWriter`)
- `TimelineReducerCharacterizationTest` (replaced by `TimelineStreamReducerTest` plus `TimelineHydrationReducerTest`)

If a future PR needs to reintroduce any of these for migration safety, name them explicitly as such and tie them to a removal date.
