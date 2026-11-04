import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { runInNewContext } from "node:vm";

import { patchLettaCodeSourceResultForTest } from "../letta-code-patch-loader.mjs";

const BUNDLE = "/root/letta-code-0.34.14/node_modules/@letta-ai/letta-code/letta.js";

// Every item is a byte-exact 0.34.14 anchor. Keep this list in lock-step with
// the loader: this is the drift alarm before the runtime loader is allowed to
// rewrite any reflection-control site.
const REFLECTION_CONTROLS_ANCHORS = [
  `function applyPersistedAgentScopedSettings(base2, raw2) {\n  if (!raw2) {\n    return base2;\n  }\n  return {\n    trigger: normalizeTrigger(raw2.trigger, base2.trigger),\n    stepCount: normalizeStepCount(raw2.stepCount, base2.stepCount),\n    merge: normalizeMerge(raw2.merge, base2.merge),\n    mergeInstructions: normalizeMergeInstructions(raw2.mergeInstructions, base2.mergeInstructions)\n  };\n}\nfunction legacyModeToReflectionSettings`,
  `  const merge4 = normalizeMerge(settings3.merge, DEFAULT_REFLECTION_SETTINGS.merge);\n  const mergeInstructions = normalizeMergeInstructions(settings3.mergeInstructions, DEFAULT_REFLECTION_SETTINGS.mergeInstructions);\n  const legacyMode = reflectionSettingsToLegacyMode(settings3);`,
  `      reflectionSettingsByAgent: {\n        ...localSettings.reflectionSettingsByAgent ?? {},\n        [agentId]: {\n          trigger: settings3.trigger,\n          stepCount: settings3.stepCount,\n          merge: merge4,\n          mergeInstructions\n        }\n      }\n    }, workingDirectory);`,
  `      reflectionSettingsByAgent: {\n        ...globalSettings.reflectionSettingsByAgent ?? {},\n        [agentId]: {\n          trigger: settings3.trigger,\n          stepCount: settings3.stepCount,\n          merge: merge4,\n          mergeInstructions\n        }\n      }\n    });\n  }\n}\nvar DEFAULT_STEP_COUNT`,
  `    merge_instructions: settings3.mergeInstructions\n  };\n}\nfunction resolveReflectionSettingsScope`,
  `&& (settings3.merge_instructions === undefined || typeof settings3.merge_instructions === "string") && (c.scope === undefined`,
  `      mergeInstructions: parsed.settings.merge_instructions ?? currentSettings.mergeInstructions\n    }, {`,
  `async function launchReflectionSubagent(options, dependencies4 = {}) {`,
  `\n}\nvar AUTO_REFLECTION_DESCRIPTION = "Reflect on recent conversations", REFLECTION_AGENT_ID_WAIT_MS = 30000, reservedReflectionAgentIds, pendingReflectionLaunches, suppressedAutomaticReflections;`,
  `        } finally {\n          releaseReflectionLaunch(agentId);\n        }\n      }\n    });\n    releaseOnComplete = true;`,
];

function occurrences(source: string, token: string): number {
  return source.split(token).length - 1;
}

function section(source: string, start: string, end: string): string {
  const from = source.indexOf(start);
  assert.notEqual(from, -1, `missing start marker: ${start}`);
  const to = source.indexOf(end, from);
  assert.notEqual(to, -1, `missing end marker: ${end}`);
  return source.slice(from, to);
}

function readBundle(): string {
  assert.ok(existsSync(BUNDLE), `expected pinned 0.34.14 bundle at ${BUNDLE}`);
  return readFileSync(BUNDLE, "utf8");
}

test("lcp-reflection-controls anchors are unique and patch atomically on letta-code 0.34.14", () => {
  const raw = readBundle();
  for (const anchor of REFLECTION_CONTROLS_ANCHORS) {
    assert.equal(occurrences(raw, anchor), 1, `anchor must match exactly once: ${anchor.slice(0, 72)}`);
  }

  const patched = patchLettaCodeSourceResultForTest(raw);
  assert.equal(patched.skippedPatches, 0, "no current patch may fail open on the pinned bundle");
  assert.match(patched.source, /globalThis\.__lcpReflectionOutcomes = globalThis\.__lcpReflectionOutcomes \|\| new Map\(\)/);
  assert.match(patched.source, /model: settings3\.model \?\? null,/);
  assert.match(patched.source, /focus_instructions: settings3\.focusInstructions \?\? "",/);
  assert.match(patched.source, /last_outcome: globalThis\.__lcpReflectionLastOutcome\(agentId\)/);
  assert.match(patched.source, /async function __lcpLaunchReflectionSubagentInner\(/);
  assert.match(patched.source, /async function launchReflectionSubagent\(options, dependencies4 = \{\}\)/);
  assert.match(patched.source, /status: success \? "completed" : "failed"/);

  // A single drift must prevent every reflection-control rewrite; do not risk a
  // schema change without the corresponding persistence and launch behavior.
  const setHandlerAnchor = REFLECTION_CONTROLS_ANCHORS[5];
  assert.ok(setHandlerAnchor, "test anchor list must include the set handler");
  const driftedRaw = raw.replace(setHandlerAnchor, "// lcp reflection set-handler drift");
  const drifted = patchLettaCodeSourceResultForTest(driftedRaw);
  assert.equal(drifted.appliedPatches, patched.appliedPatches - 1);
  assert.equal(drifted.skippedPatches, patched.skippedPatches + 1);
  assert.doesNotMatch(drifted.source, /__lcpReflectionOutcomes/);
});

test("lcp-reflection-controls leaves the fully patched letta.js bundle parseable", () => {
  const { source } = patchLettaCodeSourceResultForTest(readBundle());
  const dir = mkdtempSync(join(tmpdir(), "lcp-reflection-controls-"));
  const candidate = join(dir, "letta.mjs");
  try {
    writeFileSync(candidate, source);
    const checked = spawnSync(process.execPath, ["--check", candidate], { encoding: "utf8" });
    assert.equal(checked.status, 0, `${checked.stdout}\n${checked.stderr}`);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("lcp-reflection-controls settings round-trip and wrapper behavior execute from patched bundle", async () => {
  const { source } = patchLettaCodeSourceResultForTest(readBundle());
  const helper = section(source, "globalThis.__lcpReflectionOutcomes =", "\n\":\" //#");
  const memorySettings = section(source, "function isValidStepCount", "// src/agent/memory-runtime.ts");
  const responseAndHandler = section(source, "function toReflectionSettingsResponse", "function handleSettingsProtocolCommand");
  const validator = section(source, "function isSetReflectionSettingsCommand", "function isGetExperimentsCommand");
  const wrapper = section(source, "async function launchReflectionSubagent(options, dependencies4 = {}) {", "var AUTO_REFLECTION_DESCRIPTION");

  const state = {
    global: {
      memoryReminderInterval: "auto-compaction",
      reflectionSettingsByAgent: {},
    } as Record<string, unknown>,
    local: {} as Record<string, unknown>,
  };
  const sent: Array<Record<string, unknown>> = [];
  const context = {
    Date,
    Map,
    Number,
    __esm: <T>(initialize: () => T) => initialize,
    process: { cwd: () => "/fixture" },
    settingsManager: {
      getSettings: () => state.global,
      getLocalProjectSettings: () => state.local,
      loadLocalProjectSettings: async () => {},
      updateSettings: (next: Record<string, unknown>) => { state.global = { ...state.global, ...next }; },
      updateLocalProjectSettings: (next: Record<string, unknown>) => { state.local = { ...state.local, ...next }; },
    },
    isAgentRuntimeScope: (runtime: unknown) => Boolean(runtime && typeof runtime === "object" && "agent_id" in runtime),
    getConversationWorkingDirectory: () => "/fixture",
    emitDeviceStatusUpdate: () => {},
  };
  const api = runInNewContext(`${helper}\n${memorySettings}\n${responseAndHandler}\n${validator}\nDEFAULT_REFLECTION_SETTINGS = { trigger: "compaction-event", stepCount: DEFAULT_STEP_COUNT, merge: "auto", mergeInstructions: "" };\n({ getReflectionSettings, handleReflectionSettingsCommand, isSetReflectionSettingsCommand, persistReflectionSettingsForAgent, toReflectionSettingsResponse });`, context) as {
    getReflectionSettings(agentId: string, directory: string): Record<string, unknown>;
    handleReflectionSettingsCommand(parsed: Record<string, unknown>, socket: unknown, listener: unknown, safeSocketSend: (...args: unknown[]) => void): Promise<boolean>;
    isSetReflectionSettingsCommand(value: unknown): boolean;
    persistReflectionSettingsForAgent(agentId: string, settings: Record<string, unknown>, options: Record<string, unknown>): Promise<void>;
    toReflectionSettingsResponse(agentId: string, directory: string): Record<string, unknown>;
  };
  const base = {
    type: "set_reflection_settings",
    request_id: "request-1",
    runtime: { agent_id: "agent-1", conversation_id: "conversation-1" },
    settings: { trigger: "step-count", step_count: 5, merge: "auto" },
  };

  assert.equal(api.isSetReflectionSettingsCommand({ ...base, settings: { ...base.settings, model: 123 } }), false);
  assert.equal(api.isSetReflectionSettingsCommand({ ...base, settings: { ...base.settings, model: null, focus_instructions: "focus" } }), true);

  const send = (...args: unknown[]) => {
    const response = args[1];
    assert.ok(response && typeof response === "object", "settings command must send a response object");
    sent.push(response as Record<string, unknown>);
  };
  await api.handleReflectionSettingsCommand({
    ...base,
    settings: { ...base.settings, model: "gpt-reflection", focus_instructions: "Keep durable mobile facts." },
  }, {}, {}, send);
  let response = sent.at(-1)?.reflection_settings as Record<string, unknown>;
  assert.equal(response.model, "gpt-reflection");
  assert.equal(response.focus_instructions, "Keep durable mobile facts.");
  assert.equal(response.last_outcome, null);
  const persisted = (state.global.reflectionSettingsByAgent as Record<string, Record<string, unknown>>)["agent-1"];
  assert.ok(persisted, "per-agent reflection settings must be persisted");
  assert.equal(persisted.trigger, "step-count");
  assert.equal(persisted.stepCount, 5);
  assert.equal(persisted.merge, "auto");
  assert.equal(persisted.mergeInstructions, "");
  assert.equal(persisted.model, "gpt-reflection");
  assert.equal(persisted.focusInstructions, "Keep durable mobile facts.");

  // Omitted fields retain their saved values; JSON null explicitly returns to inherit.
  await api.handleReflectionSettingsCommand(base, {}, {}, send);
  response = sent.at(-1)?.reflection_settings as Record<string, unknown>;
  assert.equal(response.model, "gpt-reflection");
  assert.equal(response.focus_instructions, "Keep durable mobile facts.");
  await api.handleReflectionSettingsCommand({ ...base, settings: { ...base.settings, model: null, focus_instructions: "" } }, {}, {}, send);
  response = sent.at(-1)?.reflection_settings as Record<string, unknown>;
  assert.equal(response.model, null);
  assert.equal(response.focus_instructions, "");

  await api.handleReflectionSettingsCommand({
    ...base,
    settings: { ...base.settings, model: "gpt-reflection", focus_instructions: "Prioritize durable mobile facts." },
  }, {}, {}, send);
  let received: Record<string, unknown> | undefined;
  (context as Record<string, unknown>).__lcpLaunchReflectionSubagentInner = async (options: Record<string, unknown>) => {
    received = { ...options };
    return { launched: false, reason: "parent_dirty" };
  };
  const launchApi = runInNewContext(`${wrapper}\n({ launchReflectionSubagent, outcomes: globalThis.__lcpReflectionOutcomes });`, context) as {
    launchReflectionSubagent(options: Record<string, unknown>): Promise<{ launched: boolean; reason?: string }>;
    outcomes: Map<string, Record<string, unknown>>;
  };
  const launch = await launchApi.launchReflectionSubagent({ agentId: "agent-1", triggerSource: "manual", instruction: "Built-in reflection instruction." });
  assert.deepEqual(launch, { launched: false, reason: "parent_dirty" });
  assert.equal(received?.model, "gpt-reflection");
  assert.match(String(received?.instruction), /Built-in reflection instruction\.[\s\S]*Prioritize durable mobile facts\./);
  const outcome = launchApi.outcomes.get("agent-1");
  assert.equal(outcome?.status, "skipped");
  assert.equal(outcome?.reason, "parent_dirty");
  assert.equal(outcome?.trigger_source, "manual");
  assert.equal(outcome?.model, "gpt-reflection");
  assert.ok(Number.isFinite(Date.parse(String(outcome?.at))));
  assert.equal(api.toReflectionSettingsResponse("agent-1", "/fixture").last_outcome, outcome);

  // The TUI's /sleeptime selector only sends these four settings. Its save
  // must retain the per-agent model and focus set by the mobile controls.
  const sleeptimeSettings = {
    trigger: "compaction-event",
    stepCount: 9,
    merge: "explicit",
    mergeInstructions: "Merge only when the reflection is useful.",
  };
  await api.persistReflectionSettingsForAgent("agent-1", sleeptimeSettings, { persistLocalProject: false });
  let persistedAfterSleeptime = (state.global.reflectionSettingsByAgent as Record<string, Record<string, unknown>>)["agent-1"];
  assert.ok(persistedAfterSleeptime, "/sleeptime save must retain an agent entry");
  assert.equal(persistedAfterSleeptime.model, "gpt-reflection");
  assert.equal(persistedAfterSleeptime.focusInstructions, "Prioritize durable mobile facts.");

  // Null is deliberate: it clears an inherited per-agent model while omitted
  // fields above keep their previously persisted values.
  await api.persistReflectionSettingsForAgent("agent-1", { ...sleeptimeSettings, model: null }, { persistLocalProject: false });
  persistedAfterSleeptime = (state.global.reflectionSettingsByAgent as Record<string, Record<string, unknown>>)["agent-1"];
  assert.equal(persistedAfterSleeptime.model, null);
  assert.equal(persistedAfterSleeptime.focusInstructions, "Prioritize durable mobile facts.");
});
