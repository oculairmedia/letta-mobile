import { resolveDeployedLettaBundle } from "./bundle-fixtures.ts";
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath, pathToFileURL } from "node:url";

import { patchLettaCodeSourceResultForTest } from "../letta-code-patch-loader.mjs";

// The user's context cap on a profile-bound conversation. `/context-limit` (alias
// `/set-max-context`) stores the cap in the same field every model switch fills with a catalog
// default, and the profile resolver drops that field. lcp-model-profile therefore also records the
// user's cap in its own replicated field, lcp_user_context_window_limit, and the resolver reapplies
// only that one: the turn runs with the smallest of the user's cap, the binding's override and the
// bound model's own window.
//
// The behavioural tests drive the real code in a child process: the mod is activated and registers
// the profile provider for real, the cap is set with letta-code's own applySetMaxContext against the
// real local store, and the turn's settings come from the real effectiveAgentForConversation,
// resolveAvailableLocalModelForTurn and resolvePiModelForAgent. Only the models runtime is fake.

const LOADER_URL = new URL("../letta-code-patch-loader.mjs", import.meta.url).href;
const MOD_URL = new URL("../mods/profile-provider.mjs", import.meta.url).href;
const FIELD = "lcp_user_context_window_limit";

const EXPORT_HOOK_SOURCE = `
import { pathToFileURL } from "node:url";
const TARGET = process.env.LETTA_CLI_PATH_REAL ?? "";
const ENTRY = "await init_src5().then(() => exports_src2);";
export async function load(url, context, nextLoad) {
  const result = await nextLoad(url, context);
  if (!TARGET || url !== pathToFileURL(TARGET).href) return result;
  const source = typeof result.source === "string" ? result.source : Buffer.from(result.source).toString("utf8");
  if (source.split(ENTRY).length !== 2) throw new Error("user-cap export hook: CLI entry not found exactly once");
  return {
    ...result,
    source: source.replace(ENTRY, () =>
      "init_settings_manager();\\ninit_backend2();\\ninit_max_context();\\ninit_pi_model_factory();\\ninit_local_model_config();\\n" +
      "init_fake_headless_backend();\\ninit_pi_provider_mod_registry();\\ninit_model_catalog();\\n" +
      "export { settingsManager, getBackend, applySetMaxContext, effectiveAgentForConversation, resolveAvailableLocalModelForTurn, resolvePiModelForAgent, registerPiProvider, models2 };"),
    shortCircuit: true,
  };
}
`;
const EXPORT_HOOK_URL = `data:text/javascript,${encodeURIComponent(EXPORT_HOOK_SOURCE)}`;

const CATALOG = { main: { name: "Main", contextWindow: 200000, maxTokens: 16000 } };
const PLAIN_BINDING = { main: { handle: "openai/gpt-node" } };
const bindingWithOverride = (tokens: number) => ({ main: { handle: "openai/gpt-node", model_settings: { context_window_limit: tokens } } });

function deployedBundleOrSkip(t: { skip: (reason: string) => void }): string | null {
  const bundlePath = resolveDeployedLettaBundle();
  if (!bundlePath) {
    t.skip("no deployed letta-code bundle (>= 0.27) on this host");
    return null;
  }
  return bundlePath;
}

function inherited(name: string): boolean {
  return name !== "NODE_OPTIONS" && !name.startsWith("LETTA_") && name !== "MEMORY_DIR";
}

// Shared by every child: the mod registered for real, the store, and one turn's resolution.
const PRELUDE = `
  await b.settingsManager.initialize();
  const activate = (await import(${JSON.stringify(MOD_URL)})).default;
  activate({ capabilities: { providers: true }, providers: { register: (name, config) => { b.registerPiProvider(name, config); return () => {}; } } });
  const store = b.getBackend().store;
  const setCap = (agentId, conversationId, args) => b.applySetMaxContext({ agentId, conversationId, args });
  const fakeRuntime = (window) => ({
    isRuntimeManagedProvider: () => true,
    isBuiltInLocalOllamaProvider: () => false,
    getStoredCredential: async () => undefined,
    resolveTurn: async (runtimeProvider, modelId) => ({
      model: { id: modelId, name: modelId, provider: runtimeProvider, api: "openai-completions", baseUrl: "http://127.0.0.1:9/fake", reasoning: false, input: ["text"], cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, contextWindow: window, maxTokens: 100 },
      auth: { auth: { apiKey: "fake-key" } },
    }),
  });
  const turn = async (agentId, conversationId, window = 128000) => {
    const agent = b.effectiveAgentForConversation(store.retrieveExecutionAgentRecord(conversationId, agentId), store.retrieveConversation(conversationId, agentId));
    const modelsRuntime = fakeRuntime(window);
    const local = await b.resolveAvailableLocalModelForTurn({ model: agent.model, modelSettings: agent.model_settings, modelsRuntime });
    const resolved = await b.resolvePiModelForAgent(local.model, local.modelSettings, { modelsRuntime, localProviderAuthStorageDir: workDir });
    return { model: resolved.model.id, contextWindow: resolved.model.contextWindow, settings: agent.model_settings };
  };
`;

function runChild(workDir: string, binding: Record<string, unknown>, body: string, extraEnv: Record<string, string> = {}) {
  const bundle = resolveDeployedLettaBundle();
  assert.ok(bundle, "caller must skip when no deployed bundle exists");
  const env: Record<string, string> = {};
  for (const [name, value] of Object.entries(process.env)) {
    if (value !== undefined && inherited(name)) env[name] = value;
  }
  const catalogFile = join(workDir, "profiles.json");
  const bindingFile = join(workDir, `bindings-${Math.random().toString(36).slice(2)}.json`);
  writeFileSync(catalogFile, JSON.stringify(CATALOG));
  writeFileSync(bindingFile, JSON.stringify(binding));
  const register = `import { register } from "node:module";\n` +
    `register(${JSON.stringify(LOADER_URL)});\n` +
    `register(${JSON.stringify(EXPORT_HOOK_URL)});\n`;
  const script = `
    import { pathToFileURL } from "node:url";
    const workDir = process.env.LCP_WORK_DIR;
    const b = await import(pathToFileURL(process.env.LETTA_CLI_PATH_REAL).href);
    const out = {};
    ${PRELUDE}
    ${body}
    process.stdout.write("\\nRESULT " + JSON.stringify(out) + "\\n");
    process.exit(0);
  `;
  const child = spawnSync(process.execPath, [
    "--import", `data:text/javascript,${encodeURIComponent(register)}`,
    "--input-type=module", "--eval", script,
  ], {
    encoding: "utf8",
    env: {
      ...env,
      HOME: join(workDir, "home"),
      USERPROFILE: join(workDir, "home"),
      LETTA_LOCAL_BACKEND_EXPERIMENTAL: "1",
      LETTA_LOCAL_BACKEND_DIR: join(workDir, "store"),
      LETTA_CONFIG_DIR: join(workDir, "config"),
      LETTA_MODEL_PROFILES: catalogFile,
      LETTA_MODEL_PROFILE_BINDINGS: bindingFile,
      LETTA_CLI_PATH_REAL: resolve(bundle),
      LCP_WORK_DIR: workDir,
      ...extraEnv,
    },
    timeout: 120_000,
  });
  assert.equal(child.status, 0, child.stderr);
  const line = child.stdout.split("\n").find((entry) => entry.startsWith("RESULT "));
  assert.ok(line, `child printed no RESULT line: ${child.stdout}\n${child.stderr}`);
  return JSON.parse(line.slice("RESULT ".length));
}

function workDirFor(t: { after: (fn: () => void) => void }): string {
  const workDir = mkdtempSync(join(tmpdir(), "lcp-profile-user-cap-"));
  t.after(() => rmSync(workDir, { recursive: true, force: true }));
  return workDir;
}

/** Creates an agent on `model` and one named conversation that inherits it. */
const SEED = (model: string) => `
  const agent = store.createAgent({ name: "capped", model: ${JSON.stringify(model)}, tags: ["memfs:disabled"] });
  const conversation = store.createConversation({ agent_id: agent.id });
  out.agentId = agent.id;
  out.conversationId = conversation.id;
  out.storedModel = store.retrieveAgentRecord(agent.id).model;
`;

function readAgentRecord(workDir: string, agentId: string): Record<string, any> {
  const dir = join(workDir, "store", "agents");
  for (const name of readdirSync(dir)) {
    const record = JSON.parse(readFileSync(join(dir, name), "utf8"));
    if (record.id === agentId) return record;
  }
  throw new Error(`no agent record ${agentId}`);
}

function readConversationRecord(workDir: string, conversationId: string): Record<string, any> {
  const dir = join(workDir, "store", "conversations");
  for (const name of readdirSync(dir)) {
    const record = JSON.parse(readFileSync(join(dir, name, "conversation.json"), "utf8"));
    if (record.id === conversationId) return record;
  }
  throw new Error(`no conversation record ${conversationId}`);
}

test("profile user cap: a 32k cap on a profile-bound conversation reaches the turn (binding without an override)", (t) => {
  if (!deployedBundleOrSkip(t)) return;
  const workDir = workDirFor(t);
  const out = runChild(workDir, PLAIN_BINDING, `
    ${SEED("profile/main")}
    out.before = await turn(agent.id, conversation.id);
    await setCap(agent.id, conversation.id, "32000");
    out.after = await turn(agent.id, conversation.id);
  `);
  assert.equal(out.storedModel, "profile/main", "the profile handle persists as written");
  assert.equal(out.before.model, "gpt-node");
  assert.equal(out.before.contextWindow, 128000, "no user cap: the bound model's own window");
  assert.equal(out.after.model, "gpt-node");
  assert.equal(out.after.contextWindow, 32000, "the user's cap applies to the profile turn");
});

test("profile user cap: the smallest of the user's cap, the binding override and the bound model's window wins", (t) => {
  if (!deployedBundleOrSkip(t)) return;
  const body = `
    ${SEED("profile/main")}
    out.uncapped = await turn(agent.id, conversation.id);
    await setCap(agent.id, conversation.id, "32000");
    out.capped = await turn(agent.id, conversation.id);
    out.smallModel = await turn(agent.id, conversation.id, 20000);
  `;
  const below = runChild(workDirFor(t), bindingWithOverride(16000), body);
  assert.equal(below.uncapped.contextWindow, 16000, "no user cap: the binding override, as today");
  assert.equal(below.capped.contextWindow, 16000, "a binding override below the user's cap wins");

  const above = runChild(workDirFor(t), bindingWithOverride(64000), body);
  assert.equal(above.uncapped.contextWindow, 64000, "no user cap: the binding override, as today");
  assert.equal(above.capped.contextWindow, 32000, "the user's cap below the binding override wins");
  assert.equal(above.smallModel.contextWindow, 20000, "the bound model's own window below both wins");
});

test("profile user cap: the field survives a reload from disk for a named and the default conversation, and a reset removes it", (t) => {
  if (!deployedBundleOrSkip(t)) return;
  const workDir = workDirFor(t);
  const first = runChild(workDir, PLAIN_BINDING, `
    ${SEED("profile/main")}
    await setCap(agent.id, conversation.id, "32000");
    await setCap(agent.id, "default", "40000");
  `);
  const agentRecord = readAgentRecord(workDir, first.agentId);
  const conversationRecord = readConversationRecord(workDir, first.conversationId);
  assert.equal(conversationRecord[FIELD], 32000, "the named conversation's record carries the user's cap");
  assert.equal(conversationRecord.context_window_limit, 32000, "and the stock field is written as before");
  assert.equal(agentRecord[FIELD], 40000, "the default conversation's cap is in the agent record");
  assert.equal(agentRecord.model_settings.context_window_limit, 40000, "and the stock field is written as before");

  // A fresh process (another node with a plain copy of the store): load, save unrelated changes, read back.
  const second = runChild(workDir, PLAIN_BINDING, `
    store.updateAgent(process.env.LCP_AGENT_ID, { name: "renamed" });
    store.updateConversation(process.env.LCP_CONVERSATION_ID, { summary: "renamed" });
    out.named = await turn(process.env.LCP_AGENT_ID, process.env.LCP_CONVERSATION_ID);
    out.default = await turn(process.env.LCP_AGENT_ID, "default");
  `, { LCP_AGENT_ID: first.agentId, LCP_CONVERSATION_ID: first.conversationId });
  assert.equal(readAgentRecord(workDir, first.agentId)[FIELD], 40000, "the agent record normalizer keeps the field across load and save");
  assert.equal(readConversationRecord(workDir, first.conversationId)[FIELD], 32000, "the conversation record keeps the field across load and save");
  assert.equal(second.named.contextWindow, 32000, "the named conversation's cap applies on the other node");
  assert.equal(second.default.contextWindow, 40000, "the default conversation's cap applies on the other node");

  // Reset (`/context-limit` with no value) goes back to the catalog default and removes the field.
  const third = runChild(workDir, PLAIN_BINDING, `
    b.models2.push({ id: "profile-main", handle: "profile/main", label: "Main", description: "", updateArgs: { context_window: 200000 } });
    await setCap(process.env.LCP_AGENT_ID, process.env.LCP_CONVERSATION_ID, "");
    await setCap(process.env.LCP_AGENT_ID, "default", "");
    out.named = await turn(process.env.LCP_AGENT_ID, process.env.LCP_CONVERSATION_ID);
    out.default = await turn(process.env.LCP_AGENT_ID, "default");
  `, { LCP_AGENT_ID: first.agentId, LCP_CONVERSATION_ID: first.conversationId });
  assert.equal(Object.hasOwn(readAgentRecord(workDir, first.agentId), FIELD), false, "a reset removes the agent's field");
  assert.equal(Object.hasOwn(readConversationRecord(workDir, first.conversationId), FIELD), false, "a reset removes the conversation's field");
  assert.equal(third.named.contextWindow, 128000, "after a reset the bound model's window applies again");
  assert.equal(third.default.contextWindow, 128000);
});

test("profile user cap: a model switch that replaces the stored cap drops the user's cap, one that keeps it keeps it", (t) => {
  if (!deployedBundleOrSkip(t)) return;
  const workDir = workDirFor(t);
  const out = runChild(workDir, PLAIN_BINDING, `
    ${SEED("profile/main")}
    await setCap(agent.id, conversation.id, "32000");
    store.updateConversation(conversation.id, { model: "profile/main", context_window_limit: 32000 });
    out.kept = store.retrieveConversation(conversation.id, agent.id)[${JSON.stringify(FIELD)}] ?? null;
    out.keptTurn = await turn(agent.id, conversation.id);
    store.updateConversation(conversation.id, { model: "profile/main", context_window_limit: 200000 });
    out.replaced = store.retrieveConversation(conversation.id, agent.id)[${JSON.stringify(FIELD)}] ?? null;
    out.replacedTurn = await turn(agent.id, conversation.id);
  `);
  assert.equal(out.kept, 32000, "a switch that preserves the stored cap (same variant) keeps the user's cap");
  assert.equal(out.keptTurn.contextWindow, 32000);
  assert.equal(out.replaced, null, "a switch that stores another window drops the user's cap, as it does today");
  assert.equal(out.replacedTurn.contextWindow, 128000);
});

test("profile user cap: a cap a peer without this patch overwrote is ignored", (t) => {
  if (!deployedBundleOrSkip(t)) return;
  const workDir = workDirFor(t);
  const first = runChild(workDir, PLAIN_BINDING, `
    ${SEED("profile/main")}
    await setCap(agent.id, conversation.id, "32000");
  `);
  // A stock node switched the model: it rewrote context_window_limit and kept the unknown field.
  const dir = join(workDir, "store", "conversations");
  for (const name of readdirSync(dir)) {
    const file = join(dir, name, "conversation.json");
    const record = JSON.parse(readFileSync(file, "utf8"));
    if (record.id === first.conversationId) writeFileSync(file, JSON.stringify({ ...record, context_window_limit: 200000 }, null, 2) + "\n");
  }
  assert.equal(readConversationRecord(workDir, first.conversationId)[FIELD], 32000, "the stale field is still on disk");
  const out = runChild(workDir, PLAIN_BINDING, `
    out.turn = await turn(process.env.LCP_AGENT_ID, process.env.LCP_CONVERSATION_ID);
  `, { LCP_AGENT_ID: first.agentId, LCP_CONVERSATION_ID: first.conversationId });
  assert.equal(out.turn.contextWindow, 128000, "the stale cap does not apply: the bound model's window does");
});

test("profile user cap: a non-profile conversation's turn settings are unchanged", (t) => {
  if (!deployedBundleOrSkip(t)) return;
  const workDir = workDirFor(t);
  const out = runChild(workDir, PLAIN_BINDING, `
    ${SEED("lmstudio/plain")}
    await setCap(agent.id, conversation.id, "32000");
    out.turn = await turn(agent.id, conversation.id);
  `);
  assert.equal(out.storedModel, "lmstudio/plain");
  assert.equal(out.turn.model, "plain");
  assert.equal(out.turn.contextWindow, 32000, "the stock cap applies as before");
  assert.equal(Object.hasOwn(out.turn.settings, FIELD), false, "nothing is added to a non-profile turn's settings");
  assert.equal(out.turn.settings.context_window_limit, 32000);
});

// --- Anchors -------------------------------------------------------------------------------------

const USER_CAP_ANCHORS = [
  "function effectiveAgentForConversation(agent, conversation) {\n  const conversationRecord = conversation;\n",
  "  const contextWindow = configuredContextWindow && isLocalOllama ? Math.min(configuredContextWindow, hookedModel.contextWindow) : configuredContextWindow;\n",
  "    model: model2,\n    model_settings: modelSettings,\n    ...hidden !== undefined ? { hidden } : {},\n",
  "      model_settings: nextModelSettings\n    };\n    this.agents.set(agentId, updated);\n",
  "  if (isStringArray2(bodyRecord.tags)) {\n    next.tags = bodyRecord.tags;\n  }\n  return next;\n}",
  "    const updatedAgent = await backend3.updateAgent(params.agentId, {\n      context_window_limit: contextWindow\n    });",
  "  const updatedConversation = await backend3.updateConversation(params.conversationId, {\n    context_window_limit: contextWindow\n  });",
];
const USER_CAP_MARKER = "// lcp-profile-user-cap";
const count = (source: string, token: string) => source.split(token).length - 1;

test("profile user cap: every anchor matches once in the deployed bundle and is part of lcp-model-profile", (t) => {
  const bundlePath = deployedBundleOrSkip(t);
  if (!bundlePath) return;
  const bundle = readFileSync(bundlePath, "utf8");
  for (const anchor of USER_CAP_ANCHORS) assert.equal(count(bundle, anchor), 1, `anchor is unique: ${anchor.slice(0, 80)}`);
  const baseline = patchLettaCodeSourceResultForTest(bundle);
  assert.equal(baseline.skippedPatches, 0);
  assert.ok(count(baseline.source, USER_CAP_MARKER) >= USER_CAP_ANCHORS.length, "every user-cap site lands");

  for (const anchor of USER_CAP_ANCHORS) {
    const drifted = patchLettaCodeSourceResultForTest(bundle.replace(anchor, () => anchor.replace(/[a-z]/, (letter) => letter.toUpperCase())));
    assert.equal(drifted.appliedPatches, baseline.appliedPatches - 1, `drift in one site skips exactly one patch: ${anchor.slice(0, 60)}`);
    assert.equal(drifted.skippedPatches, 1);
    assert.equal(count(drifted.source, USER_CAP_MARKER), 0, "no user-cap site lands on its own");
    assert.equal(count(drifted.source, "// lcp-model-profile"), 0, "and the profile resolver hook does not land without them");
  }
});

test("profile user cap: a missed anchor is reported at load time", () => {
  const target = fileURLToPath(new URL("./model-profile-user-cap-fixture.mjs", import.meta.url));
  const result = spawnSync(process.execPath, ["--input-type=module", "--eval", `
    const { load } = await import(${JSON.stringify(LOADER_URL)});
    await load(${JSON.stringify(pathToFileURL(target).href)}, {}, async () => ({ format: "module", source: "export const drifted = true;" }));
  `], { encoding: "utf8", env: { ...process.env, NODE_OPTIONS: "", LETTA_CLI_PATH_REAL: target }, timeout: 10_000 });
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stderr, /WARN: lcp-model-profile anchors matched 0(,0){7} \(expected 1 each\)/);
});
