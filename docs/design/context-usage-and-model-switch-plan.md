# Context usage, compaction and model switch: research and plan

Date: 2026-10-09. Status: plan (no code in this PR). Tracked by the beads epic filed alongside
this document (see section 9).

Goal: an agent side-drawer card with (a) a model chip (current model plus reasoning effort; tap
opens a searchable list that changes the model) and (b) a slim context meter that opens a sheet
with a per-category breakdown, used/limit, and a **Compact** button.

## 1. Method

- Static reading only. `resources/app.asar` of the official Letta Desktop (`Programs\letta-code`,
  v0.33.6) was extracted with `@electron/asar` to a scratch dir on `E:` (deleted afterwards).
  The REA CLI was not needed; `rea setup` was not run. Bounded grep windows over the renderer
  chunk `dist/assets/index-*.js` and over the bundled
  `app.asar.unpacked/node_modules/@letta-ai/letta-code/letta.js` (0.33.6, Apache-2.0, same
  version as our desktop pin, so no diff against our pin was needed).
- The app was not launched, no network traffic captured, no credentials touched.
- Clean-room: renderer behaviour is described in our words. Only letta-code protocol facts are
  quoted. Evidence below names functions and frame names; `letta.js:NNNN` are line numbers in the
  0.33.6 bundle (stable for that version only).
- Our repo was read at `origin/main` (`592e0d4ff`, after #1827). Paths are relative to
  `android-compose/` unless stated.

## 2. Answer to the open question: breakdown or total only?

| Source | Per-category breakdown? | Evidence |
|---|---|---|
| App Server WebSocket (letta-code 0.33.6), direct | **No. Total only.** | `usage_statistics` stream delta carries `context_tokens`; `device_status`, `update_loop_status` carry no context data; none of the inbound command parsers (`letta.js:196937-197800`) is a context query. |
| Letta server REST `GET /v1/agents/{id}/context` | **Yes**, plus window size | `ContextWindowOverview` schema (renderer SDK bundle) and `getAgentContextOverview` (`letta.js:216110`). |
| Iroh `admin_rpc agent.context` (our wrapper host) | **Shape yes, numbers are an estimate** | `LocalBackendContextReader.kt`: `system = ceil(len/4)`, `messages = count*50`, `window = 200000` constant, core memory/tools/summary all 0. Without `--local-backend-dir` the method returns `capability_unavailable` (`AgentAdminHandlers.registerAgentContext`). |
| Bundled local backend (Android embedded / desktop local) | Same estimate | `AppServerLocalRepositoryTransport.getContext` calls the same `agent.context`. |

Consequences:

1. The official desktop GUI itself shows **total only** (section 4). The breakdown exists only in
   letta-code's TUI `/context` (REST based) and in leftover, unreferenced "ADE Context Window"
   strings in the renderer (system instructions, tool descriptions, core memory, external summary,
   messages, recursive memory, recall, archival, plus a "Summarize" button). No renderer code
   uses those strings in 0.33.6. We are therefore **ahead of the reference** wherever a breakdown
   is available, and must label provenance honestly where it is not.
2. Our existing code already has both a total-only path and a breakdown path (section 6). The
   work is mostly provenance, relay of compaction, the card/sheet, and fixing the Iroh estimate.
3. Do not present the Iroh estimate as real. Real per-category counts over Iroh need the host to
   obtain them from a Letta server REST (not available for the pure local backend, where no
   tokenizer exists; letta-code itself estimates with chars/4: `estimateStoredMessageTokens`,
   `letta.js:479640`).

## 3. Wire level (letta-code 0.33.6, protocol facts)

### 3.1 Context usage

- **`usage_statistics` delta** (`createUsageStatisticsChunk`, `letta.js:108975`; also the cloud
  server stream). Fields: `prompt_tokens`, `completion_tokens`, `total_tokens`,
  `cached_input_tokens`, `cache_write_tokens`, `reasoning_tokens`, `context_tokens`, `step_count`.
  `context_tokens` is the whole prompt the call held (cached prefix included); the official
  renderer takes the **latest positive** one per `agent:conversation` runtime
  (`getLatestContextTokens`) and caches it across reloads. `prompt_tokens` is not a substitute
  (cached calls report only the uncached tail). When the provider reports no usage the local
  backend falls back to a chars/4 estimate of the request (`estimateProviderContextTokens`).
  Emitted once per model call, i.e. at turn/step boundaries, not continuously.
- **Window size** is not on the wire. The official renderer resolves it by precedence:
  conversation `context_window_limit` > conversation `model_settings.context_window` /
  `max_context_window` > agent `llm_config.context_window` > model catalog entry
  `max_context_window`. Our `contextWindowTokensOf` already mirrors this
  (`sharedLogic/.../data/context/ContextWindowLimit.kt`).
- **REST `/v1/agents/{id}/context`** (`context_window_overview`), optional `conversation_id`.
  Fields (tokens unless noted): `context_window_size_max`, `context_window_size_current`,
  `num_messages`, `num_archival_memory`, `num_recall_memory` (counts), `num_tokens_system`
  (+ `system_prompt`), `num_tokens_core_memory` (+ `core_memory`),
  `num_tokens_functions_definitions` (+ `functions_definitions`), `num_tokens_messages`
  (+ `messages`), `num_tokens_summary_memory` (+ `summary_memory`),
  `num_tokens_external_memory_summary`, and optional `num_tokens_memory_filesystem`,
  `num_tokens_tool_usage_rules`, `num_tokens_directories`. Our `ContextWindowOverview` model
  already matches (`data/model/Agent.kt:20`).
- The TUI `/context` (`renderContextUsage`, `letta.js:501640`) draws six categories (System, Core
  Memory, Tools, Messages, Summary, Other = external memory) and **rescales** the category
  weights to the streamed used total, labelling it "Estimated usage by category". That rescale is
  `scaleBreakdownToUsedTokens` in the web memory viewer (`letta.js:479578`). Lesson: the REST
  breakdown and the streamed total can disagree; reconcile, do not mix raw (our
  `ContextWindowUsage.from` already does this with an "Other/Unitemised" segment).
- `device_status` (`buildDeviceStatus`, `letta.js:189396`) has `supported_commands`, `is_processing`,
  current model data via `agent_retrieve`; **no** token fields.

### 3.2 Compaction

Two entry points, both reach the same server call (`conversations.messages.compact`).

1. `execute_command {command_id:"compact", request_id, runtime:{agent_id,conversation_id}, args?}`
   (`handleExecuteCommand`, `letta.js:420758`; `handleCompactCommand`, `letta.js:421035`).
   - `args` optional mode: `all | sliding_window | self_compact_all | self_compact_sliding_window`
     (`VALID_COMPACT_MODES`; `help` prints usage). Unknown mode throws.
   - Runs PreCompact hooks first (may block), calls the API, marks post-compaction reminders,
     optionally launches a reflection subagent (if reflection trigger is `compaction-event` and
     MemFS is on), regenerates the conversation description.
   - Lifecycle: `slash_command_start {command_id,input}` delta, then `slash_command_end
     {command_id,input,output,success}` delta, then `execute_command_response
     {request_id,success,output}` (response frame is 0.33-only; the embedded 0.26.1 runtime does
     not emit it, see `transport/appserver/README.md`).
   - `output` is a **human string**: "Compaction completed (mode: X). Message buffer length
     reduced from N to M. Summary: ...". Not structured. Special case: HTTP 400 "Summarization
     failed to reduce the number of messages" becomes the success text "Compaction run, but the
     number of messages is the same".
2. `conversation_compact {request_id, conversation_id, body?}` ->
   `conversation_compact_response {request_id, success, compaction:{summary,
   num_messages_before, num_messages_after}, error?}` (`letta.js:198335`). Structured, no hooks,
   no reflection. For `conversation_id == "default"` the body must carry `agent_id`
   (that is what `handleCompactCommand` does). `body.compaction_settings` may override
   `{mode, model, ...}` for this call.

Compaction in the **message stream** (both manual and automatic):

- `event_message {event_type:"compaction", event_data:{trigger}}` marks start
  (renderer shows a muted "compacting" line).
- `summary_message {summary, compaction_stats}` marks completion. `compaction_stats`:
  `trigger` (`context_window_exceeded` | `post_step_context_check`, or a manual trigger string),
  `context_tokens_before`, `context_tokens_after`, `context_window`, `messages_count_before`,
  `messages_count_after`. The renderer shows a collapsible warning-coloured card "Summary" with
  "before -> after messages" and the summary text. This is the only place that gives
  before/after **tokens**; the two compact entry points give message counts only.

Auto-compaction: server side. The Letta server compacts on overflow
(`context_window_exceeded`) and after a step when over its threshold (`post_step_context_check`).
The local backend compacts before a call when `context_tokens > window - min(16384, 0.2*window)`
(`contextCompactionThreshold`, `letta.js:109144`), up to 3 times per turn. There is no client-side
auto-compact threshold setting in Desktop.

Compaction settings are **agent state** (`agent.compaction_settings`): `mode`
(the four above), `model` (summarizer model), `prompt`, `clip_chars`,
`sliding_window_percentage` (0..1, kept share after sliding-window summarization),
`prompt_acknowledgement`. Written through the normal agent update (renderer settings panel
"Compaction Settings"; TUI `/compaction`).

### 3.3 Context limit and model

- `execute_command {command_id:"context-limit"|"set-max-context", args:"[tokens|200k] [--override]"}`
  (`applySetMaxContext`, `letta.js:420055`). No args resets to the model default. On the default
  conversation it writes `agent.context_window_limit`; otherwise the conversation's. Values above
  the model default need `--override`.
- `list_models {request_id, force?}` -> `list_models_response {entries[], available_handles,
  byok_provider_aliases}`. Entry: `id, handle, label, description, isDefault?, isFeatured?, free?,
  supportsStructuredOutputs?, updateArgs{reasoning_effort?, context_window?, max_output_tokens?,
  provider_type?...}`. **One entry per (handle, effort tier, window)**: effort tiers are separate
  catalog entries sharing a handle.
- `update_model {request_id, runtime:{agent_id,conversation_id}, payload:{model_id?|model_handle?,
  reasoning_effort?}}` -> `update_model_response {success, applied_to:"agent"|"conversation",
  model_id, model_handle, model_settings, error?}` (`applyModelUpdateForRuntime`, `letta.js:402240`).
  Scope rule: **default conversation updates the agent; any other conversation gets a
  per-conversation override** (conversation `model` + `model_settings`). It also **preserves the
  current context window** when the same registry model is re-selected with a different effort
  (`shouldPreserveContextWindowForModelSelection`), switches the toolset for the new model, emits a
  status delta "Model updated to X (Effort)", and re-emits runtime state.
  `reasoning_effort` values: `none|minimal|low|medium|high|xhigh|max|null`.

## 4. Official Desktop renderer behaviour (own words)

- **Meter**: a small donut icon button ("Usage") in the composer area; opens a 360 px popover with
  a "Context window" row: percent, a thin bar, and "used / capacity tokens" in compact units
  (`k`, `M`). One neutral colour: **no warn/auto-compact thresholds, no categories**. Shown only
  when the display option "Token usage" is on (default off; options menu also has "Reasoning" and
  "Compactions"). Hidden until a positive `context_tokens` has been seen for the runtime; the
  last value is cached per `agent:conversation` so it survives navigation.
- **Compact**: not a button. It is the palette/slash item "Compact conversation", enabled only
  when the connected device advertises `compact` in `supported_commands`; typed args pass through
  (`/compact all`). Result is rendered by the generic slash-command rows and the compaction
  event/summary rows above. A separate "Compaction Settings" panel edits mode, summarizer model,
  prompt, clip chars, sliding-window percentage, prompt acknowledgement.
- **Model picker**: tabs Recent / Hosted / All / BYOK; search by label, description and handle;
  entries grouped per handle (a "split by context window" variant groups per handle+window); a
  featured badge, tier badges, BYOK badge, per-model details (input/output cost), copy-handle
  action, refresh of the list (force reload), an "add more models" shortcut. **Reasoning effort**
  is a separate row above the list for the selected model: the tiers come from the catalog entries
  of that handle, ordered off < none < minimal < low < medium < high < xhigh/max; a tier is
  selected by choosing the entry with that effort (so effort and window travel together).
  Selecting sends `update_model` with `model_id` (or `model_handle`, plus the BYOK override
  handle) and a 15 s timeout; the UI applies the result optimistically and rolls back on
  failure. The composer chip shows label plus effort. Scope: per-conversation override unless on
  the agent's default conversation (server decides, see `applied_to`).

## 5. Letta server REST (for HTTP-connected sessions)

- Context: `GET /v1/agents/{id}/context[?conversation_id=]` (letta-code `getAgentContextOverview`).
  Our `AgentApi.getContextWindow` already calls it with `mobile_safe=true&include_raw=false`.
- Model change: `PATCH /v1/agents/{id}` with `model` (handle) and/or `model_settings`
  (reasoning effort lives in `model_settings.reasoning_effort` or the legacy
  `llm_config.reasoning_effort`) and `context_window_limit`; conversations take `model`,
  `model_settings`, `context_window_limit` on `PATCH /v1/conversations/{id}`
  (letta-code `updateAgentLLMConfig`/`updateConversationLLMConfig`). Compact:
  `POST /v1/conversations/{id}/compact` (SDK `conversations.messages.compact`).
- Agent summary fields for the chip: `agent.model`, `llm_config.{model,context_window,
  reasoning_effort}`, `context_window_limit`, `compaction_settings`.

## 6. What our repo already has

| Capability | Where | State |
|---|---|---|
| Streamed total | `data/context/ContextTokenReadings.kt` folds `ServerFrame.UsageStatistics.contextTokens`; persisted via `ContextReadingSnapshots` | Done (r2zo8, wdm6i) |
| Window resolution | `ContextWindowLimit.kt` (`contextWindowTokensOf`: conversation switch, agent limit, model) | Done |
| Usage policy fold | `ContextWindowUsage.kt` (`from`, `total`, reconcile, free space), `ContextWindowUsageStore.kt` (`contextUsageStates`, settle rule, keep-last-good) | Done, shared |
| Chip UI | Desktop `ComposerContextChip` popover (`DesktopComposerContextUsage.kt`); Android chip fed by `AdminChatContextUsage.kt` | Total-only, because the loader only feeds readings |
| Breakdown fetch | `IAgentRepository.getContextWindow` -> HTTP `AgentApi`, Iroh `agent.context` (`IrohAdminRpcAgentSource`), bundled `transport.getContext`; used by `ProjectChatCoordinator.refreshContextWindow` and the Android `ContextWindowCard` (`AgentScaffoldPickers.kt:581`) | Done, but not wired to the chip/popover, and Iroh numbers are an estimate |
| Model list/update | `ModelControlWire` (`model.list`, `model.update`), `ModelPickerCatalog/Controller/State` (groups, recents, search, collapse), `ReasoningTier`, `ConversationModelSelections` (per-conversation switch + rollback), `ComposerEffort` | Done, relayed over Iroh (`ModelAdminHandlers`) |
| Compact commands | `AppServerCommand.ConversationCompact`, `ExecuteCommand`; `AppServerClient.conversationCompact/executeCommand`; `AppServerSlashCommandApi` (`DEFAULT_SUPPORTED_COMMANDS` includes `compact`, `context-limit`); `AppServerContextWindowPreflight` uses `conversation_compact` as an automatic repair | Client types done; no user-facing Compact action |
| Compaction rendering | `slash_command_*` deltas -> `RuntimeEventPayload.CommandStarted/Finished` (F08). **No** handling of `event_message`/`summary_message`/`compaction_stats` | Gap |
| Iroh relay | `admin_rpc` router (`AdminRpcRegistry`), per-method capability map (`IrohPeerCapabilities.forAdminMethod`), typed control frames only for `runtime_start/input/sync/abort_message/remove_queue_item/resume_queue` (`IrohNodeConnection.kt:~480-520`). Slash commands over Iroh: `slash_command.list*` is `CapabilityUnavailable("admin_rest")`; **no** `execute_command` or `conversation_compact` relay | Gap |

HTTP-only / failing-over-Iroh paths relevant here (same class as the Tool Detail fix, #1827):
`execute_command`, `conversation_compact`, `agent.update` for `compaction_settings` /
`context_window_limit` (the latter is relayed, verify the wrapper's `agent.update` allowlists the
fields), and slash-command listing. `agent.context` and `model.list/update` do relay.

## 7. Proposed design

### 7.1 Shared logic (commonMain)

New package `data/context/` additions, no platform code:

```kotlin
enum class ContextProvenance { Exact /*server tokenizer*/, Estimated /*host/letta-code chars/4*/, TotalOnly }

data class ContextMeter(            // what the card renders
    val usage: ContextWindowUsage,  // existing type
    val provenance: ContextProvenance,
    val windowSource: WindowSource, // Conversation | Agent | Model | Unknown
    val asOfMs: Long?,              // age of the streamed total
    val autoCompactAt: Float?,      // known only for local backend: 1 - min(16384, 0.2*w)/w
)

interface ContextMeterSource {      // one per surface, built from existing pieces
    val meter: Flow<ContextMeter?>  // streamed total (ContextTokenReadings) + window (contextWindowTokensOf)
    suspend fun loadBreakdown(): ContextBreakdownResult // calls IAgentRepository.getContextWindow
}
```

- **Merge rule** (extends `ContextWindowUsagePolicy`): the streamed `context_tokens` is the
  authority for *used*; a fetched overview supplies *categories* only and is rescaled to the
  streamed total when both exist and are within the same turn (same as TUI), with the leftover
  as "Other". If only the overview exists (no stream yet, e.g. fresh app), use its totals.
  Provenance is `Exact` only on HTTP; Iroh/local overviews are `Estimated`; none -> `TotalOnly`.
- **Fetch policy**: breakdown is loaded **lazily when the sheet opens** and after a settled turn
  or compaction (never mid-turn, existing `settled` rule). Never polled. Streamed total keeps the
  meter live for free.
- `CompactionRepository` (interface in commonMain, `AppServerClient`-backed implementation):
  `suspend fun compact(agentId, conversationId, mode: CompactionMode? = null): CompactionResult`
  with `CompactionResult{ summary?, messagesBefore?, messagesAfter?, tokensBefore?,
  tokensAfter?, noChange: Boolean }`.
  - Strategy A (preferred when the device advertises `compact`): `execute_command compact`
    (gets hooks and reflection parity with Desktop). Result text is unstructured, so *success* is
    `execute_command_response.success`, and numbers come from the `summary_message.compaction_stats`
    that follows on the stream (or a post-compaction `loadBreakdown`).
  - Strategy B (fallback, and the Iroh path): `conversation_compact` (structured counts). For the
    default conversation include `agent_id` in `body`.
  - Strategy chosen by capability: `device_status.supported_commands` contains `compact` and the
    host is >= 0.33; else B; else the button is hidden. Never guess on a timeout.
- **Compaction lifecycle events**: map `event_message{event_type:compaction}` ->
  `RuntimeEventPayload.CompactionStarted(trigger)` and `summary_message` ->
  `CompactionFinished(summary, stats)` in `AppServerRuntimeEventMapper`; a timeline row (collapsed
  summary card, "N -> M messages", optional "X -> Y tokens") and a presence phase
  ("Compacting") in `RunPhaseReducer`. This also covers *automatic* compactions, which today
  are invisible. After `CompactionFinished`, write `tokensAfter` into `ContextTokenReadings`
  (a reading with provenance "compaction") so the meter drops immediately instead of waiting for
  the next turn.
- **Model chip**: reuse `ModelPickerController`, `ModelPickerCatalog.filter/groups`,
  `ReasoningTier`, `ConversationModelSelections`. New small `ModelChipState(label, effort,
  scope: Agent|Conversation, window)` derived from agent + selections. Scope is decided by
  the server (`applied_to`); we show a "this conversation only" hint when it is `conversation`.

### 7.2 Command and RPC wiring

| Need | Direct WS (App Server) | Iroh | HTTP (Letta server) |
|---|---|---|---|
| Streamed total | `usage_statistics` (done) | relayed stream (done) | n/a (use REST) |
| Breakdown | none; show total + free | `agent.context` (exists; estimate) | `GET /v1/agents/{id}/context` (done) |
| Compact | `execute_command` / `conversation_compact` (client done) | **new** `conversation.compact` admin_rpc | `POST /v1/conversations/{id}/compact` |
| Model list/change | `list_models` / `update_model` (done) | `model.list` / `model.update` (done) | `PATCH` agent/conversation (done by `AgentApi`) |
| Context limit | `execute_command context-limit` | **new** admin_rpc or `agent.update`/`conversation.update` with `context_window_limit` | same PATCH |

**Iroh relay (needs wrapper host redeploy).** Follow the `WorkspaceAdminHandlers` /
`ToolAdminHandlers` pattern, not a new control frame (control frames need
`IrohPeerCapabilities.forProtocolCommand` and a branch in `IrohNodeConnection`, as the queue relay
did; admin_rpc is cheaper and already capability-mapped):

1. `ConversationAdminHandlers`: register `conversation.compact`
   (`params: conversation_id, agent_id?, mode?`) -> `nativeClient.conversationCompact(...)`
   (the host's `AppServerClient` already supports it); result
   `{summary, num_messages_before, num_messages_after}`. Use `NativeAdmin.require(nativeClient,
   NativeAdminOp.ConversationCompact)` like `ModelAdminHandlers`; add the op to
   `NativeAdminOperationPolicy` (compaction is a mutation: not auto-retried, matches
   `AppServerCommandRetryClass.AmbiguousMutation`).
2. `IrohPeerCapabilities.forAdminMethod`: map `conversation.compact` to `CONVERSATION_MANAGE`
   (not `admin.full`), add to `AdminRpcRegistry.canonicalMethods`. Already-paired desktops hold
   `CONVERSATION_MANAGE` by default, so no capability migration.
3. Optional: `agent.context` improvement on the host so Iroh numbers stop being fiction: tag the
   result `"source":"estimate"`, use the agent's real `context_window_limit`/model window instead
   of the 200000 constant, count core-memory/tools from the memfs and toolset the host already
   knows. Client reads `source` to set `ContextProvenance`.
4. Client: `IrohAdminRpcAgentDirectory.compactConversation(...)` + `IrohAgentRepository`; a
   `capability_unavailable` / unknown-method answer from an older host maps to
   `CompactionResult.Unsupported` and **hides** the Compact button (the #1827 pattern: typed
   fallback, specific error text, no silent HTTP dial).

**Version / capability gating**: Compact visible iff (device advertises `compact`) or (Iroh host
answers a cheap probe: `admin_rpc` method list / first-call capability error cached per host) or
(HTTP session). Embedded Android runtime 0.26.1 never emits `execute_command_response`; use
strategy B there and treat a missing `conversation_compact_response` as unsupported (verify on
device before claiming support). Model chip is gated by `list_models` success (existing).

## 8. UI plan (drawer card and sheet)

Card (Android side drawer, Desktop agent pane; one shared composable in `:sharedUI` once the
phase 3 move lands, otherwise thin per-platform wrappers over the shared state):

- Row 1: **model chip** = `label` + effort suffix ("Sonnet 4.5 - High"). Tap -> searchable list
  (bottom sheet on phone, popover on desktop) built from `ModelPickerState` (recents first, group
  per provider/handle, search over label/handle/description, refresh action, BYOK badge). Effort
  row above the list, enabled tiers from the selected handle's entries. Optimistic update with
  rollback via `ConversationModelSelections`.
- Row 2: **slim meter** = thin bar + "42k / 200k" + percent. Colour from our theme only:
  neutral < 70 %, warning 70-90 %, error >= 90 % (our design choice; the reference has none).
  If `autoCompactAt` is known draw a tick mark there. Tap opens the sheet.
- **Sheet**: header "Context window" + used/limit + provenance caption ("Exact", "Estimated by
  host", "Total only"); stacked bar with legend rows from `ContextWindowUsage.segments`
  (System prompt, Tool definitions, Core memory, Memory files, Summary, Messages, Other,
  Free), existing label/kind keys; for `TotalOnly` a single "In context" bar plus a hint
  line ("Breakdown needs a Letta server connection"). **Compact** button (+ overflow for mode:
  Default / All / Sliding window), disabled while a turn is running or compaction is in progress,
  hidden when unsupported. Optional later: "Set context limit" field.
- Progress: pressing Compact sets a local "Compacting..." state, shows the muted compaction row in
  the timeline from `CompactionStarted`, and on `CompactionFinished` the sheet shows "N -> M
  messages, X -> Y tokens" for a few seconds and the meter drops.

## 9. Edge cases

- **Model with a smaller window than current usage**: after `update_model` the meter can exceed
  100 %; `ContextWindowUsage.from/total` already widens `maxTokens` to hold usage. Show a warning
  strip "Context exceeds this model's window; compact before sending". Preserve-window rule: the
  server keeps the existing window on same-model effort changes, so refresh the window from
  `update_model_response.model_settings` rather than the catalog.
- **Compaction in progress / running turn**: disable Compact and model change while
  `isProcessing` (the wire protocol does not document how a busy agent treats `update_model`).
  A second Compact tap while one is in flight is ignored (idempotency key = conversation id).
- **Compaction that changes nothing**: map the 400 "failed to reduce" case to `noChange=true` and
  show "Already compact" (success tone).
- **Unsupported host** (old wrapper, embedded 0.26.1, no `compact` in `supported_commands`): hide
  Compact, keep meter; breakdown falls back to total-only.
- **Iroh without `--local-backend-dir`**: `agent.context` returns `capability_unavailable`; show
  total-only with the specific reason in the sheet, not "failed".
- **Default vs other conversation**: `conversation_compact` for `default` needs `agent_id`; model
  scope label depends on `applied_to`.
- **Stale readings**: the streamed total can predate a compaction; clear/replace on
  `CompactionFinished`; keep existing "settled" rule so mid-turn fetches are not shown.
- **Concurrent viewers**: a compaction by another client arrives as `summary_message`; the meter
  updates through the same path.
- **Frame size**: breakdown responses can be large (`messages`, `system_prompt`); keep
  `mobile_safe`/`include_raw=false` on HTTP and the host's `boundObjectStringFields` guard.

## 10. Test plan

- commonTest: `ContextMeter` merge/provenance matrix (stream only, overview only, both disagree,
  window smaller than usage, window unknown); `CompactionRepository` strategy selection by
  capability; `execute_command_response` / `conversation_compact_response` decoding fixtures taken
  from letta-code 0.33.6 (add to `LettaCode0336Frames.kt`); `event_message` + `summary_message` ->
  `Compaction*` mapping incl. missing stats; reducer test: compaction phase and meter drop.
- Iroh: host handler test for `conversation.compact` (success, no-change 400, no nativeClient ->
  `capability_unavailable`, wrong capability -> `authz.denied`) in the style of
  `ToolAdminHandlers` tests; client test that an older host's unknown-method answer yields
  `Unsupported` and hides the button (like `IrohHttpOnlyRoutesTest`).
- UI: Compose tests for the card states (loading, total-only, estimated, exact, over-window,
  compacting, unsupported); model chip search/effort/rollback; desktop popover parity.
- Manual: live host on 0.33.6 (compact via execute_command, auto-compaction visible), embedded
  0.26.1 (fallback), Iroh against a redeployed wrapper and against the old wrapper.

## 11. PR slicing and dependencies

| # | Slice | Depends on | Notes |
|---|---|---|---|
| S1 | `Compaction*` event mapping + timeline row + meter-drop on finish (client only) | none | Makes automatic compactions visible; no host change |
| S2 | `ContextMeter`/provenance model + lazy breakdown loader wired to the existing chips; Iroh `source` tolerance | none | Pure shared logic + tests |
| S3 | Host: `conversation.compact` admin_rpc + capability map + `agent.context` `source:"estimate"` and real window (**wrapper redeploy**) | none | Ship host first; old clients unaffected |
| S4 | Client `CompactionRepository` (A/B strategies, gating, Iroh method, HTTP route) | S1, S3 | Feature flag until host deployed |
| S5 | Drawer card + sheet UI (Android + Desktop), model chip surfaced in the card | S2, S4 | Reuses `ModelPicker*`; no new model plumbing |
| S6 | Compaction mode menu + context-limit field (agent.update / `context-limit`) | S5 | Optional |

S1, S2, S3 can proceed in parallel. S5 can start with S2 only (meter + model chip) and gain
Compact when S4 lands.

## 12. Beads

Epic `letta-mobile-pt1ze`. Children: S1 `letta-mobile-kr39h`, S2 `letta-mobile-cyh28`,
S3 `letta-mobile-57cta`, S4 `letta-mobile-3kble` (blocked by S1, S3), S5 `letta-mobile-3io8k`
(blocked by S2, S4), S6 `letta-mobile-joigh` (blocked by S5). Not pushed (`bd dolt push` is left
to the session-close protocol).
