# Context usage, compaction and model switch: research and plan

Date: 2026-10-09 (second pass the same day). Status: plan (no code in this PR). Tracked by the
beads epic `letta-mobile-pt1ze` (section 12).

**Premise (corrected by the user in the second pass): there is no Letta server.** The legacy
Python server is deprecated; the only backend is the letta-code process in **local-backend mode**
(desktop-bundled runtime, the Meridian host behind the Iroh wrapper, and the embedded Android
runtime). Everything below is about what that process emits and what we can compute ourselves.
The first revision designed around Letta server REST (`GET /v1/agents/{id}/context`); that design
is removed.

Goal: an agent side-drawer card with (a) a model chip (current model plus reasoning effort; tap
opens a searchable list that changes the model) and (b) a slim context meter that opens a sheet
with a per-category breakdown, used/limit and a **Compact** button.

## 1. Method

- Static reading only; app not launched, no network capture, no credentials. `rea setup` not run.
- Official Letta Desktop `resources/app.asar` extracted with `@electron/asar` to a scratch dir on
  `E:` (deleted). Renderer read with bounded grep windows; its behaviour is described in our own
  words. letta-code (Apache-2.0) protocol facts are quoted freely.
- letta-code sources compared: **0.26.1** (embedded Android pin, via `npm pack`), **0.29.12**
  (our desktop runtime, `Programs\letta-desktop\...\letta-code-runtime`), **0.33.6** (our wire
  baseline), **0.34.8** (what the official Desktop auto-updated to during this work) and **0.34.9**
  (latest on npm). `letta.js:NNNN` are line numbers in the 0.34.8 bundle unless a version is given.
- GitHub: release notes 0.28.14-0.34.9 and issue/PR search in `letta-ai/letta-code` show **no**
  local-backend context endpoint, frame or open work item. The only related PR is #814 (the TUI
  `/context` command).
- Our repo read at `origin/main` (`592e0d4ff`). Paths are relative to `android-compose/`.

## 2. Answer: does letta-code (local mode) give a per-category breakdown?

**No, in every version through 0.34.9.** Only the total is exact, and only the total is exposed.

| Source | Breakdown? | Evidence |
|---|---|---|
| App Server frames (`usage_statistics`, `device_status`, `update_loop_status`) | **Total only** | `createUsageStatisticsChunk` (`letta.js:116903`) emits `prompt/completion/total/cached_input/cache_write/reasoning_tokens` and `context_tokens`; no section fields. No inbound command parser is a context query. |
| `LocalBackend` (`letta.js:118208`) | **No context overview method** | Only `effectiveContextWindow` (`:118414`, limit) and compaction. |
| TUI `/context` (`letta.js:520532`) | Breakdown only via `getAgentContextOverview` = `GET /v1/agents/{id}/context`, fetched best effort with a 5 s abort; on failure it **silently** draws the total-only bar. In local mode there is no server to answer, so the TUI itself is total-only. | `letta.js:520532-520550` |
| Official Desktop GUI | Total only (section 4) | renderer `ContextWindowUsageRow` |
| Our Iroh/local `agent.context` | A breakdown *shape* with a **crude estimate** (section 5) | `LocalBackendContextReader.kt` |

letta-code never counts sections exactly: there is **no tokenizer** in the runtime (only the
Anthropic SDK's unused `count_tokens` client call). All its own accounting is `ceil(chars / 4)`:

- `estimateProviderContextTokens` (`letta.js:116861`): system prompt + messages + tool schemas,
  each `ceil(JSON.length / 4)`. It computes the three sections, **adds them and throws the parts
  away** (used only for the compaction trigger when the provider reports no usage).
- `estimateLocalMessageTokens` (`local-context-estimate.ts`, `letta.js:115911`): text/thinking/tool
  call chars / 4, 1200 tokens per image.
- `estimateSystemPromptSize` (`letta memory tokens`): per memory file chars / 4.
- Exact figures come only from the provider: `contextTokensFromLocalUsage` takes the provider
  `totalTokens` (else input+output+cacheRead+cacheWrite). So `context_tokens` is the whole
  conversation after that call, **completion included**.

## 3. Where the prompt is assembled (local turn path) and the choke point

`HeadlessBackend.executeConversationTurn` (`letta.js:114167`):

1. `store.listLocalMessages(conversation, agent)` -> `uiMessages`: the whole transcript; a
   compaction **summary** is an ordinary message carrying `metadata.compaction`.
2. `getOrCompileSystemPrompt` (`:118579`) -> `compileLocalSystemPrompt` (`:118128`):
   `content = injectCoreMemory(agent.system, coreMemory)`, `coreMemory` = rendered MemFS projection
   (system memory files, tree) + memory metadata. Persisted per conversation as
   `conversations/<key>/system-prompt.json` `{content, coreMemory, compiledAt, rawSystemHash,
   memfsRevision}` (0.29.12 and later; **0.26.1 stores `content` only**). If MemFS changes
   mid-conversation the update is appended as a `<memory_update>` system message
   (`midConversationSystemPrompt`), not recompiled.
3. `executor.execute({systemPrompt, midConversationSystemPrompt, body, history, uiMessages, agent})`
   -> pi stream adapter `streamOnce(input)` (`:117524`). `body.client_tools` (tool schemas, built per
   turn by the listener from the toolset registry, **not stored on disk**) become `toPiTools`; the
   request is `{systemPrompt, messages: toPiMessages(uiMessages) (+ mid-conversation message), tools}`.
4. `createUsageStatisticsChunk(part.message.usage, contextTokensEstimate)` (`:117029`) yields the
   `usage_statistics` frame.

System, memory, summary and history are therefore derivable from **disk**; tool schemas are known
only inside the process.

**Single choke point:** `streamOnce` / the call at `:117029` holds `input.systemPrompt`,
`input.uiMessages`, `input.clientTools` and the provider usage together. A patch or an upstream
change could attach a `context_breakdown` there. Even then each section is a chars/4 estimate; only
the total is exact.

Per-section tracking in 0.33-0.34: none. `contextTracker` (`context-tracker.ts`) holds
`lastContextTokens` and a history of totals; `context-budget.ts` only caps reflection-subagent
startup prompts; the system-prompt doctor keeps a per-agent memory `estimated_tokens` (clients get
only `should_doctor`).

## 4. Official Desktop behaviour (own words) and protocol facts

- **Meter**: composer donut button opening a 360 px popover "Context window": percent, thin bar,
  "used / limit" in k/M. One neutral colour, no thresholds, no categories, behind a "Token usage"
  display option (default off). Input: latest positive `usage_statistics.context_tokens` per
  `agent:conversation`, cached; window = conversation `context_window_limit` > conversation
  `model_settings` window > agent `llm_config.context_window` > catalog `max_context_window`
  (our `contextWindowTokensOf` mirrors this). Unreferenced "ADE Context Window" strings (system,
  tools, core memory, summary, messages, recall, archival, "Summarize") show a breakdown was once
  planned; no renderer code uses them.
- **Compact** is not a button: palette item "Compact conversation", shown only when
  `supported_commands` has `compact`; sends `execute_command compact` (optional mode argument).
- **Model picker**: tabs Recent/Hosted/All/BYOK, search over label/description/handle, grouped per
  handle, badges, details, refresh; a separate Reasoning row whose tiers are the catalog entries of
  the selected handle (each tier is its own entry with `updateArgs.reasoning_effort`; the window
  travels with it). Selecting sends `update_model` (15 s timeout), optimistic with rollback.

Protocol facts (local mode):

- `execute_command {command_id:"compact", args?, runtime}` -> `slash_command_start`/`slash_command_end`
  deltas -> `execute_command_response {success, output}`. **0.26.1 has `execute_command` and
  `compact` in `SUPPORTED_REMOTE_COMMANDS` but emits no response frame**, only the deltas.
  `output` is text ("Compaction completed ... reduced from N to M messages. Summary: ...").
- `conversation_compact {conversation_id, body?}` -> `conversation_compact_response {success,
  compaction{summary, num_messages_before, num_messages_after}}` exists from 0.29.x; **absent in 0.26.1**.
- **The local backend supports only compaction modes `all` and `sliding_window`**
  (`validateLocalCompactionSettingsRecord`, `:118170`); `self_compact_*` is rejected.
- Stream markers exist only for **automatic** compaction: `event_message{event_type:"compaction"}`
  then `summary_message{summary, compaction_stats{trigger, context_tokens_before/after,
  context_window, messages_count_before/after}}` (`emitCompactionChunks`, `:117483`). A **manual**
  compact streams no `summary_message`, and no `usage_statistics` follows until the next turn: the
  **streamed total goes stale after a manual compact**. Local `compaction_stats.context_tokens_*`
  are messages-only chars/4 estimates, not provider figures.
- Local auto-compaction: before a call and after a turn when `context_tokens > window -
  min(16384, 0.2 * window)` (`contextCompactionThreshold`), up to 3 times per turn.
- `context-limit` / `set-max-context` (`execute_command`) write `context_window_limit` on the agent
  (default conversation) or conversation; above the model default needs `--override`.
- `list_models` -> one entry per (handle, effort tier, window). `update_model` applies to the agent
  on the default conversation, else a per-conversation override (`applied_to`), and preserves the
  window on same-model effort changes.

## 5. What our repo already has

| Capability | Where | State |
|---|---|---|
| Streamed total | `data/context/ContextTokenReadings.kt` (+ `ContextReadingSnapshots`) | Done |
| Window resolution, usage fold, reconcile | `ContextWindowLimit.kt`, `ContextWindowUsage.kt` (`from`, `total`), `ContextWindowUsageStore.kt` | Done, shared |
| Chips | Desktop `ComposerContextChip`; Android chip via `AdminChatContextUsage.kt`; Android `ContextWindowCard` (`AgentScaffoldPickers.kt:581`) via `ProjectChatCoordinator.refreshContextWindow` | Chips total-only; card calls `getContextWindow` |
| Breakdown fetch | `IAgentRepository.getContextWindow` -> `agent.context` admin_rpc (Iroh `IrohAdminRpcAgentSource`; bundled `AppServerLocalRepositoryTransport.getContext`), served by `LocalBackendContextReader`. The REST `AgentApi.getContextWindow` is dead for us (no server). | Estimate, below |
| Model list/update | `ModelControlWire`, `ModelPicker*`, `ConversationModelSelections`, `ComposerEffort`; Iroh relay `ModelAdminHandlers` | Done |
| Compact | `AppServerCommand.ConversationCompact/ExecuteCommand`, `AppServerSlashCommandApi`, `AppServerContextWindowPreflight` | Types only; no user action; no Iroh relay |
| Compaction rendering | `slash_command_*` only; nothing for `event_message`/`summary_message`/`compaction_stats` | Gap |

**`LocalBackendContextReader` today** (`sharedLogic/src/jvmAndAndroid/.../node/iroh/`): reads the
sidecar `content` (fallback `agent.system`), `system = ceil(len/4)`, `messages = count * 50`,
`window = 200000` constant, and zeroes core memory/summary/tools/memfs. Three defects: the
50-token constant, the constant window, memory counted as system. Without `--local-backend-dir`
the wrapper answers `capability_unavailable`. The class is in `jvmAndAndroid`, so the same code
runs on desktop, the wrapper host and Android.

**Host patch mechanism (verified):** exists only on the Meridian Linux host, outside this repo. The
App Server runs with `NODE_OPTIONS=--import=file:///opt/stacks/letta-code-parallel/admin-shim/scripts/letta-code-patch-register.mjs`
(`scripts/deploy/appserver.env.example`), applying anchor-based patches to the pinned `letta.js`
(`LETTA_CLI_PATH_REAL`, currently 0.32.3 in the template) and logging `lcp-patches applied=18
skipped=0`; a drifted anchor silently makes that patch inert (`install-meridian.sh`). It does
**not** exist for the desktop-bundled runtime or the nodejs-mobile Android runtime.

## 6. Options and recommendation

| | A. Host-side estimator, calibrated to the provider total | B. Runtime patch (lcp) | C. Upstream PR to letta-code | D. Client-only total |
|---|---|---|---|---|
| Accuracy | Total exact; sections estimated (chars/4, same basis as letta-code); tools = derived residual | Total exact; sections chars/4; tools measured | as B | Total exact; no sections |
| Desktop-bundled | Yes (in-process reader) | No (no loader) | After upgrade | Yes |
| Embedded Android 0.26.1 | Yes, degraded (no `coreMemory`: memory counted in system) | No | No (ceiling) | Yes |
| Meridian host over Iroh | Yes (wrapper redeploy) | Yes; anchor upkeep, tied to one version | Eventually | Yes (already) |
| Cost / risk | Moderate, ours, testable | Fragile, one host | Slow, uncertain | Free |

**Recommendation: A, with D as the always-available floor, plus a low-priority upstream
issue/PR (C).** Nothing in letta-code can give exact sections (no tokenizer), so B and C buy only a
measured tool-schema size, and B covers one host. A works on every runtime we ship; once calibrated
to the provider total the remaining error is each section's chars/4 error, bounded and labelled
"Estimated".

### Design of A

`ContextBreakdownEstimator` (pure Kotlin in `jvmAndAndroid` beside `LocalBackendContextReader`;
no new dependency; chars/4 to match letta-code's own accounting, a real tokenizer can swap in
behind the same interface later):

- Inputs, all read-only from disk: sidecar `{content, coreMemory?}`; transcript via
  `LocalBackendMessageReader` (rows after the last `metadata.compaction`, plus that summary row);
  conversation/agent `context_window_limit` and model window; optional `reported_total`.
- Sections: **System prompt** = `content` minus `coreMemory` (0.26.1: all of `content`);
  **Memory** = `coreMemory` (optional per-file expansion from the memory dir); **Summary** = the
  compaction row; **Messages** = letta-code's per-message formula; **Tools & other** = residual.
- Calibration: the client holds the latest streamed `context_tokens` and passes it as the
  `agent.context` param `reported_total`. With `S = system + memory + summary + messages`: if
  `reported_total >= S`, `tools = reported_total - S`; if `reported_total < S`, scale the four
  sections down proportionally and `tools = 0`. The rows always sum to the reported total, so a bar
  cannot contradict the meter. Without a reported total the tools row is omitted and the sum is
  marked "partial".
- After a **manual compact** the streamed total is stale: the host recomputes `S` from the new
  transcript and returns `calibrated:false`; the client shows "Estimated after compaction" until
  the next `usage_statistics`.
- Response gains `source:"estimate"`, `calibrated`, `tools_derived`, and uses the real
  model/conversation window instead of 200000. The residual row absorbs reasoning tokens and cache
  rounding, hence the label "Tools & other".

## 7. Shared design (commonMain) and wiring

```kotlin
enum class ContextProvenance { TotalOnly, Estimated, EstimatedCalibrated }
data class ContextMeter(val usage: ContextWindowUsage, val provenance: ContextProvenance,
    val windowSource: WindowSource, val autoCompactAt: Float?)  // 1 - min(16384, 0.2*w)/w
```

- Used = streamed `context_tokens` (authority). The breakdown is fetched **lazily when the sheet
  opens** and after a settled turn or compaction; never polled, never mid-turn (existing rule).
- `CompactionRepository.compact(agentId, conversationId, mode?)` -> `CompactionResult{summary?,
  messagesBefore?, messagesAfter?, noChange}`. Strategy by capability:
  1. `compact` in `supported_commands` -> `execute_command compact` (hooks and reflection parity).
     **This is the only path on embedded 0.26.1**, which lacks `conversation_compact` and the
     response frame: success = `slash_command_end.success`;
  2. else `conversation_compact` (0.29+; default conversation needs `agent_id` in `body`);
  3. else hide the button. Offer only `all` / `sliding_window`.
- Map `event_message`/`summary_message` to `CompactionStarted/Finished` (automatic compactions are
  invisible today); on finish write `tokensAfter` into `ContextTokenReadings` flagged estimated; after
  a manual compact trigger the host re-estimate.
- Model chip reuses `ModelPickerController`, `ReasoningTier`, `ConversationModelSelections`.

| Need | Desktop-local / embedded (in-process) | Direct WS to a remote letta-code | Iroh (Meridian wrapper) |
|---|---|---|---|
| Streamed total | `usage_statistics` | same | relayed (done) |
| Breakdown | `agent.context` via reader + estimator | **total only** (a stock App Server has no admin_rpc) | `agent.context` + estimator (**wrapper redeploy**) |
| Compact | `execute_command` (0.26.1+) / `conversation_compact` | same | **new** `conversation.compact` admin_rpc |
| Model | `list_models` / `update_model` | same | `model.list` / `model.update` (done) |
| Context limit | `execute_command context-limit` | same | `agent.update`/`conversation.update` `context_window_limit`; verify allowlist |

**Iroh relay** (`WorkspaceAdminHandlers` / `ToolAdminHandlers` pattern; admin_rpc rather than a new
control frame, which would also need `forProtocolCommand` and an `IrohNodeConnection` branch):
register `conversation.compact` in `ConversationAdminHandlers` calling the host `nativeClient`
(`execute_command compact` preferred, `conversation_compact` fallback); map it to
`CONVERSATION_MANAGE` in `IrohPeerCapabilities.forAdminMethod`; add to
`AdminRpcRegistry.canonicalMethods`. An unknown-method or `capability_unavailable` answer from an
old host maps to `CompactionResult.Unsupported` and hides the button (the #1827 pattern). Paths
failing over Iroh today: `execute_command`, `conversation_compact`, slash-command listing
(`CapabilityUnavailable "admin_rest"`).

## 8. UI plan

- Row 1 **model chip** (label + effort) -> searchable list (sheet on phone, popover on desktop) from
  `ModelPickerState`; effort row for the selected handle; optimistic with rollback.
- Row 2 **slim meter**: bar + "42k / 200k" + percent; neutral < 70 %, warning 70-90 %, error >= 90 %
  (our choice; the reference has none); tick at `autoCompactAt`.
- **Sheet**: used/limit; provenance caption ("Total only" / "Estimated" / "Estimated, matched to
  provider total"); stacked bar and legend from `ContextWindowUsage.segments` (System prompt,
  Memory, Summary, Messages, Tools & other, Free). `TotalOnly`: one "In context" bar and a hint
  ("Breakdown not available from this host"). **Compact** button (+ Default / All / Sliding window
  menu), disabled while running or compacting, hidden if unsupported; afterwards "N -> M messages"
  and the meter drops to the estimate.

## 9. Edge cases

- Model with a smaller window than usage: meter > 100 % (existing widening in `ContextWindowUsage`);
  warning strip "Context exceeds this model's window; compact before sending". Refresh the window from
  `update_model_response.model_settings`, not the catalog.
- Compaction in progress / running turn: disable Compact and model change; ignore a second tap
  (key = conversation). "Ran but nothing changed" -> "Already compact".
- Unsupported host (no `compact`, old wrapper): hide Compact, keep meter, total-only.
- Wrapper without `--local-backend-dir`: `agent.context` is `capability_unavailable`; show total-only
  with the reason, not "failed".
- Stale total after manual compact: estimated caption until the next turn.
- 0.26.1: no `coreMemory` (memory merged into System), no `conversation_compact`, no
  `execute_command_response`.
- Default vs other conversation: `conversation_compact` for `default` needs `agent_id`; scope label
  from `applied_to`.
- Frame cap: `agent.context` keeps dropping `messages` and bounding strings (existing guard).

## 10. Test plan

- Estimator unit tests on fixture stores (0.26.1 sidecar without `coreMemory`, 0.29+ with it):
  section split, compaction row as summary, calibration (reported > S, < S, absent), window from
  conversation/agent/model, per-message formula parity with letta-code.
- commonTest: `ContextMeter` merge/provenance; `CompactionRepository` strategy by capability
  incl. the 0.26.1 path (`slash_command_end` only); frame fixtures from 0.33.6/0.34.8 in
  `LettaCode0336Frames.kt`; `summary_message` -> `Compaction*`; stale-total handling after manual compact.
- Host: `conversation.compact` handler tests (success, no-change, no nativeClient, wrong capability);
  `agent.context` returns `source`/`calibrated`; client test that an old host yields `Unsupported`
  (like `IrohHttpOnlyRoutesTest`).
- UI: card states (total-only, estimated, calibrated, over-window, compacting, unsupported); model
  chip search/effort/rollback.
- Manual: desktop-bundled 0.29.12, Meridian wrapper before/after redeploy, Android embedded 0.26.1.

## 11. PR slicing and dependencies

| # | Slice | Depends on |
|---|---|---|
| S1 | `Compaction*` mapping (event_message/summary_message), timeline row, meter update on finish | none |
| S2 | `ContextBreakdownEstimator` + reader rewrite (real window, sidecar split, `reported_total`, `source`/`calibrated`); client `ContextMeter`/provenance + lazy loader on existing chips (**wrapper redeploy** for Iroh) | none |
| S3 | Host `conversation.compact` admin_rpc + capability map (**wrapper redeploy**) | none |
| S4 | `CompactionRepository` (execute_command / conversation_compact / Iroh, 0.26.1 path, gating) | S1, S3 |
| S5 | Drawer card + sheet UI (Android + Desktop) with model chip and Compact | S2, S4 |
| S6 | Compaction mode menu + context-limit field | S5 |
| S7 | Optional upstream issue/PR: attach the section estimates letta-code already computes (`estimateProviderContextTokens`) to `usage_statistics` as `context_breakdown` | none |

S1-S3 are parallel. S5 can start with S2 (meter + model chip) and gain Compact when S4 lands.

## 12. Beads

Epic `letta-mobile-pt1ze`. S1 `letta-mobile-kr39h`, S2 `letta-mobile-cyh28`, S3 `letta-mobile-57cta`,
S4 `letta-mobile-3kble` (blocked by S1, S3), S5 `letta-mobile-3io8k` (blocked by S2, S4), S6
`letta-mobile-joigh` (blocked by S5), S7 `letta-mobile-pt1ze.1` (upstream). Not pushed (`bd dolt push`
is left to the session-close protocol).
