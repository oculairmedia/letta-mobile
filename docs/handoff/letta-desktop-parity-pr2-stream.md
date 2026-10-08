# Handoff: PR-2 stream integrity and live status (F06–F10)

Branch `feat/letta-desktop-parity-stream` (off `origin/main` 6a1875771). Epic `letta-mobile-bzvro`,
beads `letta-mobile-bzvro.6`–`.10` (claimed, in progress). Design: `docs/design/letta-desktop-reference-gap-analysis.md`
(on PR #1810 branch `origin/docs/letta-desktop-gap-analysis`), §5 class B and §7.

State at handoff: all code written and committed; compiles on JVM, desktop and Android
(`:sharedLogic:compileKotlinJvm :sharedUI:compileKotlinJvm :desktop:compileKotlin :app:compileRootDebugKotlin`
were green before the last CodeScene refactor commit). The **last commit (CodeScene fixes + test fixes) has NOT
been compiled or tested**. Full local gates were never completed (machine contention, see Gotchas).

## Per function

### F06 event_seq gaps + adaptive sync — partial (desktop direct sockets only)
- New: `sharedLogic/.../data/controller/fanout/EventSeqGapDetector.kt`, `.../controller/RuntimeSyncScheduler.kt`,
  `.../controller/fanout/StreamIntegrityMonitor.kt`.
- Changed: `AppServerRuntimeEventRouter.kt` (`bindStreamIntegrity`, `requestResync`, `watchedRuntimes`, monitor sees every
  frame before `fanout.route`; `connectionGenerationProvider` made public), `RuntimeEventFanout.kt` (`watchedRuntimes()`),
  `desktop/.../chat/DesktopAppServerChatGatewayBuilder.kt` (`startStreamIntegrity` when `!isIroh`).
- Design: letta-code's `event_seq` is ONE counter per listener connection (`src/websocket/listener/connection.ts`
  `nextListenerConnectionEventSeq`, v0.33.6), NOT per runtime as the doc says. So the detector tracks the connection
  (per connection generation) and a gap resyncs every watched runtime. Gap sync uses `recover_approvals=true,
  force_device_status=true`, ≤1/s per runtime, coalesced. Periodic sync (5 s busy / 30 s idle by loop status) sends
  `recover_approvals=false`. Iroh relays filter frames per viewer → false gaps, so detection is off there.
- Not done: Android/IrohDialer and iroh-wrapper wiring; foreground (Android lifecycle) / window-focus (desktop
  `DesktopNucleusEffects` focus listener) calling `router.requestResync()`. Bead `letta-mobile-l9geg`.
- Tests: `commonTest/.../controller/fanout/EventSeqGapDetectorTest.kt` (5), `commonTest/.../controller/RuntimeSyncSchedulerTest.kt` (8)
  — passed on JVM before the scheduler's `since/lastSyncAt` refactor? Yes, they passed after it (refactor was before the run).

### F07 live loop phase + retry line — done (pending green gates)
- core/runtime `RuntimeEvent.kt`: payloads `LoopPhaseChanged`, `RetryNotice`, `StatusNotice` (+ `isAdvisory`,
  `isPresentationOnly` extensions). `RuntimeProjection.kt` ignores them (`else -> next`).
- sharedLogic: `data/runtime/LoopPhase.kt`, `RuntimeLiveStatus.kt` (state + `RuntimeLiveStatusReducer`),
  `AppServerLiveStatusDeltas.kt` (fail-soft parsing), `AppServerRuntimeEventMapper.kt` (update_loop_status now emits
  `[Running?] + LoopPhaseChanged`; retry/status/command/approval_classification_end deltas emit the raw
  `RemoteStreamFrame` AND the typed payload — additive so relay/Iroh consumers are unchanged),
  `TurnDraftProcessor.kt` (`continuesTurn` excludes advisory payloads so they never flush a closed round tail),
  `presence/ConversationRunRegistry.kt` (`ConversationRunState.live`), `presence/RunPhaseReducer.kt` (folds live
  status; mascot phase unchanged).
- Android Iroh path: `transport/MobileWsServerFrames.kt` `ServerFrame.RunActivity` (in-process only),
  `iroh/RuntimeEventServerFrameMapper.kt` maps the payloads to it, `transport/WsChatBridge.kt`
  `WsTimelineEvent.RunActivity`, `transport/WsFrameMapper.kt` (null), `chat/send/ChatSendCoordinator.kt`
  (`recordRunStateOnly`), `core/android-data/.../RuntimeEventMappers.kt` (draft), `feature-chat/.../AdminChatSendPipeline.kt`
  (presentation-only drafts kept out of the durable outbox), `LocalRuntimeChatSendCoordinator.kt` (`else -> Unit`).
- UI: `ui/chat/session/ChatSessionPort.kt` `liveStatus` (default Idle); Android `AdminChatViewModel.liveStatus` +
  `AdminChatSessionPort`; desktop `DesktopChatSessionPort.liveStatus` (selected conversation in `controller.runs`);
  `sharedUI/.../chat/surface/status/RunStatusLine.kt`, strings `composeResources/values/run_status.xml`; mounted in
  `ChatSurface.kt` `Composer` (full page) via `LiveRunStatus` (collects only there).
- Tests: `AppServerRuntimeEventMapperLiveStatusTest` (8), `RuntimeLiveStatusReducerTest` (9), `RunStatusLineUiTest` (8) — passed.

### F08 command progress — partial (status shelf, no timeline row)
- `CommandStarted`/`CommandFinished` payloads, reducer pairs by `command_id` (max 3, unmatched end standalone, cleared
  on next `LocalUserAppend` unless running); command cards in `RunStatusLine` (dim/preformatted, dismiss).
- Deviation: rendered above the composer, not as `ChatRowCommand` timeline row (no timeline message kind; instruction
  to stay out of timeline files). Bead `letta-mobile-ugs4w`.

### F09 actionable errors — done (pending gates)
- `TerminalReasonKind.kt`: families `credit_limit`, `context_window_exceeded`, `model_not_supported`, `network_error`,
  `rate_limit` token. `TurnFailureNotice.kt`: copy + KNOWN_KINDS + `kindForMessage(text)`.
- `RunErrorClassifier.kt` (`RunErrorKind`, `RunErrorAction`, `classify`, `classifyDisplayed`).
- UI: `sharedUI/.../timeline/rows/ChatRowErrorAction.kt` (Retry → `rerun(lastPrompt)` or `sendText`; Switch model →
  `host.openModelPicker`; Compact → `updateComposerText("/compact")`), `ChatRowMessage.kt` ErrorBubble slot,
  `ChatRowContext.kt` `ChatRowCallbacks.lastPrompt` + `withLastPrompt`, `ChatTimeline.kt` binds it,
  `ChatRenderItemRow.kt` tag `ERROR_ACTION`.
- Tests: `RunErrorClassifierTest` (5), `ChatRowErrorActionUiTest` (6) — passed (before the withLastPrompt refactor).

### F10 0.33 tolerance + pins — done (no runtime bump)
- `transport/appserver/AppServerChannelModels.kt`: `AppServerRuntimeScope` custom serializer; null/absent `agent_id`
  → `AGENT_FREE` (""), encodes back as absent. `ControlRequest` left unchanged on purpose.
- `approval_classification_end` → `ApprovalClassified` (unconsumed; tool-card marking bead `letta-mobile-rguja`).
- Pins: table in `sharedLogic/.../transport/appserver/README.md`; CLAUDE.md, AGENTS.md (0.29.9 fixed),
  `appserver-cli/README.md`, `cli/README.md`; `appserver-cli/src/test/.../LettaCodeVersionPinsTest.kt` (JUnit 5;
  fixed imports in last commit, not yet run). Contract baseline stays 0.32.10 (bead `letta-mobile-340tc`).
- Tests: `commonTest/.../transport/appserver/LettaCode0336Frames.kt` + `LettaCode033CompatibilityTest` (10) — passed.

## Known failing / unverified tests
- `AppServerTurnEngineTurnBoundaryTest` (all targets): `idleLoopStatusBeforeEvidenceDoesNotCompleteTheTurn`,
  `waitingOnApprovalLoopStatusDoesNotCompleteTheTurn`, `settledRunDraftDoesNotPromoteRunOrCompleteNextLeaseOnIdle`
  failed with `TurbineAssertionError: Expected no events but found Item(... payload=LoopPhaseChanged(status=WAITING_ON_INPUT))`.
  FIXED in the last commit by `awaitLoopPhase(...)` helper — not yet re-run. Other turn-engine tests that count
  drafts after loop-status frames may need the same treatment; run `:sharedLogic:jvmTest --tests "*TurnEngine*"`.
- `sharedUI` `SendLiftFramesTest.pagedSendWhileScrolledUpLandsAtTheNewestEdge` failed once ("the page has no timeline
  list to scroll"); not yet determined if caused by the status line in `Composer` or flaky. Check against main.
- wasm timing flakes seen (known, rerun solo): `CanvasGetLayoutTest.pageUnderByteCap`, `TimelineSemanticBodyWindowTest`,
  `TimelineTest` memory tests.
- Not yet run at all: `:app:testRootDebugUnitTest`, `:feature-chat:testDebugUnitTest`, `:core:android-data:testDebugUnitTest`,
  `:desktop:test`, hostNative tests after last commit.

## Commands (Windows, from `android-compose/`)
```
JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-26.0.1.8-hotspot"; ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
copy C:\letta-mobile\android-compose\local.properties .   (gitignored)
gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process :sharedLogic:jvmTest --tests "*TurnBoundary*"
gradlew --no-daemon --continue -Pkotlin.compiler.execution.strategy=in-process :sharedLogic:allTests :desktop:test :sharedUI:jvmTest :appserver-cli:test :app:compileRootDebugKotlin :app:testRootDebugUnitTest :feature-chat:testDebugUnitTest
```
JDK 21 for detekt exists at `C:\Users\Emmanuel\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2` (detekt not run yet).
CodeScene: `bash scripts/codescene/cs-delta.sh` (token in `~/.config/codescene/token`).

## Gotchas
- Other agents build concurrently: the shared Kotlin compile daemon produced a bogus `Backend Internal error` on an
  untouched file (`ZipPluginPackageReader.kt`); fix = delete `sharedLogic/build/kotlin` and use
  `-Pkotlin.compiler.execution.strategy=in-process`. The session scratchpad is shared between agents — use unique log names.
- A Gradle run redirected through PowerShell `>` hung at "waiting for log flush"; run Gradle from bash with a script.
- Disk: delete `android-compose/**/build`, `.gradle`, `.kotlin` in the worktree when done.
- Never commit `hs_err_pid*.log`, `replay_pid*.log`, `scripts/desktop/`, `local.properties`.

## CodeScene
First `cs delta` flagged: ChatSendCoordinator.handleEventLocked complexity, AdminChatViewModel global conditionals,
LocalRuntimeChatSendCoordinator / RuntimeEventMappers / RuntimeProjection large methods, RunStatusLine complex
conditional, RunPhaseReducer primitive obsession, TimelineRowBinding + StreamIntegrityMonitor.forRouter excess
arguments. All addressed in the last commit; re-run `cs delta` to confirm.

## Checklist to merge-ready
1. Compile all (`:sharedLogic:compileKotlinJvm :sharedUI:compileKotlinJvm :desktop:compileKotlin :app:compileRootDebugKotlin`, plus wasm/native via allTests).
2. Run the gate command above; fix remaining turn-engine draft-count tests; investigate SendLiftFramesTest.
3. `cs delta` clean, detekt (JDK 21) advisory.
4. Update PR body (draft in this doc's sections) with acceptance status; mark ready; babysit CI.
5. Keep beads .6–.10 open until merge; follow-ups: l9geg, ugs4w, rguja, 340tc.
