# Letta Desktop reference: gap analysis for our client

Date: 2026-10-08. Status: analysis. Implementation is tracked by the beads epic
"Letta Desktop reference parity (client experience)", `letta-mobile-bzvro`. Function
Fnn is child bead `letta-mobile-bzvro.nn`; for example, F07 is `letta-mobile-bzvro.7`.

This document compares the official **Letta desktop app** (Electron, `Letta.exe`
v0.33.6, which bundles `@letta-ai/letta-code` 0.33.6) with **our client**:
letta-mobile Android plus Compose Desktop, sharing `sharedLogic`/`sharedUI`. It ends
with 30 functions (F01–F30) that would improve our client. Each one is specified
so that a single agent can implement it in a single PR.

> Naming note. On this machine, `%LOCALAPPDATA%\Programs\letta-desktop` is **our**
> Compose Desktop build (jpackage, `com.letta.mobile.desktop.MainKt`, v0.17.4).
> The official app is installed under `%LOCALAPPDATA%\Programs\letta-code`
> (`Letta.exe`, ToDesktop auto-update feed, updater cache `letta-code-updater`).

---

## 1. Method and tools

- **REA** (`rea-agents@5.0.0`, MIT, github.com/morluto/rea):
  - Installed into a scratch directory with `npm install --ignore-scripts`. The package was inspected first: it has no `postinstall`, and `prepare` only runs for git installs.
  - `rea setup` was **not** run. It edits agent and harness configuration.
  - Only the terminal CLI was used: `rea analyze-javascript-application <dir> --artifact-format directory --format json`.
  - On the full extracted app (about 250 MB of `node_modules`), the run exhausted the Node 4 GB heap (OOM, exit 134). A second run on a trimmed copy (`dist/` and `package.json` only, 12 GB heap) was still running after more than 10 minutes.
  - **The findings below therefore come from direct static reading**, not from REA output. Grep/regex was used with bounded windows. The main-process bundle `dist/main.js` is webpack output but readable. REA's documented fallback (asar extraction plus static reading) is what was used in practice.
- **Extraction:** `@electron/asar` 4.2.0, the version pinned inside REA. `resources/app.asar` was extracted to a scratch directory on `E:` outside the repo.
- **Static analysis only:**
  - The app was not launched. No account was used, and no network traffic was captured.
  - No credentials, tokens or keys were extracted or recorded. A public analytics project key in the renderer was deliberately skipped.
  - No licensing, auth or signature checks were touched.
- **Sources read:**
  - `dist/main.js`: main process, listener supervisor, remote App Server client, IPC handlers, preferences.
  - The minified renderer chunk `dist/assets/index-*.js`, searched with bounded regex windows only.
  - The open-source **letta-code** TypeScript source at tags `v0.29.12` and `v0.33.6` (GitHub `letta-ai/letta-code`, Apache-2.0), plus the npm tarball for 0.29.12. This is the authoritative protocol reference. The relevant files are `src/types/protocol_v2.ts`, the 0.33 split `src/types/*-protocol.ts`, and `src/websocket/app-server.ts`.
- **Our client:** read at `origin/main` (`6a1875771`). File references are relative to `android-compose/`.
- **Clean-room rule:** this file records behaviours, protocol message and field names, and UX flows in our own words. No proprietary code, strings, icons or assets were copied into the repo. The scratch extraction is deleted once the analysis is finished.

## 2. License note

- **Official desktop app shell** (`app.asar`, `package.json` name `letta-code`, productName `Letta`, author Letta):
  - It has no `license` field. The `Letta.exe` version resource reads only "Copyright © 2025 Letta".
  - The install directory contains **no EULA or terms file**. The only license files are `LICENSE.electron.txt`, `LICENSES.chromium.html` and `dist/3rdpartylicenses.txt`, which are third-party notices.
  - A search of the bundles for EULA or anti-reverse-engineering text found nothing relevant.
  - We therefore treat the shell as proprietary, all rights reserved, with no explicit prohibition on reverse engineering.
  - The work was limited to static interoperability analysis: understanding how a client talks to the open-source letta-code server. Behaviours only were recorded, and no code or assets were copied.
- **letta-code** (`@letta-ai/letta-code`, bundled under `app.asar.unpacked/node_modules`) is **Apache-2.0** (its `LICENSE` and `package.json`). Reading and quoting its protocol is unrestricted.
- **REA** is MIT.

## 3. Reference architecture (Letta desktop app 0.33.6)

### 3.1 Process model and supervision

- **How the listener is launched.** The Electron main process starts letta-code as a child process:
  - It runs Electron's own binary as Node (`ELECTRON_RUN_AS_NODE=1`), so it does not depend on the user's PATH. The command is `letta.js remote --env-name <name> --backend local|api`.
  - The child runs detached with its window hidden. stdout is ignored in production; stderr is always piped.
  - The process tree is killed with a process-group kill on Unix and `taskkill /T` on Windows.
- **Two listener slots.** A "primary" listener serves cloud or local mode. A second fixed "local-backend" listener serves local agents while signed in. Each slot gets a stable `LETTA_LISTENER_INSTANCE_ID` (e.g. `desktop-primary:<install-id>`). An ownership record prevents two app instances, or an unreaped predecessor, from fighting over a slot.
- **Environment passed to the child:**
  - `LETTA_DESKTOP_MODE=1` and `LETTA_LISTENER_INSTANCE_ID`.
  - Channel restore: `LETTA_RESTORE_ENABLED_CHANNELS`, plus an agent-scope variant.
  - `LETTA_CHANNEL_RUNTIME_ROOT`, pointing at the bundled Slack/Telegram/Discord runtimes.
  - `LETTA_DISABLE_CRON_SCHEDULER=1` on the secondary slot only, so cron runs exactly once.
  - Working directory: `USER_CWD` and `PERSIST_CWD=1`. The default comes from a preference, falling back to ~/Documents, then home, then the temp directory.
  - **Heap:** `NODE_OPTIONS=--max-old-space-size` set to 50 % of RAM, with a 4 GB floor. letta-code builds file indexes and holds large contexts.
- **Crash supervision:**
  - Unexpected exits restart with exponential backoff: 2 s → 4 s → 8 s → 16 s, capped at 30 s.
  - 5 rapid crashes inside a 60 s window stop auto-restart. Backoff resets after 60 s of healthy uptime.
  - A ring buffer keeps the last stderr lines. On a crash they are flushed into the log buffer and sent to crash reporting together with the exit code, signal and uptime.
  - A health snapshot is exposed to the UI: active, consecutive crashes, last exit code and signal, last stderr lines, restart pending. The UI polls it every 5 s and offers **Force restart**, which resets the backoff.
- **Suspend and resume.** On OS suspend or lock, the app sets a "suspended" flag so that crashes are not counted. On resume or unlock it resets the crash state and force-restarts the listener if it is down.
- **Environment checks.** The app checks for git and offers to install it. If the PATH changes as a result, it restarts the listener.

### 3.2 Transport

- **Cloud and local mode.** The renderer talks to a localhost relay in the main process (an Express plus `ws` proxy), never to letta-code directly.
  - Per chat, it opens an **environment status socket** with `channel=stream`. Request/response commands go over a socket with `channel=control`, correlated by `type:request_id`, with a 10 s default timeout.
- **Remote App Server mode.** This is the mode closest to our architecture.
  - The setting holds a URL and a capability token. The token is encrypted at rest with the OS keychain and is never exposed to the renderer.
  - **Test connection** probes `GET /app-server-info` over HTTP with the bearer token and a 10 s timeout. It never opens a second WebSocket, because the App Server allows one control session.
  - Probe results are classified as follows:
    - 401/403 → `authentication`
    - 404 or an unreadable payload → `incompatible`
    - other failures → `unavailable`
  - The probe also checks `protocol_version == 1`, `backend == local`, and the capabilities `agent_management`, `conversation_management`, `memory_management` and `runtime_start`. `split_channels` is deliberately not required.
  - **Session states:** `connecting`, `connected`, `reconnecting`, `authentication-error`, `incompatible`, `connection-error` and `disabled`, plus "restart required" when the saved settings differ from the active session.
  - **Reconnect:** before every reconnect the HTTP probe runs again, so a revoked token is reported instead of retried forever. Backoff is 1 s × 2ⁿ, capped at 30 s. **Authentication and incompatible failures are terminal**, meaning no retry loop.
  - A runtime bridge sends **`runtime_start` lazily** the first time any scoped command targets a given (agent, conversation) pair. Concurrent starts are de-duplicated. On reconnect the bridge clears its "started" set.
  - If `runtime_start` fails, the bridge **synthesizes a terminal `loop_error` stream delta** on that runtime, so the UI never waits for a turn that will never run.
  - REST-shaped reads are mapped onto protocol commands:
    - `agent_*` and `conversation_*`; agent count works by paging `agent_list` to exhaustion with cursor-loop protection.
    - `list_models` is projected into the model-picker shape.
    - `list_connect_providers`.
    - `list_memory` is a multi-frame response, finished by a frame with `done:true`.
    - The agent avatar is `read_memory_file` of `profile.png` in base64.
- **Stream socket client (renderer):**
  - Frames whose `runtime` scope does not match the open chat are dropped.
  - Duplicates are dropped by `idempotency_key`; the last 500 keys are kept.
  - A **gap in `event_seq` triggers a recovery `sync`**, throttled to 1 per second.
  - `sync` also runs on open and on tab visibility. While open it runs on a timer: every 5 s while busy and every 30 s while idle.
  - An app-level `ping` is sent every 30 s.
  - Reconnect uses backoff of 1 s × 2ⁿ, capped at 30 s, for at most 10 attempts. The attempt counter resets on any status frame. When the chat is idle and hidden, the socket is closed.
  - A draft send carries `request_id` and waits up to 15 s for `input_accepted`.

### 3.3 App Server protocol in use (letta-code 0.33.6)

- **Transport:** one WebSocket at `/ws`. HTTP serves `/readyz`, `/healthz` and `/app-server-info`. The server sends a protocol-level ping every 30 s and drops a socket after about 90 s with no pong.
- **Envelopes:** scoped server events carry `{runtime:{agent_id, conversation_id}, event_seq, emitted_at, idempotency_key}`.
- **Commands the reference client uses:**
  - **Runtime:** `runtime_start`, `sync` (`recover_approvals`, `force_device_status`), `input` (`create_message` and `approval_response`), `abort_message` (`run_id`), and `change_device_state` (`mode`, `cwd`, `git_op`).
  - **Queue:** `remove_queue_item`, `resume_queue`.
  - **Models:** `update_model`, `list_models`, `list_connect_providers`, `connect_provider`, `disconnect_provider`.
  - **Commands and processes:** `execute_command` (with `command_id`), `monitor_stop`, `update_toolset`, `search_branches`.
  - **Settings:** `set_reflection_settings`, `get_experiments`, `set_experiment`.
  - **Memory:** `list_memory`, `read_memory_file`, `write_memory_file`, `delete_memory_file`, `memory_history`, `memory_commit_diff`, `memory_file_at_ref`, `enable_memfs`.
  - **Files:** `search_files`, `grep_in_files`, `list_in_directory`, `get_tree`, `read_file`, `write_file`, `file_ops`, `watch_file`, `unwatch_file`, `get_cwd_map`.
  - **Cron:** `cron_*`, plus pause and resume.
  - **Channels:** `channel_*`, the full account, route, pairing and target set.
  - **Secrets:** `secret_list`, `secret_apply`.
  - **Terminal:** `terminal_*`.
  - **Agents and conversations:** `conversation_recompile`, `conversation_fork` (`agent_id`, `message_id`, `hidden`), `create_agent`.
- **Events the reference client renders:**
  - `stream_delta`. The message types are:
    - **Messages and reasoning:** `user_message`, `assistant_message`, `reasoning_message`, `hidden_reasoning_message`, `system_message`, `summary_message`, `event_message`.
    - **Tools and approvals:** `tool_call_message`, `tool_return_message`, `approval_request_message`, `approval_response_message`.
    - **Status and errors:** `stop_reason`, `usage_statistics`, `status`, `retry`, `loop_error`, `run_error_message`, `error_message`.
    - **Commands:** `client_tool_start`, `client_tool_end`, `command_start`, `command_end`, `slash_command_start`, `slash_command_end`.
    - **0.33 only:** `approval_classification_end`.
  - Other frames: `update_loop_status`, `update_device_status`, `update_queue` (including `removed[]`), `input_accepted`, `turn_finished`, `update_subagent_state`, `control_request` (`can_use_tool` with `permission_suggestions`, `blocked_path`, `diffs`), `memory_updated`, `skills_updated`, `crons_updated`, `channels_updated`, `file_changed` and `terminal_*`.
- **Protocol drift 0.29.12 → 0.33.6.** Nothing was removed on the wire.
  - **New:** `input_accepted`, `turn_finished`, `resume_queue`, `execute_command_response`, `launch_subagent`, `monitor_stop`, `cron_pause`/`cron_resume`, `set_boot_working_directory`, `runtime_external_tools_update`, `teleport_*`, and `approval_classification_end`.
  - **Changed:**
    - `RuntimeScope.agent_id` **may be null** (agent-free conversations).
    - The toolset names became `codex|default|letta|none`, and `DeviceStatus.available_toolsets` was added.
    - `retry` gains `retry_kind`, `provider`, `error_code`.
    - `update_queue` gains `removed[]`, and queue items get `paused`.
    - `background_processes` gains the `monitor` and `workflow` kinds.
    - `input` gains `request_id`, `image_failure_mode` and `response_format`.

### 3.4 UX features observed (renderer)

- **Approvals:**
  - An inline card offers Approve, then one button per **permission suggestion**, which is a persistent "always allow" rule chosen by the server. Deny takes an optional reason; an empty deny gets a default reason.
  - Several pending calls are stepped through and submitted together.
  - Responses made while offline are **queued and flushed** after the next device-status frame. A rejected response triggers a resync.
- **Permission modes:** Standard, Accept Edits and Unrestricted. Shift+Tab cycles them, the choice is persisted, and agents can have their own default. Legacy names (`default`, `bypassPermissions`, `plan`) are migrated.
- **AskUserQuestion:** a multi-step wizard with an "Other" free-text option, skip, reject-all and keyboard hints.
- **Queue:** a shelf above the composer with pause/resume and *edit*, which takes the text back into the composer. Similar shelves list running **monitors and background processes**, each with a stop action.
- **Stop:** `abort_message` with the active run id. The UI goes idle immediately, and any send that follows waits for the server to be idle.
- **Conversations:**
  - Fork from a message; the fork inherits the pinned flag and cwd. Edit appears to work as fork-at-message.
  - Rename, pin, favourite, archive, delete and share.
  - Sidebar grouping and filters, and a "recent conversations" list in the tray menu.
- **Models:**
  - The picker has Recents, Hosted, All and BYOK tabs. The recents list is shared with the TUI through `~/.letta/settings.json`.
  - Overrides can be per conversation.
  - Reasoning effort values: off, none, minimal, low, medium, high, extraHigh, max.
- **Live status:** loop phases (sending, processing, executing tool, executing command, waiting on approval), retry notices, and a context-usage meter. `/compact`, `/context-limit` and `/set-max-context` are available.
- **Slash commands:**
  - Run through `execute_command` against the device's advertised `supported_commands`. The list includes clear, compact, context-limit, doctor, init, reflect/dream, reload, upgrade-letta-code and secret.
  - Some commands are client-local: rename, new, search, models, memory, mcp.
- **Memory:** a MemFS browser with system/external split, editor, unsaved-changes guard, **git history and commit diffs**, a graph view, an "enable memfs" prompt and an optional "recompile on save".
- **Workspace:**
  - Filesystem tree, quick-open, find-in-files and an editor.
  - Branch indicator and branch search.
  - Branch diff view (`branch_diff_*`).
  - Terminal panel.
  - `@` file mentions search the device's files.
- **Agent tools:** skills (including import from URL), MCP servers with a tool tester, channels, cron schedules (pause/resume, run history, send-now), a vault (secrets), and a toolset selector.
- **Tool rendering:**
  - Tool names are mapped onto canonical kinds: Task, Edit, Write, Read, Bash, Grep, Glob, Patch, Todo, Skill, Task*, Monitor, AskUserQuestion, WebFetch/WebSearch, memory_* and `mcp__server__tool`.
  - Read-only shell calls fold into an "Exploring" group.
  - Rendering uses Shiki highlighting, Mermaid and KaTeX.
- **Attachments and voice:**
  - Images can be pasted, dropped or picked. Files over 5 MB are downscaled to at most 2000 px, and the cap is 10 MB.
  - Voice dictation runs up to 10 minutes. Transcription uses the cloud by default, or the user's own OpenAI key.
- **Shell integration:**
  - **Notifications** on turn completion *and on approval-needed*, shown only when the window is unfocused, plus a dock badge.
  - An animated tray/dock icon while processing.
  - A **power-save blocker while processing**, with a 1 s renderer heartbeat and a watchdog that stops the indicators if the heartbeat stops.
  - Per-agent notification mute.
  - Tray menu: favourite agents, recent conversations, upcoming schedules, channels.
  - Run in background and start at login.
  - Popout windows for any route.
  - ToDesktop auto-update with "restart to install".
- **Diagnostics:** an opt-in **debug panel** with logs (downloadable), a **WebSocket message history** (bounded, gated on subscribers, clearable), listener start/stop, an experiments toggle, transcript replay, a performance analyzer and a memory-pressure trim. Crashes are reported to Sentry along with listener stderr.
- **i18n:** en, fr, zh-CN and pt-BR. The locale follows the system by default, and changing it reloads all windows.
- **Not found:** a custom deep-link protocol, accessibility settings, and text-to-speech.

## 4. Gap matrix against our client

Legend: ✅ have it · 🟡 partial · ❌ missing. "Ref" is the Letta desktop app.

| Area | Ref | Ours | Evidence (ours) | Gap → F# |
|---|---|---|---|---|
| WS + Iroh transport, reconnect w/ jitter | ✅ | ✅ | `sharedLogic/.../transport/appserver/*`, `controller/reconnect/*` | — |
| `/app-server-info` probe, capability check | ✅ | 🟡 | `AppServerDiscovery.kt`, `AppServerCompatibility.kt` (no UI test, no failure classes) | F01 |
| Terminal auth/incompatible states (stop retrying) | ✅ | ❌ | `AppServerConnectionState` = Disconnected/Connecting/Ready/Failed | F02 |
| Local runtime crash restart + health + force-restart | ✅ | 🟡 | `desktop/.../runtime/DesktopLocalRuntimeManager.kt` (relaunch only on next lease) | F03 |
| Suspend/resume awareness | ✅ | ❌ | none | F04 |
| Child heap sizing / stable instance id / cwd env | ✅ | ❌ | spawn sets only backend env | F05 |
| `event_seq` gap → recovery sync, adaptive periodic sync | ✅ | 🟡 | `IngestFrameDeduplicator.kt` dedupes; no gap detection | F06 |
| Loop phase / `retry` / `status` deltas rendered | ✅ | ❌ | `update_loop_status` used only as turn boundary (`TurnBoundaryGate.kt`) | F07 |
| `command_*` / `slash_command_*` delta rendering | ✅ | ❌ | `AppServerRuntimeEventMapper.kt` passes them through untyped | F08 |
| Error taxonomy for `loop_error`/`stop_reason` | ✅ | 🟡 | `TurnFailureNotice.kt` generic | F09 |
| 0.33 wire tolerance (`agent_id:null`, `removed[]`, new toolsets, `approval_classification_end`) | ✅ | 🟡 | contract baseline 0.32.10 (`app-server-v2-contract-matrix.json`) | F10 |
| Permission suggestions ("always allow") | ✅ | ❌ | `AppServerRuntimeEventMapper.toApprovalOrExternalDraft` drops `permission_suggestions` | F11 |
| Approval diff preview (`control_request.diffs`) | ✅ | ❌ | dropped in the same mapper | F12 |
| Permission-mode picker | ✅ | ❌ | `RuntimePermissionDefaults.DEFAULT_MODE = Unrestricted`; `DeviceStateChanger.kt` has no UI | F13 |
| Multi-approval stepping + offline approval outbox | ✅ | 🟡 | `ApprovalResponseSender.kt` sends immediately | F14 |
| Conversation fork via protocol | ✅ | 🟡 | Android REST fork only; desktop none | F15 |
| Edit-and-resend | ✅ | ❌ | only `rerun` | F16 |
| Desktop rename/pin/delete | ✅ | 🟡 | `DesktopChatController.kt` archive only | F17 |
| Recent models + full effort set | ✅ | ❌ | `sharedUI/.../modelcontrol/*` no MRU | F18 |
| Typed `update_device_status` | ✅ | 🟡 | `AppServerDeviceStatus.kt` types cwd/mode/revision only | F19 |
| `execute_command` slash commands | ✅ | ❌ | `SlashCommandApi.kt` uses REST/Iroh | F20 |
| Background processes + `monitor_stop` | ✅ | ❌ | none | F21 |
| Toolset selector (`update_toolset`) | ✅ | ❌ | none | F22 |
| Cron pause/resume + `crons_updated` on App Server | ✅ | 🟡 | `CronRepository.kt` (MobileWs `crons_updated` only) | F23 |
| MemFS browser / history / diffs | ✅ | 🟡 | write/delete only; no `list_memory`/`memory_history` | F24 |
| Secrets vault (`secret_list/apply`) | ✅ | ❌ | none | F25 |
| `@` file mention + read-only file viewer | ✅ | ❌ | no `search_files`/`read_file` | F26 |
| Approval-needed notification, per-agent mute | ✅ | 🟡 | desktop toast only on finish (`DesktopNucleusEffects.kt`) | F27 |
| Sleep inhibitor while processing + heartbeat watchdog | ✅ | ❌ | none | F28 |
| Wire-frame inspector (debug panel) | ✅ | ❌ | `Telemetry.kt` events only | F29 |
| Runtime log viewer / diagnostics export | ✅ | ❌ | `~/.letta-mobile/logs/local-runtime.log` with no viewer | F30 |
| Queue shelf, interrupt, AskUserQuestion, subagents, context meter, markdown/mermaid/diff, image attach, schedules UI, skills, MCP admin (Android), auto-update, deep links | ✅ | ✅ | see §4.1 | — |
| Terminal panel, full file editor, branch diff, channels full CRUD, teleport, i18n locales, shortcut registry | ✅ | ❌ | — | deferred (§6) |

### 4.1 Things we already do (not listed as work)

- **Send queue:** `update_queue`, resume and remove.
- **Interrupt:** `abort_message`.
- **AskUserQuestion card.**
- **Subagent rows and panel:** `update_subagent_state`.
- **Context-usage meter.**
- **Rendering:** streaming markdown, code highlighting, Mermaid, math and unified diffs in tool output.
- **Images:** paste, drop and pick, with resize to a 1568 px longest edge.
- **Schedules UI**, including create, run history and trigger.
- **Skills:** enable, disable and install.
- **MCP admin** on Android.
- **Desktop auto-update** (Nucleus).
- **Deep links:** `meridian://` on desktop, `letta://` on Android. The reference app has none.
- **Notifications:** desktop toast on finish with inline reply; Android push service.
- **Reconnect and resume:** reconnect with `runtime_start` + `sync(recover_approvals)`, and ambiguous-turn reconciliation by `client_message_id`.

### 4.2 Version pins (inconsistent; fix as part of F10)

| Where | Version |
|---|---|
| Contract baseline (`sharedLogic/src/jvmTest/resources/appserver/app-server-v2-contract-matrix.json`) | 0.32.10 |
| Live golden capture | 0.32.3 |
| Desktop bundled runtime (`desktop/runtime/runtime-manifest.json`) and `appserver-cli` replay evidence | 0.29.12 |
| `CLAUDE.md` | 0.29.12 |
| `AGENTS.md` | 0.29.9 |
| Reference app | 0.33.6 |

---

## 5. Functions F01–F30

Fields per item: **Class** · **Problem** · **Reference behaviour** · **Proposal** · **Acceptance** · **Effort** (S ≤ 1 day, M 2–4 days, L 1–2 weeks) · **Priority** · **Deps** · **Platform**.

Paths: `SL` = `sharedLogic/src/commonMain/kotlin/com/letta/mobile/`, `UI` = `sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/`, `DK` = `desktop/src/main/kotlin/com/letta/mobile/desktop/`, `AP` = `app/src/main/java/com/letta/mobile/`.

### Class A: Process and connection lifecycle

**F01 · App Server "Test connection" with classified failures**
- **Class:** A
- **Problem:** users save a URL or token and only find out it is wrong when chat silently fails. An Iroh or WS endpoint cannot be validated before saving.
- **Reference behaviour:** an HTTP `GET /app-server-info` probe with the bearer token and a 10 s timeout. It never opens a WebSocket, because the server allows one control session. The result is one of:
  - `ok`, with identity (backend, letta-code version, protocol version)
  - `authentication` (401/403)
  - `incompatible` (404, unreadable payload, wrong protocol_version, or missing capabilities `agent_management`/`conversation_management`/`memory_management`/`runtime_start`)
  - `unavailable`
- **Proposal:**
  - Add `SL/data/transport/appserver/AppServerProbe.kt` with `suspend fun probe(endpoint: AppServerEndpoint): AppServerProbeResult`, a sealed type with `Ok(identity)`, `Authentication`, `Incompatible(reason)` and `Unavailable(detail)`. Reuse the parsing in `AppServerDiscovery.kt` and `AppServerCompatibility.kt`.
  - For Iroh endpoints, probe with the existing `app_server_info` admin request over the dialer.
  - Add a "Test connection" button to `DK/DesktopDestinationSettings.kt` (`BackendSettingsCard`) and to the Android `AP/ui/screens/config/ConfigViewModel` for App Server and Iroh modes.
- **Acceptance:**
  - Unit tests cover each class: 401, 403, 404, malformed JSON, protocol_version 2, a missing capability, timeout, and ok.
  - The UI shows the identity on success and a localized reason on failure.
  - The probe never disturbs an active session.
- **Effort:** M · **Priority:** P1 · **Deps:** none · **Platform:** shared (+ Android and desktop settings UI)

**F02 · Terminal connection states (stop retrying bad tokens / incompatible servers)**
- **Class:** A
- **Problem:** a revoked token or an incompatible server makes our reconnect loop retry forever, and the UI shows only "Failed" or "Connecting".
- **Reference behaviour:**
  - Before each reconnect, the HTTP probe runs first.
  - `authentication` and `incompatible` are **terminal**: the session stops and shows that state.
  - Other failures go to `reconnecting`, with backoff of 1 s × 2ⁿ capped at 30 s.
  - The status model is connecting / connected / reconnecting / authentication-error / incompatible / connection-error / disabled.
- **Proposal:**
  - Extend `AppServerConnectionState` (`SL/data/transport/appserver/`) with `Reconnecting(attempt, nextDelay)`, `AuthenticationError(detail)` and `Incompatible(detail)`.
  - In `controller/reconnect/AppServerReconnectSupervisor.kt`, call the F01 probe before each attempt, and stop scheduling on terminal kinds until the user changes settings or presses Retry.
  - Render the state in the desktop connection banner (`DK/chat/DesktopChatConnectionWatcher.kt`) and the Android `HealthIndicator.kt`.
- **Acceptance:**
  - A fake server returning 401 leads to exactly one probe, then `AuthenticationError`, with no further attempts.
  - A network drop goes to `Reconnecting` with growing delays and then recovers.
  - Tests are in `sharedLogic` commonTest.
- **Effort:** M · **Priority:** P1 · **Deps:** F01 · **Platform:** shared

**F03 · Desktop local-runtime crash supervisor with health and force restart**
- **Class:** A
- **Problem:** if the bundled letta-code child dies, desktop only relaunches it on the next lease. Users see a dead chat with no explanation and no restart button.
- **Reference behaviour:**
  - Exit watcher. Restart with backoff 2/4/8/16 s, capped at 30 s.
  - Give up after 5 crashes within 60 s. Reset the counters after 60 s of healthy uptime.
  - Intentional stops (our own kill, exit 0) never restart.
  - Keep a ring buffer of the last 10 stderr lines and flush it to the log on a crash.
  - Health snapshot: `active`, `consecutiveCrashes`, `lastExitCode`, `lastSignal`, `recentStderr`, `restartPending`. The UI polls it and offers "Force restart", which resets the counters.
- **Proposal:**
  - Add `DK/runtime/DesktopRuntimeSupervisor.kt` around `DesktopLocalRuntimeManager.kt` (it owns `Process.onExit()`), with `val health: StateFlow<RuntimeHealth>` and `fun forceRestart()`.
  - Add a health card in desktop settings and a "Runtime stopped — Restart" action in the chat connection banner.
- **Acceptance:**
  - A test with a fake process launcher covers backoff timing, the give-up limit, healthy-uptime reset, and intentional-exit suppression.
  - Killing the node process by hand in a manual run restarts it within 2 s, and chat recovers through the existing reconnect.
- **Effort:** M · **Priority:** P1 · **Deps:** none · **Platform:** desktop

**F04 · Suspend/resume awareness**
- **Class:** A
- **Problem:** after the laptop sleeps or Android dozes, sockets and the child process die. Crash counters and backoff then make recovery slow, or they trip the give-up limit.
- **Reference behaviour:**
  - On suspend or lock, mark the system as suspended and do not count crashes.
  - On resume or unlock, reset crash state, restart the runtime if it is down, and resync.
- **Proposal:**
  - Add `SL/data/controller/reconnect/SystemSuspendSignal.kt`, an `expect` source exposing `Flow<SuspendEvent>`.
    - Desktop: JNA `WM_POWERBROADCAST` / `WTSRegisterSessionNotification` on Windows, with a wall-clock-jump detector as the fallback.
    - Android: `PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED` plus `ConnectivityManager` network callbacks.
  - On resume, the reconnect supervisor resets its backoff and reconnects immediately. F03 resets its crash state.
- **Acceptance:**
  - A test with a simulated suspend → crash → resume shows no crash counted and an immediate reconnect.
  - A manual sleep/wake on Windows recovers chat in under 5 s.
- **Effort:** M · **Priority:** P2 · **Deps:** F03 (desktop part) · **Platform:** shared + desktop + Android

**F05 · Runtime spawn parity: heap, instance id, cwd**
- **Class:** A
- **Problem:** large repos can run the Node default heap (about 1.5 to 4 GB) out of memory. A manual `letta server` can collide with our child process. The child's cwd is the app's install directory.
- **Reference behaviour:**
  - `NODE_OPTIONS=--max-old-space-size` at 50 % of RAM, with a 4 GB floor.
  - A stable per-install `LETTA_LISTENER_INSTANCE_ID`.
  - `USER_CWD` from a "default working directory" preference, falling back to Documents, home, then temp. Also `PERSIST_CWD=1`.
- **Proposal:**
  - In `DK/runtime/DesktopLettaCodeRuntime.kt` (`DesktopLocalRuntimeHost`), add these env vars and a `defaultWorkingDirectory` desktop setting.
  - Keep the stable install id in the desktop settings store.
- **Acceptance:**
  - A unit test checks the generated environment for 8, 16 and 64 GB inputs.
  - The child's cwd equals the preference.
  - The instance id is stable across restarts.
- **Effort:** S · **Priority:** P2 · **Deps:** none · **Platform:** desktop

### Class B: Stream integrity and live status

**F06 · `event_seq` gap detection and adaptive sync cadence**
- **Class:** B
- **Problem:** if a frame is lost (relay hiccup, Iroh stream reset), the UI can show a stale turn until the next reconnect.
- **Reference behaviour:**
  - Track the last `event_seq` per runtime. A gap triggers a `sync` (`recover_approvals:true`), throttled to 1 per second.
  - Also sync on open, on foreground/visibility, every 5 s while busy and every 30 s while idle.
- **Proposal:**
  - Add `SL/data/controller/fanout/EventSeqGapDetector.kt`, fed from `RuntimeEventFanout` after the `IngestFrameDeduplicator`. It is keyed by `(runtime, connectionGeneration)` and resets on reconnect.
  - Add `SL/data/controller/RuntimeSyncScheduler.kt` to emit throttled `AppServerCommand.Sync`.
  - Android: foreground triggers a sync through the lifecycle observer. Desktop: window focus triggers one.
- **Acceptance:**
  - Tests: injecting seq 1, 2, 4 gives one sync. A burst of gaps gives at most 1 sync per second. Duplicates do not count as gaps. A generation change resets tracking.
- **Effort:** M · **Priority:** P1 · **Deps:** none · **Platform:** shared

**F07 · Live loop-phase and retry status line**
- **Class:** B
- **Problem:** during long turns users see only a spinner. Provider retries and rate-limit backoff look like hangs.
- **Reference behaviour:**
  - The status label follows `update_loop_status.status`: SENDING_API_REQUEST, WAITING_FOR_API_RESPONSE, RETRYING_API_REQUEST, PROCESSING_API_RESPONSE, EXECUTING_CLIENT_SIDE_TOOL, EXECUTING_COMMAND, WAITING_ON_APPROVAL, WAITING_ON_INPUT.
  - `retry` deltas show reason, attempt/max and delay, plus `retry_kind`/`provider` in 0.33.
  - `status` deltas show a message at its level.
- **Proposal:**
  - Add `SL/data/runtime/LoopPhase.kt`, a sealed type mapped from `update_loop_status`.
  - Add `RuntimeEventPayload.RetryNotice` and `StatusNotice` in `core/runtime`, mapped in `AppServerRuntimeEventMapper.kt` (new `when` branches for `retry` and `status`).
  - Add a `UI/chat/surface/composer/TurnPhaseIndicator.kt` composable, shared by both platforms, above the composer.
- **Acceptance:**
  - Mapper tests for each phase and for retry/status.
  - The indicator shows "Retrying (2/5) in 4 s" and clears on `turn_finished` / WAITING_ON_INPUT.
- **Effort:** M · **Priority:** P1 · **Deps:** none · **Platform:** shared

**F08 · Command and slash-command lifecycle rendering**
- **Class:** B
- **Problem:** server-side commands (`/compact`, `/doctor`, `/init`, bash commands run by the harness) produce `command_*` / `slash_command_*` deltas that we pass through untyped. Users see nothing, or raw JSON.
- **Reference behaviour:** a start/end pair grouped by `command_id`. It shows the input, a spinner, then the output (honouring `dim_output` and `preformatted`) and a success/failure badge.
- **Proposal:**
  - Add `RuntimeEventPayload.CommandStarted`/`CommandFinished` in `core/runtime` and map them in `AppServerRuntimeEventMapper.kt`.
  - Add the timeline row `UI/chat/surface/timeline/rows/ChatRowCommand.kt`.
- **Acceptance:** golden-frame tests from a 0.33 capture. The row renders in both clients. Unmatched `end` frames render standalone.
- **Effort:** S · **Priority:** P2 · **Deps:** none (F20 benefits) · **Platform:** shared

**F09 · Actionable run-error taxonomy**
- **Class:** B
- **Problem:** `loop_error` and `stop_reason` errors show as a generic failure, so users don't know whether to wait, top up credit, or switch model.
- **Reference behaviour:** errors are classified into codes such as RATE_LIMIT_EXCEEDED, CREDIT_LIMIT_EXCEEDED, MODEL_NOT_SUPPORTED, CONTEXT_WINDOW_EXCEEDED and NETWORK_ERROR. Each is a toast with expandable details plus a contextual action (retry, open model picker, compact).
- **Proposal:**
  - Add `SL/data/runtime/RunErrorClassifier.kt`, mapping `loop_error.{message, stop_reason, api_error}` and `error_message` into `RunErrorKind` plus the raw detail.
  - Extend `TurnFailureNotice.kt` and `DK/DesktopInlineError.kt` / `ChatSurfaceSnackbars.kt` with a per-kind action.
- **Acceptance:** classifier table tests using recorded error payloads. Each kind renders an action. Unknown errors fall back to the current generic notice.
- **Effort:** M · **Priority:** P2 · **Deps:** none · **Platform:** shared

**F10 · letta-code 0.33 wire tolerance and version-pin unification**
- **Class:** B
- **Problem:**
  - Our pins disagree (§4.2), and the reference app already ships 0.33.6.
  - 0.33 adds `agent_id:null` scopes, `update_queue.removed[]`, `approval_classification_end`, new toolset names and new background-process kinds. Strict decoding or keying on agent_id would break.
- **Reference behaviour:** consumes 0.33.6.
- **Proposal:**
  - Make `AppServerRuntimeScope.agentId` nullable on inbound frames, and key runtime maps on `(agentId?, conversationId)`.
  - Decode `removed[]` (dequeued/cancelled) into the queue state.
  - Decode `approval_classification_end` so that auto-allowed and auto-denied calls are marked on tool cards.
  - Add 0.33.6 to the contract matrix, and record one source of truth for the pin in `desktop/runtime/runtime-manifest.json`, `AppServerRestartReplayEvidence.kt`, CLAUDE.md and AGENTS.md.
- **Acceptance:**
  - `:sharedLogic:allTests` passes with a new 0.33.6 golden capture.
  - The appserver-contract workflow is green on 0.33.6.
  - All version references agree.
- **Effort:** M · **Priority:** P1 · **Deps:** none · **Platform:** shared + desktop runtime + docs

### Class C: Approvals and permission modes

**F11 · Permission suggestions ("always allow …")**
- **Class:** C
- **Problem:** users must approve the same safe tool every time. The server already offers reusable rules that we throw away.
- **Reference behaviour:**
  - `control_request.request.permission_suggestions[{id, text}]` is rendered as extra buttons ("Approve and always allow …").
  - Choosing one sends `decision {behavior:"allow", selected_permission_suggestion_ids:[id]}`.
  - `blocked_path` is shown when present.
- **Proposal:**
  - Extend `ToolApprovalRequest` (`core/runtime`) with `suggestions: List<PermissionSuggestion>` and `blockedPath: String?`, populated in `AppServerRuntimeEventMapper.toApprovalOrExternalDraft`.
  - Extend `ApprovalResponseSender.kt` / `AppServerApprovalDecisions.kt` to carry the selected ids. The wire field already exists in `AppServerInputModels.kt`.
  - Add the buttons in `UI/chat/surface/timeline/rows/ChatRowApprovals.kt` and the Android `ChatApprovals.kt`.
- **Acceptance:** mapper and sender tests. Picking a suggestion produces the correct wire JSON. A second identical tool call is not prompted for (verified against a 0.32.10+ server in the appserver-cli probe).
- **Effort:** M · **Priority:** P1 · **Deps:** none · **Platform:** shared (+ Android row)

**F12 · Approval diff preview**
- **Class:** C
- **Problem:** approving an Edit/Write without seeing the change is risky, especially on a phone.
- **Reference behaviour:** `control_request.request.diffs[]` (DiffPreview) is rendered inline in the approval card as a unified diff with +/- counts and expand/collapse.
- **Proposal:**
  - Add `diffs: List<ApprovalDiffPreview>` to `ToolApprovalRequest`.
  - Render it with the existing `SL/data/diff/UnifiedDiff.kt` and the desktop `DesktopChatDiffBlocks.kt`, lifted into `UI/chat/render/ApprovalDiffPreview.kt` so both platforms share it.
- **Acceptance:** an approval with a diff shows the file path, line counts and colored hunks. Large diffs are truncated with "show all".
- **Effort:** S · **Priority:** P2 · **Deps:** F11 (same files; do in the same PR) · **Platform:** shared

**F13 · Permission-mode picker (and default setting)**
- **Class:** C
- **Problem:** the mode is hard-coded to Unrestricted (`RuntimePermissionDefaults.kt`), so users cannot choose safer modes per agent or conversation.
- **Reference behaviour:**
  - The composer chip shows the current mode: Standard, Accept edits, Unrestricted, or Strict where supported.
  - Shift+Tab cycles the mode, which is sent via `change_device_state {mode}`.
  - The chip reflects `device_status.current_permission_mode`.
  - The default is persisted globally and per agent.
- **Proposal:**
  - Add `UI/chat/surface/composer/PermissionModeChip.kt`, bound to `DeviceStateChanger.kt` and the device-status snapshot.
  - Add a default-mode setting in `LettaConfigPersistence` (Android) and the desktop settings store.
  - `RuntimePermissionDefaults` reads that setting.
  - Desktop gets the Shift+Tab binding in the composer key handler.
- **Acceptance:**
  - Switching the mode updates the chip only after `update_device_status` echoes it, with an optimistic pending state.
  - The setting survives a restart. This supersedes bead letta-mobile-4mps3.
- **Effort:** M · **Priority:** P1 · **Deps:** F19 · **Platform:** shared

**F14 · Multi-approval stepping and offline approval outbox**
- **Class:** C
- **Problem:**
  - With several parallel tool calls pending, users answer card by card with no overview.
  - An approval tapped during a reconnect is lost.
- **Reference behaviour:**
  - Pending approvals are stepped through as "1 of N" and the decisions are submitted together.
  - Responses made while disconnected are queued and flushed after the next device status.
  - If the server rejects a stale response, the client resyncs, and the pending set is rebuilt from `device_status.pending_control_requests`.
- **Proposal:**
  - Add `SL/data/runtime/ApprovalOutbox.kt`, keyed by `request_id`, persisted in memory per runtime and drained by `ReconnectCoordinator` after the `sync` response.
  - Add a batched-approval header in `ChatRowApprovals.kt`.
- **Acceptance:**
  - Test: approve while the transport is down, reconnect, and exactly one `approval_response` is sent.
  - Test: a stale response leads to a resync and the card is removed.
- **Effort:** M · **Priority:** P2 · **Deps:** F19 (pending_control_requests) · **Platform:** shared

### Class D: Conversation and session management

**F15 · Fork conversation from a message via the protocol**
- **Class:** D
- **Problem:** desktop cannot fork, and Android forks over REST, which does not work against an App Server or Iroh backend.
- **Reference behaviour:**
  - `conversation_fork {conversation_id, body:{agent_id?, message_id?, hidden?}}` returns the new conversation.
  - The fork inherits the pinned flag and cwd, then opens.
  - The parameters must sit in the body; dropping them forks the whole conversation.
- **Proposal:**
  - Add `AppServerCommand.ConversationFork` and its response in `SL/data/transport/appserver/`.
  - Add `ConversationRepository.fork(conversationId, fromMessageId)`, routed by backend.
  - Add a "Fork from here" message action in the shared timeline row menu.
- **Acceptance:** wire test for the body shape. The forked conversation opens with the same cwd. On Android, App Server mode no longer hits REST.
- **Effort:** M · **Priority:** P2 · **Deps:** none · **Platform:** shared

**F16 · Edit and resend a user message**
- **Class:** D
- **Problem:** fixing a typo means retyping the message. Only "rerun" exists.
- **Reference behaviour:** editing a sent user message forks at the message before it, prefills the composer with the old text, and sends into the fork.
- **Proposal:** add `ChatActions.editAndResend(messageId)`. It calls F15 with `message_id` set to the prior message, switches to the fork, prefills the composer, and focuses it. Add a pencil action on user bubbles.
- **Acceptance:** the original conversation is unchanged. The fork contains history up to the prior message plus the edited text.
- **Effort:** S · **Priority:** P3 · **Deps:** F15 · **Platform:** shared

**F17 · Desktop conversation rename / pin / delete parity**
- **Class:** D
- **Problem:** desktop users can archive but cannot rename, pin or delete conversations. Android can.
- **Reference behaviour:** rename (a shortcut plus `/rename`), pin (sorted first, stored on the client), and delete with confirmation, all from the sidebar context menu.
- **Proposal:**
  - Add `conversation_update {title}` in `DesktopChatController.kt`.
  - Store pinned conversation ids in the desktop settings store.
  - Implement delete in `DesktopLocalBackendAdminGateway.kt`, which currently throws.
  - Add a context menu in the desktop sidebar.
- **Acceptance:** rename persists on the server. Pinned conversations survive a restart and sort first. Delete removes the conversation, with an undo snackbar where possible.
- **Effort:** M · **Priority:** P2 · **Deps:** none · **Platform:** desktop

**F18 · Recent models and full reasoning-effort set**
- **Class:** D
- **Problem:** picking a model means scrolling the full catalogue every time.
- **Reference behaviour:**
  - The picker has a "Recents" tab: an MRU of handles, shared with the TUI via `~/.letta/settings.json` on desktop.
  - The effort selector offers none, minimal, low, medium, high, xhigh and max where the model supports them.
- **Proposal:**
  - Add `SL/data/model/RecentModelsStore.kt` (MRU of 8, `expect`/`actual` persistence). The desktop actual reads and merges the `recentModels` key in `~/.letta/settings.json` without clobbering other keys.
  - Add a Recents section in `UI/modelcontrol/*`.
  - Extend the effort enum.
- **Acceptance:** the MRU updates on `update_model` success. Desktop round-trips the TUI file. Unknown keys are preserved.
- **Effort:** S · **Priority:** P2 · **Deps:** none · **Platform:** shared

### Class E: Device status, commands and agent tools

**F19 · Typed `update_device_status` repository**
- **Class:** E
- **Problem:** we type only cwd, mode and revision, so many features cannot be built. These include the git branch indicator, server version, supported commands, toolsets, background processes, pending approvals and experiments.
- **Reference behaviour:** the whole `device_status` drives UI state.
- **Proposal:**
  - Extend `SL/data/transport/appserver/AppServerDeviceStatus.kt`, keeping it tolerant (unknown fields ignored, malformed fields read as null), with:
    - `gitContext{branch, recentBranches}`
    - `lettaCodeVersion`
    - `supportedCommands`
    - `currentToolset`, `toolsetPreference`, `availableToolsets`
    - `backgroundProcesses`
    - `pendingControlRequests`
    - `experiments`
    - `memoryDirectory`
    - `isProcessing`
  - Add `SL/data/controller/DeviceStatusRepository.kt` exposing `StateFlow<DeviceStatusSnapshot?>` per runtime.
  - Show the branch in the composer cwd chip.
- **Acceptance:** decode tests against 0.29, 0.32 and 0.33 captures. The branch chip updates on checkout.
- **Effort:** M · **Priority:** P1 · **Deps:** none · **Platform:** shared

**F20 · Slash commands through `execute_command`**
- **Class:** E
- **Problem:** server slash commands go through REST/Iroh paths that App Server backends lack. The list is static, not what the server supports.
- **Reference behaviour:**
  - Autocomplete is built from `device_status.supported_commands` (0.33: clear, clear-messages, doctor, dream, reflect, init, compact, reload, context-limit, channels, upgrade-letta-code, toolset, secret, monitor_stop).
  - The client sends `execute_command {command_id, args?, runtime, request_id}` and reads `execute_command_response` (0.33) plus the `command_*` deltas.
- **Proposal:**
  - Add `AppServerCommand.ExecuteCommand` and its response.
  - Add an App Server branch in `SL/data/commands/SlashCommandApi.kt`.
  - Merge the device commands with the client-local commands in `ComposerAutocompleteViews.kt`.
- **Acceptance:** `/compact` against an App Server compacts the conversation and renders via F08. Unsupported commands are hidden.
- **Effort:** M · **Priority:** P2 · **Deps:** F19, F08 · **Platform:** shared

**F21 · Background processes shelf with stop**
- **Class:** E
- **Problem:** agents start long-running bash, agent tasks and monitors. Users cannot see or stop them from our client.
- **Reference behaviour:**
  - A shelf above the composer lists `device_status.background_processes`: bash, agent_task, and in 0.33 monitor and workflow. Each entry shows description and age.
  - Monitors can be stopped with `monitor_stop {process_id}` (15 s confirmation).
- **Proposal:**
  - Add `UI/chat/surface/composer/BackgroundProcessShelf.kt` fed by F19.
  - Add `AppServerCommand.MonitorStop`.
  - On desktop, merge into `DesktopBackgroundTasksPanel.kt`.
- **Acceptance:** the shelf appears and disappears with the device status. Stop sends the command, and the item disappears after confirmation.
- **Effort:** S · **Priority:** P3 · **Deps:** F19 · **Platform:** shared

**F22 · Toolset selector**
- **Class:** E
- **Problem:** some models work better with a specific toolset (codex vs default vs letta), and users cannot switch.
- **Reference behaviour:** a menu lists `available_toolsets{id, display_name, description, is_featured}` and sends `update_toolset {runtime, toolset_preference}`. The current value comes from device status.
- **Proposal:** add `AppServerCommand.UpdateToolset` and a toolset row in the model-control sheet (`UI/modelcontrol`).
- **Acceptance:** selecting a toolset updates `current_toolset` in device status. The control is hidden when the server reports no list.
- **Effort:** S · **Priority:** P3 · **Deps:** F19 · **Platform:** shared

**F23 · Cron pause/resume and live refresh on App Server**
- **Class:** E
- **Problem:** schedules can only be deleted, not paused. The list goes stale on App Server backends because `crons_updated` is decoded only on the MobileWs path.
- **Reference behaviour:** `cron_pause` and `cron_resume {scheduled_for?}` (0.33; status `paused`), plus a refresh on the `crons_updated` push.
- **Proposal:**
  - Add the commands in `AppServerCommand.kt` and decode `crons_updated` in `AppServerInboundFrame.kt`.
  - Add `CronRepository.pause/resume`.
  - Add a pause toggle in `UI/schedules/*` and `DK/schedules/*`.
  - Feature-gate by server version and hide the toggle on servers older than 0.33.
- **Acceptance:** pause and resume round-trip. A schedule created from another client appears without a manual refresh.
- **Effort:** S · **Priority:** P2 · **Deps:** F10 · **Platform:** shared

### Class F: Memory, secrets and workspace

**F24 · MemFS browser with history and diffs**
- **Class:** F
- **Problem:** we can write and delete memory files but cannot list them, see how memory changed, or audit what the agent rewrote.
- **Reference behaviour:**
  - `list_memory {agent_id, include_references}` returns several frames, with `done:true` on the last.
  - `read_memory_file` supports utf8 or base64.
  - `memory_history` gives a commit list. `memory_commit_diff {sha}` and `memory_file_at_ref` show past versions.
  - A `memory_updated{affected_paths}` push refreshes the view.
  - `enable_memfs` is offered when memfs is off.
  - The UI splits system and external files and guards unsaved changes.
- **Proposal:**
  - Add a multi-frame request helper (`AppServerRequestCorrelator.requestStream(type, isFinal)`) and the commands and responses above.
  - Add `SL/data/memory/MemfsRepository.kt`.
  - Add a History tab and a diff view to `UI/memory/MemoryPage` (reusing `UnifiedDiff.kt`).
- **Acceptance:**
  - A multi-page listing is collected completely, and a timeout error is surfaced.
  - The history shows commits, and selecting one shows the diff.
  - A `memory_updated` push refreshes an open file, with a conflict prompt when there are unsaved edits.
- **Effort:** L · **Priority:** P2 · **Deps:** none · **Platform:** shared

**F25 · Agent secrets vault**
- **Class:** F
- **Problem:** agents' tools need API keys, and users have no client UI to set them. The alternative is pasting keys into chat, which is unsafe.
- **Reference behaviour:** a per-agent "Vault" page. `secret_list {agent_id}` returns key names only. `secret_apply {agent_id, set:{k:v}, unset:[k]}` updates them. Values are write-only in the UI.
- **Proposal:**
  - Add the commands in `AppServerCommand.kt`.
  - Add `SL/data/secrets/AgentSecretsRepository.kt`.
  - Add `UI/agent/AgentSecretsPane.kt`, reachable from the agent settings on both platforms.
  - Never log values; redact them in F29.
- **Acceptance:** keys are listed without values. Set and unset round-trip. Values are absent from telemetry and frame logs (test).
- **Effort:** M · **Priority:** P3 · **Deps:** none · **Platform:** shared

**F26 · `@` file mentions and read-only file viewer**
- **Class:** F
- **Problem:** users cannot point the agent at a file in its workspace without knowing and typing the full path, and cannot view a file the agent mentions.
- **Reference behaviour:**
  - Typing `@` searches the device workspace with `search_files {query, max_results:25, cwd}`, debounced, and inserts the path.
  - Clicking a path in a tool card opens a viewer using `read_file`.
- **Proposal:**
  - Add the `search_files` and `read_file` commands.
  - Add a file-mention provider in the composer autocomplete.
  - Add `UI/workspace/FileViewerSheet.kt`: read-only, syntax-highlighted via `SharedMarkdownCodeFence`, with a size cap.
- **Acceptance:** results arrive within the cwd and the selected path is inserted. Tool-card paths open the viewer. Binary or oversized files show a placeholder.
- **Effort:** M · **Priority:** P3 · **Deps:** F19 (cwd) · **Platform:** shared

### Class G: Notifications, background and diagnostics

**F27 · Approval-needed notifications and per-agent mute**
- **Class:** G
- **Problem:** an agent waiting for approval in the background just stalls. Our notifications fire only on turn completion. Noisy agents cannot be muted.
- **Reference behaviour:**
  - An OS notification ("<agent> is waiting for approval") when an approval arrives while the window is unfocused or the app is backgrounded. Clicking it opens the conversation.
  - The same applies to "done".
  - A badge clears on focus.
  - Each agent has a mute toggle.
- **Proposal:**
  - Add `SL/data/notify/AttentionEvents.kt`, which derives `ApprovalNeeded`/`TurnDone` from runtime events with focus gating.
  - Desktop: post through `DK/DesktopNucleusEffects.kt`.
  - Android: through `NotificationDeliveryCoordinator` (`AP/channel/*`), with an "Open" action.
  - Store per-agent mute in settings.
- **Acceptance:** an unfocused approval posts exactly one notification per request id. A muted agent posts none. A click deep-links to the conversation.
- **Effort:** M · **Priority:** P1 · **Deps:** none · **Platform:** shared + desktop + Android

**F28 · Keep-awake while processing, with a heartbeat watchdog**
- **Class:** G
- **Problem:** long turns die when the laptop sleeps mid-run, and a stuck "processing" indicator can stay forever.
- **Reference behaviour:**
  - While any runtime is processing, the app holds a power-save blocker that prevents suspension.
  - The UI heartbeats every 1 s. If heartbeats stop, a watchdog clears the processing indicators and the blocker.
  - Everything is released on idle.
- **Proposal:**
  - Add `SL/data/runtime/ProcessingActivityTracker.kt`, which aggregates loop status per runtime into `Flow<Boolean>` with a staleness timeout.
  - Desktop actual: `SetThreadExecutionState(ES_CONTINUOUS|ES_SYSTEM_REQUIRED)` via JNA on Windows, with no-ops elsewhere.
  - Android: the existing foreground service plus a partial wake lock scoped to the active turn.
  - Add an opt-out setting.
- **Acceptance:** the blocker is acquired at turn start and released at turn end or on watchdog expiry (tests with a fake clock). The Windows sleep timer does not fire during a 10-minute turn (manual).
- **Effort:** M · **Priority:** P2 · **Deps:** F07 (loop phases) · **Platform:** shared + desktop + Android

**F29 · Wire-frame inspector (debug panel)**
- **Class:** G
- **Problem:** protocol bugs are hard to diagnose from user reports, and we keep no record of what was actually sent and received.
- **Reference behaviour:**
  - An opt-in debug panel with a bounded ring buffer of inbound and outbound WebSocket messages, with timestamp, direction and type.
  - The buffer is only fed while a subscriber is open, so it costs nothing otherwise. It can be cleared and exported.
- **Proposal:**
  - Add `SL/data/transport/appserver/FrameTap.kt`: a ring buffer of the last 2000 frames, redacted (tokens, `secret_apply` values, image base64 truncated), gated by a subscriber count.
  - Hook it in the transport adapter for both WS and Iroh.
  - Add the viewer `UI/diagnostics/FrameInspector.kt`, with filter by type/runtime and export to JSONL, on the desktop debug destination and the Android Telemetry screen.
- **Acceptance:** no allocation when there are no subscribers (test). Redaction tests pass. The exported JSONL can be replayed by `appserver-cli`.
- **Effort:** M · **Priority:** P2 · **Deps:** none · **Platform:** shared

**F30 · Runtime log viewer and diagnostics bundle**
- **Class:** G
- **Problem:** desktop runtime logs live in `~/.letta-mobile/logs/local-runtime.log`, which users never find. Crash context is lost.
- **Reference behaviour:** a log viewer with level filters and download, crash stderr flushed into the log, and a "download logs" action.
- **Proposal:**
  - Add `DK/diagnostics/DesktopLogViewer.kt`, which tails the runtime log, the app log and F03's stderr ring.
  - Add an "Export diagnostics" action that zips logs, the F29 export, the version and settings (redacted) to a user-chosen path.
  - Android: an export of the telemetry and frame logs through the share sheet.
- **Acceptance:** the viewer live-tails. The exported zip contains the expected files with no secrets (test against a redaction fixture).
- **Effort:** M · **Priority:** P3 · **Deps:** F03, F29 · **Platform:** desktop (+ Android export)

---

## 6. Deferred (observed, not numbered)

These are not in F01–F30. They are either large, product decisions, or low value for a mobile-first client:

- Embedded terminal (`terminal_*`), a full file editor and tree (`file_ops` CRDT, `watch_file`), the branch diff view (`branch_diff_*`, not in letta-code 0.33 OSS protocol_v2), and branch checkout.
- Complete channel CRUD: accounts bind/unbind, pairings, routes, targets.
- Teleport between computers.
- Popout windows.
- Voice transcription on desktop.
- More UI locales: the reference app ships fr, zh-CN and pt-BR. We first need to finish extracting the desktop's hard-coded strings.
- A keyboard-shortcut registry and help overlay: the reference app shows hotkeys with mod+P.
- Run step trace: TTFT and a token breakdown.
- Memory-pressure trimming of cached timelines.

## 7. Suggested PR grouping (7 PRs, one agent each)

| PR | Functions | Main files touched | Notes |
|---|---|---|---|
| PR-1 Connection & runtime lifecycle | F01, F02, F04, F03, F05 | `SL/data/transport/appserver/AppServerProbe.kt`, `controller/reconnect/*`, `DK/runtime/*`, settings cards | Desktop runtime files only here |
| PR-2 Stream integrity & status | F06, F07, F08, F09, F10 | `controller/fanout/*`, `AppServerRuntimeEventMapper.kt` (stream_delta branches), `core/runtime` payloads, composer indicator, contract fixtures | **Land before PR-3**: both edit `AppServerRuntimeEventMapper.kt` and `core/runtime` payloads |
| PR-3 Approvals & permission modes | F11, F12, F14, F13 | `ToolApprovalRequest`, mapper `toApprovalOrExternalDraft`, `ChatRowApprovals.kt`, `ApprovalOutbox.kt`, `PermissionModeChip.kt` | F13/F14 need F19 (PR-4). Ship F11/F12 first, or rebase after PR-4 |
| PR-4 Device status & commands | F19, F20, F21, F22, F23 | `AppServerDeviceStatus.kt`, `DeviceStatusRepository.kt`, `AppServerCommand.kt` (new commands), `SlashCommandApi.kt`, schedules | `AppServerCommand.kt` is also touched by PR-5 and PR-6 (append-only, so trivial conflicts) |
| PR-5 Conversations & models | F15, F16, F17, F18 | `ConversationRepository`, timeline message menu, `DK/chat/DesktopChatController.kt`, sidebar, `UI/modelcontrol/*` | Independent |
| PR-6 Memory, secrets & workspace | F24, F25, F26 | `AppServerRequestCorrelator` (multi-frame), `MemfsRepository.kt`, `UI/memory/*`, secrets pane, file viewer | Independent |
| PR-7 Attention, power & diagnostics | F27, F28, F29, F30 | `SL/data/notify/*`, `ProcessingActivityTracker.kt`, `FrameTap.kt`, `DK/DesktopNucleusEffects.kt`, `DK/diagnostics/*`, Android notification coordinator | F28 needs F07 (PR-2); F30 needs F03 (PR-1) |

**Recommended order:** start PR-1, PR-2, PR-5 and PR-6 in parallel. Then PR-4. Then PR-3 and PR-7.

## 8. Open questions

1. **Version pin:** should we move the contract baseline and the desktop bundled runtime to 0.33.6, matching the reference app (F10)? Or keep 0.32.x and only make decoding tolerant?
2. **Permission-mode default:** should new installs default to Standard, as the reference app does, instead of today's Unrestricted (F13)? This is a behaviour change for existing users.
3. **Recent models:** should desktop share `~/.letta/settings.json` with the letta-code TUI (F18)? That couples us to an upstream file format.
4. **Keep-awake on Android:** is a turn-scoped partial wake lock acceptable for battery policy (F28)?
5. **Secrets vault:** should it be exposed on Android, or stay desktop-only for safety (F25)?
