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
import { resolve as resolvePath } from "node:path";

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

const REFLECTION_CONTROLS_PATCHES_0_34_39 = [
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

// The bundler renames these locals between releases (0.34.39: settings3, merge4, dependencies4,
// error5; 0.34.70: settings, merge3, dependencies, error4); the code around them is unchanged.
const REFLECTION_CONTROLS_BINDINGS = [
  {},
  { settings3: "settings", merge4: "merge3", dependencies4: "dependencies", error5: "error4" },
];
function renameBindings(text, renames) {
  return Object.entries(renames).reduce((out, [from, to]) => out.replace(new RegExp(`\\b${from}\\b`, "g"), to), text);
}
const REFLECTION_CONTROLS_VARIANTS = REFLECTION_CONTROLS_BINDINGS.map((renames) =>
  REFLECTION_CONTROLS_PATCHES_0_34_39.map(({ token, replacement }) => ({
    token: renameBindings(token, renames),
    replacement: renameBindings(replacement, renames),
  })));

// lcp-model-catalog: native provider catalogs are bundled, not /v1/models
// discovery. Match the two flattening sites atomically so the catalog used by
// both getBuiltinModels and provider execution receives the same metadata.
// These are the verified subscription proxy limits (f5f01c2), not public API
// limits. gpt-6.1-luna is intentionally omitted: upstream returned model_not_found.
const MODEL_CATALOG_PATCHES = [
  {
    token: '  ANTHROPIC_MODELS = flattenChatModelCatalog("anthropic", anthropic_default);',
    addition: `
  // lcp-model-catalog: our Anthropic route (anthropic-claude-max-proxy models/specifications.py)
  // serves claude-haiku-5-5 with a 200k context and 65,536 completion tokens. Keep an upstream
  // entry's other metadata (0.34.70 ships one at 1M/128k) but clamp it to the route's limits.
  if (ANTHROPIC_MODELS["claude-haiku-5-5"] || ANTHROPIC_MODELS["claude-haiku-4-5"]) {
    ANTHROPIC_MODELS["claude-haiku-5-5"] = {
      ...(ANTHROPIC_MODELS["claude-haiku-5-5"] ?? ANTHROPIC_MODELS["claude-haiku-4-5"]),
      id: "claude-haiku-5-5", name: "Claude Haiku 5.5",
      contextWindow: 200000, maxTokens: 65536
    };
  }`,
  },
  {
    token: '  OPENAI_MODELS = flattenChatModelCatalog("openai", openai_default);',
    addition: `
  // lcp-model-catalog: use the ChatGPT upstream maximum, not its default. Terra is listed too:
  // 0.34.70 ships its own gpt-6.1-terra entry at the 272k default.
  for (const id of ["gpt-6-astra", "gpt-6-sol", "gpt-6-luna", "gpt-6.1-sol", "gpt-6.1-terra"]) {
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
  }
  // Visible, API-supported subscription identities from codex/models 0.167.0.
  // Metadata templates do not rewrite the requested upstream model identity.
  for (const [id, name, contextWindow] of [
    ["gpt-6.2-sol", "GPT-6.2 Sol", 872000],
    ["gpt-6.2-astra", "GPT-6.2 Astra", 872000]
  ]) {
    if (!OPENAI_MODELS[id] && OPENAI_MODELS["gpt-6.1-sol"]) {
      OPENAI_MODELS[id] = {
        ...OPENAI_MODELS["gpt-6.1-sol"], id, name, contextWindow, maxTokens: 128000,
        thinkingLevelMap: { off: null, minimal: null, low: "low", medium: "medium",
          high: "high", xhigh: "xhigh", max: "max" }
      };
    }
  }`,
  },
  {
    token: '  GOOGLE_MODELS = flattenChatModelCatalog("google", google_default);',
    addition: `
  // lcp-model-catalog: Antigravity Gemini 4 identities served by the lc-gemini
  // proxy chain (shim -> gateway -> CLIProxy). Limits are the proxy catalog's.
  if (GOOGLE_MODELS["gemini-3.8-flash"]) {
    for (const [id, name, maxTokens] of [
      ["gemini-4-neon-medium", "Gemini 4 Neon (Medium)", 65536],
      ["gemini-4-neon-high", "Gemini 4 Neon (High)", 1048576],
      ["gemini-4-argon-medium", "Gemini 4 Argon (Medium)", 65536],
      ["gemini-4-argon-high", "Gemini 4 Argon (High)", 1048576],
      ["gemini-4-hydrogen-medium", "Gemini 4 Hydrogen (Medium)", 262144],
      ["gemini-4-hydrogen-xhigh", "Gemini 4 Hydrogen (XHigh)", 1048576]
    ]) {
      if (!GOOGLE_MODELS[id]) {
        GOOGLE_MODELS[id] = { ...GOOGLE_MODELS["gemini-3.8-flash"], id, name, contextWindow: 1048576, maxTokens };
      }
    }
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

// lcp-conversation-list-mtime (letta-mobile-w4sqr): listConversations calls
// refreshLoadedConversationRecordsFromStorage on EVERY list, and
// refreshConversationRecordFromStorage readFileSyncs every loaded conversation.json only to
// compare the text with conversationRecordJsonByKey: ~2,400 synchronous reads (~37 ms) per
// list on the App Server's single event loop. Agent records already skip on an mtime stamp;
// conversations had none. Now a stat taken BEFORE each read is stamped with the exact text
// that read returned, and the refresh skips the read only when the file's (mtimeMs, ctimeMs,
// size, ino) still equal the stamp AND the cached text is still the stamped text. So:
//  - any write by this process (persistConversationState, cacheConversationRecord, or a
//    future write site) replaces the cached text and invalidates the stamp by itself, and any
//    write by another process moves mtime/ctime; either way the stock read runs;
//  - a missing stat, a missing stamp, a parse/key mismatch or a read error all take the stock
//    read path unchanged, including evicting resident messages when the JSON really changed;
//  - a file modified within RACY_WINDOW_MS of the stat is never stamped (git's "racily clean"
//    rule), so a same-size rewrite inside one timestamp tick cannot hide behind the stamp.
// One anchor spans the scan read, the refresh and the scan's statSync5 call, so all three
// bindings the replacement uses (join22, readFileSync9, statSync5) are proven in scope, and
// drift skips the whole patch.
const CONVERSATION_LIST_MTIME_TOKEN =
  `  loadConversationRecordFromDir(conversationDir) {\n` +
  `    try {\n` +
  `      const recordJson = readFileSync9(join22(conversationDir, "conversation.json"), "utf8");\n` +
  `      const conversation = parsePersistedLocalConversation(recordJson, (id2) => this.executionAgentIdForConversation(id2));\n` +
  `      if (!conversation)\n` +
  `        return;\n` +
  `      return this.cacheConversationRecord(conversationDir, conversation, {\n` +
  `        recordJson\n` +
  `      });\n` +
  `    } catch {\n` +
  `      return;\n` +
  `    }\n` +
  `  }\n` +
  `  refreshConversationRecordFromStorage(key) {\n` +
  `    const existing = this.conversations.get(key);\n` +
  `    const metadata = this.transcriptMetadataByConversationKey.get(key);\n` +
  `    const conversationDir = metadata?.conversationDir ?? this.conversationDirForKey(key);\n` +
  `    if (!conversationDir)\n` +
  `      return existing;\n` +
  `    try {\n` +
  `      const recordJson = readFileSync9(join22(conversationDir, "conversation.json"), "utf8");\n` +
  `      const cachedJson = this.conversationRecordJsonByKey.get(key);\n` +
  `      if (existing && cachedJson === recordJson)\n` +
  `        return existing;\n` +
  `      const conversation = parsePersistedLocalConversation(recordJson, (id2) => this.executionAgentIdForConversation(id2));\n` +
  `      if (!conversation)\n` +
  `        return existing;\n` +
  `      const loadedKey = this.conversationKey(conversation.id, conversation.agent_id);\n` +
  `      if (loadedKey !== key)\n` +
  `        return existing;\n` +
  `      if (existing && cachedJson !== undefined) {\n` +
  `        this.evictResidentHeadMessages(key, this.localMessagesByConversationKey.get(key) ?? []);\n` +
  `        this.localMessagesByConversationKey.delete(key);\n` +
  `        this.loadedConversationKeys.delete(key);\n` +
  `        this.tailResidentConversationKeys.delete(key);\n` +
  `        this.residentTailStartIndexByConversationKey.delete(key);\n` +
  `        this.unreadTranscriptHeadBytesByKey.delete(key);\n` +
  `      }\n` +
  `      return this.cacheConversationRecord(conversationDir, conversation, {\n` +
  `        forceRefresh: true,\n` +
  `        recordJson\n` +
  `      });\n` +
  `    } catch {\n` +
  `      return existing;\n` +
  `    }\n` +
  `  }\n` +
  `  loadConversationRecordsFromStorage() {\n` +
  `    if (this.conversationRecordsScanned)\n` +
  `      return;\n` +
  `    this.conversationRecordsScanned = true;\n` +
  `    const conversationsDir = this.conversationsDir();\n` +
  `    if (!conversationsDir || !existsSync15(conversationsDir))\n` +
  `      return;\n` +
  `    let entries;\n` +
  `    try {\n` +
  `      entries = readdirSync3(conversationsDir);\n` +
  `    } catch {\n` +
  `      return;\n` +
  `    }\n` +
  `    for (const entry of entries) {\n` +
  `      const conversationDir = join22(conversationsDir, entry);\n` +
  `      try {\n` +
  `        if (!statSync5(conversationDir).isDirectory())`;
const CONVERSATION_LIST_MTIME_STAMP_METHODS =
  `  lcpConversationRecordStat(recordPath) {\n` +
  `    const statStartedAtMs = Date.now();\n` +
  `    try {\n` +
  `      const stats = statSync5(recordPath);\n` +
  `      return { mtimeMs: stats.mtimeMs, ctimeMs: stats.ctimeMs, size: stats.size, ino: stats.ino, statStartedAtMs };\n` +
  `    } catch {\n` +
  `      return;\n` +
  `    }\n` +
  `  }\n` +
  `  lcpRememberConversationRecordStamp(key, recordPath, stat, recordJson) {\n` +
  `    const RACY_WINDOW_MS = 2000;\n` +
  `    const stamps = this.lcpConversationRecordStampByKey ??= new Map;\n` +
  `    if (!stat || Math.max(stat.mtimeMs, stat.ctimeMs) > stat.statStartedAtMs - RACY_WINDOW_MS) {\n` +
  `      stamps.delete(key);\n` +
  `      return;\n` +
  `    }\n` +
  `    stamps.set(key, { recordPath, mtimeMs: stat.mtimeMs, ctimeMs: stat.ctimeMs, size: stat.size, ino: stat.ino, recordJson });\n` +
  `  }\n` +
  `  lcpConversationRecordStampMatches(key, recordPath, stat, cachedJson) {\n` +
  `    const stamp = this.lcpConversationRecordStampByKey?.get(key);\n` +
  `    if (!stamp || !stat || cachedJson === undefined)\n` +
  `      return false;\n` +
  `    return stamp.recordJson === cachedJson && stamp.recordPath === recordPath && stamp.mtimeMs === stat.mtimeMs && stamp.ctimeMs === stat.ctimeMs && stamp.size === stat.size && stamp.ino === stat.ino;\n` +
  `  }\n`;
const CONVERSATION_LIST_MTIME_REPLACEMENT =
  CONVERSATION_LIST_MTIME_STAMP_METHODS +
  `  loadConversationRecordFromDir(conversationDir) {\n` +
  `    try {\n` +
  `      const recordPath = join22(conversationDir, "conversation.json");\n` +
  `      const recordStat = this.lcpConversationRecordStat(recordPath);\n` +
  `      const recordJson = readFileSync9(recordPath, "utf8");\n` +
  `      const conversation = parsePersistedLocalConversation(recordJson, (id2) => this.executionAgentIdForConversation(id2));\n` +
  `      if (!conversation)\n` +
  `        return;\n` +
  `      const cached = this.cacheConversationRecord(conversationDir, conversation, {\n` +
  `        recordJson\n` +
  `      });\n` +
  `      this.lcpRememberConversationRecordStamp(this.conversationKey(conversation.id, conversation.agent_id), recordPath, recordStat, recordJson);\n` +
  `      return cached;\n` +
  `    } catch {\n` +
  `      return;\n` +
  `    }\n` +
  `  }\n` +
  `  refreshConversationRecordFromStorage(key) {\n` +
  `    const existing = this.conversations.get(key);\n` +
  `    const metadata = this.transcriptMetadataByConversationKey.get(key);\n` +
  `    const conversationDir = metadata?.conversationDir ?? this.conversationDirForKey(key);\n` +
  `    if (!conversationDir)\n` +
  `      return existing;\n` +
  `    try {\n` +
  `      const recordPath = join22(conversationDir, "conversation.json");\n` +
  `      const cachedJson = this.conversationRecordJsonByKey.get(key);\n` +
  `      const recordStat = this.lcpConversationRecordStat(recordPath);\n` +
  `      if (existing && this.lcpConversationRecordStampMatches(key, recordPath, recordStat, cachedJson))\n` +
  `        return existing;\n` +
  `      const recordJson = readFileSync9(recordPath, "utf8");\n` +
  `      this.lcpRememberConversationRecordStamp(key, recordPath, recordStat, recordJson);\n` +
  `      if (existing && cachedJson === recordJson)\n` +
  `        return existing;\n` +
  CONVERSATION_LIST_MTIME_TOKEN.slice(
    CONVERSATION_LIST_MTIME_TOKEN.indexOf(`      const conversation = parsePersistedLocalConversation(recordJson, (id2) => this.executionAgentIdForConversation(id2));\n      if (!conversation)\n        return existing;`),
  );

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
// The model binding is `model` in 0.34.39 and `model2` in 0.34.70 (a top-level `model` now exists).
const VISION_GATE_VARIANTS = ["model", "model2"].map((model) => ({
  token: `toChatMessages(messages, ${model}.input.includes("image"))`,
  replacement: `toChatMessages(messages, globalThis.__lcpFixLocalVisionInput(undefined, ${model}.id, ${model}.input).includes("image"))`,
}));

// lcp-qi2f: the ACTUAL inbound-user-image gate. downgradeUnsupportedImages(
// messages, model) replaces user image blocks with NON_VISION_USER_IMAGE_
// PLACEHOLDER ("(image omitted: model does not support images)") whenever
// `model.input.includes("image")` is false — a SEPARATE path from the
// toChatMessages gate above, and the one that strips images Emmanuel attaches.
// Rewrite its early-return guard to honor the vision-model id check via the
// same helper. Anchored on the unique function signature so we only touch
// this site.
const DOWNGRADE_IMAGES_VARIANTS = ["model", "model2"].map((model) => ({
  token: `function downgradeUnsupportedImages(messages, ${model}) {\n  if (${model}.input.includes("image")) {`,
  replacement: `function downgradeUnsupportedImages(messages, ${model}) {\n  if (globalThis.__lcpFixLocalVisionInput(undefined, ${model}.id, ${model}.input).includes("image")) {`,
}));

/** The one variant whose token occurs exactly once while no other variant occurs at all, else null. */
function uniqueVariant(source, variants) {
  const counts = variants.map(({ token }) => countOccurrences(source, token));
  return counts.reduce((sum, count) => sum + count, 0) === 1 ? variants[counts.indexOf(1)] : null;
}

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

function retryOtid(state) {
  let { patched, appliedPatches, skippedPatches } = state;
  const { path, warn } = state;

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

  Object.assign(state, { patched, appliedPatches, skippedPatches });
}

function agentLazyload(state) {
  let { patched, appliedPatches, skippedPatches } = state;
  const { path, warn } = state;

  // All three land together: lazy loading alone fixes nothing while the read paths
  // still test the map directly, and the read paths alone would never find the agent.
  {
    const refreshCount = countOccurrences(patched, AGENT_LAZYLOAD_REFRESH_TOKEN);
    const listConversationCount = countOccurrences(patched, AGENT_LAZYLOAD_LIST_CONVERSATION_TOKEN);
    const listAgentCount = countOccurrences(patched, AGENT_LAZYLOAD_LIST_AGENT_TOKEN);
    if ([refreshCount, listConversationCount, listAgentCount].every((count) => count === 1)) {
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

  Object.assign(state, { patched, appliedPatches, skippedPatches });
}

// 0.34.39 binds the parse callback's argument as id2, 0.34.70 as id; the anchor is otherwise identical.
const CONVERSATION_LIST_MTIME_CALLBACK = "(id2) => this.executionAgentIdForConversation(id2)";
const CONVERSATION_LIST_MTIME_VARIANTS = ["id2", "id"].map((binding) => {
  const callback = `(${binding}) => this.executionAgentIdForConversation(${binding})`;
  return {
    token: CONVERSATION_LIST_MTIME_TOKEN.replaceAll(CONVERSATION_LIST_MTIME_CALLBACK, callback),
    replacement: CONVERSATION_LIST_MTIME_REPLACEMENT.replaceAll(CONVERSATION_LIST_MTIME_CALLBACK, callback),
  };
});

function conversationListMtime(state) {
  let { patched, appliedPatches, skippedPatches } = state;
  const { path, warn } = state;

  {
    const counts = CONVERSATION_LIST_MTIME_VARIANTS.map(({ token }) => countOccurrences(patched, token));
    const occurrences = counts.reduce((sum, count) => sum + count, 0);
    const variant = CONVERSATION_LIST_MTIME_VARIANTS[counts.indexOf(1)];
    if (occurrences === 1 && variant) {
      patched = patched.replace(variant.token, () => variant.replacement);
      appliedPatches += 1;
    } else {
      skippedPatches += 1;
      if (warn) process.stderr.write(
        `[letta-code-patch] WARN: lcp-conversation-list-mtime anchor matched ${occurrences} time(s), expected exactly 1, ` +
        `in ${path} — running without it; every conversation list re-reads every conversation.json\n`,
      );
    }
  }

  Object.assign(state, { patched, appliedPatches, skippedPatches });
}

function simplePatch({ token, replacement, replaceAll, helper, warning }) {
  return (state) => {
    if (!state.patched.includes(token)) {
      state.skippedPatches += 1;
      if (state.warn) process.stderr.write(warning(state.path));
      return;
    }
    state.patched = replaceAll
      ? state.patched.replaceAll(token, replacement)
      : state.patched.replace(token, replacement);
    state.appliedPatches += 1;
    if (helper) state.patched = injectHelperAfterShebang(state.patched, helper);
  };
}

const settle = simplePatch({
  token: SETTLE_BUG_LITERAL, replacement: SETTLE_FIX_LITERAL,
  replaceAll: false, helper: null,
  warning: (path) => `[letta-code-patch] WARN: settle-bug literal not found in ${path} — ` +
      `running unpatched (letta-code likely upgraded past 0.26.1)\n`,
});
const thinkingSettings = simplePatch({
  token: THINKING_SETTINGS_BUG_LITERAL, replacement: THINKING_SETTINGS_FIX_LITERAL,
  replaceAll: true, helper: null,
  warning: (path) => `[letta-code-patch] WARN: thinking-settings literal not found in ${path} — ` +
      `running without lcp-9pn settings guard\n`,
});
function thinkingRequest(state) {
  let { patched, appliedPatches, skippedPatches } = state;
  const { path, warn } = state;

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

  Object.assign(state, { patched, appliedPatches, skippedPatches });
}

function thinkingChokepoint(state) {
  let { patched, appliedPatches, skippedPatches } = state;
  const { path, warn } = state;

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

  Object.assign(state, { patched, appliedPatches, skippedPatches });
}

function modelSettings(state) {
  let { patched, appliedPatches, skippedPatches } = state;
  const { path, warn } = state;

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

  Object.assign(state, { patched, appliedPatches, skippedPatches });
}

const localVisionInput = simplePatch({
  token: LOCAL_VISION_INPUT_TOKEN, replacement: LOCAL_VISION_INPUT_REPLACEMENT,
  replaceAll: false, helper: LOCAL_VISION_INPUT_HELPER_DEFINITION,
  warning: (path) => `[letta-code-patch] WARN: local vision input token not found in ${path} — ` +
      `Read(image) may still be placeholdered by model.input gates\n`,
});
function visionGate(state) {
  let { patched, appliedPatches, skippedPatches } = state;
  const { path, warn } = state;

  // lcp-qi2f: inbound user-image gate at the request-build site.
  const variant = uniqueVariant(patched, VISION_GATE_VARIANTS);
  if (variant) {
    patched = patched.replace(variant.token, () => variant.replacement);
    appliedPatches += 1;
    // Ensure the helper is present even if the input-construction token above
    // was absent (idempotent: the helper uses `|| function` guard).
    patched = injectHelperAfterShebang(patched, LOCAL_VISION_INPUT_HELPER_DEFINITION);
  } else {
    skippedPatches += 1;
    if (warn) process.stderr.write(
      `[letta-code-patch] WARN: vision gate token (toChatMessages supportsImages) not found exactly once in ${path} — ` +
      `inbound user images may still be omitted\n`,
    );
  }

  Object.assign(state, { patched, appliedPatches, skippedPatches });
}

function downgradeImages(state) {
  let { patched, appliedPatches, skippedPatches } = state;
  const { path, warn } = state;

  // lcp-qi2f: downgradeUnsupportedImages — the real inbound user-image strip.
  const variant = uniqueVariant(patched, DOWNGRADE_IMAGES_VARIANTS);
  if (variant) {
    patched = patched.replace(variant.token, () => variant.replacement);
    appliedPatches += 1;
    patched = injectHelperAfterShebang(patched, LOCAL_VISION_INPUT_HELPER_DEFINITION);
  } else {
    skippedPatches += 1;
    if (warn) process.stderr.write(
      `[letta-code-patch] WARN: downgradeUnsupportedImages token not found exactly once in ${path} — ` +
      `inbound user images may still be omitted\n`,
    );
  }

  Object.assign(state, { patched, appliedPatches, skippedPatches });
}

function multimodalToolReturn(state) {
  let { patched, appliedPatches, skippedPatches } = state;
  const { path, warn } = state;

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

  Object.assign(state, { patched, appliedPatches, skippedPatches });
}

const toolReturnContent = simplePatch({
  token: TOOL_RETURN_CONTENT_TOKEN, replacement: TOOL_RETURN_CONTENT_REPLACEMENT,
  replaceAll: false, helper: TOOL_RETURN_CONTENT_HELPER_DEFINITION,
  warning: (path) => `[letta-code-patch] WARN: tool-return content coercion token not found in ${path} — ` +
      `Read(image) approval turns may still be normalized to text only\n`,
});
function patchToolsetCatalog(patched) {
  let appliedCount = 0;
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
      appliedCount += 1;
    }
  }
  return { patched, appliedCount };
}

function generateImageTool(state) {
  let { patched, appliedPatches, skippedPatches } = state;
  const { path, warn } = state;

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
  const catalog = patchToolsetCatalog(patched);
  patched = catalog.patched;
  appliedPatches += catalog.appliedCount;
  if (catalog.appliedCount) appliedGenerateImageTool = true;
  if (appliedGenerateImageTool) {
    patched = injectHelperAfterShebang(patched, GENERATE_IMAGE_TOOL_HELPER_DEFINITION);
  } else {
    skippedPatches += 1;
    if (warn) process.stderr.write(
      `[letta-code-patch] WARN: generate_image tool registry tokens not found in ${path} — ` +
      `native image generation tool will not be registered\n`,
    );
  }

  Object.assign(state, { patched, appliedPatches, skippedPatches });
}

function systemPrompt(state) {
  let { patched, appliedPatches, skippedPatches } = state;
  const { path, warn } = state;

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

  Object.assign(state, { patched, appliedPatches, skippedPatches });
}

function cloneMap(state) {
  let { patched, appliedPatches, skippedPatches } = state;
  const { path, warn } = state;

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
    if (bulkVariant && [bulkOccurrences, appendOccurrences, compareOccurrences].every((count) => count === 1)) {
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

  Object.assign(state, { patched, appliedPatches, skippedPatches });
}

function openaiApproval(state) {
  let { patched, appliedPatches, skippedPatches } = state;
  const { path, warn } = state;

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

  Object.assign(state, { patched, appliedPatches, skippedPatches });
}

function assistantOtid(state) {
  let { patched, appliedPatches, skippedPatches } = state;
  const { path, warn } = state;

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
    if (stamp && [persistOccurrences, projectOccurrences].every((count) => count === 1)) {
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

  Object.assign(state, { patched, appliedPatches, skippedPatches });
}

function conversationLastRun(state) {
  let { patched, appliedPatches, skippedPatches } = state;
  const { path, warn } = state;

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

  Object.assign(state, { patched, appliedPatches, skippedPatches });
}

function buildAgentInfo(state) {
  let { patched, appliedPatches, skippedPatches } = state;
  const { path, warn } = state;

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

  Object.assign(state, { patched, appliedPatches, skippedPatches });
}

function reflectionControls(state) {
  let { patched, appliedPatches, skippedPatches } = state;
  const { path, warn } = state;

  // lcp-reflection-controls: Keep command wire shape, per-agent persistence,
  // and launch behavior coherent. A partial patch would advertise settings that
  // are dropped by persistence or a model that never reaches the subagent, so
  // every exact anchor must match once before any rewrite is made.
  {
    const countsByVariant = REFLECTION_CONTROLS_VARIANTS.map((patches) => patches.map(({ token }) => countOccurrences(patched, token)));
    const matching = REFLECTION_CONTROLS_VARIANTS.filter((_, index) => countsByVariant[index].every((count) => count === 1));
    const counts = countsByVariant.map((variantCounts) => variantCounts.join(",")).join(" | ");
    if (matching.length === 1) {
      for (const { token, replacement } of matching[0]) {
        patched = patched.replace(token, () => replacement);
      }
      patched = injectHelperAfterShebang(patched, REFLECTION_CONTROLS_HELPER);
      appliedPatches += 1;
    } else {
      skippedPatches += 1;
      if (warn) process.stderr.write(
        `[letta-code-patch] WARN: lcp-reflection-controls anchors matched ${counts} (expected 1 each in exactly one binding variant) in ${path} — ` +
        `running without per-agent reflection model/focus/outcomes\n`,
      );
    }
  }

  Object.assign(state, { patched, appliedPatches, skippedPatches });
}

function modelCatalog(state) {
  let { patched, appliedPatches, skippedPatches } = state;
  const { path, warn } = state;

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

  Object.assign(state, { patched, appliedPatches, skippedPatches });
}

function contextCap(state) {
  let { patched, appliedPatches, skippedPatches } = state;
  const { path, warn } = state;

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

  Object.assign(state, { patched, appliedPatches, skippedPatches });
}

function reflectionHide(state) {
  let { patched, appliedPatches, skippedPatches } = state;
  const { path, warn } = state;

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

  Object.assign(state, { patched, appliedPatches, skippedPatches });
}

function subagentErrorLog(state) {
  let { patched, appliedPatches, skippedPatches } = state;
  const { path, warn } = state;

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

  Object.assign(state, { patched, appliedPatches, skippedPatches });
}

// lcp-agent-task-status (letta-mobile-mdhuf): publish every running-state
// transition together, or leave the bundle untouched if any anchor drifts.
// One site list per release shape. 0.34.70 renamed the catch binding (errorMessage5 ->
// errorMessage6) and added two failure transitions to the task stop path (a confirmed remote
// stop that timed out, and a stopped conversation); both must notify like the others.
const AGENT_TASK_STATUS_COMMON = [
  ['  backgroundTasks.set(taskId, bgTask);\n  registerSubagentAbortHook(subagentId, buildBackgroundTaskAbortHook(taskId, abortController));', 'bgTask'],
  ['    bgTask.status = result3.success ? "completed" : "failed";\n    if (result3.error) {', 'bgTask'],
];
const AGENT_TASK_STATUS_STOP = ['      task.status = "failed";\n      scheduleBackgroundTaskCleanup(task_id);', 'task'];
const AGENT_TASK_STATUS_VARIANTS = [
  [...AGENT_TASK_STATUS_COMMON, ['    bgTask.status = "failed";\n    bgTask.error = errorMessage5;', 'bgTask'], AGENT_TASK_STATUS_STOP],
  [
    ...AGENT_TASK_STATUS_COMMON,
    ['    bgTask.status = "failed";\n    bgTask.error = errorMessage6;', 'bgTask'],
    ['          task.status = "failed";\n          scheduleBackgroundTaskCleanup(task_id);\n          return {', 'task'],
    ['          task.status = "failed";\n          scheduleBackgroundTaskCleanup(task_id);\n          task.cancelPendingWakes', 'task'],
    AGENT_TASK_STATUS_STOP,
  ],
];
function agentTaskStatus(state) {
  const token = "// lcp-agent-task-status";
  if (state.patched.includes(token)) return;
  const matching = AGENT_TASK_STATUS_VARIANTS.filter((variant) =>
    variant.every(([anchor]) => countOccurrences(state.patched, anchor) === 1));
  // Exactly one release shape, or none: an ambiguous bundle is drift too.
  const sites = matching.length === 1 ? matching[0] : null;
  if (!sites) {
    const counts = AGENT_TASK_STATUS_VARIANTS.map((variant) => variant.map(([anchor]) => countOccurrences(state.patched, anchor)).join(","));
    state.skippedPatches += 1;
    if (state.warn) process.stderr.write(
      `[letta-code-patch] WARN: lcp-agent-task-status anchors matched ${counts.join(" | ")} (expected 1 each in exactly one release variant; ${matching.length} matched) in ${state.path} — running without agent task status notifications\n`,
    );
    return;
  }
  for (const [anchor, task] of sites) {
    const newline = anchor.indexOf("\n");
    const indent = anchor.match(/^ */)[0];
    const replacement = anchor.slice(0, newline) + `\n${indent}notifyBackgroundProcessStateChanged(${task}.runtimeScope); ${token}` + anchor.slice(newline);
    state.patched = state.patched.replace(anchor, replacement);
  }
  state.appliedPatches += 1;
}

// Host-neutral agent store. Agent state replicates between nodes in both directions, so the
// bytes the local store writes must mean the same thing on every machine: no counters that
// two nodes advance independently, no CRLF that depends on the writer's OS, and no absolute
// host paths. Each patch below is one counted lcp id, matches its anchors exactly once and
// fails open on drift like every other patch in this file.

// lcp-conversation-uuid: nextConversationId() minted `local-conv-<N>` from a backend-wide
// counter (the max numeric suffix over every loaded conversation), so two nodes mint the same
// id for different conversations. `randomUUID8` is node:crypto's randomUUID, imported at module
// scope, and createEphemeralConversation already mints `local-conv-<uuid>`. The loop still
// skips an id whose record or directory exists. conversationSeq becomes dead: numericSuffix()
// of a uuid suffix only ever feeds that counter.
const CONVERSATION_UUID_TOKEN =
  "      this.conversationSeq += 1;\n" +
  "      const conversationId = `${this.conversationIdPrefix}${this.conversationSeq}`;";
const CONVERSATION_UUID_REPLACEMENT =
  "      const conversationId = `${this.conversationIdPrefix}${randomUUID8()}`; // lcp-conversation-uuid";

// lcp-ui-msg-uuid: nextLocalMessageId() minted `ui-msg-<N>` from a counter rebuilt only from
// loaded in_context ids, so after compaction (or on another node) it can reuse an id already in
// the conversation's history. Readers checked against 0.34.39:
//  - LocalStore pagination finds before/after cursors by id equality, not by order;
//  - numericSuffix() only feeds localMessageSeq, now dead;
//  - numericId()'s /ui-msg-(\d+)$/ (migrateLocalBackendTranscripts) only seeds the counter for
//    ids it mints while converting pre-pi legacy transcripts. A uuid suffix never matches it
//    (hyphens), so it reads 0 and the numeric ids it mints cannot collide with uuid ids;
//  - App Server clients key on the `ui-msg-` prefix only.
const UI_MSG_UUID_TOKEN =
  "    this.localMessageSeq += 1;\n" +
  "    return `${this.localMessageIdPrefix}${this.localMessageSeq}`;";
const UI_MSG_UUID_REPLACEMENT =
  "    return `${this.localMessageIdPrefix}${randomUUID8()}`; // lcp-ui-msg-uuid";

// lcp-memory-lf: the memory repo inherits core.autocrlf from the host and has no
// .gitattributes, so a Windows node's agent Bash tool (PowerShell) commits CRLF blobs that
// differ byte-for-byte from the same memory on Linux. prepareLocalOnlyMemoryRepoForGitOps runs
// on every system-prompt compile and every harness memory write, so existing repos converge
// too. It sets core.autocrlf=false (node-local config), writes `* text=auto eol=lf` when the
// repo has no .gitattributes, and commits it once through commitMemoryPaths; a repo with no
// HEAD yet carries it in its init commit instead. Git failures are logged and never block the
// caller. A memo skips the git calls once a directory has converged in this process.
const MEMORY_LF_PREPARE_HEAD_TOKEN = "async function prepareLocalOnlyMemoryRepoForGitOps(memoryDir, author) {\n";
const MEMORY_LF_PREPARE_TAIL_TOKEN =
  '  if (await getLocalGitConfig(memoryDir, "commit.gpgsign") === null) {\n' +
  '    await setLocalGitConfig(memoryDir, "commit.gpgsign", "false");\n' +
  "  }\n" +
  "}";
const MEMORY_LF_PREPARE_TAIL_REPLACEMENT =
  '  if (await getLocalGitConfig(memoryDir, "commit.gpgsign") === null) {\n' +
  '    await setLocalGitConfig(memoryDir, "commit.gpgsign", "false");\n' +
  "  }\n" +
  "  await __lcpEnsureMemoryLineEndings(memoryDir, author);\n" +
  "}";
// The fs and path bindings carry minifier suffixes; capture them from initializeLocalMemoryRepo,
// which lives in the same module, instead of hard-coding them.
const MEMORY_LF_INIT_FS_PATTERN =
  /if \(!(existsSync\d*)\((join\d*)\(params\.memoryDir, "\.git"\)\)\) \{\n {4}await runGit\(params\.memoryDir, \["init"\]\);/g;
const MEMORY_LF_INIT_COMMIT_PATTERN =
  /(writeFileSync\d*)\(fullPath, file\.content, "utf8"\);\n {4}pathspecs\.push\(relativePath\);\n {2}\}\n {2}await prepareLocalOnlyMemoryRepoForGitOps\(params\.memoryDir, author\);\n {2}if \(pathspecs\.length > 0\) \{/g;

function memoryLfHelper(existsSync, join, writeFileSync) {
  return (
    "// lcp-memory-lf: LF line endings for the memory repo, on every host.\n" +
    "async function __lcpEnsureMemoryLineEndings(memoryDir, author) {\n" +
    "  const converged = globalThis.__lcpMemoryLineEndingsConverged ??= new Set;\n" +
    "  if (converged.has(memoryDir)) return;\n" +
    "  try {\n" +
    '    if (await getLocalGitConfig(memoryDir, "core.autocrlf") !== "false") {\n' +
    '      await setLocalGitConfig(memoryDir, "core.autocrlf", "false");\n' +
    "    }\n" +
    `    const attributesPath = ${join}(memoryDir, ".gitattributes");\n` +
    `    if (!${existsSync}(attributesPath)) {\n` +
    `      ${writeFileSync}(attributesPath, "* text=auto eol=lf\\n", "utf8");\n` +
    "    }\n" +
    "    if (!await hasMemoryHead(memoryDir)) return;\n" +
    "    let tracked = false;\n" +
    "    try {\n" +
    '      await runGit(memoryDir, ["cat-file", "-e", "HEAD:.gitattributes"]);\n' +
    "      tracked = true;\n" +
    "    } catch {}\n" +
    "    if (!tracked) {\n" +
    '      await commitMemoryPaths(memoryDir, [".gitattributes"], "chore: normalize memory line endings to LF", author);\n' +
    "    }\n" +
    "    converged.add(memoryDir);\n" +
    "  } catch (error4) {\n" +
    '    debugWarn("memfs-git", `lcp-memory-lf: could not normalize line endings in ${memoryDir}: ${error4 instanceof Error ? error4.message : String(error4)}`);\n' +
    "  }\n" +
    "}\n"
  );
}

// lcp-session-cwd: the messages.jsonl session line recorded the App Server's process.cwd(), an
// absolute host path. Both writers (the LocalStore header and the transcript migration) now
// record LETTA_WORKSPACE_ID when the node sets it and omit the field otherwise. No letta-code
// reader needs it: the only `row.cwd` readers parse other tools' transcripts, and they treat
// it as optional.
const SESSION_CWD_SITES = [
  {
    token:
      "    timestamp: conversation.created_at ?? currentIsoTimestamp(),\n" +
      "    cwd: process.cwd()\n" +
      "  };",
    replacement:
      "    timestamp: conversation.created_at ?? currentIsoTimestamp(),\n" +
      "    ...process.env.LETTA_WORKSPACE_ID ? { cwd: process.env.LETTA_WORKSPACE_ID } : {} // lcp-session-cwd\n" +
      "  };",
  },
  {
    token:
      "      timestamp: input.createdAt ?? new Date().toISOString(),\n" +
      "      cwd: process.cwd()\n" +
      "    },",
    replacement:
      "      timestamp: input.createdAt ?? new Date().toISOString(),\n" +
      "      ...process.env.LETTA_WORKSPACE_ID ? { cwd: process.env.LETTA_WORKSPACE_ID } : {} // lcp-session-cwd\n" +
      "    },",
  },
];

// lcp-agent-description: the default description embedded the creating process's cwd, which
// is persisted in the agent record and echoed in every agent-info reminder.
const AGENT_DESCRIPTION_TOKEN = "options.description ?? `Letta Code agent created in ${process.cwd()}`;";
const AGENT_DESCRIPTION_REPLACEMENT = 'options.description ?? "Letta Code agent"; // lcp-agent-description';

// lcp-memory-dir-token: the agent-info reminder, persisted verbatim in messages.jsonl, printed
// this node's absolute memory root. Emit the literal $MEMORY_DIR, which every node sets for
// tools and which the memory projections already use. This anchor sits after the region
// lcp-clr-x rewrites and does not overlap it.
const MEMORY_DIR_TOKEN =
  "- **Memory directory (also stored in \\`MEMORY_DIR\\` env var)**: \\`${getScopedMemoryFilesystemRoot(agentInfo.id)}\\``";
const MEMORY_DIR_REPLACEMENT =
  "- **Memory directory (also stored in \\`MEMORY_DIR\\` env var)**: \\`$MEMORY_DIR\\``";

// lcp-model-profile: replicated agent and conversation records store a model profile handle
// (`profile/<id>`), never a provider handle. resolvePiModelForAgent is the one turn-time site
// where a stored handle becomes a provider, model and auth (turns, context preflight,
// compaction and summaries all call it), so the node's binding is applied here and nothing is
// written back. The binding comes from globalThis.__lcpResolveModelProfile, which the node-local
// profile-provider mod installs (mods/profile-provider.mjs). A profile handle with no resolver,
// or one the resolver does not bind, throws: it never falls through to another provider.
const MODEL_PROFILE_PREFIX = "profile/";
// The user's context cap on a profile-bound conversation. `/context-limit` (alias
// `/set-max-context`, applySetMaxContext) stores the cap in context_window_limit, the field every
// model switch also fills with the catalog default, and the profile resolver drops that field
// because it cannot tell the two apart. So the cap is also recorded in its own field, in the
// conversation record (or the agent record for the default conversation), which replicates with
// the record. It is only valid while the stock field still holds it: a later write that stores a
// different window there (a model switch to another default, a reset) removes it, so a user cap
// is lost exactly when it is lost today, and the reader ignores it when the two differ (a node
// without this patch keeps unknown conversation fields while it rewrites context_window_limit). On a profile turn the field rides in the turn's settings
// from the same layer the stock cap comes from, the resolver gets it as its third argument, and
// the effective window is the smallest of it, the binding's context_window_limit and the bound
// model's own window. Without a user cap nothing changes. Local backend only.
const USER_CAP_FIELD = "lcp_user_context_window_limit";
const USER_CAP_MARKER = "// lcp-profile-user-cap";
const USER_CAP_EFFECTIVE_TOKEN =
  "function effectiveAgentForConversation(agent, conversation) {\n" +
  "  const conversationRecord = conversation;\n";
const USER_CAP_EFFECTIVE_REPLACEMENT =
  `function effectiveAgentForConversation(agent, conversation) { ${USER_CAP_MARKER}: a profile turn carries the user's cap.\n` +
  "  const effective = __lcpStockEffectiveAgentForConversation(agent, conversation);\n" +
  `  if (typeof effective.model !== "string" || !effective.model.startsWith(${JSON.stringify(MODEL_PROFILE_PREFIX)})) return effective;\n` +
  "  const conversationRecord = isRecord2(conversation) ? conversation : {};\n" +
  '  const fromConversation = typeof conversationRecord.context_window_limit === "number";\n' +
  '  const conversationDefault = isRecord2(conversationRecord.model_settings) && typeof conversationRecord.model_settings.context_window_limit === "number";\n' +
  `  const userCap = fromConversation ? conversationRecord.${USER_CAP_FIELD} : conversationDefault ? undefined : agent?.${USER_CAP_FIELD};\n` +
  "  const storedCap = fromConversation ? conversationRecord.context_window_limit : agent?.model_settings?.context_window_limit;\n" +
  '  if (typeof userCap !== "number" || userCap !== storedCap) return effective;\n' +
  `  return { ...effective, model_settings: { ...effective.model_settings, ${USER_CAP_FIELD}: userCap } };\n` +
  "}\n" +
  "function __lcpStockEffectiveAgentForConversation(agent, conversation) {\n" +
  "  const conversationRecord = conversation;\n";
const USER_CAP_CLAMP_TOKEN =
  "  const contextWindow = configuredContextWindow && isLocalOllama ? Math.min(configuredContextWindow, hookedModel.contextWindow) : configuredContextWindow;\n";
const USER_CAP_CLAMP_REPLACEMENT =
  `  const contextWindow = configuredContextWindow && (isLocalOllama || lcpUserContextWindowLimit !== undefined) ? Math.min(configuredContextWindow, hookedModel.contextWindow) : configuredContextWindow; ${USER_CAP_MARKER}\n`;
const USER_CAP_NORMALIZE_TOKEN =
  "    model: model2,\n" +
  "    model_settings: modelSettings,\n" +
  "    ...hidden !== undefined ? { hidden } : {},\n";
const USER_CAP_NORMALIZE_REPLACEMENT =
  USER_CAP_NORMALIZE_TOKEN +
  `    ...typeof value.${USER_CAP_FIELD} === "number" ? { ${USER_CAP_FIELD}: value.${USER_CAP_FIELD} } : {}, ${USER_CAP_MARKER}\n`;
const USER_CAP_AGENT_UPDATE_TOKEN =
  "      model_settings: nextModelSettings\n" +
  "    };\n" +
  "    this.agents.set(agentId, updated);\n";
const USER_CAP_AGENT_UPDATE_REPLACEMENT =
  "      model_settings: nextModelSettings\n" +
  "    };\n" +
  `    if (typeof bodyRecord.${USER_CAP_FIELD} === "number") updated.${USER_CAP_FIELD} = bodyRecord.${USER_CAP_FIELD}; ${USER_CAP_MARKER}\n` +
  `    if (bodyRecord.${USER_CAP_FIELD} === null || updated.${USER_CAP_FIELD} !== updated.model_settings.context_window_limit) delete updated.${USER_CAP_FIELD};\n` +
  "    this.agents.set(agentId, updated);\n";
const USER_CAP_CONVERSATION_UPDATE_TOKEN =
  "  if (isStringArray2(bodyRecord.tags)) {\n" +
  "    next.tags = bodyRecord.tags;\n" +
  "  }\n" +
  "  return next;\n" +
  "}";
const USER_CAP_CONVERSATION_UPDATE_REPLACEMENT =
  "  if (isStringArray2(bodyRecord.tags)) {\n" +
  "    next.tags = bodyRecord.tags;\n" +
  "  }\n" +
  `  if (typeof bodyRecord.${USER_CAP_FIELD} === "number") next.${USER_CAP_FIELD} = bodyRecord.${USER_CAP_FIELD}; ${USER_CAP_MARKER}\n` +
  `  if (bodyRecord.${USER_CAP_FIELD} === null || next.${USER_CAP_FIELD} !== next.context_window_limit) delete next.${USER_CAP_FIELD};\n` +
  "  return next;\n" +
  "}";
// A reset (`/context-limit` with no value) stores the catalog default and sends null, which the
// two update sites above turn into a removal. Only the local store gets the field.
const USER_CAP_WRITE = `,\n      ...backend3.store ? { ${USER_CAP_FIELD}: reset ? null : contextWindow } : {} ${USER_CAP_MARKER}\n`;
const USER_CAP_SET_AGENT_TOKEN =
  "    const updatedAgent = await backend3.updateAgent(params.agentId, {\n" +
  "      context_window_limit: contextWindow\n" +
  "    });";
const USER_CAP_SET_AGENT_REPLACEMENT =
  "    const updatedAgent = await backend3.updateAgent(params.agentId, {\n" +
  "      context_window_limit: contextWindow" + USER_CAP_WRITE +
  "    });";
const USER_CAP_SET_CONVERSATION_TOKEN =
  "  const updatedConversation = await backend3.updateConversation(params.conversationId, {\n" +
  "    context_window_limit: contextWindow\n" +
  "  });";
const MODEL_PROFILE_TOKEN =
  "async function resolvePiModelForAgent(modelHandle, modelSettings = {}, options = {}) {\n" +
  "  const concreteModelHandle = ";
const MODEL_PROFILE_REPLACEMENT =
  "async function resolvePiModelForAgent(modelHandle, modelSettings = {}, options = {}) {\n" +
  "  let lcpUserContextWindowLimit;\n" +
  `  if (typeof modelHandle === "string" && modelHandle.startsWith(${JSON.stringify(MODEL_PROFILE_PREFIX)})) { // lcp-model-profile\n` +
  "    const resolveModelProfile = globalThis.__lcpResolveModelProfile;\n" +
  '    if (typeof resolveModelProfile !== "function") {\n' +
  "      throw new Error(`Model profile ${modelHandle} cannot be resolved: the profile-provider mod is not installed on this node.`);\n" +
  "    }\n" +
  `    const { ${USER_CAP_FIELD}: userCap, ...profileSettings } = modelSettings ?? {};\n` +
  '    lcpUserContextWindowLimit = typeof userCap === "number" && Number.isFinite(userCap) && userCap > 0 ? userCap : undefined;\n' +
  "    const bound = resolveModelProfile(modelHandle, profileSettings, lcpUserContextWindowLimit);\n" +
  `    if (!bound || typeof bound.handle !== "string" || !bound.handle || bound.handle.startsWith(${JSON.stringify(MODEL_PROFILE_PREFIX)})) {\n` +
  "      throw new Error(`Model profile ${modelHandle} is not bound to a provider handle on this node. Add it to LETTA_MODEL_PROFILE_BINDINGS.`);\n" +
  "    }\n" +
  "    modelHandle = bound.handle;\n" +
  "    modelSettings = bound.modelSettings ?? {};\n" +
  "  }\n" +
  "  const concreteModelHandle = ";

const USER_CAP_SET_CONVERSATION_REPLACEMENT =
  "  const updatedConversation = await backend3.updateConversation(params.conversationId, {\n" +
  "    context_window_limit: contextWindow" + USER_CAP_WRITE.replace(/\n {6}/, "\n    ") +
  "  });";

// lcp-memory-dir-expand: file tools (Read, Write, Edit, Glob, Grep, ...) expand `$VAR` in a path
// from the App Server's own process.env, where MEMORY_DIR is unset because one process serves
// every agent; only shell children get it. So a literal `$MEMORY_DIR/...`, which the agent-info
// reminder (lcp-memory-dir-token) and skill content (lcp-skill-dir) now print, resolved against
// the cwd. expandPathArgs is the one site every file-tool call and permission check goes through;
// it now fills MEMORY_DIR and LETTA_MEMORY_DIR with the scoped memory root of the agent whose tool
// is running, the same root the shell exports, when the environment does not already carry them.
const MEMORY_DIR_EXPAND_TOKEN =
  "function expandPathArgs(toolName2, toolArgs, workingDirectory, env6 = process.env, homeDir) {\n" +
  "  if (!CWD_FILE_PATH_TOOLS.has(canonicalToolName(toolName2)))\n" +
  "    return toolArgs;\n";
const MEMORY_DIR_EXPAND_MARKER = "// lcp-memory-dir-expand";
const MEMORY_DIR_EXPAND_REPLACEMENT =
  `${MEMORY_DIR_EXPAND_MARKER}: file tools expand $MEMORY_DIR to this agent's memory root on every node.\n` +
  "function __lcpWithMemoryDirEnv(env) {\n" +
  "  if (env.MEMORY_DIR?.trim() && env.LETTA_MEMORY_DIR?.trim()) return env;\n" +
  "  let memoryDir = env.MEMORY_DIR?.trim() || env.LETTA_MEMORY_DIR?.trim() || null;\n" +
  "  if (!memoryDir) {\n" +
  "    try {\n" +
  "      memoryDir = resolveScopedMemoryDir({ env });\n" +
  "    } catch {\n" +
  "      memoryDir = null;\n" +
  "    }\n" +
  "  }\n" +
  "  return memoryDir ? { ...env, MEMORY_DIR: memoryDir, LETTA_MEMORY_DIR: memoryDir } : env;\n" +
  "}\n" +
  MEMORY_DIR_EXPAND_TOKEN +
  "  env6 = __lcpWithMemoryDirEnv(env6); // lcp-memory-dir-expand\n";

// lcp-skill-dir: renderSkillContent substituted the absolute skill dir for <SKILL_DIR> and
// ${CLAUDE_SKILL_DIR} and printed it as "Skill directory:", and the result is persisted in the
// <skill_content> tool result. A skill under this agent's memory root is now addressed as
// $MEMORY_DIR/<relative path>, which shells expand on every node and file tools expand through
// lcp-memory-dir-expand (a precondition site below, so the two never apply apart). Skills found
// anywhere else (bundled, global, project) keep their install path. The resource list already
// holds paths relative to the skill dir. The dir is inserted through a replacer function, so `$&`,
// `$1` or `$$` in a directory name stay literal (stock passed it as a replacement string).
const SKILL_DIR_HELPER_TOKEN = "function renderSkillContent(skillName, skillContent, skillPath, options = {}) {";
const SKILL_DIR_HELPER_REPLACEMENT =
  "// lcp-skill-dir: a skill under this agent's memory root is addressed as $MEMORY_DIR/<path>.\n" +
  "function __lcpHostNeutralSkillDir(skillDir, agentId) {\n" +
  "  try {\n" +
  "    const memoryDir = resolveScopedMemoryDir(agentId ? { agentId } : {});\n" +
  "    if (!memoryDir) return skillDir;\n" +
  '    const slashes = (value) => value.replace(/\\\\/g, "/").replace(/\\/+$/, "");\n' +
  '    const fold = process.platform === "win32" ? (value) => value.toLowerCase() : (value) => value;\n' +
  "    const root = slashes(memoryDir);\n" +
  "    const dir = slashes(skillDir);\n" +
  '    if (fold(dir) === fold(root)) return "$MEMORY_DIR";\n' +
  '    if (fold(dir).startsWith(`${fold(root)}/`)) return `$MEMORY_DIR/${dir.slice(root.length + 1)}`;\n' +
  "  } catch {}\n" +
  "  return skillDir;\n" +
  "}\n" +
  SKILL_DIR_HELPER_TOKEN;
const SKILL_DIR_SUBSTITUTE_TOKEN =
  "  const withSkillDir = skillContent.replace(/<SKILL_DIR>/g, skillDir).replace(/\\$\\{CLAUDE_SKILL_DIR\\}/g, skillDir);";
const SKILL_DIR_SUBSTITUTE_REPLACEMENT =
  "  const lcpSkillDir = __lcpHostNeutralSkillDir(skillDir, options.agentId); // lcp-skill-dir\n" +
  "  const withSkillDir = skillContent.replace(/<SKILL_DIR>/g, () => lcpSkillDir).replace(/\\$\\{CLAUDE_SKILL_DIR\\}/g, () => lcpSkillDir);";
const SKILL_DIR_FOOTER_TOKEN =
  "Skill directory: ${skillDir}\nRelative paths in this skill are relative to the skill directory.";
const SKILL_DIR_FOOTER_REPLACEMENT =
  "Skill directory: ${lcpSkillDir}\nRelative paths in this skill are relative to the skill directory.";

// lcp-system-prompt-tracking: whether an agent's system prompt is a managed preset (and at which
// hash and letta-code version) or custom lived only in the node-local
// $LETTA_CONFIG_DIR/settings.json. The App Server reads it on every turn
// (scheduleManagedSystemPromptUpdate) to decide whether to upgrade agent.system after a
// letta-code bump, so a node without that entry froze a managed prompt as "custom" while the
// node that had it upgraded the prompt. The tracking now lives in the agent record, which
// replicates, as system_prompt_tracking { preset, hash?, version? }. The getters read the record
// first and fall back to settings.json for agents that predate this (copying the entry into the
// record the first time, and only while the refreshed record is still untracked, so it never
// overwrites tracking a peer wrote meanwhile). The setters still write settings.json, then the
// record. A record write that throws puts the previous in-memory record back, so an identical
// retry is not mistaken for a no-op. Local backend only; any other backend keeps settings.json.
const SYSTEM_PROMPT_TRACKING_CLASS_TOKEN = "\nclass SettingsManager {\n";
const SYSTEM_PROMPT_TRACKING_HELPERS =
  "\n// lcp-system-prompt-tracking: per-agent system prompt tracking lives in the agent record.\n" +
  "function __lcpLocalAgentStore() {\n" +
  "  try {\n" +
  "    if (!isLocalBackendEnvEnabled()) return null;\n" +
  "    const store = getBackend()?.store;\n" +
  '    return store && typeof store.refreshAgentRecordFromStorage === "function" && typeof store.persistAgent === "function" && store.agents instanceof Map ? store : null;\n' +
  "  } catch {\n" +
  "    return null;\n" +
  "  }\n" +
  "}\n" +
  "function __lcpWriteSystemPromptTracking(agentId, settings, onlyIfUntracked = false) {\n" +
  "  const store = __lcpLocalAgentStore();\n" +
  "  if (!store) return;\n" +
  "  let previous;\n" +
  "  let next;\n" +
  "  try {\n" +
  "    const record = store.refreshAgentRecordFromStorage(agentId);\n" +
  "    if (!record) return;\n" +
  "    if (onlyIfUntracked && record.system_prompt_tracking !== undefined) return;\n" +
  "    const tracking = {\n" +
  '      preset: settings?.systemPromptPreset ?? "",\n' +
  "      ...settings?.systemPromptHash ? { hash: settings.systemPromptHash } : {},\n" +
  "      ...settings?.systemPromptVersion ? { version: settings.systemPromptVersion } : {}\n" +
  "    };\n" +
  "    if (JSON.stringify(record.system_prompt_tracking ?? null) === JSON.stringify(tracking)) return;\n" +
  "    previous = record;\n" +
  "    next = { ...record, system_prompt_tracking: tracking };\n" +
  "    store.agents.set(agentId, next);\n" +
  "    store.persistAgent(agentId);\n" +
  "  } catch (error4) {\n" +
  "    if (next && store.agents.get(agentId) === next) store.agents.set(agentId, previous);\n" +
  '    debugWarn("settings", `lcp-system-prompt-tracking: could not record system prompt tracking for ${agentId}: ${error4 instanceof Error ? error4.message : String(error4)}`);\n' +
  "  }\n" +
  "}\n" +
  "function __lcpSystemPromptTracking(manager, agentId) {\n" +
  "  const settings = manager.getAgentSettings(agentId);\n" +
  "  const store = __lcpLocalAgentStore();\n" +
  "  let record;\n" +
  "  try {\n" +
  "    record = store?.refreshAgentRecordFromStorage(agentId);\n" +
  "  } catch {\n" +
  "    record = undefined;\n" +
  "  }\n" +
  "  if (!record) return settings;\n" +
  "  const tracking = record.system_prompt_tracking;\n" +
  '  if (tracking && typeof tracking === "object" && !Array.isArray(tracking)) {\n' +
  "    return {\n" +
  "      systemPromptPreset: tracking.preset || undefined,\n" +
  "      systemPromptHash: tracking.hash || undefined,\n" +
  "      systemPromptVersion: tracking.version || undefined\n" +
  "    };\n" +
  "  }\n" +
  "  if (settings?.systemPromptPreset || settings?.systemPromptHash || settings?.systemPromptVersion) {\n" +
  "    __lcpWriteSystemPromptTracking(agentId, settings, true);\n" +
  "  }\n" +
  "  return settings;\n" +
  "}\n" +
  SYSTEM_PROMPT_TRACKING_CLASS_TOKEN;
const SYSTEM_PROMPT_TRACKING_METHODS_TOKEN =
  "  getSystemPromptPreset(agentId) {\n" +
  "    return this.getAgentSettings(agentId)?.systemPromptPreset;\n" +
  "  }\n" +
  "  getSystemPromptHash(agentId) {\n" +
  "    return this.getAgentSettings(agentId)?.systemPromptHash;\n" +
  "  }\n" +
  "  getSystemPromptVersion(agentId) {\n" +
  "    return this.getAgentSettings(agentId)?.systemPromptVersion;\n" +
  "  }\n" +
  "  setSystemPromptPreset(agentId, preset) {\n" +
  "    this.upsertAgentSettings(agentId, {\n" +
  "      systemPromptPreset: preset,\n" +
  "      systemPromptHash: null,\n" +
  "      systemPromptVersion: null\n" +
  "    });\n" +
  "  }\n" +
  "  setManagedSystemPrompt(agentId, prompt) {\n" +
  "    this.upsertAgentSettings(agentId, {\n" +
  "      systemPromptPreset: prompt.preset,\n" +
  "      systemPromptHash: prompt.hash,\n" +
  "      systemPromptVersion: prompt.version\n" +
  "    });\n" +
  "  }\n" +
  "  setSystemPromptCustom(agentId) {\n" +
  "    this.upsertAgentSettings(agentId, {\n" +
  '      systemPromptPreset: "custom",\n' +
  "      systemPromptHash: null,\n" +
  "      systemPromptVersion: null\n" +
  "    });\n" +
  "  }\n" +
  "  clearSystemPromptPreset(agentId) {\n" +
  "    this.upsertAgentSettings(agentId, {\n" +
  '      systemPromptPreset: "",\n' +
  "      systemPromptHash: null,\n" +
  "      systemPromptVersion: null\n" +
  "    });\n" +
  "  }\n";
const SYSTEM_PROMPT_TRACKING_METHODS_REPLACEMENT =
  "  getSystemPromptPreset(agentId) {\n" +
  "    return __lcpSystemPromptTracking(this, agentId)?.systemPromptPreset; // lcp-system-prompt-tracking\n" +
  "  }\n" +
  "  getSystemPromptHash(agentId) {\n" +
  "    return __lcpSystemPromptTracking(this, agentId)?.systemPromptHash;\n" +
  "  }\n" +
  "  getSystemPromptVersion(agentId) {\n" +
  "    return __lcpSystemPromptTracking(this, agentId)?.systemPromptVersion;\n" +
  "  }\n" +
  "  setSystemPromptPreset(agentId, preset) {\n" +
  "    this.upsertAgentSettings(agentId, {\n" +
  "      systemPromptPreset: preset,\n" +
  "      systemPromptHash: null,\n" +
  "      systemPromptVersion: null\n" +
  "    });\n" +
  "    __lcpWriteSystemPromptTracking(agentId, this.getAgentSettings(agentId));\n" +
  "  }\n" +
  "  setManagedSystemPrompt(agentId, prompt) {\n" +
  "    this.upsertAgentSettings(agentId, {\n" +
  "      systemPromptPreset: prompt.preset,\n" +
  "      systemPromptHash: prompt.hash,\n" +
  "      systemPromptVersion: prompt.version\n" +
  "    });\n" +
  "    __lcpWriteSystemPromptTracking(agentId, this.getAgentSettings(agentId));\n" +
  "  }\n" +
  "  setSystemPromptCustom(agentId) {\n" +
  "    this.upsertAgentSettings(agentId, {\n" +
  '      systemPromptPreset: "custom",\n' +
  "      systemPromptHash: null,\n" +
  "      systemPromptVersion: null\n" +
  "    });\n" +
  "    __lcpWriteSystemPromptTracking(agentId, this.getAgentSettings(agentId));\n" +
  "  }\n" +
  "  clearSystemPromptPreset(agentId) {\n" +
  "    this.upsertAgentSettings(agentId, {\n" +
  '      systemPromptPreset: "",\n' +
  "      systemPromptHash: null,\n" +
  "      systemPromptVersion: null\n" +
  "    });\n" +
  "    __lcpWriteSystemPromptTracking(agentId, this.getAgentSettings(agentId));\n" +
  "  }\n";
// normalizeAgentRecord rebuilds every loaded record from a whitelist, so the field has to be
// carried through it or the next load (and the persist after it) would drop it.
const SYSTEM_PROMPT_TRACKING_NORMALIZE_TOKEN =
  "    ...compactionSettings !== undefined ? { compaction_settings: compactionSettings } : {}\n" +
  "  };\n" +
  "}";
const SYSTEM_PROMPT_TRACKING_NORMALIZE_REPLACEMENT =
  "    ...compactionSettings !== undefined ? { compaction_settings: compactionSettings } : {},\n" +
  '    ...value.system_prompt_tracking && typeof value.system_prompt_tracking === "object" && !Array.isArray(value.system_prompt_tracking) ? { system_prompt_tracking: { ...value.system_prompt_tracking } } : {} // lcp-system-prompt-tracking\n' +
  "  };\n" +
  "}";

/**
 * One counted patch whose literal sites all match exactly once, applied atomically.
 *
 * @param {string} id the lcp id, for the drift warning
 * @param {{ token: string; replacement: string }[]} sites
 * @param {string} consequence what running without the patch means
 */
function exactOncePatch(id, sites, consequence) {
  return (state) => {
    const counts = sites.map(({ token }) => countOccurrences(state.patched, token));
    if (!counts.every((count) => count === 1)) {
      state.skippedPatches += 1;
      if (state.warn) process.stderr.write(
        `[letta-code-patch] WARN: ${id} anchors matched ${counts.join(",")} (expected 1 each) in ${state.path} — ` +
        `running without it; ${consequence}\n`,
      );
      return;
    }
    for (const { token, replacement } of sites) state.patched = state.patched.replace(token, () => replacement);
    state.appliedPatches += 1;
  };
}

const conversationUuid = exactOncePatch(
  "lcp-conversation-uuid",
  [{ token: CONVERSATION_UUID_TOKEN, replacement: CONVERSATION_UUID_REPLACEMENT }],
  "conversation ids stay node-local counters that collide across nodes",
);

const uiMsgUuid = exactOncePatch(
  "lcp-ui-msg-uuid",
  [{ token: UI_MSG_UUID_TOKEN, replacement: UI_MSG_UUID_REPLACEMENT }],
  "message ids stay node-local counters that can repeat across nodes",
);

const sessionCwd = exactOncePatch(
  "lcp-session-cwd",
  SESSION_CWD_SITES,
  "messages.jsonl session lines keep the App Server's absolute cwd",
);

const agentDescription = exactOncePatch(
  "lcp-agent-description",
  [{ token: AGENT_DESCRIPTION_TOKEN, replacement: AGENT_DESCRIPTION_REPLACEMENT }],
  "new agents keep a description that names the creating host's cwd",
);

const memoryDirToken = exactOncePatch(
  "lcp-memory-dir-token",
  [{ token: MEMORY_DIR_TOKEN, replacement: MEMORY_DIR_REPLACEMENT }],
  "the agent-info reminder keeps printing this node's absolute memory root",
);

const modelProfile = exactOncePatch(
  "lcp-model-profile",
  [
    { token: MODEL_PROFILE_TOKEN, replacement: MODEL_PROFILE_REPLACEMENT },
    { token: USER_CAP_EFFECTIVE_TOKEN, replacement: USER_CAP_EFFECTIVE_REPLACEMENT },
    { token: USER_CAP_CLAMP_TOKEN, replacement: USER_CAP_CLAMP_REPLACEMENT },
    { token: USER_CAP_NORMALIZE_TOKEN, replacement: USER_CAP_NORMALIZE_REPLACEMENT },
    { token: USER_CAP_AGENT_UPDATE_TOKEN, replacement: USER_CAP_AGENT_UPDATE_REPLACEMENT },
    { token: USER_CAP_CONVERSATION_UPDATE_TOKEN, replacement: USER_CAP_CONVERSATION_UPDATE_REPLACEMENT },
    { token: USER_CAP_SET_AGENT_TOKEN, replacement: USER_CAP_SET_AGENT_REPLACEMENT },
    { token: USER_CAP_SET_CONVERSATION_TOKEN, replacement: USER_CAP_SET_CONVERSATION_REPLACEMENT },
  ],
  "profile/<id> handles are not resolved through this node's bindings and a user's context cap is not kept for them; their turns fail against the profile provider's unbound endpoint",
);

const memoryDirExpand = exactOncePatch(
  "lcp-memory-dir-expand",
  [{ token: MEMORY_DIR_EXPAND_TOKEN, replacement: MEMORY_DIR_EXPAND_REPLACEMENT }],
  "file tools resolve a literal $MEMORY_DIR path against the cwd instead of the agent's memory root",
);

const skillDir = exactOncePatch(
  "lcp-skill-dir",
  [
    // Precondition: $MEMORY_DIR skill paths are only usable where file tools expand them.
    { token: MEMORY_DIR_EXPAND_MARKER + ":", replacement: MEMORY_DIR_EXPAND_MARKER + ":" },
    { token: SKILL_DIR_HELPER_TOKEN, replacement: SKILL_DIR_HELPER_REPLACEMENT },
    { token: SKILL_DIR_SUBSTITUTE_TOKEN, replacement: SKILL_DIR_SUBSTITUTE_REPLACEMENT },
    { token: SKILL_DIR_FOOTER_TOKEN, replacement: SKILL_DIR_FOOTER_REPLACEMENT },
  ],
  "skill content persists this node's absolute path for skills under the agent's memory root",
);

const systemPromptTracking = exactOncePatch(
  "lcp-system-prompt-tracking",
  [
    { token: SYSTEM_PROMPT_TRACKING_CLASS_TOKEN, replacement: SYSTEM_PROMPT_TRACKING_HELPERS },
    { token: SYSTEM_PROMPT_TRACKING_METHODS_TOKEN, replacement: SYSTEM_PROMPT_TRACKING_METHODS_REPLACEMENT },
    { token: SYSTEM_PROMPT_TRACKING_NORMALIZE_TOKEN, replacement: SYSTEM_PROMPT_TRACKING_NORMALIZE_REPLACEMENT },
  ],
  "managed system prompt tracking stays in this node's settings.json and does not replicate with the agent",
);

function memoryLineEndings(state) {
  const fsMatch = matchOnce(state.patched, MEMORY_LF_INIT_FS_PATTERN);
  const commitMatch = matchOnce(state.patched, MEMORY_LF_INIT_COMMIT_PATTERN);
  const counts = [MEMORY_LF_PREPARE_HEAD_TOKEN, MEMORY_LF_PREPARE_TAIL_TOKEN]
    .map((token) => countOccurrences(state.patched, token));
  if (!fsMatch || !commitMatch || !counts.every((count) => count === 1)) {
    state.skippedPatches += 1;
    if (state.warn) process.stderr.write(
      `[letta-code-patch] WARN: lcp-memory-lf anchors matched initFs=${fsMatch ? 1 : "!1"} ` +
      `initCommit=${commitMatch ? 1 : "!1"} prepare=${counts.join(",")} (expected 1 each) in ${state.path} — ` +
      `running without it; memory repos keep host line endings\n`,
    );
    return;
  }
  const [, existsSync, join] = fsMatch;
  const [commitSite, writeFileSync] = commitMatch;
  state.patched = state.patched
    .replace(MEMORY_LF_PREPARE_HEAD_TOKEN, () => memoryLfHelper(existsSync, join, writeFileSync) + MEMORY_LF_PREPARE_HEAD_TOKEN)
    .replace(MEMORY_LF_PREPARE_TAIL_TOKEN, () => MEMORY_LF_PREPARE_TAIL_REPLACEMENT)
    .replace(commitSite, () => commitSite.replace(
      "  if (pathspecs.length > 0) {",
      `  if (${existsSync}(${join}(params.memoryDir, ".gitattributes"))) pathspecs.push(".gitattributes"); // lcp-memory-lf\n` +
      "  if (pathspecs.length > 0) {",
    ));
  state.appliedPatches += 1;
}

// lcp-conversation-turn-lock: one writer per local conversation, across processes.
//
// letta-code serialises turns per conversation only inside one process (the App Server's
// TurnLifecycle per conversation runtime, HeadlessBackend.activeRunByConversation). A message
// sent with SendAgentMessage on the local backend is a detached `letta -p --conv <id>` child
// with its own LocalStore over the same files. It knows nothing of the turn already running in
// the App Server or in a subagent's headless process, so it starts a second turn at once: its
// startup closes the running turn's pending tool call as "stale" and both processes then
// append to the same transcript (two final assistant messages on one parent).
//
// The lock is a file per conversation key under the OS temp dir (never in the replicated store),
// holding the owner's pid and process start time. It is created complete (written to a temp file,
// then hard-linked into place), a dead owner's lock is reclaimed, and a file that cannot be
// parsed is reclaimed once it is older than a few seconds. Holders:
//  - an App Server turn, inside handleIncomingMessageInner once its lifecycle lease has begun.
//    While it waits the turn is already the conversation's active turn: clients see it (loop
//    status plus a "waiting" status notice) and the stock cancel aborts the lease signal, which
//    ends the wait and finishes the turn as cancelled. It is released in the turn's last finally.
//  - a `letta -p` run from the moment its conversation is known until the process exits, so
//    its stale-approval recovery and its turn only ever run when no other turn is live.
// HeadlessBackend.executeConversationTurn refuses with the stock "busy with another active run"
// error while another live process holds the key, cancelConversation does not settle tool calls
// of a conversation whose turn belongs to another process, and the App Server's sync-time approval
// recovery leaves such a conversation alone instead of caching denials for its live tool calls.
// SendAgentMessage reports `queued` instead of `spawned` when the target has a turn in progress.
// That label is a sample taken at send time: a target whose previous spawned message is still
// starting has not taken the lock yet and is reported `spawned`, though the new message will wait
// behind it. The lock, not the label, is what keeps one writer on the transcript.
const TURN_LOCK_MARK = "// lcp-conversation-turn-lock";

const TURN_LOCK_HELPER = `
${TURN_LOCK_MARK}: cross-process turn lock per local conversation.
globalThis.__lcpTurnLock ??= (() => {
  const getBuiltin = typeof process.getBuiltinModule === "function" ? process.getBuiltinModule : null;
  if (!getBuiltin) {
    process.stderr.write("[lcp-turn-lock] WARN: process.getBuiltinModule unavailable; conversations are not locked across processes\\n");
    return { acquire: async () => () => {}, holder: () => null, heldElsewhere: () => false, assertNotHeldElsewhere: () => {}, acquireForHeadless: async () => {} };
  }
  const fs = getBuiltin("node:fs");
  const path = getBuiltin("node:path");
  const os = getBuiltin("node:os");
  const crypto = getBuiltin("node:crypto");
  const POLL_MS = 250;
  const NOTICE_MS = 30000;
  const RECLAIM_GUARD_STALE_MS = 10000;
  const UNREADABLE_STALE_MS = 5000;
  const startTime = (pid) => {
    try {
      return fs.readFileSync("/proc/" + pid + "/stat", "utf8").split(") ").pop().split(" ")[19] ?? null;
    } catch {
      return null;
    }
  };
  const self = { pid: process.pid, start: startTime(process.pid), token: crypto.randomUUID() };
  const held = new Map();
  const storageDirOf = (backend) => {
    try {
      return typeof backend?.getLocalStorageDir === "function" ? backend.getLocalStorageDir() ?? null : null;
    } catch {
      return null;
    }
  };
  // The same key the local store uses; a default conversation without an agent id resolves the
  // agent the way the backend does for its own turns.
  const conversationKey = (backend, agentId, conversationId) => {
    const id = conversationId || "default";
    if (id !== "default") return "conversation:" + id;
    let agent = agentId;
    if (!agent) {
      try {
        agent = backend?.store?.resolveAgentIdForConversation?.("default") ?? null;
      } catch {
        agent = null;
      }
    }
    return agent ? "default:" + agent : null;
  };
  const lockFile = (storageDir, key) => {
    const uid = typeof process.getuid === "function" ? process.getuid() : "user";
    const digest = crypto.createHash("sha256").update(path.resolve(storageDir) + "\\0" + key).digest("hex").slice(0, 40);
    return path.join(os.tmpdir(), "letta-turn-locks-" + uid, digest + ".lock");
  };
  const hasFieldOfType = (value, field, type) => typeof value?.[field] === type;
  const isOwnerRecord = (value) => hasFieldOfType(value, "pid", "number") && hasFieldOfType(value, "token", "string");
  const parseOwner = (text) => {
    try {
      const owner = JSON.parse(text);
      return isOwnerRecord(owner) ? owner : null;
    } catch {
      return null;
    }
  };
  const mtimeOf = (file) => {
    try {
      return fs.statSync(file).mtimeMs;
    } catch {
      return null;
    }
  };
  const readOwner = (file) => {
    let text;
    try {
      text = fs.readFileSync(file, "utf8");
    } catch (error) {
      if (error?.code === "ENOENT") return null;
      return { unreadable: true, mtimeMs: null };
    }
    return parseOwner(text) ?? { unreadable: true, mtimeMs: mtimeOf(file) };
  };
  const ownerAlive = (owner) => {
    if (!owner) return false;
    if (owner.unreadable) return true;
    try {
      process.kill(owner.pid, 0);
    } catch (error) {
      if (error?.code !== "EPERM") return false;
    }
    if (owner.start) {
      const current = startTime(owner.pid);
      if (current !== null && current !== owner.start) return false;
    }
    return true;
  };
  // An unparseable file is honoured only while it may be a writer mid-create: an mtime more than
  // UNREADABLE_STALE_MS away from now in either direction (clock skew, a copied file) is stale.
  const stale = (owner) => owner.unreadable
    ? owner.mtimeMs !== null && Math.abs(Date.now() - owner.mtimeMs) > UNREADABLE_STALE_MS
    : !ownerAlive(owner);
  const sameOwner = (a, b) => a.unreadable ? b.unreadable === true : b.token === a.token;
  const unlinkQuietly = (file) => {
    try { fs.unlinkSync(file); } catch {}
  };
  const dropExpiredGuard = (guard) => {
    try {
      if (Date.now() - fs.statSync(guard).mtimeMs > RECLAIM_GUARD_STALE_MS) fs.unlinkSync(guard);
    } catch {}
  };
  // True when this process now holds the reclaim guard; a guard another process holds is dropped
  // only once it is old enough to be abandoned.
  const takeReclaimGuard = (guard) => {
    try {
      fs.writeFileSync(guard, String(process.pid), { flag: "wx", mode: 0o600 });
      return true;
    } catch (error) {
      if (error?.code !== "EEXIST") throw error;
      dropExpiredGuard(guard);
      return false;
    }
  };
  const reclaimMessage = (file, seen) => seen.unreadable
    ? "[lcp-turn-lock] reclaimed an unreadable turn lock " + file + "\\n"
    : "[lcp-turn-lock] reclaimed the turn lock of dead pid " + seen.pid + " (" + (seen.holder ?? "unknown") + ")\\n";
  const reclaimUnderGuard = (file, seen) => {
    const now = readOwner(file);
    if (!now) return;
    if (!sameOwner(seen, now) || !stale(now)) return;
    fs.unlinkSync(file);
    process.stderr.write(reclaimMessage(file, seen));
  };
  const reclaimIfStale = (file, seen) => {
    const guard = file + ".reclaim";
    if (!takeReclaimGuard(guard)) return;
    try {
      reclaimUnderGuard(file, seen);
    } finally {
      unlinkQuietly(guard);
    }
  };
  // Atomic create with content: the lock file never exists half-written.
  const createLock = (file, content) => {
    const temp = file + "." + process.pid + "." + crypto.randomUUID() + ".tmp";
    try {
      fs.writeFileSync(temp, content, { flag: "wx", mode: 0o600 });
      fs.linkSync(temp, file);
      return true;
    } catch (error) {
      if (error?.code === "EEXIST") return false;
      throw error;
    } finally {
      try { fs.unlinkSync(temp); } catch {}
    }
  };
  const tryTake = (file, holder, key) => {
    fs.mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 });
    if (createLock(file, JSON.stringify({ ...self, holder, key, at: new Date().toISOString() }))) return null;
    const owner = readOwner(file);
    if (!owner) return { retryNow: true };
    if (stale(owner)) {
      reclaimIfStale(file, owner);
      return readOwner(file) === null ? { retryNow: true } : owner;
    }
    return owner;
  };
  const releaseFile = (file) => {
    const count = (held.get(file) ?? 0) - 1;
    if (count > 0) {
      held.set(file, count);
      return;
    }
    held.delete(file);
    try {
      if (readOwner(file)?.token === self.token) fs.unlinkSync(file);
    } catch {}
  };
  process.once("exit", () => {
    for (const file of [...held.keys()]) {
      held.set(file, 1);
      releaseFile(file);
    }
  });
  const abortError = (signal) => signal.reason ?? Object.assign(new Error("The turn lock wait was aborted"), { name: "AbortError" });
  const sleep = (ms, signal) => new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(abortError(signal));
    const onAbort = () => {
      clearTimeout(timer);
      reject(abortError(signal));
    };
    const timer = setTimeout(() => {
      signal?.removeEventListener("abort", onAbort);
      resolve();
    }, ms);
    signal?.addEventListener("abort", onAbort, { once: true });
  });
  const describe = (owner) => owner.unreadable ? "an unreadable lock" : "pid " + owner.pid + " (" + (owner.holder ?? "unknown") + ")";
  const makeRelease = (file) => {
    let released = false;
    return () => {
      if (released) return;
      released = true;
      releaseFile(file);
    };
  };
  // One take attempt: a failure to use the lock at all is reported once and ends the wait.
  const attemptTake = (wait) => {
    try {
      return { owner: tryTake(wait.file, wait.holder, wait.key) };
    } catch (error) {
      process.stderr.write("[lcp-turn-lock] ERROR: cannot use the turn lock for " + wait.key + " (" + (error?.message ?? error) + "); running this turn unlocked\\n");
      return { failed: true };
    }
  };
  const takeOwnership = (wait) => {
    held.set(wait.file, 1);
    if (wait.waited) process.stderr.write("[lcp-turn-lock] " + wait.holder + " acquired " + wait.key + " after waiting " + (Date.now() - wait.startedWaiting) + "ms\\n");
    return makeRelease(wait.file);
  };
  const noteBusy = (wait, owner) => {
    if (!wait.waited) {
      wait.waited = true;
      try { wait.options.onWait?.(owner); } catch {}
    }
    if (wait.noticed || Date.now() - wait.startedWaiting < NOTICE_MS) return;
    wait.noticed = true;
    process.stderr.write("[lcp-turn-lock] " + wait.holder + " is waiting for the turn of " + describe(owner) + " on " + wait.key + "\\n");
  };
  const lockTarget = (backend, agentId, conversationId) => {
    const storageDir = storageDirOf(backend);
    const key = conversationKey(backend, agentId, conversationId);
    return storageDir && key ? { file: lockFile(storageDir, key), key } : null;
  };
  const throwIfAborted = (signal) => {
    if (signal?.aborted) throw abortError(signal);
  };
  const reenter = (file) => {
    held.set(file, held.get(file) + 1);
    return makeRelease(file);
  };
  /**
   * Waits until this process owns the conversation and returns the release function.
   * options.signal ends the wait with its abort reason; options.onWait(owner) runs once when the
   * conversation is first found busy.
   */
  async function acquire(backend, agentId, conversationId, holder, options = {}) {
    const target = lockTarget(backend, agentId, conversationId);
    if (!target) return () => {};
    const { file, key } = target;
    if (held.has(file)) return reenter(file);
    const wait = { file, holder, key, options, startedWaiting: Date.now(), waited: false, noticed: false };
    for (;;) {
      throwIfAborted(options.signal);
      const attempt = attemptTake(wait);
      if (attempt.failed) return () => {};
      if (attempt.owner === null) return takeOwnership(wait);
      if (!attempt.owner.retryNow) {
        noteBusy(wait, attempt.owner);
        await sleep(POLL_MS, options.signal);
      }
    }
  }
  function holder(backend, agentId, conversationId) {
    const target = lockTarget(backend, agentId, conversationId);
    if (!target) return null;
    const { file } = target;
    if (held.has(file)) return { pid: process.pid, holder: "this process" };
    const owner = readOwner(file);
    return ownerAlive(owner) ? owner : null;
  }
  // cancelConversation takes a conversation id, or an agent id for the default conversation.
  function heldElsewhere(backend, conversationOrAgentId) {
    for (const [agentId, conversationId] of [[null, conversationOrAgentId], [conversationOrAgentId, "default"]]) {
      if (conversationId === "default" && !agentId) continue;
      const owner = holder(backend, agentId, conversationId);
      if (owner && owner.pid !== process.pid) return true;
    }
    return false;
  }
  function assertNotHeldElsewhere(backend, conversationId, agentId) {
    const owner = holder(backend, agentId, conversationId);
    if (owner && owner.pid !== process.pid) {
      throw new Error("Conversation " + conversationId + " is busy with another active run (a turn in " + describe(owner) + ")");
    }
  }
  async function acquireForHeadless(backend, agentId, conversationId, options) {
    if (options.remote) return;
    if (options.bidirectional) {
      process.stderr.write("[lcp-turn-lock] WARN: --input-format stream-json sessions do not hold the conversation turn lock\\n");
      return;
    }
    await acquire(backend, agentId, conversationId, "letta -p pid " + process.pid);
  }
  return { acquire, holder, heldElsewhere, assertNotHeldElsewhere, acquireForHeadless };
})();
`;

const TURN_LOCK_WAIT_NOTICE =
  '"Waiting for the turn already running on this conversation (" + (lcpOwner.unreadable ? "unknown owner" : lcpOwner.holder ?? "pid " + lcpOwner.pid) + ") to finish."';

const TURN_LOCK_SITES = [
  {
    // App Server: the release slot, declared before the turn body's try.
    token:
      "  try {\n" +
      "    runtime.lastTerminalLoopErrorMessage = null;\n" +
      "    runtime.lastTerminalLoopErrorRunId = null;\n",
    replacement:
      `  let lcpReleaseTurnLock = () => {}; ${TURN_LOCK_MARK}\n` +
      "  try {\n" +
      "    runtime.lastTerminalLoopErrorMessage = null;\n" +
      "    runtime.lastTerminalLoopErrorRunId = null;\n",
  },
  {
    // App Server: wait inside the turn, after its lease began and its state went out, before the
    // turn touches the store. Stop aborts turnAbortSignal; the catch below finishes it as cancelled.
    token:
      "    telemetry.setCurrentAgentId(agentId ?? null);\n" +
      "    let turnToolContextId = null;\n" +
      "    const setup = await prepareListenerTurn({\n",
    replacement:
      "    telemetry.setCurrentAgentId(agentId ?? null);\n" +
      "    lcpReleaseTurnLock = await globalThis.__lcpTurnLock.acquire(getBackend(), agentId, conversationId, \"app-server pid \" + process.pid, {\n" +
      "      signal: turnAbortSignal,\n" +
      `      onWait: (lcpOwner) => emitStatusDelta(socket, runtime, { message: ${TURN_LOCK_WAIT_NOTICE}, level: "info", agentId: agentId ?? undefined, conversationId })\n` +
      `    }); ${TURN_LOCK_MARK}\n` +
      "    let turnToolContextId = null;\n" +
      "    const setup = await prepareListenerTurn({\n",
  },
  {
    // App Server: release in the turn's last finally, after its terminal and cleanup ran.
    token:
      "    } finally {\n" +
      "      releaseListenerTurnContext({ runtime, agentId, conversationId });\n" +
      "    }\n" +
      "    evictConversationRuntimeIfIdle(runtime);\n" +
      "  }\n" +
      "}\n" +
      "var init_turn = __esm(() => {\n",
    replacement:
      "    } finally {\n" +
      "      releaseListenerTurnContext({ runtime, agentId, conversationId });\n" +
      `      lcpReleaseTurnLock(); ${TURN_LOCK_MARK}\n` +
      "    }\n" +
      "    evictConversationRuntimeIfIdle(runtime);\n" +
      "  }\n" +
      "}\n" +
      "var init_turn = __esm(() => {\n",
  },
  {
    // letta -p: the conversation is known here and nothing has touched its transcript yet; the
    // stale-approval recovery and the turn come after.
    token:
      '  markMilestone("HEADLESS_CONVERSATION_READY");\n' +
      "  setConversationId(conversationId);\n",
    replacement:
      '  markMilestone("HEADLESS_CONVERSATION_READY");\n' +
      `  await globalThis.__lcpTurnLock.acquireForHeadless(backend3, agent.id, conversationId, { remote: usesRemoteEnvironment, bidirectional: isBidirectionalMode }); ${TURN_LOCK_MARK}\n` +
      "  setConversationId(conversationId);\n",
  },
  {
    // Any other path into a local turn refuses while another live process owns the conversation.
    token:
      "    if (activeRun && !isTerminalRun(activeRun)) {\n" +
      "      throw new Error(`Conversation ${conversationId} already has an active run (${activeRun.id})`);\n" +
      "    }\n",
    replacement:
      "    if (activeRun && !isTerminalRun(activeRun)) {\n" +
      "      throw new Error(`Conversation ${conversationId} already has an active run (${activeRun.id})`);\n" +
      "    }\n" +
      `    globalThis.__lcpTurnLock.assertNotHeldElsewhere(this, conversationId, turnAgentId); ${TURN_LOCK_MARK}\n`,
  },
  {
    // A cancel with no local run must not close the tool calls of another process's live turn
    // (Stop on an App Server turn that was still waiting for the lock lands here).
    token:
      "    } else {\n" +
      "      this.store.settleInterruptedToolCalls(conversationIdOrAgentId);\n" +
      "    }\n" +
      "    if (runId) {\n",
    replacement:
      `    } else if (!globalThis.__lcpTurnLock.heldElsewhere(this, conversationIdOrAgentId)) { ${TURN_LOCK_MARK}\n` +
      "      this.store.settleInterruptedToolCalls(conversationIdOrAgentId);\n" +
      "    }\n" +
      "    if (runId) {\n",
  },
  {
    // App Server sync (runtime_start, reconnect) reads a pending tool call in the transcript as a
    // stale approval to close. A call that belongs to another process's live turn is not stale:
    // treat that conversation as busy, the way the stock check treats a local active turn.
    token:
      "  const sameActiveScope = runtime.agentId === scope.agent_id && runtime.conversationId === scope.conversation_id;\n" +
      "  if (sameActiveScope && runtime.turnLifecycle.kind !== \"idle\") {\n" +
      "    clearRecoveredApprovalState(runtime);\n" +
      "    return;\n" +
      "  }\n",
    replacement:
      "  const sameActiveScope = runtime.agentId === scope.agent_id && runtime.conversationId === scope.conversation_id;\n" +
      "  if (sameActiveScope && runtime.turnLifecycle.kind !== \"idle\") {\n" +
      "    clearRecoveredApprovalState(runtime);\n" +
      "    return;\n" +
      "  }\n" +
      `  if (globalThis.__lcpTurnLock.holder(resolvedDeps.getBackend(), scope.agent_id, scope.conversation_id)) { ${TURN_LOCK_MARK}\n` +
      "    clearRecoveredApprovalState(runtime);\n" +
      "    return;\n" +
      "  }\n",
  },
  {
    // SendAgentMessage on the local backend: say the message is queued when the target is busy.
    token:
      "      (deps.spawnLocal ?? spawnDetached)(launcher.command, launcher.args);\n" +
      "      return {\n" +
      "        content: JSON.stringify({\n" +
      '          status: "spawned",\n' +
      "          conversation_id: destination.conversationId\n" +
      "        }),\n",
    replacement:
      `      const lcpBusyTurn = globalThis.__lcpTurnLock.holder(backend3, destination.agentId, destination.conversationId); ${TURN_LOCK_MARK}\n` +
      "      (deps.spawnLocal ?? spawnDetached)(launcher.command, launcher.args);\n" +
      "      return {\n" +
      "        content: JSON.stringify(lcpBusyTurn ? {\n" +
      '          status: "queued",\n' +
      "          conversation_id: destination.conversationId,\n" +
      '          delivery: "after_current_turn",\n' +
      "          note: \"The recipient had a turn in progress when you sent this. Your message waits and runs as one of the recipient's next turns, in order; don't resend it.\"\n" +
      "        } : {\n" +
      '          status: "spawned",\n' +
      "          conversation_id: destination.conversationId\n" +
      "        }),\n",
  },
];

function conversationTurnLock(state) {
  if (state.patched.includes(TURN_LOCK_MARK)) return;
  const counts = TURN_LOCK_SITES.map(({ token }) => countOccurrences(state.patched, token));
  if (!counts.every((count) => count === 1)) {
    state.skippedPatches += 1;
    if (state.warn) process.stderr.write(
      `[letta-code-patch] WARN: lcp-conversation-turn-lock anchors matched ${counts.join(",")} (expected 1 each) in ${state.path} — ` +
      `running without it; a message to a busy local agent starts a concurrent turn on its conversation\n`,
    );
    return;
  }
  for (const { token, replacement } of TURN_LOCK_SITES) state.patched = state.patched.replace(token, () => replacement);
  state.patched = injectHelperAfterShebang(state.patched, TURN_LOCK_HELPER);
  state.appliedPatches += 1;
}

// Patch order is significant: each step receives the previous step's output.
const PATCH_STEPS = [
  agentTaskStatus,
  retryOtid,
  agentLazyload,
  conversationListMtime,
  settle,
  thinkingSettings,
  thinkingRequest,
  thinkingChokepoint,
  modelSettings,
  localVisionInput,
  visionGate,
  downgradeImages,
  multimodalToolReturn,
  toolReturnContent,
  generateImageTool,
  systemPrompt,
  cloneMap,
  openaiApproval,
  assistantOtid,
  conversationLastRun,
  buildAgentInfo,
  reflectionControls,
  modelCatalog,
  contextCap,
  reflectionHide,
  subagentErrorLog,
  conversationUuid,
  uiMsgUuid,
  memoryLineEndings,
  sessionCwd,
  agentDescription,
  memoryDirToken,
  modelProfile,
  memoryDirExpand,
  skillDir,
  systemPromptTracking,
  conversationTurnLock,
];

/**
 * @param {string} raw
 * @param {string} path
 * @param {boolean} warn
 * @returns {{ source: string; appliedPatches: number; skippedPatches: number }}
 */
function patchLettaCodeSource(raw, path, warn) {
  const state = { patched: raw, appliedPatches: 0, skippedPatches: 0, path, warn };
  for (const step of PATCH_STEPS) step(state);
  return { source: state.patched, appliedPatches: state.appliedPatches, skippedPatches: state.skippedPatches };
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

function targetPath(url) {
  if (!TARGET_PATH || !url.startsWith("file://")) return null;
  let path;
  try { path = fileURLToPath(url); } catch { return null; }
  return samePath(path, TARGET_PATH) ? path : null;
}

// LETTA_CLI_PATH_REAL may use forward slashes or another drive-letter case on Windows,
// while fileURLToPath returns backslashes, so compare resolved paths (case-insensitive on win32).
function samePath(a, b) {
  const ra = resolvePath(a);
  const rb = resolvePath(b);
  return process.platform === "win32" ? ra.toLowerCase() === rb.toLowerCase() : ra === rb;
}

function sourceText(source) {
  if (typeof source === "string") return source;
  if (source instanceof Uint8Array) return Buffer.from(source).toString("utf8");
  return null;
}

/** @type {import("node:module").LoadHook} */
export async function load(url, context, nextLoad) {
  const result = await nextLoad(url, context);
  const path = targetPath(url);
  if (path === null) return result;
  const raw = sourceText(result.source);
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
