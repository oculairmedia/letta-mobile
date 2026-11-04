// Node.js ESM loader hook that applies runtime patches to letta-code's bundled CLI.
// Fixes settle-on-turn agent-id bug, thinking settings schema violations, and model_settings inheritance edge cases.
/**
 * lcp-ith — runtime patch for letta-code's `executeConversationTurn`
 * settle-on-turn bug.
 *
 * Upstream bug (letta-code 0.26.1, letta.js around line 196391):
 *
 *   if (!isApprovalTurn) {
 *     this.store.settleInterruptedToolCalls(conversationId, {
 *       reason: TURN_DID_NOT_COMPLETE
 *     });
 *   }
 *
 * The call omits `agentId`. Inside the store, `toolSettlementTargets`
 * with no agent id calls `findConversation(conversationId)` with no
 * agent id either; that lookup builds the cache key as
 *   "default":"<defaultAgentId>"  i.e.  "default:agent-local-default"
 * and misses for any migrated agent whose id is `agent-<uuid>`. Settle
 * silently returns 0; any orphaned tool_use on disk (worker SIGTERM
 * mid-turn, context-exceeded mid-tool-result, etc.) is never closed
 * out. Every subsequent turn against the affected conversation fails
 * Anthropic validation with
 *   "tool_use ids were found without tool_result blocks immediately
 *    after: toolu_XXX..."
 *
 * The patched call passes `body.agent_id` (the authoritative agent id
 * the SDK puts in the request body — same value `appendTurnInput`
 * derives) with a graceful fallback to the store's
 * `resolveAgentIdForConversation` helper for any older call shape that
 * doesn't carry it. With the right agent id, `toolSettlementTargets`
 * hits the conversation cache, `settleInterruptedToolCallsForConversation`
 * synthesizes an error tool_result for every orphan tool_use, and the
 * next API request goes out with a matched tool_use/tool_result pair.
 *
 * Fail-safe: if a future letta-code release changes the literal source
 * text we're matching, this loader logs a warning and returns the
 * original module unchanged. Better unpatched than refusing to start.
 */
import { fileURLToPath } from "node:url";

const TARGET_PATH = process.env["LETTA_CLI_PATH_REAL"] ?? "";

// lcp-reflection-controls: Letta's persisted reflection settings are copied
// field-by-field, so new per-agent keys need every read/write/command site to
// land together. The launch wrapper leaves the stock launch body intact: this
// matters because every automatic and manual caller is function-hoisted to the
// wrapper, while the stock onComplete callback keeps its own lifecycle.
const REFLECTION_CONTROLS_HELPER = `
globalThis.__lcpReflectionOutcomes = globalThis.__lcpReflectionOutcomes || new Map();
globalThis.__lcpRecordReflectionOutcome = function(agentId, outcome) {
  if (typeof agentId !== "string" || !agentId) return;
  globalThis.__lcpReflectionOutcomes.set(agentId, { ...outcome, at: new Date().toISOString() });
};
globalThis.__lcpReflectionLastOutcome = function(agentId) {
  return globalThis.__lcpReflectionOutcomes.get(agentId) ?? null;
};
`;

const REFLECTION_CONTROLS_PATCHES = [
  {
    token: `function applyPersistedAgentScopedSettings(base2, raw2) {\n  if (!raw2) {\n    return base2;\n  }\n  return {\n    trigger: normalizeTrigger(raw2.trigger, base2.trigger),\n    stepCount: normalizeStepCount(raw2.stepCount, base2.stepCount),\n    merge: normalizeMerge(raw2.merge, base2.merge),\n    mergeInstructions: normalizeMergeInstructions(raw2.mergeInstructions, base2.mergeInstructions)\n  };\n}\nfunction legacyModeToReflectionSettings`,
    replacement: `function applyPersistedAgentScopedSettings(base2, raw2) {\n  if (!raw2) {\n    return base2;\n  }\n  return {\n    trigger: normalizeTrigger(raw2.trigger, base2.trigger),\n    stepCount: normalizeStepCount(raw2.stepCount, base2.stepCount),\n    merge: normalizeMerge(raw2.merge, base2.merge),\n    mergeInstructions: normalizeMergeInstructions(raw2.mergeInstructions, base2.mergeInstructions),\n    model: raw2.model === null || typeof raw2.model === "string" ? raw2.model : base2.model ?? null,\n    focusInstructions: typeof raw2.focusInstructions === "string" ? raw2.focusInstructions : base2.focusInstructions ?? ""\n  };\n}\nfunction legacyModeToReflectionSettings`,
  },
  {
    token: `  const merge4 = normalizeMerge(settings3.merge, DEFAULT_REFLECTION_SETTINGS.merge);\n  const mergeInstructions = normalizeMergeInstructions(settings3.mergeInstructions, DEFAULT_REFLECTION_SETTINGS.mergeInstructions);\n  const legacyMode = reflectionSettingsToLegacyMode(settings3);`,
    replacement: `  const merge4 = normalizeMerge(settings3.merge, DEFAULT_REFLECTION_SETTINGS.merge);\n  const mergeInstructions = normalizeMergeInstructions(settings3.mergeInstructions, DEFAULT_REFLECTION_SETTINGS.mergeInstructions);\n  const currentSettings = getReflectionSettings(agentId, workingDirectory);\n  const model = settings3.model === undefined ? currentSettings.model ?? null : settings3.model === null ? null : typeof settings3.model === "string" ? settings3.model : null;\n  const focusInstructions = settings3.focusInstructions === undefined ? currentSettings.focusInstructions ?? "" : typeof settings3.focusInstructions === "string" ? settings3.focusInstructions : "";\n  const legacyMode = reflectionSettingsToLegacyMode(settings3);`,
  },
  {
    token: `      reflectionSettingsByAgent: {\n        ...localSettings.reflectionSettingsByAgent ?? {},\n        [agentId]: {\n          trigger: settings3.trigger,\n          stepCount: settings3.stepCount,\n          merge: merge4,\n          mergeInstructions\n        }\n      }\n    }, workingDirectory);`,
    replacement: `      reflectionSettingsByAgent: {\n        ...localSettings.reflectionSettingsByAgent ?? {},\n        [agentId]: {\n          trigger: settings3.trigger,\n          stepCount: settings3.stepCount,\n          merge: merge4,\n          mergeInstructions,\n          model,\n          focusInstructions\n        }\n      }\n    }, workingDirectory);`,
  },
  {
    token: `      reflectionSettingsByAgent: {\n        ...globalSettings.reflectionSettingsByAgent ?? {},\n        [agentId]: {\n          trigger: settings3.trigger,\n          stepCount: settings3.stepCount,\n          merge: merge4,\n          mergeInstructions\n        }\n      }\n    });\n  }\n}\nvar DEFAULT_STEP_COUNT`,
    replacement: `      reflectionSettingsByAgent: {\n        ...globalSettings.reflectionSettingsByAgent ?? {},\n        [agentId]: {\n          trigger: settings3.trigger,\n          stepCount: settings3.stepCount,\n          merge: merge4,\n          mergeInstructions,\n          model,\n          focusInstructions\n        }\n      }\n    });\n  }\n}\nvar DEFAULT_STEP_COUNT`,
  },
  {
    token: `    merge_instructions: settings3.mergeInstructions\n  };\n}\nfunction resolveReflectionSettingsScope`,
    replacement: `    merge_instructions: settings3.mergeInstructions,\n    model: settings3.model ?? null,\n    focus_instructions: settings3.focusInstructions ?? "",\n    last_outcome: globalThis.__lcpReflectionLastOutcome(agentId)\n  };\n}\nfunction resolveReflectionSettingsScope`,
  },
  {
    token: `&& (settings3.merge_instructions === undefined || typeof settings3.merge_instructions === "string") && (c.scope === undefined`,
    replacement: `&& (settings3.merge_instructions === undefined || typeof settings3.merge_instructions === "string") && (settings3.model === undefined || settings3.model === null || typeof settings3.model === "string") && (settings3.focus_instructions === undefined || typeof settings3.focus_instructions === "string") && (c.scope === undefined`,
  },
  {
    token: `      mergeInstructions: parsed.settings.merge_instructions ?? currentSettings.mergeInstructions\n    }, {`,
    replacement: `      mergeInstructions: parsed.settings.merge_instructions ?? currentSettings.mergeInstructions,\n      model: parsed.settings.model === undefined ? currentSettings.model : parsed.settings.model,\n      focusInstructions: parsed.settings.focus_instructions === undefined ? currentSettings.focusInstructions : parsed.settings.focus_instructions\n    }, {`,
  },
  {
    token: `async function launchReflectionSubagent(options, dependencies4 = {}) {`,
    replacement: `async function __lcpLaunchReflectionSubagentInner(options, dependencies4 = {}) {`,
  },
  {
    token: `\n}\nvar AUTO_REFLECTION_DESCRIPTION = "Reflect on recent conversations", REFLECTION_AGENT_ID_WAIT_MS = 30000, reservedReflectionAgentIds, pendingReflectionLaunches, suppressedAutomaticReflections;`,
    replacement: `\n}\n// lcp-reflection-controls: all callers above and below this declaration resolve\n// here by function hoisting. The inner function has no caller-supplied completion\n// hook; completion is recorded at its existing onComplete finally block below.\nasync function launchReflectionSubagent(options, dependencies4 = {}) {\n  const reflectionSettings = options.reflectionSettings ?? getReflectionSettings(options.agentId);\n  options.reflectionSettings ??= reflectionSettings;\n  if (options.model == null && typeof reflectionSettings?.model === "string" && reflectionSettings.model.trim()) {\n    options.model = reflectionSettings.model;\n  }\n  const focusInstructions = typeof reflectionSettings?.focusInstructions === "string" ? reflectionSettings.focusInstructions : "";\n  if (focusInstructions.trim()) {\n    const focusSuffix = "\\n\\nAdditional user-provided reflection focus:\\n" + focusInstructions;\n    if (typeof options.reflectionPromptOverride === "string") {\n      options.reflectionPromptOverride += focusSuffix;\n    } else {\n      options.instruction = (typeof options.instruction === "string" ? options.instruction : "") + focusSuffix;\n    }\n  }\n  const outcome = (status, reason) => globalThis.__lcpRecordReflectionOutcome(options.agentId, {\n    status,\n    ...(reason === undefined ? {} : { reason }),\n    ...(typeof options.triggerSource === "string" ? { trigger_source: options.triggerSource } : {}),\n    ...(typeof options.model === "string" ? { model: options.model } : {})\n  });\n  try {\n    const result3 = await __lcpLaunchReflectionSubagentInner(options, dependencies4);\n    if (result3?.launched === true) {\n      outcome("launched");\n    } else {\n      outcome("skipped", result3?.reason);\n    }\n    return result3;\n  } catch (error5) {\n    outcome("failed", error5 instanceof Error ? error5.message : String(error5));\n    throw error5;\n  }\n}\nvar AUTO_REFLECTION_DESCRIPTION = "Reflect on recent conversations", REFLECTION_AGENT_ID_WAIT_MS = 30000, reservedReflectionAgentIds, pendingReflectionLaunches, suppressedAutomaticReflections;`,
  },
  {
    token: `        } finally {\n          releaseReflectionLaunch(agentId);\n        }\n      }\n    });\n    releaseOnComplete = true;`,
    replacement: `        } finally {\n          globalThis.__lcpRecordReflectionOutcome(agentId, {\n            status: success ? "completed" : "failed",\n            ...(!success && error5 !== undefined ? { reason: error5 instanceof Error ? error5.message : String(error5) } : {}),\n            trigger_source: triggerSource,\n            ...(typeof reflectionModel === "string" ? { model: reflectionModel } : typeof options.model === "string" ? { model: options.model } : {})\n          });\n          releaseReflectionLaunch(agentId);\n        }\n      }\n    });\n    releaseOnComplete = true;`,
  },
];


// lcp-model-catalog: native provider catalogs are bundled, not /v1/models
// discovery. Match the two flattening sites atomically so the catalog used by
// both getBuiltinModels and provider execution receives the same metadata.
// These are the verified subscription proxy limits (f5f01c2), not public API
// limits. gpt-6.1-luna is intentionally omitted: upstream returned model_not_found.
const MODEL_CATALOG_PATCHES = [
  {
    token: '  ANTHROPIC_MODELS = flattenChatModelCatalog("anthropic", anthropic_default);',
    addition: `
  // lcp-model-catalog: retain an upstream entry if a future bundle adds it.
  if (!ANTHROPIC_MODELS["claude-haiku-5-5"] && ANTHROPIC_MODELS["claude-haiku-4-5"]) {
    ANTHROPIC_MODELS["claude-haiku-5-5"] = {
      ...ANTHROPIC_MODELS["claude-haiku-4-5"],
      id: "claude-haiku-5-5", name: "Claude Haiku 5.5",
      contextWindow: 200000, maxTokens: 65536
    };
  }`,
  },
  {
    token: '  OPENAI_MODELS = flattenChatModelCatalog("openai", openai_default);',
    addition: `
  // lcp-model-catalog: use the ChatGPT upstream maximum, not its default.
  for (const id of ["gpt-6-astra", "gpt-6-sol", "gpt-6-luna", "gpt-6.1-sol"]) {
    if (OPENAI_MODELS[id]) OPENAI_MODELS[id] = { ...OPENAI_MODELS[id], contextWindow: 872000 };
  }
  if (!OPENAI_MODELS["gpt-6.1-terra"] && OPENAI_MODELS["gpt-6.1-sol"]) {
    OPENAI_MODELS["gpt-6.1-terra"] = {
      ...OPENAI_MODELS["gpt-6.1-sol"],
      id: "gpt-6.1-terra", name: "GPT-6.1 Terra",
      cost: { input: 1, output: 5, cacheRead: 0.05, cacheWrite: 1.25,
        tiers: [{ inputTokensAbove: 272000, input: 2, output: 7.5, cacheRead: 0.1, cacheWrite: 2.5 }] },
      contextWindow: 872000, maxTokens: 128000
    };
  }`,
  },
];

// lcp-retry-otid (letta-mobile-93cml): App Server retries refresh every input
// OTID, then HeadlessBackend.executeConversationTurn appends the user again.
// LocalStore only dedupes transcript entries by the newly allocated message ID.
// Keep local user identities across retries AND dedupe before allocating that
// ID. Use the terminal client's existing local-backend capability predicate;
// remote Letta requests still receive fresh OTIDs. Keep the positional image
// failure-policy remap, since approval/non-user identities still change.
// Search only the resident tail that pushLocalMessage already loads: retries
// happen seconds apart, while the original input is still resident. Do not
// read the full transcript or alter unread-head validation/repair state.
// Limitation: an input evicted from the resident tail (e.g. by compaction)
// is no longer deduped; there is deliberately no disk fallback.
const RETRY_OTID_REFRESH_TOKEN =
  `function refreshTurnInputOtidsForNewRequest(state2) {\n` +
  `  const refreshedMessages = refreshInputOtidsForNewRequest(state2.messages);\n` +
  `  return createTurnInputState(refreshedMessages, remapImageFailureModesByPosition(state2.messages, refreshedMessages, state2.imageFailureModesByMessageOtid));\n` +
  `}`;
const RETRY_OTID_REFRESH_REPLACEMENT =
  `function refreshTurnInputOtidsForNewRequest(state2) {\n` +
  `  const backendCapabilities = getBackend().capabilities;\n` +
  `  const retryFromPersistedLocalState = backendCapabilities.localModelCatalog && !backendCapabilities.remoteMemfs;\n` +
  `  const refreshedMessages = state2.messages.map((message2) =>\n` +
  `    retryFromPersistedLocalState && message2.role === "user" && message2.otid\n` +
  `      ? message2\n` +
  `      : refreshInputOtidsForNewRequest([message2])[0]);\n` +
  `  return createTurnInputState(refreshedMessages, remapImageFailureModesByPosition(state2.messages, refreshedMessages, state2.imageFailureModesByMessageOtid));\n` +
  `}`;
const RETRY_OTID_PERSIST_TOKEN =
  `  appendUserLocalMessage(conversationId, agentId, message) {\n` +
  `    const conversation = this.ensureConversation(conversationId, agentId);\n` +
  `    const date = this.currentLocalMessageDate();`;
const RETRY_OTID_PERSIST_REPLACEMENT =
  `  appendUserLocalMessage(conversationId, agentId, message) {\n` +
  `    const conversation = this.ensureConversation(conversationId, agentId);\n` +
  `    const otid = optionalString3(message.otid ?? message.client_message_id);\n` +
  `    if (otid) {\n` +
  `      const existing = this.residentLocalMessagesForConversation(conversation.id, agentId)\n` +
  `        .find((stored) => stored.role === "user" && stored.otid === otid);\n` +
  `      if (existing) return existing;\n` +
  `    }\n` +
  `    const date = this.currentLocalMessageDate();`;

// lcp-agent-lazyload (letta-mobile-gxooq): LocalStore fills this.agents once, at App
// Server start (loadFromStorage), and afterwards only with agents THIS process creates.
// Subagents are created by their own process and written to agents/<id>.json, so an
// App Server that started earlier answers "Agent not found" for them on
// listConversationMessages / listAgentMessages (strictAgentAccess), and the subagent
// view shows "message.list native command unsuccessful". refreshAgentRecordFromStorage
// now loads an agent it has never seen from that same file (same normalization, same
// mtime bookkeeping as loadFromStorage), and the two read paths consult it before
// refusing. Nothing is created: an agent with no file is still "not found".
const AGENT_LAZYLOAD_REFRESH_TOKEN =
  `  refreshAgentRecordFromStorage(agentId) {\n` +
  `    const existing = this.agents.get(agentId);\n` +
  `    if (!this.storageDir || !existing)\n` +
  `      return existing;`;
const AGENT_LAZYLOAD_REFRESH_REPLACEMENT =
  `  refreshAgentRecordFromStorage(agentId) {\n` +
  `    const existing = this.agents.get(agentId);\n` +
  `    if (this.storageDir && !existing && typeof agentId === "string" && agentId) {\n` +
  `      const mtimeMs = this.agentRecordFileMtimeMs(agentId);\n` +
  `      if (mtimeMs === undefined)\n` +
  `        return existing;\n` +
  `      try {\n` +
  `        const raw = readJsonFile2(join20(this.storageDir, "agents", \`\${encodePathSegment(agentId)}.json\`));\n` +
  `        const agent = normalizeAgentRecord(raw, this.defaultAgentModel);\n` +
  `        if (!agent || agent.id !== agentId)\n` +
  `          return existing;\n` +
  `        this.agents.set(agentId, agent);\n` +
  `        this.recordAgentRecordMtime(agentId, mtimeMs);\n` +
  `        return agent;\n` +
  `      } catch {\n` +
  `        return existing;\n` +
  `      }\n` +
  `    }\n` +
  `    if (!this.storageDir || !existing)\n` +
  `      return existing;`;
const AGENT_LAZYLOAD_LIST_CONVERSATION_TOKEN =
  `    if (this.strictAgentAccess && !conversation?.agent_free && !this.agents.has(agentId)) {\n` +
  `      throw new LocalBackendNotFoundError("Agent", agentId);\n` +
  `    }`;
const AGENT_LAZYLOAD_LIST_CONVERSATION_REPLACEMENT =
  `    if (this.strictAgentAccess && !conversation?.agent_free && !this.refreshAgentRecordFromStorage(agentId)) {\n` +
  `      throw new LocalBackendNotFoundError("Agent", agentId);\n` +
  `    }`;
const AGENT_LAZYLOAD_LIST_AGENT_TOKEN =
  `  listAgentMessages(agentId, body) {\n` +
  `    if (this.strictAgentAccess && !this.agents.has(agentId)) {`;
const AGENT_LAZYLOAD_LIST_AGENT_REPLACEMENT =
  `  listAgentMessages(agentId, body) {\n` +
  `    if (this.strictAgentAccess && !this.refreshAgentRecordFromStorage(agentId)) {`;

const SETTLE_BUG_LITERAL =
  `this.store.settleInterruptedToolCalls(conversationId, {\n` +
  `        reason: TURN_DID_NOT_COMPLETE\n` +
  `      });`;

const SETTLE_FIX_LITERAL =
  `this.store.settleInterruptedToolCalls(conversationId, {\n` +
  `        agentId: body?.agent_id ?? this.store?.resolveAgentIdForConversation?.(conversationId),\n` +
  `        reason: TURN_DID_NOT_COMPLETE\n` +
  `      });`;

// lcp-9pn: letta-code 0.26.2 can construct Anthropic/Bedrock thinking
// settings as `{ type: "disabled", budget_tokens: N }` when callers pass both
// `--no-reasoner`/`enable_reasoner=false` and `max_reasoning_tokens`. Anthropic
// rejects that strict schema with:
//   thinking.disabled.budget_tokens: Extra inputs are not permitted
// Only enabled thinking accepts budget_tokens; adaptive/disabled must omit it.
const THINKING_SETTINGS_BUG_LITERAL =
  `thinking = {\n` +
  `        type: updateArgs?.enable_reasoner === false ? "disabled" : "enabled",\n` +
  `        ...typeof updateArgs?.max_reasoning_tokens === "number" && {\n` +
  `          budget_tokens: updateArgs.max_reasoning_tokens\n` +
  `        }\n` +
  `      };`;

const THINKING_SETTINGS_FIX_LITERAL =
  `thinking = {\n` +
  `        type: updateArgs?.enable_reasoner === false ? "disabled" : "enabled",\n` +
  `        ...updateArgs?.enable_reasoner !== false && {\n` +
  `          budget_tokens: typeof updateArgs?.max_reasoning_tokens === "number"\n` +
  `            ? updateArgs.max_reasoning_tokens\n` +
  `            : Math.floor(Number(process.env.LETTA_CODE_THINKING_BUDGET_TOKENS || 10000))\n` +
  `        }\n` +
  `      };`;

const THINKING_HELPERS =
  `globalThis.__lcpFixThinking = globalThis.__lcpFixThinking || ((payload) => {\n` +
  `  if (!payload || typeof payload !== "object" || !payload.thinking || typeof payload.thinking !== "object") return payload;\n` +
  `  const next = { ...payload, thinking: { ...payload.thinking } };\n` +
  `  if (next.thinking.type === "enabled" && typeof next.thinking.budget_tokens !== "number") {\n` +
  `    next.thinking.budget_tokens = Number(process.env.LETTA_CODE_THINKING_BUDGET_TOKENS || 10000);\n` +
  `  }\n` +
  `  if (next.thinking.type !== "enabled" && "budget_tokens" in next.thinking) {\n` +
  `    delete next.thinking.budget_tokens;\n` +
  `  }\n` +
  `  return next;\n` +
  `});\n` +
  `globalThis.__lcpFixModelSettings = globalThis.__lcpFixModelSettings || ((payload) => {\n` +
  `  if (!payload || typeof payload !== "object" || !payload.thinking || typeof payload.thinking !== "object") return payload;\n` +
  `  const next = { ...payload, thinking: { ...payload.thinking } };\n` +
  `  if (next.thinking.type === "enabled" && typeof next.thinking.budget_tokens !== "number") {\n` +
  `    next.thinking.budget_tokens = Number(process.env.LETTA_CODE_THINKING_BUDGET_TOKENS || 10000);\n` +
  `  }\n` +
  `  if (next.thinking.type !== "enabled" && "budget_tokens" in next.thinking) {\n` +
  `    delete next.thinking.budget_tokens;\n` +
  `  }\n` +
  `  return next;\n` +
  `});\n`;

const ANTHROPIC_CREATE_LITERAL =
  `client.messages.create({ ...params, stream: true }, requestOptions)`;

const ANTHROPIC_CREATE_FIX_LITERAL =
  `client.messages.create({ ...globalThis.__lcpFixThinking(params), stream: true }, requestOptions)`;

const ANTHROPIC_STREAM_LITERAL =
  `this.client.beta.messages.stream({ ...params }, options)`;

const ANTHROPIC_STREAM_FIX_LITERAL =
  `this.client.beta.messages.stream({ ...globalThis.__lcpFixThinking(params) }, options)`;

const MODEL_SETTINGS_RETURN_LITERAL = `return modelSettings;`;
const MODEL_SETTINGS_RETURN_FIX_LITERAL = `return globalThis.__lcpFixModelSettings(modelSettings);`;

const EFFECTIVE_AGENT_MODEL_SETTINGS_LITERAL =
  `model_settings: {\n` +
  `      ...agent2.model_settings,\n` +
  `      ...conversationModelSettings2 ?? {},\n` +
  `      ...typeof conversationRecord.context_window_limit === "number" ? { context_window_limit: conversationRecord.context_window_limit } : {}\n` +
  `    }`;

const EFFECTIVE_AGENT_MODEL_SETTINGS_FIX_LITERAL =
  `model_settings: globalThis.__lcpFixModelSettings({\n` +
  `      ...agent2.model_settings,\n` +
  `      ...conversationModelSettings2 ?? {},\n` +
  `      ...typeof conversationRecord.context_window_limit === "number" ? { context_window_limit: conversationRecord.context_window_limit } : {}\n` +
  `    })`;

const THINKING_REQUEST_GUARD_PATTERN =
  /  if \((options\d*)\?\.metadata\) \{\n    const userId = \1\.metadata\.user_id;/g;

/**
 * @param {string} options the captured binding name
 * @returns {string}
 */
function thinkingRequestGuardInsert(options) {
  return (
    `  if (params.thinking && params.thinking.type !== "enabled" && "budget_tokens" in params.thinking) {\n` +
    `    delete params.thinking.budget_tokens;\n` +
    `  }\n` +
    `  if (${options}?.metadata) {\n` +
    `    const userId = ${options}.metadata.user_id;`
  );
}

// lcp-7kk: universal chokepoint guard.
//
// The per-builder guard above (lcp-9pn) only covers ONE of the several bundled
// `buildAnthropicParams` copies — the anchor it keys on (`const userId =
// options3.metadata.user_id` at a specific indent) matches a single copy. The
// live `local_backend_error` turns send `thinking: { type:"disabled",
// budget_tokens: N }` from a path that copy never sees, so Anthropic rejects
// every continuation with:
//   thinking.disabled.budget_tokens: Extra inputs are not permitted
//
// Rather than chase which of N minified builder copies produced the param, we
// normalize at the real Anthropic send chokepoints: request paths use
// `(client.)(beta.)messages.create({ ...params, ... }, ...)`, while tool-runner
// streaming paths use `(client.)beta.messages.stream({ ...params }, ...)`. The
// bundle's send-path variable is `params` (not `requestParams`). We route
// `params` through a normalizer that strips every non-`type` key from a
// non-enabled `thinking` block and adds a conservative budget to enabled
// thinking when upstream forgot one. The `messages.create/stream({ ` prefixes
// scope the rewrite to Anthropic SDK sends only — the positional
// `conversations.messages.create(id, body, ...)` Letta calls don't match.
const THINKING_CHOKEPOINT_TOKEN = `messages.create({ ...params,`;
const THINKING_CHOKEPOINT_REPLACEMENT = `messages.create({ ...globalThis.__lcpFixThinking(params),`;
const THINKING_STREAM_CHOKEPOINT_TOKEN = `messages.stream({ ...params`;
const THINKING_STREAM_CHOKEPOINT_REPLACEMENT = `messages.stream({ ...globalThis.__lcpFixThinking(params)`;

// Injected once at the top of letta.js. Idempotent and surgical, matching
// Anthropic's strict thinking schema:
//   - "disabled" permits ZERO sibling keys → reduce to { type:"disabled" }.
//     (This is the exact shape that triggers the live error:
//      thinking.disabled.budget_tokens: Extra inputs are not permitted)
//   - "adaptive" permits `display` but NOT `budget_tokens` → drop only
//     budget_tokens, keep display.
//   - "enabled" requires budget_tokens. Some subagent-spawn paths construct
//     `{ type:"enabled" }` with no budget, which Anthropic rejects with
//     `thinking.enabled.budget_tokens: Field required`; add a conservative
//     default at the same chokepoint.
// Returns the original object when nothing needs changing (no allocation, no
// behavior change for healthy requests).
const THINKING_HELPER_DEFINITION =
  `globalThis.__lcpFixThinking = globalThis.__lcpFixThinking || function (p) {\n` +
  `  try {\n` +
  `    const t = p && p.thinking;\n` +
  `    if (t && typeof t === "object" && typeof t.type === "string") {\n` +
  `      if (t.type === "enabled" && typeof t.budget_tokens !== "number") {\n` +
  `        const configured = Number(process.env.LETTA_CODE_THINKING_BUDGET_TOKENS || 10000);\n` +
  `        const budget = Number.isFinite(configured) && configured > 0 ? Math.floor(configured) : 10000;\n` +
  `        return { ...p, thinking: { ...t, budget_tokens: budget } };\n` +
  `      }\n` +
  `      if (t.type === "disabled" && Object.keys(t).length > 1) {\n` +
  `        return { ...p, thinking: { type: "disabled" } };\n` +
  `      }\n` +
  `      if (t.type !== "enabled" && "budget_tokens" in t) {\n` +
  `        const { budget_tokens, ...rest } = t;\n` +
  `        return { ...p, thinking: rest };\n` +
  `      }\n` +
  `    }\n` +
  `  } catch {}\n` +
  `  return p;\n` +
  `};\n`;

// lcp-0u15: local backend can persist `agent.model_settings.thinking` before
// the request reaches the Anthropic SDK chokepoint. Subagents inherit those
// settings through LocalStore/effectiveAgentForConversation; if a model preset
// contributed only `{ type: "enabled" }`, the later provider turn fails with
// `thinking.enabled.budget_tokens: Field required`. Normalize model_settings at
// storage and merge boundaries as the same schema-safe fallback used for SDK
// requests.
const MODEL_SETTINGS_HELPER_DEFINITION =
  `globalThis.__lcpFixModelSettings = globalThis.__lcpFixModelSettings || function (m) {\n` +
  `  try {\n` +
  `    const t = m && m.thinking;\n` +
  `    if (t && typeof t === "object" && typeof t.type === "string") {\n` +
  `      if (t.type === "enabled" && typeof t.budget_tokens !== "number") {\n` +
  `        const configured = Number(process.env.LETTA_CODE_THINKING_BUDGET_TOKENS || 10000);\n` +
  `        const budget = Number.isFinite(configured) && configured > 0 ? Math.floor(configured) : 10000;\n` +
  `        return { ...m, thinking: { ...t, budget_tokens: budget } };\n` +
  `      }\n` +
  `      if (t.type === "disabled" && Object.keys(t).length > 1) {\n` +
  `        return { ...m, thinking: { type: "disabled" } };\n` +
  `      }\n` +
  `      if (t.type !== "enabled" && "budget_tokens" in t) {\n` +
  `        const { budget_tokens, ...rest } = t;\n` +
  `        return { ...m, thinking: rest };\n` +
  `      }\n` +
  `    }\n` +
  `  } catch {}\n` +
  `  return m;\n` +
  `};\n`;

const MODEL_SETTINGS_RETURN_TOKEN = `  return modelSettings;\n}`;
const MODEL_SETTINGS_RETURN_REPLACEMENT = `  return globalThis.__lcpFixModelSettings(modelSettings);\n}`;

const EFFECTIVE_AGENT_SETTINGS_TOKEN =
  `    model_settings: {\n` +
  `      ...agent2.model_settings,\n` +
  `      ...conversationModelSettings2 ?? {},\n` +
  `      ...typeof conversationRecord.context_window_limit === "number" ? { context_window_limit: conversationRecord.context_window_limit } : {}\n` +
  `    }`;

const EFFECTIVE_AGENT_SETTINGS_REPLACEMENT =
  `    model_settings: globalThis.__lcpFixModelSettings({\n` +
  `      ...agent2.model_settings,\n` +
  `      ...conversationModelSettings2 ?? {},\n` +
  `      ...typeof conversationRecord.context_window_limit === "number" ? { context_window_limit: conversationRecord.context_window_limit } : {}\n` +
  `    })`;

// lcp-taqw: local model discovery can register vision-capable OpenAI-compatible
// models with input:["text"] even when the model id clearly maps to a vision
// family (Claude, Gemini, GPT, LLaVA, etc.). Later tool-result converters gate
// Read(image) pixels behind `model.input.includes("image")`; when discovery
// under-reports the capability, the converter emits only `(see attached image)`.
// Patch the single registeredModelToPiModel() seam so every downstream provider
// converter sees the same corrected input capabilities. Limit this to local
// backend mode so remote/Constellation model declarations remain authoritative.
// Vision model identification is DATA, not code: the shim owns the pattern
// list in lib/model-catalog.ts (VISION_MODEL_PATTERNS) and exports it into
// LETTA_VISION_MODELS (comma-separated, case-insensitive substrings) at
// startup, which this spawned CLI process inherits. The regex below is only
// a fallback for processes launched outside the shim (manual CLI runs).
// To add a vision model: edit VISION_MODEL_PATTERNS once, or set
// SHIM_VISION_MODELS_EXTRA on the service unit — no edit here needed.
const LOCAL_VISION_INPUT_HELPER_DEFINITION =
  `globalThis.__lcpFixLocalVisionInput = globalThis.__lcpFixLocalVisionInput || function (providerName, modelId, input) {\n` +
  `  try {\n` +
  `    const current = Array.isArray(input) ? input : ["text"];\n` +
  `    if (current.includes("image")) return current;\n` +
  `    if (process.env.LETTA_LOCAL_BACKEND_EXPERIMENTAL !== "1" && !process.env.LETTA_LOCAL_BACKEND_DIR) return current;\n` +
  `    const haystack = (String(providerName || "") + "/" + String(modelId || "")).toLowerCase();\n` +
  `    const envList = String(process.env.LETTA_VISION_MODELS || "")\n` +
  `      .split(",").map((s) => s.trim().toLowerCase()).filter(Boolean);\n` +
  `    if (envList.length > 0) {\n` +
  `      if (envList.some((p) => haystack.includes(p)) || /\\bvl\\b/.test(haystack)) {\n` +
  `        return [...current, "image"];\n` +
  `      }\n` +
  `    } else if (/llava|vision|\\bvl\\b|qwen-vl|qwen2-vl|qwen2\\.5-vl|opus|sonnet|haiku|claude|fable|gpt-|gpt5|gemini|grok|minimax/i.test(haystack)) {\n` +
  `      return [...current, "image"];\n` +
  `    }\n` +
  `  } catch {}\n` +
  `  return Array.isArray(input) ? input : ["text"];\n` +
  `};\n`;

const LOCAL_VISION_INPUT_TOKEN = `input: input.model.input,`;
const LOCAL_VISION_INPUT_REPLACEMENT =
  `input: globalThis.__lcpFixLocalVisionInput(input.providerName, input.model.id, input.model.input),`;

// lcp-qi2f: the INBOUND user-image gate. toChatMessages(messages, supportsImages)
// computes supportsImages = `model.input.includes("image")` at the request-build
// site. For custom/discovered lmstudio handles (e.g. lmstudio/opus-4-8) the
// resolved model.input may NOT include "image" even though the model is
// vision-capable — so user-attached images are replaced with
// "(image omitted: model does not support images)" before reaching the model.
// The LOCAL_VISION_INPUT patch above fixes model.input at the construction
// site, but this request-build site can read a model object that bypassed it.
// Patch the gate directly to also honor the vision-model id check, reusing the
// same helper (it accepts an input array and returns one including "image").
const VISION_GATE_TOKEN = `toChatMessages(messages, model.input.includes("image"))`;
const VISION_GATE_REPLACEMENT =
  `toChatMessages(messages, globalThis.__lcpFixLocalVisionInput(undefined, model.id, model.input).includes("image"))`;

// lcp-qi2f: the ACTUAL inbound-user-image gate. downgradeUnsupportedImages(
// messages, model) replaces user image blocks with NON_VISION_USER_IMAGE_
// PLACEHOLDER ("(image omitted: model does not support images)") whenever
// `model.input.includes("image")` is false — a SEPARATE path from the
// toChatMessages gate above, and the one that strips images Emmanuel attaches.
// Rewrite its early-return guard to honor the vision-model id check via the
// same helper. Anchored on the unique function signature so we only touch
// this site.
const DOWNGRADE_IMAGES_TOKEN =
  `function downgradeUnsupportedImages(messages, model) {\n  if (model.input.includes("image")) {`;
const DOWNGRADE_IMAGES_REPLACEMENT =
  `function downgradeUnsupportedImages(messages, model) {\n  if (globalThis.__lcpFixLocalVisionInput(undefined, model.id, model.input).includes("image")) {`;

// lcp-taqw: executeSingleDecision() emitted `tool_return_message` stream
// chunks with getDisplayableToolReturn(toolResult.toolReturn). That helper is
// intentionally text-only for UI display, so the local store appended a
// toolResult containing just `[Image: file.png]` and then ignored the later raw
// batch result as a duplicate. Preserve the raw multimodal tool return on the
// stream chunk; projection layers can still stringify it for UI, while the next
// model turn keeps the image block.
// The bundler suffixes colliding local names (toolResult -> toolResult2 in
// 0.34.x), so accept either spelling and keep the replacement matching it.
const MULTIMODAL_TOOL_RETURN_VARIANTS = ["toolResult", "toolResult2"].map((name) => ({
  token: `tool_return: getDisplayableToolReturn(${name}.toolReturn),`,
  replacement: `tool_return: ${name}.toolReturn,`,
}));

// lcp-taqw: normalizeOutgoingApprovalMessages() validates tool returns before
// sending approval turns back into the local backend. Its built-in
// isToolReturnContent() only accepts local stored image blocks shaped
// `{type:"image", mimeType, data}`, while Read(image) returns Anthropic-style
// legacy blocks shaped `{type:"image", source:{type:"base64", media_type,
// data}}`. Without preserving that legacy shape, coerceToolReturnContent()
// falls back to normalizeToolReturnText(), which keeps only `[Image: file.png]`
// and drops the pixels before applyApprovalResults() can persist them. Keep the
// legacy shape here because applyApprovalResults()->toolResultContentFromUnknown
// is the seam that converts legacy `source` blocks into local `mimeType/data`.
const TOOL_RETURN_CONTENT_HELPER_DEFINITION =
  `globalThis.__lcpCoerceToolReturnContent = globalThis.__lcpCoerceToolReturnContent || function (value) {\n` +
  `  try {\n` +
  `    if (typeof value === "string") return value;\n` +
  `    if (Array.isArray(value)) {\n` +
  `      const out = [];\n` +
  `      for (const part of value) {\n` +
  `        if (!part || typeof part !== "object") return normalizeToolReturnText(value);\n` +
  `        if (part.type === "text" && typeof part.text === "string") {\n` +
  `          out.push({ type: "text", text: part.text });\n` +
  `          continue;\n` +
  `        }\n` +
  `        if (part.type === "image" && typeof part.data === "string" && typeof part.mimeType === "string") {\n` +
  `          out.push({ type: "image", mimeType: part.mimeType, data: part.data });\n` +
  `          continue;\n` +
  `        }\n` +
  `        const source = part.type === "image" && part.source && typeof part.source === "object" ? part.source : null;\n` +
  `        if (source && source.type === "base64" && typeof source.media_type === "string" && typeof source.data === "string") {\n` +
  `          out.push({ type: "image", source: { type: "base64", media_type: source.media_type, data: source.data } });\n` +
  `          continue;\n` +
  `        }\n` +
  `        return normalizeToolReturnText(value);\n` +
  `      }\n` +
  `      return out;\n` +
  `    }\n` +
  `  } catch {}\n` +
  `  return isToolReturnContent(value) ? value : normalizeToolReturnText(value);\n` +
  `};\n`;

const TOOL_RETURN_CONTENT_TOKEN =
  `function coerceToolReturnContent(value) {\n` +
  `  if (isToolReturnContent(value))\n` +
  `    return value;\n` +
  `  return normalizeToolReturnText(value);\n` +
  `}`;

const TOOL_RETURN_CONTENT_REPLACEMENT =
  `function coerceToolReturnContent(value) {\n` +
  `  return globalThis.__lcpCoerceToolReturnContent(value);\n` +
  `}`;

// lcp-8e41: restore the native image generation tool clobbered by divergent
// patch-loader history. Patch the bundled local tool registry and model default
// allowlists so agents can call image generation through the normal tool
// pipeline without spending quota in startup/tests.
const GENERATE_IMAGE_TOOL_HELPER_DEFINITION =
  `globalThis.__lcpAddGenerateImageTool = globalThis.__lcpAddGenerateImageTool || function (toolDefinitions, defineToolFn) {\n` +
  `  if (!toolDefinitions || typeof toolDefinitions !== "object" || toolDefinitions.generate_image) return toolDefinitions;\n` +
  `  const schema = {\n` +
  `    type: "object",\n` +
  `    additionalProperties: false,\n` +
  `    properties: {\n` +
  `      prompt: { type: "string", description: "Detailed description of the image to generate." },\n` +
  `      size: { type: "string", enum: ["1024x1024", "1024x1536", "1536x1024"], description: "Output dimensions. Defaults to 1024x1024." },\n` +
  `      quality: { type: "string", enum: ["low", "medium", "high"], description: "Generation quality. Defaults to medium." },\n` +
  `      model: { type: "string", description: "Image model handle. Defaults to gpt-image-2." },\n` +
  `      output_dir: { type: "string", description: "Directory where the generated image should be saved. Defaults to .letta/generated-images under the current working directory." }\n` +
  `    },\n` +
  `    required: ["prompt"]\n` +
  `  };\n` +
  `  const description = "Generate an image from a text prompt using the local max proxy image endpoint. Saves the image to disk and returns the artifact path, metadata, and an inline image block.";\n` +
  `  async function generateImage(args) {\n` +
  `    const prompt = typeof args?.prompt === "string" ? args.prompt.trim() : "";\n` +
  `    if (!prompt) throw new Error("prompt required");\n` +
  `    const size = typeof args?.size === "string" && ["1024x1024", "1024x1536", "1536x1024"].includes(args.size) ? args.size : "1024x1024";\n` +
  `    const quality = typeof args?.quality === "string" && ["low", "medium", "high"].includes(args.quality) ? args.quality : "medium";\n` +
  `    const model = typeof args?.model === "string" && args.model.trim() ? args.model.trim() : "gpt-image-2";\n` +
  `    const endpoint = process.env.LETTA_IMAGE_GENERATION_URL || process.env.OPENAI_IMAGES_BASE_URL && process.env.OPENAI_IMAGES_BASE_URL.replace(/\\/$/, "") + "/images/generations" || "http://127.0.0.1:8082/v1/images/generations";\n` +
  `    const response = await fetch(endpoint, {\n` +
  `      method: "POST",\n` +
  `      headers: { "content-type": "application/json", "authorization": "Bearer " + (process.env.LETTA_IMAGE_GENERATION_API_KEY || process.env.OPENAI_API_KEY || "dummy") },\n` +
  `      body: JSON.stringify({ model, prompt, size, quality, n: 1, response_format: "b64_json" })\n` +
  `    });\n` +
  `    if (!response.ok) {\n` +
  `      const detail = await response.text().catch(() => "");\n` +
  `      throw new Error("image generation failed: HTTP " + response.status + (detail ? " " + detail.slice(0, 500) : ""));\n` +
  `    }\n` +
  `    const payload = await response.json();\n` +
  `    const first = payload && Array.isArray(payload.data) ? payload.data[0] : undefined;\n` +
  `    const b64 = first && typeof first.b64_json === "string" ? first.b64_json : undefined;\n` +
  `    if (!b64) throw new Error("image generation response did not include data[0].b64_json");\n` +
  `    const fs = await import("node:fs/promises");\n` +
  `    const path = await import("node:path");\n` +
  `    const crypto = await import("node:crypto");\n` +
  `    const outputDir = typeof args?.output_dir === "string" && args.output_dir.trim() ? args.output_dir.trim() : path.join(process.cwd(), ".letta", "generated-images");\n` +
  `    await fs.mkdir(outputDir, { recursive: true });\n` +
  `    const digest = crypto.createHash("sha256").update(prompt + "\\0" + Date.now().toString() + "\\0" + b64.slice(0, 64)).digest("hex").slice(0, 16);\n` +
  `    const filePath = path.join(outputDir, "generated-" + digest + ".png");\n` +
  `    await fs.writeFile(filePath, Buffer.from(b64, "base64"));\n` +
  `    const meta = { path: filePath, mime_type: "image/png", model, size, quality, prompt };\n` +
  `    return {\n` +
  `      content: [\n` +
  `        { type: "text", text: JSON.stringify(meta, null, 2) },\n` +
  `        { type: "image", source: { type: "base64", media_type: "image/png", data: b64 } }\n` +
  `      ],\n` +
  `      details: meta\n` +
  `    };\n` +
  `  }\n` +
  `  return { ...toolDefinitions, generate_image: defineToolFn({ schema, description, impl: generateImage }) };\n` +
  `};\n`;

const TOOL_DEFINITIONS_ASSIGN_TOKEN = `  TOOL_DEFINITIONS = toolDefinitions;\n});`;
const TOOL_DEFINITIONS_ASSIGN_REPLACEMENT =
  `  TOOL_DEFINITIONS = globalThis.__lcpAddGenerateImageTool(toolDefinitions, defineTool);\n});`;

// Anchor only on the LAST entry before the array close. The entry preceding
// "Write" drifts across letta.js versions (was "TaskUpdate", is now
// "TodoWrite"), so do NOT depend on it — match `"Write"\n];` and insert
// generate_image after it. (lcp-htu3 bundle-drift guard.)
const DEFAULT_TOOLS_TOKEN =
  `  "Write"\n` +
  `];`;
const DEFAULT_TOOLS_REPLACEMENT =
  `  "Write",\n` +
  `  "generate_image"\n` +
  `];`;

const DEFAULT_TOOLS_INDENTED_TOKEN =
  `    "Write"\n` +
  `  ];`;
const DEFAULT_TOOLS_INDENTED_REPLACEMENT =
  `    "Write",\n` +
  `    "generate_image"\n` +
  `  ];`;

const OPENAI_TOOLS_TOKEN =
  `  "ApplyPatch",\n` +
  `  "UpdatePlan"\n` +
  `];`;
const OPENAI_TOOLS_REPLACEMENT =
  `  "ApplyPatch",\n` +
  `  "UpdatePlan",\n` +
  `  "generate_image"\n` +
  `];`;

const OPENAI_TOOLS_INDENTED_TOKEN =
  `    "ApplyPatch",\n` +
  `    "UpdatePlan"\n` +
  `  ];`;
const OPENAI_TOOLS_INDENTED_REPLACEMENT =
  `    "ApplyPatch",\n` +
  `    "UpdatePlan",\n` +
  `    "generate_image"\n` +
  `  ];`;

// letta-code >= 0.32.17 (lcp-htu3 drift, 2026-09-24): the flat DEFAULT_TOOLS /
// OPENAI_DEFAULT_TOOLS arrays were replaced by TOOLSET_CATALOG.<id>.tools in
// src/tools/toolset-catalog.ts (8-space entries, `      ]` close, no `;`). One
// anchor per toolset whose last entry is stable: default ends "Write", codex
// ends "ApplyPatch","UpdatePlan", letta ends "ViewImage","UpdatePlan". Each is
// verified unique in 0.32.17 and absent in 0.32.3, so both bundles keep
// registering generate_image in every default toolset.
const TOOLSET_CATALOG_DEFAULT_TOKEN =
  `        "Write"\n` +
  `      ]`;
const TOOLSET_CATALOG_DEFAULT_REPLACEMENT =
  `        "Write",\n` +
  `        "generate_image"\n` +
  `      ]`;
const TOOLSET_CATALOG_CODEX_TOKEN =
  `        "ApplyPatch",\n` +
  `        "UpdatePlan"\n` +
  `      ]`;
const TOOLSET_CATALOG_CODEX_REPLACEMENT =
  `        "ApplyPatch",\n` +
  `        "UpdatePlan",\n` +
  `        "generate_image"\n` +
  `      ]`;
const TOOLSET_CATALOG_LETTA_TOKEN =
  `        "ViewImage",\n` +
  `        "UpdatePlan"\n` +
  `      ]`;
const TOOLSET_CATALOG_LETTA_REPLACEMENT =
  `        "ViewImage",\n` +
  `        "UpdatePlan",\n` +
  `        "generate_image"\n` +
  `      ]`;

// lcp-aioi8-p1 (patch #18): system-prompt dirty-check write.
//
// `persistCompiledSystemPrompt` rewrites the full ~670KB system-prompt.json on
// EVERY message append (it is called unconditionally at the end of
// persistConversationState), even when the compiled prompt is byte-identical.
// Patch: serialize once, compare against a per-conversation-key memo of the
// last-written JSON string (globalThis.__lcpSysPromptJson, injected after the
// shebang), and return without touching disk when unchanged. `deleteAgent` not
// clearing the memo is harmless — one string per live conversation key,
// bounded, and small relative to the writes being removed.
//
// The anchor is the entire method body through the writeFileSync17 tail; the
// tail's template literal contains a REAL newline (see SETTLE_BUG_LITERAL
// pattern note), hence the explicit "\n" split below. Verified unique in
// letta-code 0.27.22 — the loader additionally enforces exactly-one occurrence
// at apply time (fail-open skip otherwise).
const SYS_PROMPT_MEMO_HELPER_DEFINITION =
  "globalThis.__lcpSysPromptJson = globalThis.__lcpSysPromptJson || new Map();\n";

// The three fs bindings are numbered by the minifier and renumber on most releases, so they
// are captured rather than spelled. Everything else here is the behaviour being replaced.
const SYS_PROMPT_PERSIST_PATTERN =
  /  persistCompiledSystemPrompt\(conversationId, agentId\) \{\n    if \(!this\.storageDir\)\n      return;\n    const key = this\.conversationKey\(conversationId, agentId\);\n    const prompt = this\.compiledSystemPromptByConversationKey\.get\(key\);\n    if \(!prompt\)\n      return;\n    const conversationDir = (join\d+)\(this\.storageDir, "conversations", encodePathSegment\(key\)\);\n    (mkdirSync\d+)\(conversationDir, \{ recursive: true \}\);\n    (writeFileSync\d+)\(\1\(conversationDir, "system-prompt\.json"\), `\$\{JSON\.stringify\(prompt, null, 2\)\}\n`\);\n  \}/g;

/**
 * @param {string} join
 * @param {string} mkdirSync
 * @param {string} writeFileSync
 * @returns {string}
 */
function sysPromptPersistReplacement(join, mkdirSync, writeFileSync) {
  return (
    "  persistCompiledSystemPrompt(conversationId, agentId) {\n" +
    "    if (!this.storageDir)\n" +
    "      return;\n" +
    "    const key = this.conversationKey(conversationId, agentId);\n" +
    "    const prompt = this.compiledSystemPromptByConversationKey.get(key);\n" +
    "    if (!prompt)\n" +
    "      return;\n" +
    "    const json = JSON.stringify(prompt, null, 2);\n" +
    "    if (globalThis.__lcpSysPromptJson.get(key) === json)\n" +
    "      return;\n" +
    `    const conversationDir = ${join}(this.storageDir, "conversations", encodePathSegment(key));\n` +
    `    ${mkdirSync}(conversationDir, { recursive: true });\n` +
    `    ${writeFileSync}(${join}(conversationDir, "system-prompt.json"), json + "\\n");\n` +
    "    globalThis.__lcpSysPromptJson.set(key, json);\n" +
    "  }"
  );
}

// lcp-aioi8-p2 (patch #19): dehydrate the persisted-message dedupe map.
//
// `persistedMessageByMessageIdByConversationKey` holds a structuredClone of
// EVERY message ever persisted per conversation (multi-hundred-MB clone graphs
// on large transcripts). Its only value-read site is the dedupe check in
// appendConversationSessionMessageEntry, which compares via
// JSON.stringify(a) === JSON.stringify(b) (localMessagesHaveSameSnapshot).
// Do NOT evict entries (a miss falls through the guard and appends a duplicate
// transcript line) — DEHYDRATE them: store the JSON string instead of the
// clone, and compare stored-string === JSON.stringify(message). Semantics are
// identical because the original comparator is stringify-equality and
// structuredClone preserves key order.
//
// The three sub-replacements below MUST apply atomically — a partial apply
// changes the type of the map values for only some writers/readers and breaks
// dedupe. The bulk-rebuild anchor is deliberately the full multi-line
// `new Map(Array.from(...))` literal: the bare token `cloneLocalMessage(message)`
// occurs more than once in the bundle and must never be used as an anchor.
const CLONE_MAP_BULK_TOKEN =
  "    this.persistedMessageByMessageIdByConversationKey.set(key, new Map(Array.from(transcript.messageById, ([messageId, message]) => [\n" +
  "      messageId,\n" +
  "      cloneLocalMessage(message)\n" +
  "    ])));";

const CLONE_MAP_BULK_REPLACEMENT =
  "    this.persistedMessageByMessageIdByConversationKey.set(key, new Map(Array.from(transcript.messageById, ([messageId, message]) => [\n" +
  "      messageId,\n" +
  "      JSON.stringify(message)\n" +
  "    ])));";
// letta-code >= 0.32.17 renamed the local to `transcript2` (2026-09-24). Same
// site, same shape; the apply block accepts whichever variant matches once.
const CLONE_MAP_BULK_TOKEN_V2 = CLONE_MAP_BULK_TOKEN.replaceAll("transcript.messageById", "transcript2.messageById");
const CLONE_MAP_BULK_REPLACEMENT_V2 = CLONE_MAP_BULK_REPLACEMENT.replaceAll("transcript.messageById", "transcript2.messageById");

const CLONE_MAP_APPEND_TOKEN =
  ".set(entry.message.id, cloneLocalMessage(entry.message));";

const CLONE_MAP_APPEND_REPLACEMENT =
  ".set(entry.message.id, JSON.stringify(entry.message));";

const CLONE_MAP_COMPARE_TOKEN =
  "localMessagesHaveSameSnapshot(persistedMessage, message)";

const CLONE_MAP_COMPARE_REPLACEMENT =
  "persistedMessage === JSON.stringify(message)";

// lcp-oaiap: the OpenAI-compatible API (/v1/chat/completions and /v1/responses)
// aborts a turn the moment the runtime reports WAITING_ON_APPROVAL, replacing
// the agent's real answer with a canned "this API does not support" note.
//
// That is a RACE, not a capability gate. With auto-approval configured the
// runtime passes briefly through WAITING_ON_APPROVAL, then approves and runs the
// tool: the on-disk conversation holds the toolCall, a toolResult with
// isError:false, and a correct final assistant message -- all written AFTER the
// HTTP response was already finished with the canned text. Verified 2026-09-04
// with `echo HELLO_FROM_TOOL`: local-conv-270 ends with "Exact output:
// HELLO_FROM_TOOL" while the API returned the approval note. /v1/responses is
// the same code path (it emits status:"incomplete" plus the same note), so this
// is not fixable by switching surfaces.
//
// Ignore the transient status instead of finishing. A genuinely stuck approval
// stays bounded by the existing OPENAI_TURN_TIMEOUT_MS (15 min) backstop, which
// reports an honest timeout rather than a false capability claim.
// Set LETTA_OPENAI_API_APPROVAL_BAIL=1 to restore stock bail-out behavior.
const OPENAI_APPROVAL_BAIL_TOKEN = `if (status === "WAITING_ON_APPROVAL") {`;
const OPENAI_APPROVAL_BAIL_REPLACEMENT =
  `if (status === "WAITING_ON_APPROVAL" && globalThis.__lcpOpenAiApprovalBail()) {`;
const OPENAI_APPROVAL_BAIL_HELPER =
  `globalThis.__lcpOpenAiApprovalBail = globalThis.__lcpOpenAiApprovalBail || function () {\n` +
  `  return process.env.LETTA_OPENAI_API_APPROVAL_BAIL === "1";\n` +
  `};\n`;

// lcp-otid — persist the streamed assistant otid on the durable record.
//
// letta-code already mints a stable per-segment id for streamed assistant text
// (`provider-assistant-<segmentIndex>-<uuid>`, via otidForContentSegment) and
// emits it as `otid` on every assistant_message delta; mobile sees it prefixed
// as `cm-stream-provider-assistant-...`. The durable write does NOT keep it:
// toLocalAssistantMessage stores responseId, model, provider and usage, so the
// stored row shares no identifier with the stream that produced it.
//
// Clients are therefore forced to pair the streamed row with the settled row by
// matching their text, and that fails the instant a stream drops its tail: both
// rows stay on screen and the reply renders twice. Measured on device
// 2026-09-11 — streamed 368 characters against a stored 369, a single missing
// "?" was enough to duplicate a reply.
//
// The user path already persists its otid, which is exactly why user messages
// have a key and assistant messages do not. This closes that asymmetry. The
// local-message event carries the record that is about to be persisted, and it
// arrives on the same generator that owns `assistantOtids`, after the deltas
// that minted them — so the ids are stamped in place, with no global state and
// no cross-talk between concurrent conversations.
// The bundle's minifier names this binding `event` in 0.30.x and `event2` in
// 0.29.x, so the stamp is tried against both spellings and the first that
// matches exactly once wins. Everything else about the site is identical.
// letta-code >= 0.32.17 (2026-09-24): the generator no longer owns an
// `assistantOtids` map (upstream streams and stores the same ui-msg-*:assistant:N
// id, so the stream/durable identity gap this patch closes is gone there). The
// stamp site still matches, so reference the map defensively: `typeof` makes a
// missing binding a no-op (the helper returns the message unchanged for a
// falsy map) instead of a ReferenceError that failed EVERY turn with
// `local_backend_error: assistantOtids is not defined`.
const OTID_STAMP_VARIANTS = ["event", "event2"].map((binding) => ({
  token: `            yield createLocalMessageChunk(${binding}.message);`,
  replacement:
    `            yield createLocalMessageChunk(globalThis.__lcpStampAssistantOtids(${binding}.message, typeof assistantOtids === "undefined" ? undefined : assistantOtids));`,
}));

// toLocalAssistantMessage names every field it keeps, so a stamped otid has to
// be named here too or the durable write drops it again.
const OTID_PERSIST_TOKEN =
  `    usage: message.usage ?? emptyLocalUsage(),`;
const OTID_PERSIST_REPLACEMENT =
  `    ...message.otid ? { otid: message.otid } : {},\n` +
  `    usage: message.usage ?? emptyLocalUsage(),`;

const OTID_HELPER =
  `globalThis.__lcpStampAssistantOtids = globalThis.__lcpStampAssistantOtids || function (message, otids) {\n` +
  `  try {\n` +
  `    if (!message || typeof message !== "object") return message;\n` +
  `    if (!otids || typeof otids.values !== "function") return message;\n` +
  `    const byIndex = {};\n` +
  `    for (const [index, value] of otids.entries()) {\n` +
  `      if (typeof value === "string" && value.length > 0) byIndex[String(index)] = value;\n` +
  `    }\n` +
  `    const indexes = Object.keys(byIndex).map(Number).sort((a, b) => a - b);\n` +
  `    if (indexes.length === 0) return message;\n` +
  `    if (typeof message.otid !== "string" || message.otid.length === 0) {\n` +
  `      message.otid = byIndex[String(indexes[0])];\n` +
  `    }\n` +
  `    const metadata =\n` +
  `      message.metadata && typeof message.metadata === "object"\n` +
  `        ? message.metadata\n` +
  `        : (message.metadata = {});\n` +
  `    if (!metadata.segment_otids || typeof metadata.segment_otids !== "object") {\n` +
  `      metadata.segment_otids = byIndex;\n` +
  `    }\n` +
  `    return message;\n` +
  `  } catch {\n` +
  `    return message;\n` +
  `  }\n` +
  `};\n`;

// Persisting the otid is only half of it: the history projection rebuilds
// assistant frames from the stored record and names every field it emits, so
// without this the client reading history still sees no identity and falls back
// to matching text. The segment index the projection is flushing is the same
// index the otid was minted under, so the lookup is exact even when one stored
// reply projects into several assistant frames.
const OTID_PROJECT_TOKEN =
  `    messages.push({\n` +
  `      id: isFirst ? message.id : \`\${message.id}:assistant:\${pendingTextStartIndex}\`,\n` +
  `      date,\n` +
  `      agent_id: agentId,\n` +
  `      conversation_id: conversationId,\n` +
  `      message_type: "assistant_message",\n` +
  `      role: "assistant",\n` +
  `      content: pendingTextContent\n` +
  `    });`;

const OTID_PROJECT_REPLACEMENT =
  `    messages.push({\n` +
  `      id: isFirst ? message.id : \`\${message.id}:assistant:\${pendingTextStartIndex}\`,\n` +
  `      date,\n` +
  `      agent_id: agentId,\n` +
  `      conversation_id: conversationId,\n` +
  `      message_type: "assistant_message",\n` +
  `      role: "assistant",\n` +
  `      ...globalThis.__lcpSegmentOtid(message, pendingTextStartIndex, isFirst),\n` +
  `      content: pendingTextContent\n` +
  `    });`;

const OTID_PROJECT_HELPER =
  `globalThis.__lcpSegmentOtid = globalThis.__lcpSegmentOtid || function (message, segmentIndex, isFirst) {\n` +
  `  try {\n` +
  `    const segments = message && message.metadata && message.metadata.segment_otids;\n` +
  `    const fromIndex =\n` +
  `      segments && typeof segments === "object" ? segments[String(segmentIndex)] : undefined;\n` +
  `    const otid = typeof fromIndex === "string" && fromIndex.length > 0\n` +
  `      ? fromIndex\n` +
  `      : isFirst && typeof message?.otid === "string" && message.otid.length > 0\n` +
  `        ? message.otid\n` +
  `        : undefined;\n` +
  `    return otid ? { otid } : {};\n` +
  `  } catch {\n` +
  `    return {};\n` +
  `  }\n` +
  `};\n`;

// lcp-clr: conversation-scoped last-run timestamp; lcp-clr-x adds a
// cross-conversation fallback when the in-flight conversation is empty.
//
// Upstream bug (letta-code >= 0.27.x, the listener warmup path): the
// agent-info system reminder renders
//   `- **Last message**: <ts> (<relative>)`
// from `agent.last_run_completion`, which the local backend derives inside
// `projectAgent()` from the agent's hardcoded `default` conversation:
//
//   const defaultConversation = this.findConversation("default", record.id);
//   const lastRunCompletion =
//     defaultConversation?.last_message_at ?? defaultConversation?.updated_at ?? null;
//
// So in any non-default conversation the reminder reports the `default`
// conversation's timestamp — and the relative-time formatter then says
// "55 days ago" for a conversation the user opened 30 seconds ago. Cloud
// `last_run_completion` is genuinely agent-scoped (the cloud backend's only
// notion of a run is per-agent), so `projectAgent` is defensible as written;
// the bug is that the reminder's read site in the listen path always asks
// for the agent-scoped value even when the in-flight conversation is in
// scope. Half conversation-scoped, half agent-scoped.
//
// The single listen-path site that builds `listenAgentMetadata` already has
// `getBackend()`, `agentId`, and the in-flight `conversationId` in scope —
// `conversationId` is then passed straight into `buildListenReminderContext`
// a few lines below — so we route the timestamp through a helper that
// prefers the conversation's own `last_message_at` / `updated_at`,
// then falls back to the most-recent OTHER conversation on this agent when
// this conversation is still empty (lcp-clr-x), and otherwise returns
// `kind: "none"` so the renderer can say "No previous messages" honestly.
//
// Why the cross-conv fallback (lcp-clr-x): a brand-new conversation opened
// mid-session has no `last_message_at`, but the user may have meaningful
// continuity in a sibling conversation that started 42 minutes ago. We
// don't want to surface that as "Last message in this conversation" (a
// lie) or as "No previous messages" (technically true, useless). The
// helper returns a structured object `{ kind, last_message_at,
// conversation_id, summary }` so the renderer can emit a SECOND line:
//   - **Most recent activity**: <ts> (<relative>) in <conversation_id>.
// The banner's primary `Last message:` line still reads "No previous
// messages in this conversation" in the cross-conv case — we never let
// the sibling-conv timestamp masquerade as THIS conversation's.
//
// Interactive path coverage: the same bug exists in the React
// state-setting sites at ~515437 and ~515495 (the `setAgentLastRunAt`
// callers), but both feed `hasSentAgentInfo`-gated values that never
// reach the reminder in the same turn the listen path runs. Subsequent
// turns get the React-state value (still a bare ISO string from
// `agent.last_run_completion`); the renderer normalizes that to
// `kind: "this"` so interactive-path behaviour is preserved.
//
// Fail-safe: the helper swallows every error and returns
// `kind: "none"` on failure. Renderer normalization treats any string
// `lastRunAt` (legacy interactive-path output) as `kind: "this"`, so
// even if the helper is missing the renderer still produces the
// upstream banner. No bead exists for either fix — the repo's beads DB
// is currently schema-skewed (v65 DB vs v53 binary), so the fix is
// tracked only in git.
const CONVERSATION_LAST_RUN_TOKEN =
  `      if (!runtime.reminderState.hasSentAgentInfo && cachedAgent) {\n` +
  `        listenAgentMetadata = {\n` +
  `          name: cachedAgent.name ?? null,\n` +
  `          description: cachedAgent.description ?? null,\n` +
  `          lastRunAt: cachedAgent.last_run_completion ?? null\n` +
  `        };\n` +
  `      }`;

const CONVERSATION_LAST_RUN_REPLACEMENT =
  `      if (!runtime.reminderState.hasSentAgentInfo && cachedAgent) {\n` +
  `        listenAgentMetadata = {\n` +
  `          name: cachedAgent.name ?? null,\n` +
  `          description: cachedAgent.description ?? null,\n` +
  `          lastRunAt: await globalThis.__lcpConversationLastRunAt(getBackend(), agentId, conversationId, cachedAgent.last_run_completion ?? null)\n` +
  `        };\n` +
  `      }`;

// Returns a structured object so the renderer can dispatch by kind.
//   { kind: "this",   last_message_at, conversation_id, summary }
//   { kind: "recent", last_message_at, conversation_id, summary }
//   { kind: "none",   last_message_at: null, conversation_id: null, summary: null }
//
// `agentFallback` (the buggy `agent.last_run_completion`) is no longer
// surfaced — returning it was the original bug. Cross-conv fallback uses
// `listConversations({ agent_id, limit: 5 })` which is already
// pre-sorted descending by `last_message_at` in the local backend
// (`src/backend/local/local-conversations.ts:listLocalConversations`).
// `limit: 5` is one disk scan; cheap for the cold-start path.
const CONVERSATION_LAST_RUN_HELPER_DEFINITION =
  `globalThis.__lcpConversationLastRunAt = globalThis.__lcpConversationLastRunAt || async function (backend, agentId, conversationId) {\n` +
  `  const noneResult = () => ({ kind: "none", last_message_at: null, conversation_id: null, summary: null });\n` +
  `  try {\n` +
  `    if (!backend || typeof backend.retrieveConversation !== "function" ||\n` +
  `        typeof conversationId !== "string" || conversationId === "" || conversationId === "default") {\n` +
  `      return noneResult();\n` +
  `    }\n` +
  `    const conversation = await backend.retrieveConversation(conversationId, agentId);\n` +
  `    if (conversation && typeof conversation === "object") {\n` +
  `      const stamp = conversation.last_message_at ?? conversation.updated_at ?? null;\n` +
  `      if (typeof stamp === "string" && !Number.isNaN(Date.parse(stamp))) {\n` +
  `        return { kind: "this", last_message_at: stamp, conversation_id: conversationId, summary: conversation.summary ?? null };\n` +
  `      }\n` +
  `    }\n` +
  `    // lcp-clr-x: in-flight conversation is empty or un-stamped. Surface\n` +
  `    // the most-recent sibling conversation on the same agent instead of\n` +
  `    // claiming nothing happened. limit=5 caps the file scan; the local\n` +
  `    // backend pre-sorts desc by last_message_at.\n` +
  `    if (backend && typeof backend.listConversations === "function" && typeof agentId === "string" && agentId !== "") {\n` +
  `      const recent = await backend.listConversations({ agent_id: agentId, limit: 5 });\n` +
  `      if (Array.isArray(recent)) {\n` +
  `        const sibling = recent.find((c) => c && typeof c === "object" && c.id !== conversationId && typeof c.last_message_at === "string" && !Number.isNaN(Date.parse(c.last_message_at)));\n` +
  `        if (sibling) {\n` +
  `          return { kind: "recent", last_message_at: sibling.last_message_at, conversation_id: sibling.id, summary: sibling.summary ?? null };\n` +
  `        }\n` +
  `      }\n` +
  `    }\n` +
  `  } catch {}\n` +
  `  return noneResult();\n` +
  `};\n`;

// lcp-clr-x: rewrite buildAgentInfo so it dispatches on the structured
// object shape returned by the helper. The interactive path still sets
// `lastRunAt` to a bare ISO string; we normalize that to `{ kind: "this",
// last_message_at: <str> }` so one branch path covers both shapes.
//
// The renderer change is the only place that reads `agentInfo.lastRunAt`,
// so patching this single function is sufficient — the reminder-build
// chain is `buildSharedReminderParts({ agent: { lastRunAt, ... } })` →
// `buildAgentInfo(options)` and no other consumer inspects this field.
// `getRelativeTime` is a sibling function in the same source file, in
// scope wherever buildAgentInfo is.
const BUILD_AGENT_INFO_TOKEN =
  `function buildAgentInfo(options) {\n` +
  `  try {\n` +
  `    const { agentInfo, conversationId } = options;\n` +
  `    let lastRunInfo = "No previous messages";\n` +
  `    if (agentInfo.lastRunAt) {\n` +
  `      try {\n` +
  `        const lastRunDate = new Date(agentInfo.lastRunAt);\n` +
  `        const localLastRun = lastRunDate.toLocaleString();\n` +
  `        const relativeTime = getRelativeTime(agentInfo.lastRunAt);\n` +
  `        lastRunInfo = \`\${localLastRun} (\${relativeTime})\`;\n` +
  `      } catch {\n` +
  `        lastRunInfo = "(failed to parse last run time)";\n` +
  `      }\n` +
  `    }\n` +
  `    const showMemoryDir = (() => {\n` +
  `      try {\n` +
  `        return isLocalBackendEnvEnabled() || settingsManager.isMemfsEnabled(agentInfo.id);\n` +
  `      } catch {\n` +
  `        return false;\n` +
  `      }\n` +
  `    })();`;

const BUILD_AGENT_INFO_REPLACEMENT =
  `function buildAgentInfo(options) {\n` +
  `  try {\n` +
  `    const { agentInfo, conversationId } = options;\n` +
  `    // Normalize: legacy interactive-path callers pass a bare ISO string;\n` +
  `    // the new lcp-clr helper passes a structured object. Treat strings\n` +
  `    // as kind:"this" so one branch path covers both shapes.\n` +
  `    const lastRunRaw = typeof agentInfo.lastRunAt === "string" && agentInfo.lastRunAt\n` +
  `      ? { kind: "this", last_message_at: agentInfo.lastRunAt, conversation_id: conversationId ?? null, summary: null }\n` +
  `      : (agentInfo.lastRunAt && typeof agentInfo.lastRunAt === "object" ? agentInfo.lastRunAt : { kind: "none", last_message_at: null, conversation_id: null, summary: null });\n` +
  `    const lastRunInfo = globalThis.__lcpFormatLastRun(lastRunRaw);\n` +
  `    const showMemoryDir = (() => {\n` +
  `      try {\n` +
  `        return isLocalBackendEnvEnabled() || settingsManager.isMemfsEnabled(agentInfo.id);\n` +
  `      } catch {\n` +
  `        return false;\n` +
  `      }\n` +
  `    })();`;

// Renderer formatter. Dispatches on `kind` and emits the full
// "Last message:" line text. For `kind: "recent"` it returns two
// concatenated lines (the renderer places them after the bullet row
// in the existing template). For all other kinds it returns a single
// line. The wrapped `try/catch` keeps the reminder building even when
// a sibling conversation object is malformed.
//
// The string-concatenation at the bottom matches the original builder's
// `return \`...${lastRunInfo}...\`;` shape — same `lastRunInfo` variable
// gets the same interpolated position, so no other line needs to move.
const BUILD_AGENT_INFO_HELPER_DEFINITION =
  `globalThis.__lcpFormatLastRun = globalThis.__lcpFormatLastRun || function (lastRun) {\n` +
  `  try {\n` +
  `    if (!lastRun || typeof lastRun !== "object") return "No previous messages";\n` +
  `    if (lastRun.kind !== "this" && lastRun.kind !== "recent" && lastRun.kind !== "none") return "No previous messages";\n` +
  `    const fmtLine = (stamp) => {\n` +
  `      const d = new Date(stamp);\n` +
  `      if (Number.isNaN(d.getTime())) return "No previous messages";\n` +
  `      const local = d.toLocaleString();\n` +
  `      const relative = getRelativeTime(stamp);\n` +
  `      return \`\${local} (\${relative})\`;\n` +
  `    };\n` +
  `    if (lastRun.kind === "this") {\n` +
  `      return lastRun.last_message_at ? fmtLine(lastRun.last_message_at) : "No previous messages";\n` +
  `    }\n` +
  `    if (lastRun.kind === "none") {\n` +
  `      return "No previous messages";\n` +
  `    }\n` +
  `    // kind === "recent": primary line says THIS conversation is empty;\n` +
  `    // second line attributes the recency to the sibling conv by id.\n` +
  `    const primary = "No previous messages in this conversation";\n` +
  `    const sibling = fmtLine(lastRun.last_message_at);\n` +
  `    const targetId = lastRun.conversation_id ? \` in \${lastRun.conversation_id}\` : "";\n` +
  `    return \`\${primary}\\n- **Most recent activity**: \${sibling}\${targetId}\`;\n` +
  `  } catch {\n` +
  `    return "(failed to parse last run time)";\n` +
  `  }\n` +
  `};\n`;

let appliedOnce = false;

/**
 * Exact-occurrence regex match, the pattern counterpart of [countOccurrences].
 *
 * Some anchors differ between releases only in minifier-generated binding names —
 * `join39` against `join34`, `options3` against `options` — which drifts the literal without
 * changing a line of behaviour. Capturing those names instead of hard-coding them keeps one
 * anchor working across releases, while the uniqueness check that follows is the same
 * fail-open guard an exact literal gets.
 *
 * @param {string} source
 * @param {RegExp} pattern global-flagged
 * @returns {RegExpMatchArray | null} the only match, or null when it did not match exactly once
 */
function matchOnce(source, pattern) {
  const matches = [...source.matchAll(pattern)];
  return matches.length === 1 ? matches[0] : null;
}

/**
 * Exact-occurrence counter used for anchor uniqueness assertions. A drifted
 * anchor that happens to match an unintended second site would otherwise be
 * patched silently at the wrong location; patches that declare an expected
 * count of 1 are skipped (fail-open) when the count differs.
 *
 * @param {string} source
 * @param {string} token
 * @returns {number}
 */
function countOccurrences(source, token) {
  return source.split(token).length - 1;
}

/**
 * @param {string} raw
 * @param {string} path
 * @param {boolean} warn
 * @returns {{ source: string; appliedPatches: number; skippedPatches: number }}
 */
function patchLettaCodeSource(raw, path, warn) {
  let patched = raw;
  let appliedPatches = 0;
  let skippedPatches = 0;

  // Both changes must land together: stable OTIDs alone do not stop appends,
  // and dedupe alone cannot recognize the refreshed identities of a retry.
  {
    const refreshCount = countOccurrences(patched, RETRY_OTID_REFRESH_TOKEN);
    const persistCount = countOccurrences(patched, RETRY_OTID_PERSIST_TOKEN);
    if (refreshCount === 1 && persistCount === 1) {
      patched = patched.replace(RETRY_OTID_REFRESH_TOKEN, RETRY_OTID_REFRESH_REPLACEMENT);
      patched = patched.replace(RETRY_OTID_PERSIST_TOKEN, RETRY_OTID_PERSIST_REPLACEMENT);
      appliedPatches += 1;
    } else {
      skippedPatches += 1;
      if (warn) process.stderr.write(
        `[letta-code-patch] WARN: lcp-retry-otid anchors matched refresh=${refreshCount} persist=${persistCount}, ` +
        `expected exactly 1 each, in ${path} — skipping atomically; local retries may duplicate user prompts\n`,
      );
    }
  }

  // All three land together: lazy loading alone fixes nothing while the read paths
  // still test the map directly, and the read paths alone would never find the agent.
  {
    const refreshCount = countOccurrences(patched, AGENT_LAZYLOAD_REFRESH_TOKEN);
    const listConversationCount = countOccurrences(patched, AGENT_LAZYLOAD_LIST_CONVERSATION_TOKEN);
    const listAgentCount = countOccurrences(patched, AGENT_LAZYLOAD_LIST_AGENT_TOKEN);
    if (refreshCount === 1 && listConversationCount === 1 && listAgentCount === 1) {
      patched = patched.replace(AGENT_LAZYLOAD_REFRESH_TOKEN, AGENT_LAZYLOAD_REFRESH_REPLACEMENT);
      patched = patched.replace(AGENT_LAZYLOAD_LIST_CONVERSATION_TOKEN, AGENT_LAZYLOAD_LIST_CONVERSATION_REPLACEMENT);
      patched = patched.replace(AGENT_LAZYLOAD_LIST_AGENT_TOKEN, AGENT_LAZYLOAD_LIST_AGENT_REPLACEMENT);
      appliedPatches += 1;
    } else {
      skippedPatches += 1;
      if (warn) process.stderr.write(
        `[letta-code-patch] WARN: lcp-agent-lazyload anchors matched refresh=${refreshCount} ` +
        `listConversation=${listConversationCount} listAgent=${listAgentCount}, expected exactly 1 each, ` +
        `in ${path} — skipping atomically; agents created after App Server start stay unreadable\n`,
      );
    }
  }

  if (patched.includes(SETTLE_BUG_LITERAL)) {
    patched = patched.replace(SETTLE_BUG_LITERAL, SETTLE_FIX_LITERAL);
    appliedPatches += 1;
  } else {
    skippedPatches += 1;
    if (warn) process.stderr.write(
      `[letta-code-patch] WARN: settle-bug literal not found in ${path} — ` +
      `running unpatched (letta-code likely upgraded past 0.26.1)\n`,
    );
  }

  if (patched.includes(THINKING_SETTINGS_BUG_LITERAL)) {
    patched = patched.replaceAll(THINKING_SETTINGS_BUG_LITERAL, THINKING_SETTINGS_FIX_LITERAL);
    appliedPatches += 1;
  } else {
    skippedPatches += 1;
    if (warn) process.stderr.write(
      `[letta-code-patch] WARN: thinking-settings literal not found in ${path} — ` +
      `running without lcp-9pn settings guard\n`,
    );
  }

  {
    const guard = matchOnce(patched, THINKING_REQUEST_GUARD_PATTERN);
    if (guard) {
      patched = patched.replace(guard[0], thinkingRequestGuardInsert(guard[1] ?? "options"));
      appliedPatches += 1;
    } else {
      skippedPatches += 1;
      if (warn) process.stderr.write(
        `[letta-code-patch] WARN: thinking-request guard anchor did not match exactly once in ${path} — ` +
        `running without lcp-9pn request guard\n`,
      );
    }
  }

  // lcp-7kk: universal chokepoint normalizer for every Anthropic request.
  let appliedThinkingChokepoint = false;
  if (patched.includes(THINKING_CHOKEPOINT_TOKEN)) {
    patched = patched.replaceAll(THINKING_CHOKEPOINT_TOKEN, THINKING_CHOKEPOINT_REPLACEMENT);
    appliedPatches += 1;
    appliedThinkingChokepoint = true;
  }
  if (patched.includes(THINKING_STREAM_CHOKEPOINT_TOKEN)) {
    patched = patched.replaceAll(THINKING_STREAM_CHOKEPOINT_TOKEN, THINKING_STREAM_CHOKEPOINT_REPLACEMENT);
    appliedPatches += 1;
    appliedThinkingChokepoint = true;
  }
  if (appliedThinkingChokepoint) {
    patched = injectHelperAfterShebang(patched, THINKING_HELPER_DEFINITION);
  } else {
    skippedPatches += 1;
    if (warn) process.stderr.write(
      `[letta-code-patch] WARN: thinking chokepoint token not found in ${path} — ` +
      `running without lcp-7kk request normalizer\n`,
    );
  }

  let appliedModelSettingsGuard = false;
  if (patched.includes(MODEL_SETTINGS_RETURN_TOKEN)) {
    patched = patched.replaceAll(MODEL_SETTINGS_RETURN_TOKEN, MODEL_SETTINGS_RETURN_REPLACEMENT);
    appliedPatches += 1;
    appliedModelSettingsGuard = true;
  }
  if (patched.includes(EFFECTIVE_AGENT_SETTINGS_TOKEN)) {
    patched = patched.replaceAll(EFFECTIVE_AGENT_SETTINGS_TOKEN, EFFECTIVE_AGENT_SETTINGS_REPLACEMENT);
    appliedPatches += 1;
    appliedModelSettingsGuard = true;
  }
  if (appliedModelSettingsGuard) {
    patched = injectHelperAfterShebang(patched, MODEL_SETTINGS_HELPER_DEFINITION);
  } else {
    skippedPatches += 1;
    if (warn) process.stderr.write(
      `[letta-code-patch] WARN: model_settings thinking guard token not found in ${path} — ` +
      `running without lcp-0u15 settings normalizer\n`,
    );
  }

  if (patched.includes(LOCAL_VISION_INPUT_TOKEN)) {
    patched = patched.replace(LOCAL_VISION_INPUT_TOKEN, LOCAL_VISION_INPUT_REPLACEMENT);
    appliedPatches += 1;
    patched = injectHelperAfterShebang(patched, LOCAL_VISION_INPUT_HELPER_DEFINITION);
  } else {
    skippedPatches += 1;
    if (warn) process.stderr.write(
      `[letta-code-patch] WARN: local vision input token not found in ${path} — ` +
      `Read(image) may still be placeholdered by model.input gates\n`,
    );
  }

  // lcp-qi2f: inbound user-image gate at the request-build site.
  if (patched.includes(VISION_GATE_TOKEN)) {
    patched = patched.replace(VISION_GATE_TOKEN, VISION_GATE_REPLACEMENT);
    appliedPatches += 1;
    // Ensure the helper is present even if the input-construction token above
    // was absent (idempotent: the helper uses `|| function` guard).
    patched = injectHelperAfterShebang(patched, LOCAL_VISION_INPUT_HELPER_DEFINITION);
  } else {
    skippedPatches += 1;
    if (warn) process.stderr.write(
      `[letta-code-patch] WARN: vision gate token (toChatMessages supportsImages) not found in ${path} — ` +
      `inbound user images may still be omitted\n`,
    );
  }

  // lcp-qi2f: downgradeUnsupportedImages — the real inbound user-image strip.
  if (patched.includes(DOWNGRADE_IMAGES_TOKEN)) {
    patched = patched.replace(DOWNGRADE_IMAGES_TOKEN, DOWNGRADE_IMAGES_REPLACEMENT);
    appliedPatches += 1;
    patched = injectHelperAfterShebang(patched, LOCAL_VISION_INPUT_HELPER_DEFINITION);
  } else {
    skippedPatches += 1;
    if (warn) process.stderr.write(
      `[letta-code-patch] WARN: downgradeUnsupportedImages token not found in ${path} — ` +
      `inbound user images may still be omitted\n`,
    );
  }

  const multimodalVariant = MULTIMODAL_TOOL_RETURN_VARIANTS.find((variant) => patched.includes(variant.token));
  if (multimodalVariant) {
    patched = patched.replace(multimodalVariant.token, multimodalVariant.replacement);
    appliedPatches += 1;
  } else {
    skippedPatches += 1;
    if (warn) process.stderr.write(
      `[letta-code-patch] WARN: multimodal tool-return token not found in ${path} — ` +
      `Read(image) may still be reduced to display text before storage\n`,
    );
  }

  if (patched.includes(TOOL_RETURN_CONTENT_TOKEN)) {
    patched = patched.replace(TOOL_RETURN_CONTENT_TOKEN, TOOL_RETURN_CONTENT_REPLACEMENT);
    appliedPatches += 1;
    patched = injectHelperAfterShebang(patched, TOOL_RETURN_CONTENT_HELPER_DEFINITION);
  } else {
    skippedPatches += 1;
    if (warn) process.stderr.write(
      `[letta-code-patch] WARN: tool-return content coercion token not found in ${path} — ` +
      `Read(image) approval turns may still be normalized to text only\n`,
    );
  }

  let appliedGenerateImageTool = false;
  if (patched.includes(TOOL_DEFINITIONS_ASSIGN_TOKEN)) {
    patched = patched.replace(TOOL_DEFINITIONS_ASSIGN_TOKEN, TOOL_DEFINITIONS_ASSIGN_REPLACEMENT);
    appliedPatches += 1;
    appliedGenerateImageTool = true;
  }
  if (patched.includes(DEFAULT_TOOLS_TOKEN)) {
    patched = patched.replaceAll(DEFAULT_TOOLS_TOKEN, DEFAULT_TOOLS_REPLACEMENT);
    appliedPatches += 1;
    appliedGenerateImageTool = true;
  }
  if (patched.includes(DEFAULT_TOOLS_INDENTED_TOKEN)) {
    patched = patched.replaceAll(DEFAULT_TOOLS_INDENTED_TOKEN, DEFAULT_TOOLS_INDENTED_REPLACEMENT);
    appliedPatches += 1;
    appliedGenerateImageTool = true;
  }
  if (patched.includes(OPENAI_TOOLS_TOKEN)) {
    patched = patched.replaceAll(OPENAI_TOOLS_TOKEN, OPENAI_TOOLS_REPLACEMENT);
    appliedPatches += 1;
    appliedGenerateImageTool = true;
  }
  if (patched.includes(OPENAI_TOOLS_INDENTED_TOKEN)) {
    patched = patched.replaceAll(OPENAI_TOOLS_INDENTED_TOKEN, OPENAI_TOOLS_INDENTED_REPLACEMENT);
    appliedPatches += 1;
    appliedGenerateImageTool = true;
  }
  // letta-code >= 0.32.17 toolset catalog (see TOOLSET_CATALOG_*_TOKEN).
  /** @type {Array<[string, string]>} */
  const toolsetCatalogVariants = [
    [TOOLSET_CATALOG_DEFAULT_TOKEN, TOOLSET_CATALOG_DEFAULT_REPLACEMENT],
    [TOOLSET_CATALOG_CODEX_TOKEN, TOOLSET_CATALOG_CODEX_REPLACEMENT],
    [TOOLSET_CATALOG_LETTA_TOKEN, TOOLSET_CATALOG_LETTA_REPLACEMENT],
  ];
  for (const [token, replacement] of toolsetCatalogVariants) {
    if (countOccurrences(patched, token) === 1) {
      patched = patched.replace(token, replacement);
      appliedPatches += 1;
      appliedGenerateImageTool = true;
    }
  }
  if (appliedGenerateImageTool) {
    patched = injectHelperAfterShebang(patched, GENERATE_IMAGE_TOOL_HELPER_DEFINITION);
  } else {
    skippedPatches += 1;
    if (warn) process.stderr.write(
      `[letta-code-patch] WARN: generate_image tool registry tokens not found in ${path} — ` +
      `native image generation tool will not be registered\n`,
    );
  }

  // lcp-aioi8-p1 (patch #18): dirty-check system-prompt.json writes.
  // FAIL-OPEN with a unique-match assertion: anchor absent OR matched more
  // than once → warn + skip, never throw at boot.
  {
    const persist = matchOnce(patched, SYS_PROMPT_PERSIST_PATTERN);
    if (persist) {
      const [token, join, mkdirSync, writeFileSync] = persist;
      patched = patched.replace(
        token,
        sysPromptPersistReplacement(join ?? "", mkdirSync ?? "", writeFileSync ?? ""),
      );
      patched = injectHelperAfterShebang(patched, SYS_PROMPT_MEMO_HELPER_DEFINITION);
      appliedPatches += 1;
    } else {
      skippedPatches += 1;
      if (warn) process.stderr.write(
        `[letta-code-patch] WARN: persistCompiledSystemPrompt anchor did not match exactly once in ${path} — ` +
        `running without lcp-aioi8-p1 dirty-check (every append rewrites system-prompt.json)\n`,
      );
    }
  }

  // lcp-aioi8-p2 (patch #19): dehydrate the persisted-message dedupe map.
  // ATOMIC: all three anchors must each match exactly once or NONE are
  // applied — a partial apply would mix clone-objects and JSON strings in the
  // same map and break the duplicate-append guard. FAIL-OPEN on any mismatch.
  {
    /** @type {Array<[string, string]>} */
    const bulkVariants = [
      [CLONE_MAP_BULK_TOKEN, CLONE_MAP_BULK_REPLACEMENT],
      [CLONE_MAP_BULK_TOKEN_V2, CLONE_MAP_BULK_REPLACEMENT_V2],
    ];
    const bulkOccurrences = bulkVariants.reduce((n, [token]) => n + countOccurrences(patched, token), 0);
    const bulkVariant = bulkVariants.find(([token]) => countOccurrences(patched, token) === 1);
    const appendOccurrences = countOccurrences(patched, CLONE_MAP_APPEND_TOKEN);
    const compareOccurrences = countOccurrences(patched, CLONE_MAP_COMPARE_TOKEN);
    if (bulkOccurrences === 1 && bulkVariant && appendOccurrences === 1 && compareOccurrences === 1) {
      const [bulkToken, bulkReplacement] = bulkVariant;
      patched = patched.replace(bulkToken, bulkReplacement);
      patched = patched.replace(CLONE_MAP_APPEND_TOKEN, CLONE_MAP_APPEND_REPLACEMENT);
      patched = patched.replace(CLONE_MAP_COMPARE_TOKEN, CLONE_MAP_COMPARE_REPLACEMENT);
      appliedPatches += 1;
    } else {
      skippedPatches += 1;
      if (warn) process.stderr.write(
        `[letta-code-patch] WARN: clone-map anchors matched bulk=${bulkOccurrences} append=${appendOccurrences} compare=${compareOccurrences} ` +
        `(expected exactly 1 each) in ${path} — skipping all of lcp-aioi8-p2 atomically ` +
        `(persisted-message dedupe map keeps full structuredClone graphs)\n`,
      );
    }
  }

  // lcp-oaiap: stop the OpenAI API from aborting auto-approved tool turns.
  {
    const occurrences = countOccurrences(patched, OPENAI_APPROVAL_BAIL_TOKEN);
    if (occurrences === 1) {
      patched = patched.replace(OPENAI_APPROVAL_BAIL_TOKEN, OPENAI_APPROVAL_BAIL_REPLACEMENT);
      patched = injectHelperAfterShebang(patched, OPENAI_APPROVAL_BAIL_HELPER);
      appliedPatches += 1;
    } else {
      skippedPatches += 1;
      if (warn) process.stderr.write(
        `[letta-code-patch] WARN: WAITING_ON_APPROVAL anchor matched ${occurrences} time(s), expected exactly 1, in ${path} — ` +
        `running without lcp-oaiap (the OpenAI API will keep replacing auto-approved tool answers ` +
        `with the canned "requires interactive approval" note)\n`,
      );
    }
  }

  // lcp-otid: carry the streamed segment otid onto the durable assistant record,
  // so clients can pair the streamed row with the settled one by identity rather
  // than by matching their text. Both anchors must be unique and both must land,
  // or neither does: stamping without persisting is dead weight, and persisting
  // without stamping stores an otid that no stream ever emitted.
  {
    const stamp = OTID_STAMP_VARIANTS.find(
      (variant) => countOccurrences(patched, variant.token) === 1,
    );
    const stampOccurrences = stamp ? 1 : 0;
    const persistOccurrences = countOccurrences(patched, OTID_PERSIST_TOKEN);
    const projectOccurrences = countOccurrences(patched, OTID_PROJECT_TOKEN);
    if (stamp && persistOccurrences === 1 && projectOccurrences === 1) {
      patched = patched.replace(stamp.token, stamp.replacement);
      patched = patched.replace(OTID_PERSIST_TOKEN, OTID_PERSIST_REPLACEMENT);
      patched = patched.replace(OTID_PROJECT_TOKEN, OTID_PROJECT_REPLACEMENT);
      patched = injectHelperAfterShebang(patched, OTID_HELPER);
      patched = injectHelperAfterShebang(patched, OTID_PROJECT_HELPER);
      appliedPatches += 1;
    } else {
      skippedPatches += 1;
      if (warn) process.stderr.write(
        `[letta-code-patch] WARN: assistant-otid anchors matched stamp=${stampOccurrences} persist=${persistOccurrences} project=${projectOccurrences} ` +
        `(expected exactly 1 each) in ${path} — skipping lcp-otid atomically ` +
        `(stored assistant messages keep no stream identity, so clients fall back to content matching)\n`,
      );
    }
  }

  // lcp-clr: conversation-scoped lastRunAt for the agent-info reminder.
  // Unique-match assertion (skip on miss or duplicate rather than patching
  // the wrong site): the listen path is the only place that builds
  // `listenAgentMetadata` from `cachedAgent.last_run_completion`, but a
  // future version could clone that block for the warmup path — in which
  // case the patch must skip and leave that copy alone.
  {
    const occurrences = countOccurrences(patched, CONVERSATION_LAST_RUN_TOKEN);
    if (occurrences === 1) {
      patched = patched.replace(CONVERSATION_LAST_RUN_TOKEN, CONVERSATION_LAST_RUN_REPLACEMENT);
      patched = injectHelperAfterShebang(patched, CONVERSATION_LAST_RUN_HELPER_DEFINITION);
      appliedPatches += 1;
    } else {
      skippedPatches += 1;
      if (warn) process.stderr.write(
        `[letta-code-patch] WARN: conversation-last-run token matched ${occurrences} time(s), expected exactly 1, in ${path} — ` +
        `running without lcp-clr (the agent-info reminder will continue to report the agent's ` +
        `default-conversation timestamp in non-default conversations)\n`,
      );
    }
  }

  // lcp-clr-x: rewrite buildAgentInfo to dispatch on the structured object
  // returned by the lcp-clr helper. Fail-open on count != 1 — never patch
  // the wrong site or break the reminder builder. The two patches are
  // independent: if lcp-clr-x lands but lcp-clr didn't (anchor drifted),
  // the renderer still works against the legacy string-shaped lastRunAt
  // because the normalization branch treats strings as kind:"this".
  {
    const occurrences = countOccurrences(patched, BUILD_AGENT_INFO_TOKEN);
    if (occurrences === 1) {
      patched = patched.replace(BUILD_AGENT_INFO_TOKEN, BUILD_AGENT_INFO_REPLACEMENT);
      patched = injectHelperAfterShebang(patched, BUILD_AGENT_INFO_HELPER_DEFINITION);
      appliedPatches += 1;
    } else {
      skippedPatches += 1;
      if (warn) process.stderr.write(
        `[letta-code-patch] WARN: buildAgentInfo token matched ${occurrences} time(s), expected exactly 1, in ${path} — ` +
        `running without lcp-clr-x (cross-conversation fallback won't render; "No previous messages" ` +
        `and the agent-level timestamp keep showing as before)\n`,
      );
    }
  }

  // lcp-reflection-controls: Keep command wire shape, per-agent persistence,
  // and launch behavior coherent. A partial patch would advertise settings that
  // are dropped by persistence or a model that never reaches the subagent, so
  // every exact anchor must match once before any rewrite is made.
  {
    const counts = REFLECTION_CONTROLS_PATCHES.map(({ token }) => countOccurrences(patched, token));
    if (counts.every((count) => count === 1)) {
      for (const { token, replacement } of REFLECTION_CONTROLS_PATCHES) {
        patched = patched.replace(token, replacement);
      }
      patched = injectHelperAfterShebang(patched, REFLECTION_CONTROLS_HELPER);
      appliedPatches += 1;
    } else {
      skippedPatches += 1;
      if (warn) process.stderr.write(
        `[letta-code-patch] WARN: lcp-reflection-controls anchors matched ${counts.join(",")} (expected 1 each) in ${path} — ` +
        `running without per-agent reflection model/focus/outcomes\n`,
      );
    }
  }

  // lcp-model-catalog: unique anchors, atomic update, fail open on drift.
  {
    const counts = MODEL_CATALOG_PATCHES.map(({ token }) => countOccurrences(patched, token));
    if (counts.every((count) => count === 1)) {
      for (const { token, addition } of MODEL_CATALOG_PATCHES) {
        patched = patched.replace(token, token + addition);
      }
      appliedPatches += 1;
    } else {
      skippedPatches += 1;
      if (warn) process.stderr.write(
        `[letta-code-patch] WARN: lcp-model-catalog anchors matched ${counts.join(",")} (expected 1 each) in ${path} — ` +
        `running with the bundled native model catalog\n`,
      );
    }
  }

  // lcp-ctxcap: lmStudioDiscover clamps every discovered model's context window
  // to LOCAL_ENDPOINT_DEFAULT_CONTEXT_WINDOW (128k) even when the endpoint
  // (our LiteLLM adapter) advertises more, so 1M-context models show as 128k in
  // clients. Keep the default as the fallback for unknown models but trust an
  // advertised value. Unique-match assertion; fail-open to the stock clamp.
  {
    const token = "Math.min(metadata.contextLength ?? LOCAL_ENDPOINT_DEFAULT_CONTEXT_WINDOW, LOCAL_ENDPOINT_DEFAULT_CONTEXT_WINDOW)";
    const occurrences = countOccurrences(patched, token);
    if (occurrences === 1) {
      patched = patched.replace(token, "(metadata.contextLength ?? LOCAL_ENDPOINT_DEFAULT_CONTEXT_WINDOW)");
      appliedPatches += 1;
    } else {
      skippedPatches += 1;
      if (warn) process.stderr.write(
        `[letta-code-patch] WARN: local-endpoint context clamp matched ${occurrences} time(s), expected exactly 1, in ${path} — ` +
        `running without lcp-ctxcap (local-endpoint models stay capped at 128k)\n`,
      );
    }
  }

  // lcp-reflhide: reflection integration runs as the PARENT agent (existingAgentId) in a fresh
  // conversation that it only titles, so every integration landed in the parent's conversation
  // list (126 on PM-letta-mobile by 2026-10-22; letta-code archives one only when the integration
  // succeeds). Mark it hidden wherever it is titled: listLocalConversations drops hidden rows and
  // the conversation stays retrievable by id. Expect both title sites (on start, and the archive
  // on success); fail-open to stock when the count differs.
  {
    const token = "summary: buildReflectionIntegrationConversationTitle(";
    const occurrences = countOccurrences(patched, token);
    if (occurrences === 2) {
      patched = patched.split(token).join("hidden: true, " + token);
      appliedPatches += 1;
    } else {
      skippedPatches += 1;
      if (warn) process.stderr.write(
        `[letta-code-patch] WARN: reflection integration title matched ${occurrences} time(s), expected exactly 2, in ${path} — ` +
        `running without lcp-reflhide (reflection integration conversations stay listed)\n`,
      );
    }
  }

  // lcp-subagent-errlog: a subagent runs as a child `letta -p` process and letta-code reports its
  // failures only through debugWarn, which writes nothing unless debug logging is on, so a failed
  // reflection integration left no trace anywhere (letta-mobile-vmwl1). Also write subagent
  // warnings, and each failed exit with the child's stderr tail, to stderr, which the App Server
  // log captures. Two unique-match sites, applied together; fail-open to stock otherwise.
  {
    const warnToken = "function debugWarn(prefix, message, ...args) {\n";
    const warnReplacement = warnToken +
      "  if (prefix === \"subagent\") process.stderr.write(`[lcp-subagent] WARN: ${message}\\n`);\n";
    const exitToken = "function describeSubagentExit(exitCode, exitSignal, stderr) {\n";
    const exitReplacement = exitToken +
      "  process.stderr.write(`[lcp-subagent] exit code=${exitCode} signal=${exitSignal} stderr tail: ` +\n" +
      "    `${String(stderr ?? \"\").trim().slice(-2000).replace(/\\s*\\n\\s*/g, \" ⏎ \")}\\n`);\n";
    const warnCount = countOccurrences(patched, warnToken);
    const exitCount = countOccurrences(patched, exitToken);
    if (warnCount === 1 && exitCount === 1) {
      patched = patched.replace(warnToken, warnReplacement).replace(exitToken, exitReplacement);
      appliedPatches += 1;
    } else {
      skippedPatches += 1;
      if (warn) process.stderr.write(
        `[letta-code-patch] WARN: subagent error sites matched debugWarn=${warnCount} describeSubagentExit=${exitCount}, ` +
        `expected 1 each, in ${path} — running without lcp-subagent-errlog\n`,
      );
    }
  }

  return { source: patched, appliedPatches, skippedPatches };
}

/**
 * @param {string} raw
 * @returns {string}
 */
export function patchLettaCodeSourceForTest(raw) {
  return patchLettaCodeSource(raw, "<test>", false).source;
}

/**
 * Full result (applied/skipped counts) for tests that assert the fail-open
 * and unique-match-assertion behavior.
 *
 * @param {string} raw
 * @returns {{ source: string; appliedPatches: number; skippedPatches: number }}
 */
export function patchLettaCodeSourceResultForTest(raw) {
  return patchLettaCodeSource(raw, "<test>", false);
}

// Exported for tests that eval the helper definition against a stub backend
// to exercise the kind-dispatch semantics of `__lcpConversationLastRunAt`.
// The body is unchanged — only the export is new.
export { CONVERSATION_LAST_RUN_HELPER_DEFINITION };

// Insert injected source after a leading `#!` shebang (which must remain line 1
// for Node to strip it). ESM hoists `import` declarations, so a statement placed
// before them is legal — the only hard constraint is the shebang position.
/**
 * @param {string} source
 * @param {string} helper
 * @returns {string}
 */
function injectHelperAfterShebang(source, helper) {
  if (source.startsWith("#!")) {
    const nl = source.indexOf("\n");
    if (nl >= 0) {
      return source.slice(0, nl + 1) + helper + source.slice(nl + 1);
    }
  }
  return helper + source;
}

/** @type {import("node:module").LoadHook} */
export async function load(url, context, nextLoad) {
  const result = await nextLoad(url, context);
  if (!TARGET_PATH || !url.startsWith("file://")) return result;

  let path;
  try { path = fileURLToPath(url); } catch { return result; }
  if (path !== TARGET_PATH) return result;

  const raw =
    typeof result.source === "string"
      ? result.source
      : result.source instanceof Uint8Array
        ? Buffer.from(result.source).toString("utf8")
        : null;
  if (raw === null) return result;

  const { source: patched, appliedPatches, skippedPatches } = patchLettaCodeSource(raw, path, !appliedOnce);

  if (!appliedOnce) {
    // Boot-summary drift alert: after any bundle update the runbook check is
    // that `applied=` matches the expected patch count — a silent fail-open
    // shows up here instead of only as a buried per-patch stderr warn.
    process.stderr.write(
      `[letta-code-patch] lcp-patches applied=${appliedPatches} skipped=${skippedPatches}\n`,
    );
  }

  if (appliedPatches === 0) return result;

  if (!appliedOnce) {
    process.stderr.write(
      `[letta-code-patch] applied ${appliedPatches} runtime patch(es) to ${path}\n`,
    );
    appliedOnce = true;
  }
  return { ...result, source: patched, shortCircuit: true };
}
