# Handoff: Letta Desktop parity PR-6 — memory browser, secrets vault, file mentions (F24–F26)

Epic `letta-mobile-bzvro`; design `docs/design/letta-desktop-reference-gap-analysis.md` (PR #1810, §5 F24–F26, §7 PR-6).
Beads: `letta-mobile-bzvro.24` (F24), `.25` (F25), `.26` (F26), follow-up `.37` (Iroh relay). All claimed, left open.

## PRs (three, each off `main`, all draft / WIP)

| PR | Branch | Function |
|---|---|---|
| #1813 | `feat/letta-desktop-parity-memory-workspace` | F24 MemFS browser (this doc lives here) |
| #1814 | `feat/letta-desktop-parity-secrets-vault` | F25 secrets vault |
| #1815 | `feat/letta-desktop-parity-file-mentions` | F26 `@` file mentions + file viewer |

Each branch starts with the **same** commit `feat(appserver): one request path for agent-workspace commands`. It is identical in all three; after the first PR squash-merges, `git fetch && git rebase origin/main` on the others drops it (or resolves trivially — same content).

## Shared groundwork (commit 1, in every PR)

- `sharedLogic/.../transport/appserver/AppServerWorkspaceCommand.kt` — sealed sub-interface of `AppServerCommand` with `requestId`, `responseType`, `isRead`, `isStreamed`; `isFinalWorkspaceFrame` (`done` missing or true).
- `AppServerClient.workspaceRequest(command): List<JsonObject>` (default throws). Implemented in `DefaultAppServerClient` (`AppServerClient.kt`), forwarded in `ReconnectingAppServerClient`, routed in `DualLaneAppServerClient` (reads → admin lane, writes → runtime lane).
- `AppServerRequestRegistry.requestStream(...)` — multi-frame correlation; **per-frame** timeout; failed generation fails the stream. Test `AppServerRequestStreamTest`.
- `AppServerCommandRetryClass` — one branch: `is AppServerWorkspaceCommand -> if (isRead) SafeRead else AmbiguousMutation`.
- Test helper `commonTest/.../appserver/ScriptedAppServerTransport.kt` (wire-level fake App Server).
- Desktop: `DesktopLocalAppServerClientRegistry.direct` (+ `currentOrNull()`, `clients` flow), installed in `DesktopAppServerChatGatewayBuilder` when **not** Iroh; `desktop/.../workspace/DesktopWorkspaceSources.kt` hands the direct client/events/requestId to feature sources and fails with `NO_DIRECT_SESSION` over Iroh.

**Key design decision:** workspace *responses are not typed `AppServerInboundFrame`s*. They decode as `AppServerInboundFrame.Unknown` and each feature decodes the raw envelope. Reasons: (1) `AppServerRuntimeEventMapper.kt` has an exhaustive `when` and is owned by PR-2 — adding sealed subtypes would force edits there; (2) runtime-less `Unknown` frames are rejected by the turn engine scope gate and the observer paths only map `StreamDelta`, so responses (incl. plaintext secrets) never reach timelines/viewers. Each feature has a test asserting its response types still decode as `Unknown` — if someone adds them to `AppServerProtocol.KNOWN_INBOUND_MESSAGE_TYPES`, those tests fail (and matching would silently time out).

Protocol shapes were verified against the installed `@letta-ai/letta-code@0.32.14` (`%APPDATA%/npm/node_modules/@letta-ai/letta-code/dist/types/types/protocol_v2.d.ts` and `letta.js`).

## F24 — MemFS browser, history, diffs — DONE (desktop), Android not bound

Files: `sharedLogic/.../transport/appserver/AppServerMemfsCommand.kt`; `sharedLogic/.../data/memory/memfs/` (`MemfsModels`, `MemfsSource` port, `AppServerMemfsSource`, `MemfsCommitDiff`, `MemfsPageState`, `MemfsPageReducer` (+ `MemfsFileRef`/`MemfsHistoryScope`/`MemfsCommitRef`), `MemfsPageController`); `sharedUI/.../ui/shell/pages/memfs/` (`MemfsPage`, `MemfsPageList`, `MemfsPageEditor`, `MemfsPageHistory`); desktop `workspace/DesktopMemfsBinding.kt`, `workspace/DesktopMemoryDestination.kt` (Blocks | Files switch), `DesktopAppBootstrap.kt` (`DesktopLibraryControllers.memfs`), `DesktopDestinationScaffold.kt`, `DesktopShellMainPane.kt`.

Decisions: `list_memory` returns bodies *without* frontmatter, so the editor reads `read_memory_file` (full file) and saves via existing `write_memory_file`. `memory_updated` has no agent id → treated as possibly affecting the shown agent. A push while our own save is in flight is its echo, not a conflict (`MemfsEditor.followsServer`). Unsaved-changes guard covers open/close/agent switch (`MemfsNavigation`).

Tests (all pass locally): `AppServerMemfsCommandTest`, `AppServerMemfsSourceTest`, `MemfsCommitDiffTest`, `MemfsPageControllerTest` (15), `MemfsPageTest` (11, sharedUI), `DesktopMemfsBindingTest` (2).

CI at handoff: #1813 all required checks passed except `perf-gate` still pending.

## F25 — Per-agent secrets vault — DONE (desktop), Android flag OFF

Files: `AppServerSecretCommand.kt`; `sharedLogic/.../data/secrets/` (`AgentSecrets.kt` — `SecretValue`, `SecretKey`, `AgentSecret`, `AgentSecretChanges`, `AgentSecretsSource`, `AgentSecretsFeature`, `AgentSecretKeys`; `AppServerAgentSecretsSource.kt` + `AgentSecretsRedaction`; `AgentVaultState.kt`; `AgentVaultReducer.kt`; `AgentVaultController.kt`); `sharedUI/.../ui/shell/pages/vault/` (`AgentVaultPage`, `AgentVaultPageParts`); desktop `workspace/DesktopSecretsBinding.kt`, `workspace/DesktopAgentSettingsPane.kt` (Profile | Secrets in the Edit agent pane), `DesktopMainContentPane.kt`.

**Safety rules (must be kept):**
- `secret_list_response` in letta-code 0.32 returns **plaintext values** (the design doc said names only — it is wrong).
- Never log values: `SecretValue.toString()` is redacted; `SecretApply.toString()` lists keys only; decode failures throw **without cause** (kotlinx errors quote the JSON input); no Telemetry calls in the vault code.
- Masked by default, fixed-width mask (`SecretValue.MASK`, no length leak); reveal is explicit per key; `hideAll()` on page dispose; agent switch drops reveals; `close()` drops all values from memory; replacing a value starts **blank** (old value never copied into a field); value field is a password field.
- Server-side storage only: nothing persisted locally.
- `AgentSecretsRedaction.redact(frame)` must be applied by F29 (frame inspector) / F30 (diagnostics export).
- `AgentSecretsFeature.Desktop = on`, `.Android = off` (design open question 5).

Tests (pass locally): `AppServerAgentSecretsSourceTest` (7, incl. Telemetry/state/toString no-leak), `AgentVaultControllerTest` (8), `AgentVaultPageTest` (8), `DesktopSecretsBindingTest` (2).

**CI at handoff: #1814 FAILED `test` and `shared-multiplatform`** (run 37796056312) — NOT yet diagnosed. Local runs of the same code (combined branch) passed `:app:testRootDebugUnitTest`, `:desktop:test`, `:sharedUI:jvmTest`, `:sharedLogic:allTests` (except known flakes). First step: `gh run view 37796056312 --log-failed | grep -E "FAILED|e: "`. Suspects: a flaky test (see list below) or the `SecretKey` value class (`kotlin.jvm.JvmInline` in commonMain — check wasm/native compile). Rerun once if it looks like a flake.

## F26 — `@` file mentions + read-only file viewer — DONE (desktop), Android not bound

Files: `AppServerFileCommand.kt`; `sharedLogic/.../data/workspace/` (`WorkspaceFiles.kt` port `WorkspaceFileSource` + `WorkspaceFileContent` + `WorkspacePaths`; `AppServerWorkspaceFileSource.kt`; `FileMentionController.kt` + `MentionDraft`/`MentionSearch`; `WorkspaceFileViewerController.kt` + `WorkspaceFileOpener` + `ToolFileTargets`); `sharedUI/.../ui/shell/pages/workspace/` (`WorkspaceFileViewer.kt`, `WorkspaceFileLink.kt` with `LocalWorkspaceFileOpener` + `ToolCallFileLink`); **one line** in `sharedUI/.../chat/surface/timeline/rows/ChatRowToolCard.kt`; desktop `workspace/DesktopWorkspaceFiles.kt`, `DesktopShellFrame.kt` (merges file mentions into mentionables), `LettaDesktopApp.kt` (viewer host).

Decisions: F19 (PR-4) not built → cwd is a caller argument; today the conversation cwd (`DesktopChatController.selectedConversationWorkingDirectory`); PR-4 binds device-status cwd at the same seam. Composer untouched (results merge into `ChatComposerUiState.mentionables`). `read_file` is strict UTF-8 server-side; error starting "File is not valid UTF-8 text" → Binary; NUL → Binary; > 400 000 chars → TooLarge.

Tests (pass locally): `AppServerWorkspaceFileSourceTest` (7), `WorkspaceFilesControllersTest` (9), `WorkspaceFileViewerTest` (7), `DesktopWorkspaceFilesTest` (1). CI at handoff: #1815 required checks passing so far, `perf-gate` pending.

## Not done / remaining (prioritised)

1. Diagnose and fix #1814 CI failure (`test`, `shared-multiplatform`).
2. Wait for `perf-gate` on all three; rerun infra flakes.
3. Rebase the other two after the first merges (shared commit 1).
4. `letta-mobile-bzvro.37`: Iroh node relay for workspace commands (IrohNodeConnection answers unknown types with "Unknown command type"); capability classes in `IrohPeerCapabilities` (memfs read/write → MEMORY_READ/WRITE, files → CHAT_SEND?, secrets → ADMIN_FULL); then Android bindings (MemFS page, file mentions; vault stays off). Until then Android has no source and shows nothing.
5. F29/F30 must use `AgentSecretsRedaction`.

## Commands (Windows, PowerShell)

```powershell
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-26.0.1.8-hotspot"
Set-Location android-compose   # copy local.properties from the main checkout first
.\gradlew.bat --no-daemon :sharedLogic:jvmTest --tests "com.letta.mobile.data.memory.memfs.*"
.\gradlew.bat --no-daemon :sharedLogic:jvmTest --tests "com.letta.mobile.data.secrets.*"
.\gradlew.bat --no-daemon :sharedLogic:jvmTest --tests "com.letta.mobile.data.workspace.*"
.\gradlew.bat --no-daemon :sharedUI:jvmTest --tests "com.letta.mobile.ui.shell.pages.*"
.\gradlew.bat --no-daemon :desktop:test --tests "com.letta.mobile.desktop.workspace.*"
.\gradlew.bat --no-daemon --continue :sharedLogic:allTests :desktop:test :sharedUI:jvmTest :app:compileRootDebugKotlin :app:testRootDebugUnitTest
```
A full gate took ~40 min with other agents' builds on the machine; run long builds in the background. Disk on C: is tight (~19 GB free at start): delete `build/`, `.gradle/`, `.kotlin/` in the worktree when done. `:desktop:test` builds Rust natives (mermaid, octotablet) the first time (~5 min).

CodeScene: `bash scripts/codescene/cs-delta.sh` (token in `~/.config/codescene/token`; compares merge-base..HEAD, **committed** changes only). Clean on all three branches at handoff. UI lint: `python scripts/ui-design-lint.py --path pages/memfs|pages/vault|pages/workspace|desktop/workspace --max 0` → 0 findings. detekt not run (needs JDK 21; none installed besides Android Studio JBR).

## Known flaky tests (unrelated)
- `IrohConnectionSupervisorTest.readyRespectsScheduledBackoffAfterFailure` (jvm) — failed once in the full run, passed solo.
- wasm: `CanvasGetLayoutTest.pageUnderByteCap`, `CanvasComposeReserveTest.theReserveNeverShrinksAsTextGrows`, `TimelineTest.append at 2x scale keeps memory flat` — timing/memory sensitive.

## Gotchas
- Worktree-isolated agents: compound shell commands with `cd`/variables touching git get refused; run plain commands.
- Kotlin raw strings in tests: a JSON string ending right before `"""` needs four quotes (`...value""""`).
- `jq` is not installed (the CI watch script in scratch used it and failed); use `gh pr checks <n>` directly.
- Commit trailer: `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`; never commit `hs_err_pid*.log`, `replay_pid*.log`, `scripts/desktop/`, `local.properties`.
