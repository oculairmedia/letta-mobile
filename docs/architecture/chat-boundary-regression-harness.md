# Chat app-bot-timeline boundary regression harness

**Status:** Canonical test matrix (2026-05-10)

This is the regression harness for the seam between:

1. app route/session selection (`AdminChatViewModel`, `ClientModeChatSender`),
2. bot gateway stream semantics (`WsBotClient`, `RemoteBotSession`), and
3. timeline ingestion/reconcile (`TimelineRepository`, `TimelineSyncLoop`, `ClientModeTimelineStreamReducer`).

When changing any of those layers, run the relevant matrix row instead of relying on one module's tests alone.

## Canonical tripwires

| Failure mode / contract | Canonical tests | Command |
|---|---|---|
| Existing-conversation Client Mode sends must preserve the route conversation and route agent instead of opening/switching sessions silently. | `ClientModeChatSenderTest.existing client mode send does not force new conversation`; `AdminChatViewModelTest.c87t - existing-conversation entry under client mode routes through gateway`; `WsBotClientLifecycleTest.session init must not silently switch to a different agent` | `./gradlew :app:testRootDebugUnitTest --tests 'com.letta.mobile.ui.screens.chat.ClientModeChatSenderTest' --tests 'com.letta.mobile.ui.screens.chat.AdminChatViewModelTest'`; `./gradlew :bot:testDebugUnitTest --tests 'com.letta.mobile.bot.protocol.WsBotClientLifecycleTest'` |
| Fresh Client Mode sends must use `conversationId = null` / force-new at the gateway, then migrate exactly one optimistic USER echo when the gateway returns `conversation_id`. | `ClientModeChatSenderTest.fresh client mode send uses null conversation id plus force new`; `AdminChatViewModelTest.fresh route client mode migrates optimistic user to gateway-created conversation`; `AdminChatViewModelTest.fresh route buffers pre-conversation assistant chunks and replays them to timeline` | `./gradlew :app:testRootDebugUnitTest --tests 'com.letta.mobile.ui.screens.chat.ClientModeChatSenderTest' --tests 'com.letta.mobile.ui.screens.chat.AdminChatViewModelTest'` |
| Assistant stream chunks are deltas, not cumulative snapshots; terminal `done=true` frames must not replay content or create a duplicate final message. | `WsBotClientLifecycleTest.assistant chunks are emitted to callers verbatim as deltas (lv3e wire contract)`; `RemoteBotSessionTest.not re-emit assistant text on the terminal frame for delta streams`; `RemoteBotSessionTest.reject terminal chunks that replay assistant content`; `AdminChatViewModelTest.client mode multi-chunk text stream renders concatenated assistant bubble`; `AdminChatViewModelTest.client mode terminal snapshot does not duplicate assistant bubble` | `./gradlew :bot:testDebugUnitTest --tests 'com.letta.mobile.bot.protocol.WsBotClientLifecycleTest' --tests 'com.letta.mobile.bot.core.RemoteBotSessionTest'`; `./gradlew :app:testRootDebugUnitTest --tests 'com.letta.mobile.ui.screens.chat.AdminChatViewModelTest'` |
| Timeline is the source of truth for Client Mode assistant/reasoning/tool events, including pre-conversation buffering and local→confirmed collapse. | `TimelineReducerCharacterizationTest.client mode stream chunks reduce into timeline locals`; `TimelineReducerCharacterizationTest.client mode user local is fuzzy-collapsed when matching server echo arrives`; `TimelineReducerCharacterizationTest.client mode reasoning local is fuzzy-collapsed when matching server reasoning arrives`; `TimelineReducerCharacterizationTest.client mode batched tool results update original timeline local`; `TimelineReducerCharacterizationTest.client mode tool result before batched call folds into final batch local`; `AdminChatViewModelTest.client mode stream chunks render from timeline repository path` | `./gradlew :core:testDebugUnitTest --tests 'com.letta.mobile.data.timeline.TimelineReducerCharacterizationTest'`; `./gradlew :app:testRootDebugUnitTest --tests 'com.letta.mobile.ui.screens.chat.AdminChatViewModelTest'` |
| Deliberate session restart / agent switch behavior must be explicit and never a silent wrong-agent recovery. | `WsBotClientLifecycleTest.session init must not silently switch to a different agent`; `WsBotClientLifecycleTest.existing conversation id is sent during session start for resume` | `./gradlew :bot:testDebugUnitTest --tests 'com.letta.mobile.bot.protocol.WsBotClientLifecycleTest'` |

## Reusable fixtures and helpers

- `ClientModeChatSenderTest` uses the minimal reusable app-boundary fixture: a mocked `InternalBotClient`, mocked `ClientModeController`, and a captured `BotChatRequest`. Extend this first for request-shape contracts.
- `AdminChatViewModelTest.createViewModel(...)` plus the shared fake repositories/flows is the app integration fixture for route state, streaming, and timeline observation.
- `WsBotClientLifecycleTest.lifecycleServer { socket, text -> ... }` is the bot protocol fixture for WebSocket session/reconnect/session-init contracts.
- `RemoteBotSessionTest` fixtures validate the gateway's exported chunk contract before Android app code sees it.
- `TimelineReducerCharacterizationTest.withLoop { ... }` is the core reducer fixture for local/confirmed timeline transitions without app dependencies.

## Notes for future changes

- Add tests to the smallest layer that owns the behavior, then add exactly one cross-layer tripwire when the bug historically escaped that layer.
- Test names should describe the contract (`existing-conversation entry under client mode routes through gateway`) rather than only the implementation method.
- Keep terminal stream frames payload-free. If a gateway change intentionally emits a terminal content snapshot, update the bot contract and timeline reducer tests in the same PR.
- Keep Client Mode reconcile heuristics scoped to `MessageSource.CLIENT_MODE_HARNESS`; strict otid support should replace fuzzy matching without weakening these tests.
