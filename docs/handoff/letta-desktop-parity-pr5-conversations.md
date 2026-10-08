# Handoff: PR-5 Conversations and models (F15-F18)

Branch `feat/letta-desktop-parity-conversations` (one commit on top of `origin/main` 4a520a24d, rebased after
#1802 shared nav drawer landed). Spec: `docs/design/letta-desktop-reference-gap-analysis.md` (PR #1810),
section 5 F15-F18. Beads `letta-mobile-bzvro.15`-`.18` (claimed, open). Follow-ups filed: `.31`, `.32`, `.33`.

## Status summary

| F | Status | Notes |
|---|---|---|
| F15 fork from a message | Done (desktop + Android) | Android in-chat fork on the paged canonical timeline only forks through the prompt (bead .32) |
| F16 edit and resend | Done on desktop; Android works when `ChatUiState.messages` holds the timeline | bead .32 for the paged timeline |
| F17 rename / pin / delete | Done on desktop (shared nav drawer rows) | undo snackbar deferred (.31); Android drawer binding deferred (.33) |
| F18 recent models + effort set | Done | kept in the app settings store only; TUI interop dropped by decision |

## Verification state (last runs, Windows, JDK 26)

- `:sharedLogic:jvmTest` PASS (before rebase; the rebase only touched sharedUI/desktop sidebar files).
- `:desktop:test` PASS and `:sharedUI:jvmTest` PASS (after the rebase).
- `:app:compileRootDebugKotlin` PASS, `:app:testRootDebugUnitTest` PASS, `:feature-chat:testDebugUnitTest` PASS (before rebase).
- `:core:android-data:testDebugUnitTest`: 20 failures, all Room/SQLite temp-file `AccessDeniedException` /
  `SQLITE_CANTOPEN` on Windows (RoomTimeline*, RoomValidationMeasured*, TimelineOwned*). Environmental, not
  from this change; ConversationRepositoryTest and IrohAdminRpcConversationSourceTest pass. Confirm on CI (Linux).
- NOT re-run after the rebase: `:app:compileRootDebugKotlin`, wasm (`:sharedLogic:compileKotlinWasmJs`,
  `:web:compileKotlinWasmJs`), `:sharedLogic:allTests` (a combined run OOM'd the Kotlin daemon; run tasks separately
  with `--max-workers=2`). detekt NOT run (needs JDK 21; only JDK 26 on this machine).
- `cs delta` (CodeScene, token in `~/.config/codescene/token`, run `cs delta` with no branch for uncommitted work):
  clean before the rebase (only improvements). The rebase added sharedUI shell edits that were not re-checked.

## Commands

```bash
cd android-compose
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-26.0.1.8-hotspot"   # Windows box; CLAUDE.md says /usr/lib/jvm/jdk-26 on Linux
./gradlew --no-daemon --max-workers=2 :sharedLogic:jvmTest
./gradlew --no-daemon --max-workers=2 :desktop:test :sharedUI:jvmTest
./gradlew --no-daemon --max-workers=2 :app:compileRootDebugKotlin :app:testRootDebugUnitTest
./gradlew --no-daemon --max-workers=2 :sharedLogic:compileKotlinWasmJs :web:compileKotlinWasmJs
./gradlew --no-daemon --max-workers=2 :sharedLogic:allTests     # wasm timing tests flake: rerun solo
```
Disk is tight: delete `build/`, `.gradle/`, `.kotlin/` when done. Never commit `hs_err_pid*.log`, `replay_pid*.log`,
`scripts/desktop/`, `local.properties`.

## Design and files

### F15 fork (shared)
- Wire: `sharedLogic/.../transport/appserver/AppServerConversationFork.kt` (new). `AppServerConversationFork` is a
  top-level subclass of the sealed `AppServerCommand` (own file so PR-4/PR-6 do not collide). Options go in `body`
  (`agent_id`, `message_id`, `hidden`) as letta-code `ConversationForkCommand`; a top-level `message_id` is ignored upstream.
- The response is NOT a typed `AppServerInboundFrame`: adding one would force editing the exhaustive `when` in
  `AppServerRuntimeEventMapper.kt`, owned by PR-2. It arrives as `AppServerInboundFrame.Unknown` and
  `AppServerConversationForkResponse.from(frame)` reads the raw envelope. Upstream returns only `{id}`, so callers re-read
  the conversation.
- Client: `AppServerClient.conversationFork` (default throws), implemented in `DefaultAppServerClient` (registry
  correlation on the Unknown frame), `ReconnectingAppServerClient`, `DualLaneAppServerClient` (runtime lane).
  `AppServerCommandRetryClass`: AmbiguousMutation.
- Host admin RPC `conversation.fork`: `ConversationForkHandler.kt` (new, jvmAndAndroid) registered in
  `ConversationAdminHandlers.kt`; `NativeAdminOp.ConversationFork` (MutationAmbiguous); `IrohPeerCapabilities`
  conversation-manage set; contract updates in `AdminRpcContractTest`, `iroh-admin-ownership-matrix.json` (slice lgns8.7),
  `ShimOffParityGateTest` fake.
- Clients: `IrohAdminRpcChatGateway`, `IrohAdminRpcConversationListSource` (Android Iroh; also deduplicated its RPC calls),
  `LettaHttpChatGateway` (REST `/fork?message_id=`), `AppServerLocalAdminGateway` (desktop bundled), desktop
  `DesktopLocalBackendAdminGateway`, `DesktopHybridAppServerChatGateway`, `DesktopRuntimeOwnedChatGateway` (delegate).
  Capability interface `data/chat/branch/ConversationForkGateway.kt`; params builder `IrohConversationForkRpc.kt`.
- Android repository: `IConversationRepository.forkConversation(id, agentId, throughMessageId)`; `ConversationRepository`
  routes Iroh/App Server via `conversation.fork`, REST via `ConversationApi.forkConversation(ConversationForkRequest)`.
  `SessionScopedConversationRepository` forwards. Fakes in `core/testutil` and `core/android-data/src/test` override the
  request-based fork.
- Planning (shared): `data/chat/branch/ConversationBranchPlanner.kt` (fork point = end of the prompt's exchange; edit point
  = last stored message before the prompt; Empty when it opened the conversation), `ConversationBranching.kt`
  (falls back to server history via `BranchBackend.history` when the page does not hold the message),
  `ConversationBranchRunner.kt` (one at a time, error reporting), `ChatGatewayBranchBackend.kt` (gateway binding,
  newest 200 messages).
- UI: `ChatActions.forkFromMessage/editAndResend` (default no-op) and `ChatSurfaceCapabilities.fork/editAndResend`;
  `MessageActionPolicy.kt` (canFork/canEdit); `ChatRowUserPrompt.kt` menu entries "Edit and resend" / "Fork from here"
  (strings in `chat_rows.xml`). Only the user prompt row has a message menu, so fork lives there.
- Desktop: `DesktopConversationBrancher.kt` (fork keeps source cwd via `DesktopWorkingDirectoryController` and pin),
  `DesktopConversationManagement.kt` (owned by `DesktopChatController.conversationManagement`), port wiring in
  `DesktopChatSessionPort.kt`.
- Android: `feature-chat/.../coordination/ChatConversationBranching.kt`, `AdminChatViewModel.forkFromMessage/editAndResend`,
  `AdminChatActions`, `AdminChatSessionPort` (capabilities on when navigation exists), `ChatScreenNavigationCallbacks.onOpenConversation`
  wired from `AgentScaffoldMainContent` (onSwitchConversation), `SharedChatPage`.

### F16 edit and resend
- Desktop: fork then select it in place and set the composer text. Android: fork, put the draft in
  `PendingComposerDrafts.shared` under the fork id, navigate; the new chat's `startTimelineObserver` takes it.
- Composer focus is not requested (prefill only).

### F17 desktop rename/pin/delete
- #1802 moved the sidebar into sharedUI shell (`ShellAgentPanel`, `ShellConversationRow`, `ShellRowMenus`). This PR extends
  them: `ShellConversationManageMenu` entries in `ShellRowMenus.conversation`, inline `ShellConversationRenameField`
  (`ShellConversationRename.kt`), pin icon, `ShellConversationRowModel.pinned`, `ShellConversationMarks.pinnedIds`
  (pinned first in `ShellSidebarMapping`), `ShellAgentPanelActions.onRenameConversation/onPinConversation` (null = hidden).
- Desktop binding: `DesktopAgentSidebarModels/Ui.kt`, `DesktopShellContent.kt` -> `chatController.conversationManagement`.
- Pins: `sharedLogic/.../data/chat/runtime/PinnedConversations.kt` over `SecureSettingsStore`
  (key `desktop.conversations.pinned`), `DesktopConversationPrefs.kt`, built in `DesktopIrohBindings.kt`.
- Rename = conversation `summary` through `ConversationSummaryGateway` (now implemented by the local/hybrid desktop
  gateways too, so generated titles also persist in local mode). Optimistic with rollback.
- Delete on the bundled App Server (no `conversation_delete` upstream): `conversation_update {archived:true, hidden:true}`
  (`AppServerLocalAdminGateway.setConversationRemoved`). The old test asserting fail-closed delete was replaced.
- GOTCHA: the shared drawer row menus are also edited by other drawer work; merge carefully. The delete confirm text
  still says "permanently removed" although local delete archives+hides.

### F18 recent models + effort
- `sharedLogic/.../data/model/RecentModelsStore.kt` (MRU, 8 shown, 10 stored, re-reads before write),
  `SettingsStoreRecentModels` (app settings store only). TUI interop was dropped by decision: nothing reads or writes
  `~/.letta/settings.json`; it may come later as a one-time import of the TUI `recentModels` list.
- Recording on success: `ConversationModelRepository(recents=)` (Android, Hilt `ModelControlModule`) and desktop
  `applyConversationModel` -> `conversationManagement.recordModel`.
- Picker: `ModelPickerSource.recents` + `withRecents`, `ModelPickerCatalog.withRecents` prepends a "Recent" group
  (not counted in `modelCount`). Effort: `ComposerEffort` now None..Max incl. XHigh with `wire`, `labelOf`, `sorted`;
  composer effort popover shows sorted human labels.

## Tests added (all passing at last run)
commonTest: `ConversationBranchingTest`, `ConversationBranchRunnerTest`, `AppServerConversationForkTest`,
`PinnedConversationsTest`, `RecentModelsStoreTest`, `ModelPickerRecentsTest`, `MessageActionPolicyTest` (+2),
`ComposerEffortTest` (updated). jvmTest: `ConversationForkHandlerTest`. desktop: `DesktopConversationBrancherTest`, `DesktopLocalBackendAdminGatewayTest` (delete/rename/fork). sharedUI:
`ChatRowInteractionUiTest` (+2), `ModelControlUiTest` (+1), `ChatComposerPanelUiTest` (label "High"),
`ShellAgentPanelTest` (+3 rename/pin, written after the rebase, passed), `ShellRowMenusTest` (+1), `ShellSidebarMappingTest` (+1).

## Remaining checklist (priority order)
1. Re-run after rebase: `:app:compileRootDebugKotlin`, wasm compile, `:sharedLogic:allTests`; fix anything.
2. Re-run `cs delta` and fix new findings (never lower thresholds); detekt on JDK 21 if available.
3. Push CI green; mark PR ready; fill acceptance status in the PR body.
4. Optional polish: delete-confirm wording; composer focus after edit.
5. Follow-ups: .31 undo snackbar, .32 Android paged-timeline history for branching, .33 Android drawer rename/pin.
