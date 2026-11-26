import { resolveDeployedLettaBundle } from "./bundle-fixtures.ts";
import { test } from "node:test";
import assert from "node:assert/strict";
import { copyFileSync, cpSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { createServer as createNetServer, type AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { spawn, spawnSync } from "node:child_process";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath, pathToFileURL } from "node:url";

import { patchLettaCodeSourceResultForTest } from "../letta-code-patch-loader.mjs";

// lcp-model-profile plus the node-local profile-provider mod. The static tests check the anchor
// against the deployed bundle. The behavioural tests drive the real, runtime-patched
// resolvePiModelForAgent in a child process with the mod activated under a node binding, and a
// fake models runtime that records which provider and model a turn would reach.

const LOADER_URL = new URL("../letta-code-patch-loader.mjs", import.meta.url).href;
const MOD_URL = new URL("../mods/profile-provider.mjs", import.meta.url).href;

const STOCK =
  "async function resolvePiModelForAgent(modelHandle, modelSettings = {}, options = {}) {\n" +
  "  const concreteModelHandle = ";
const PATCHED_MARKER = "// lcp-model-profile";

// Exports resolvePiModelForAgent instead of running the CLI entry.
const EXPORT_HOOK_SOURCE = `
import { pathToFileURL } from "node:url";
const TARGET = process.env.LETTA_CLI_PATH_REAL ?? "";
const ENTRY = "await init_src5().then(() => exports_src2);";
export async function load(url, context, nextLoad) {
  const result = await nextLoad(url, context);
  if (!TARGET || url !== pathToFileURL(TARGET).href) return result;
  const source = typeof result.source === "string" ? result.source : Buffer.from(result.source).toString("utf8");
  if (source.split(ENTRY).length !== 2) throw new Error("model-profile export hook: CLI entry not found exactly once");
  return {
    ...result,
    source: source.replace(ENTRY, () => "init_pi_model_factory();\\nexport { resolvePiModelForAgent };"),
    shortCircuit: true,
  };
}
`;
const EXPORT_HOOK_URL = `data:text/javascript,${encodeURIComponent(EXPORT_HOOK_SOURCE)}`;

const CATALOG = {
  main: { name: "Main", contextWindow: 200000, maxTokens: 16000 },
  fast: { name: "Fast", contextWindow: 64000, maxTokens: 4096 },
  spare: { name: "Spare (bound nowhere)" },
};
const BINDING_A = {
  main: { handle: "openai/gpt-node-a" },
  fast: { handle: "lmstudio/a-fast", model_settings: { context_window_limit: 500 } },
};
const BINDING_B = {
  main: { handle: "anthropic/claude-node-b" },
  fast: { handle: "lmstudio/b-fast" },
};
// What a profile handle's record holds after an App Server create/update: write-time defaults
// for the profile provider plus profile-level intent.
const STORED_SETTINGS = { provider_type: "openai", context_window_limit: 64000, max_tokens: 4096, temperature: 0.3 };

const count = (source: string, token: string) => source.split(token).length - 1;

function deployedBundleOrSkip(t: { skip: (reason: string) => void }): string | null {
  const bundlePath = resolveDeployedLettaBundle();
  if (!bundlePath) {
    t.skip("no deployed letta-code bundle (>= 0.27) on this host");
    return null;
  }
  return bundlePath;
}

test("lcp-model-profile: applies exactly once at the head of resolvePiModelForAgent", (t) => {
  const bundlePath = deployedBundleOrSkip(t);
  if (!bundlePath) return;
  const bundle = readFileSync(bundlePath, "utf8");
  assert.equal(count(bundle, STOCK), 1, "stock anchor is unique in the bundle");
  assert.equal(count(bundle, PATCHED_MARKER), 0);
  const result = patchLettaCodeSourceResultForTest(bundle);
  assert.equal(result.skippedPatches, 0, "no patch may fail-open against the deployed bundle");
  assert.equal(count(result.source, STOCK), 0, "stock form is gone after patching");
  assert.equal(count(result.source, PATCHED_MARKER), 1, "patched form lands exactly once");
  const head = result.source.indexOf("async function resolvePiModelForAgent(");
  assert.ok(result.source.indexOf(PATCHED_MARKER, head) - head < 200, "the hook runs before any provider resolution");
});

test("lcp-model-profile: drift skips only this patch", (t) => {
  const bundlePath = deployedBundleOrSkip(t);
  if (!bundlePath) return;
  const bundle = readFileSync(bundlePath, "utf8");
  const baseline = patchLettaCodeSourceResultForTest(bundle);
  const drifted = bundle.replace(STOCK, () => STOCK.replace("options = {}", "options = {}, extra"));
  assert.notEqual(drifted, bundle, "the drift applies");
  const result = patchLettaCodeSourceResultForTest(drifted);
  assert.equal(result.appliedPatches, baseline.appliedPatches - 1, "exactly one patch stops applying");
  assert.equal(result.skippedPatches, 1, "and is counted as skipped");
  assert.equal(count(result.source, PATCHED_MARKER), 0, "nothing of it lands");

  const duplicated = `${bundle}\n/*\n${STOCK}\n*/\n`;
  const dup = patchLettaCodeSourceResultForTest(duplicated);
  assert.equal(dup.appliedPatches, baseline.appliedPatches - 1, "a duplicated anchor is refused, not patched twice");
  assert.equal(count(dup.source, PATCHED_MARKER), 0);
});

/** Whether a parent environment variable may reach the child: not the loader hook and no LETTA_ settings. */
function inherited(name: string): boolean {
  return name !== "NODE_OPTIONS" && !name.startsWith("LETTA_");
}

type Resolution = { handle: string; ok: boolean; provider?: string; runtimeProvider?: string; model?: string; contextWindow?: number; maxTokens?: number; error?: string };

/**
 * Resolves each handle through the patched resolvePiModelForAgent in a fresh child process.
 * `binding` null means the mod is not activated (no resolver installed on this node).
 */
function resolveInChild(
  t: { after: (fn: () => void) => void },
  handles: string[],
  binding: Record<string, unknown> | null,
): Resolution[] {
  const bundle = resolveDeployedLettaBundle();
  assert.ok(bundle, "caller must skip when no deployed bundle exists");
  const workDir = mkdtempSync(join(tmpdir(), "lcp-model-profile-"));
  t.after(() => rmSync(workDir, { recursive: true, force: true }));
  const catalogFile = join(workDir, "profiles.json");
  const bindingFile = join(workDir, "bindings.json");
  writeFileSync(catalogFile, JSON.stringify(CATALOG));
  writeFileSync(bindingFile, JSON.stringify(binding ?? {}));
  const env: Record<string, string> = {};
  for (const [name, value] of Object.entries(process.env)) {
    if (value !== undefined && inherited(name)) env[name] = value;
  }
  const register = `import { register } from "node:module";\n` +
    `register(${JSON.stringify(LOADER_URL)});\n` +
    `register(${JSON.stringify(EXPORT_HOOK_URL)});\n`;
  const script = `
    import { pathToFileURL } from "node:url";
    const { resolvePiModelForAgent } = await import(pathToFileURL(process.env.LETTA_CLI_PATH_REAL).href);
    const registered = [];
    if (process.env.LCP_ACTIVATE_MOD === "1") {
      const activate = (await import(${JSON.stringify(MOD_URL)})).default;
      activate({ capabilities: { providers: true }, providers: { register: (name, config) => { registered.push({ name, models: config.models.map((m) => m.id) }); return () => {}; } } });
    }
    const calls = [];
    const modelsRuntime = {
      isRuntimeManagedProvider: () => true,
      isBuiltInLocalOllamaProvider: () => false,
      getStoredCredential: async () => undefined,
      resolveTurn: async (runtimeProvider, modelId) => {
        calls.push({ runtimeProvider, modelId });
        return { model: { id: modelId, name: modelId, provider: runtimeProvider, api: "openai-completions", baseUrl: "http://127.0.0.1:9/fake", reasoning: false, input: ["text"], cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, contextWindow: 1000, maxTokens: 100 }, auth: { auth: { apiKey: "fake-key" } } };
      },
    };
    const out = { registered, resolutions: [] };
    for (const handle of JSON.parse(process.env.LCP_HANDLES)) {
      calls.length = 0;
      try {
        const resolved = await resolvePiModelForAgent(handle, ${JSON.stringify(STORED_SETTINGS)}, { modelsRuntime, localProviderAuthStorageDir: process.env.LCP_WORK_DIR });
        out.resolutions.push({ handle, ok: true, provider: resolved.provider, runtimeProvider: calls[0]?.runtimeProvider, model: resolved.model.id, contextWindow: resolved.model.contextWindow, maxTokens: resolved.model.maxTokens });
      } catch (error) {
        out.resolutions.push({ handle, ok: false, error: error instanceof Error ? error.message : String(error), reachedRuntime: calls.length > 0 });
      }
    }
    process.stdout.write("\\nRESULT " + JSON.stringify(out) + "\\n");
  `;
  const child = spawnSync(process.execPath, [
    "--import", `data:text/javascript,${encodeURIComponent(register)}`,
    "--input-type=module", "--eval", script,
  ], {
    encoding: "utf8",
    env: {
      ...env,
      LETTA_CLI_PATH_REAL: resolve(bundle),
      LCP_WORK_DIR: workDir,
      LCP_HANDLES: JSON.stringify(handles),
      LCP_ACTIVATE_MOD: binding === null ? "0" : "1",
      LETTA_MODEL_PROFILES: catalogFile,
      LETTA_MODEL_PROFILE_BINDINGS: bindingFile,
    },
    timeout: 120_000,
  });
  assert.equal(child.status, 0, child.stderr);
  const line = child.stdout.split("\n").find((entry) => entry.startsWith("RESULT "));
  assert.ok(line, `child printed no RESULT line: ${child.stdout}\n${child.stderr}`);
  const out = JSON.parse(line.slice("RESULT ".length));
  if (binding !== null) {
    assert.deepEqual(out.registered, [{ name: "profile", models: ["main", "fast", "spare"] }], "the mod registers the shared catalog");
  }
  return out.resolutions;
}

test("lcp-model-profile: one stored handle resolves to each node's own binding", (t) => {
  if (!deployedBundleOrSkip(t)) return;
  const handles = ["profile/main", "profile/fast"];
  const [aMain, aFast] = resolveInChild(t, handles, BINDING_A);
  const [bMain, bFast] = resolveInChild(t, handles, BINDING_B);

  assert.deepEqual([aMain.ok, aFast.ok, bMain.ok, bFast.ok], [true, true, true, true], JSON.stringify([aMain, aFast, bMain, bFast]));
  assert.equal(aMain.provider, "openai");
  assert.equal(aMain.model, "gpt-node-a", "the real model id reaches the provider, not the profile id");
  assert.equal(bMain.provider, "anthropic");
  assert.equal(bMain.model, "claude-node-b");
  assert.equal(aFast.model, "a-fast");
  assert.equal(bFast.model, "b-fast");
  assert.equal(aFast.runtimeProvider, bFast.runtimeProvider, "same provider family, different bound model");

  // Stored provider-bound defaults (context_window_limit 64000, max_tokens 4096) are dropped,
  // so the real model's own limits apply; a node binding may override them.
  assert.equal(bFast.contextWindow, 1000);
  assert.equal(bFast.maxTokens, 100);
  assert.equal(aFast.contextWindow, 500, "the node binding's model_settings overlay applies");
});

test("lcp-model-profile: an unbound profile throws and never reaches a provider", (t) => {
  if (!deployedBundleOrSkip(t)) return;
  const [spare, unknown] = resolveInChild(t, ["profile/spare", "profile/nope"], BINDING_A) as (Resolution & { reachedRuntime?: boolean })[];
  for (const result of [spare, unknown]) {
    assert.equal(result.ok, false, `${result.handle} must not resolve`);
    assert.match(result.error ?? "", /is not bound to a provider handle on this node/);
    assert.match(result.error ?? "", /LETTA_MODEL_PROFILE_BINDINGS/);
    assert.equal(result.reachedRuntime, false, "no provider was asked for the turn");
  }
});

test("lcp-model-profile: with no resolver installed, profile handles throw and others pass through", (t) => {
  if (!deployedBundleOrSkip(t)) return;
  const [profile, plain] = resolveInChild(t, ["profile/main", "lmstudio/plain"], null) as (Resolution & { reachedRuntime?: boolean })[];
  assert.equal(profile.ok, false);
  assert.match(profile.error ?? "", /profile-provider mod is not installed on this node/);
  assert.equal(profile.reachedRuntime, false);
  assert.equal(plain.ok, true, plain.error);
  assert.equal(plain.model, "plain", "provider handles are untouched by the hook");
});

test("profile-provider mod: refuses a binding that points at another profile", (t) => {
  if (!deployedBundleOrSkip(t)) return;
  const [loop] = resolveInChild(t, ["profile/main"], { main: { handle: "profile/fast" }, fast: { handle: "lmstudio/x" } });
  assert.equal(loop.ok, false);
  assert.match(loop.error ?? "", /is not bound to a provider handle/);
});

// ---------------------------------------------------------------------------------------------
// Contract: one store, two nodes, two bindings. The agent and conversation are created over the
// App Server on one node, the store is copied as plain files to two nodes with different
// bindings, each node runs a turn on the agent default and on the conversation override, and
// the replicated records must stay byte-equal while each turn reaches that node's own upstream.
// Fake OpenAI-compatible upstreams, no real credentials.

const REGISTER_PATH = fileURLToPath(new URL("../letta-code-patch-register.mjs", import.meta.url));
const MOD_PATH = fileURLToPath(MOD_URL);

type UpstreamCall = { tag: string; model: string; kind: string | null; auth: string | null };

/** A fake OpenAI-compatible (LM Studio shaped) upstream that records which model each turn asked for. */
async function startFakeUpstream(tag: string, calls: UpstreamCall[], models: string[]): Promise<{ port: number; close: () => Promise<void> }> {
  const server = createServer((req, res) => {
    let body = "";
    req.on("data", (chunk) => { body += chunk; });
    req.on("end", () => {
      const json = (status: number, value: unknown) => {
        res.writeHead(status, { "content-type": "application/json" });
        res.end(JSON.stringify(value));
      };
      if (req.url === "/v1/models") return json(200, { object: "list", data: models.map((id) => ({ id, object: "model", owned_by: tag })) });
      if (req.url === "/api/v0/models") {
        return json(200, { object: "list", data: models.map((id) => ({ id, object: "model", type: "llm", state: "loaded", capabilities: [] })) });
      }
      if (req.url !== "/v1/chat/completions") return json(404, { error: { type: "not_found", message: `Unknown route: ${req.url}` } });
      let parsed: { model?: string; stream?: boolean } = {};
      try { parsed = JSON.parse(body); } catch { /* empty body */ }
      const model = parsed.model ?? "";
      calls.push({ tag, model, kind: (req.headers["x-letta-request-kind"] as string | undefined) ?? null, auth: req.headers.authorization ?? null });
      const id = `chatcmpl-${tag}-${calls.length}`;
      const text = `ANSWERED-BY-${tag}-${model}`;
      if (!parsed.stream) {
        return json(200, { id, object: "chat.completion", created: 0, model, choices: [{ index: 0, message: { role: "assistant", content: text }, finish_reason: "stop" }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } });
      }
      res.writeHead(200, { "content-type": "text/event-stream" });
      const chunk = (delta: unknown, finish: string | null) =>
        res.write(`data: ${JSON.stringify({ id, object: "chat.completion.chunk", created: 0, model, choices: [{ index: 0, delta, finish_reason: finish }] })}\n\n`);
      chunk({ role: "assistant", content: text }, null);
      chunk({}, "stop");
      res.write(`data: ${JSON.stringify({ id, object: "chat.completion.chunk", created: 0, model, choices: [], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } })}\n\n`);
      res.write("data: [DONE]\n\n");
      res.end();
    });
  });
  await new Promise<void>((done) => server.listen(0, "127.0.0.1", done));
  const port = (server.address() as AddressInfo).port;
  return { port, close: () => new Promise<void>((done) => server.close(() => done())) };
}

async function freePort(): Promise<number> {
  const server = createNetServer();
  await new Promise<void>((done) => server.listen(0, "127.0.0.1", done));
  const port = (server.address() as AddressInfo).port;
  await new Promise<void>((done) => server.close(() => done()));
  return port;
}

type NodeDirs = { root: string; work: string; backend: string; env: Record<string, string> };

/** Lays out one node: isolated home, node-local mod and binding, and an LM Studio provider on its own upstream. */
function layOutNode(root: string, name: string, binding: Record<string, unknown>, upstreamPort: number, catalogFile: string): NodeDirs {
  const dir = join(root, name);
  const [home, config, backend, work, tmp] = ["home", "config", "backend", "work", "tmp"].map((sub) => join(dir, sub));
  for (const path of [home, join(config, "mods"), join(backend, "providers"), work, tmp]) mkdirSync(path, { recursive: true });
  copyFileSync(MOD_PATH, join(config, "mods", "profile-provider.mjs"));
  const bindingFile = join(dir, "bindings.json");
  writeFileSync(bindingFile, JSON.stringify(binding));
  writeFileSync(join(backend, "providers", "auth.json"), JSON.stringify({
    version: 1,
    providers: {
      "lc-lmstudio": {
        id: "local-provider-lc-lmstudio", name: "lc-lmstudio", provider_type: "lmstudio", provider_category: "byok",
        auth: { type: "api", key: `lm-studio-${name}` }, base_url: `http://127.0.0.1:${upstreamPort}/v1`,
      },
    },
  }));
  const env: Record<string, string> = {};
  for (const [key, value] of Object.entries(process.env)) {
    if (value !== undefined && inherited(key) && !/_API_KEY$/.test(key)) env[key] = value;
  }
  Object.assign(env, {
    HOME: home, USERPROFILE: home, TMPDIR: tmp, TMP: tmp, TEMP: tmp, NO_COLOR: "1",
    LETTA_CONFIG_DIR: config,
    LETTA_LOCAL_BACKEND_EXPERIMENTAL: "1", LETTA_LOCAL_BACKEND_DIR: backend, LETTA_LOCAL_BACKEND_EXECUTOR: "pi",
    LETTA_MODEL_PROFILES: catalogFile, LETTA_MODEL_PROFILE_BINDINGS: bindingFile,
    LETTA_DISABLE_TELEMETRY: "1", LETTA_TELEMETRY_DISABLED: "1",
  });
  return { root: dir, work, backend, env };
}

/** Starts the node's App Server with the runtime patches, the way the host runs it. */
async function startAppServer(node: NodeDirs, bundle: string): Promise<{ url: string; stop: () => void; stderr: () => string }> {
  const port = await freePort();
  const child = spawn(process.execPath, [
    "--import", pathToFileURL(REGISTER_PATH).href, bundle, "app-server", "--listen", `ws://127.0.0.1:${port}`,
  ], { cwd: node.work, env: { ...node.env, LETTA_CLI_PATH_REAL: bundle }, stdio: ["ignore", "pipe", "pipe"] });
  let stdout = "";
  let stderr = "";
  child.stdout.on("data", (chunk) => { stdout += chunk; });
  child.stderr.on("data", (chunk) => { stderr += chunk; });
  const stop = () => {
    if (child.exitCode !== null) return;
    if (process.platform === "win32" && child.pid) spawnSync("taskkill", ["/F", "/T", "/PID", String(child.pid)], { stdio: "ignore" });
    else child.kill("SIGKILL");
  };
  const deadline = Date.now() + 90_000;
  while (!stdout.includes("Listening")) {
    if (child.exitCode !== null || Date.now() > deadline) {
      stop();
      throw new Error(`App Server did not start (exit ${child.exitCode}):\n${stdout}\n${stderr}`);
    }
    await delay(200);
  }
  return { url: `ws://127.0.0.1:${port}/ws`, stop, stderr: () => stderr };
}

type Step = { send: Record<string, unknown>; until: string };

/** Sends each command in turn and waits for a frame containing its `until` text. Returns every frame. */
async function runSteps(url: string, steps: Step[], timeoutMs = 60_000): Promise<string[]> {
  const frames: string[] = [];
  const ws = new WebSocket(url);
  try {
    await new Promise<void>((done, fail) => {
      const timer = setTimeout(() => fail(new Error(`timed out at step ${index}:\n${frames.join("\n")}`)), timeoutMs);
      let index = 0;
      const next = () => {
        if (index >= steps.length) { clearTimeout(timer); done(); return; }
        ws.send(JSON.stringify(steps[index].send));
      };
      ws.addEventListener("open", next);
      ws.addEventListener("error", () => { clearTimeout(timer); fail(new Error(`WebSocket error:\n${frames.join("\n")}`)); });
      ws.addEventListener("message", (event) => {
        const text = typeof event.data === "string" ? event.data : Buffer.from(event.data as ArrayBuffer).toString("utf8");
        frames.push(text);
        if (index < steps.length && text.includes(steps[index].until)) { index += 1; next(); }
      });
    });
  } finally {
    ws.close();
  }
  return frames;
}

function frameWith(frames: string[], text: string): Record<string, any> {
  const frame = frames.find((entry) => entry.includes(text));
  assert.ok(frame, `no frame with ${text}:\n${frames.join("\n")}`);
  return JSON.parse(frame);
}

function turnSteps(agentId: string, conversationId: string, tag: string): Step[] {
  const runtime = { agent_id: agentId, conversation_id: conversationId };
  return [
    { send: { type: "runtime_start", request_id: `rs-${tag}`, ...runtime }, until: `"request_id":"rs-${tag}"` },
    {
      send: {
        type: "input", request_id: `in-${tag}`, runtime,
        payload: { kind: "create_message", messages: [{ role: "user", content: `turn ${tag}`, client_message_id: `cm-${tag}` }] },
      },
      until: "\"stop_reason\"",
    },
  ];
}

/** conversation.json of every conversation directory, keyed by directory name. */
function readConversations(backend: string): Record<string, Record<string, unknown>> {
  const dir = join(backend, "conversations");
  const out: Record<string, Record<string, unknown>> = {};
  for (const entry of readdirSync(dir)) out[entry] = JSON.parse(readFileSync(join(dir, entry, "conversation.json"), "utf8"));
  return out;
}

function readAgentFile(backend: string, agentId: string): string {
  const dir = join(backend, "agents");
  const files = readdirSync(dir);
  assert.equal(files.length, 1, `one agent record: ${files.join(", ")}`);
  const raw = readFileSync(join(dir, files[0]), "utf8");
  assert.equal(JSON.parse(raw).id, agentId);
  return raw;
}

test("contract: agent and conversation records stay byte-equal under two bindings", { timeout: 600_000 }, async (t) => {
  const bundlePath = deployedBundleOrSkip(t);
  if (!bundlePath) return;
  const bundle = resolve(bundlePath);
  const root = mkdtempSync(join(tmpdir(), "lcp-model-profile-contract-"));
  const servers: { stop: () => void }[] = [];
  const upstreams: { close: () => Promise<void> }[] = [];
  t.after(async () => {
    for (const server of servers) server.stop();
    for (const upstream of upstreams) await upstream.close();
    await delay(500);
    rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 500 });
  });
  const catalogFile = join(root, "profiles.json");
  writeFileSync(catalogFile, JSON.stringify(CATALOG));
  const calls: UpstreamCall[] = [];
  const upstreamA = await startFakeUpstream("nodeA", calls, ["a-main", "a-fast"]);
  const upstreamB = await startFakeUpstream("nodeB", calls, ["b-main", "b-fast"]);
  upstreams.push(upstreamA, upstreamB);
  const bindingA = { main: { handle: "lmstudio/a-main" }, fast: { handle: "lmstudio/a-fast" } };
  const bindingB = { main: { handle: "lmstudio/b-main" }, fast: { handle: "lmstudio/b-fast" } };

  // Seed: create the agent on profile/main and override one conversation to profile/fast, over the App Server.
  const seed = layOutNode(root, "seed", bindingA, upstreamA.port, catalogFile);
  const seedServer = await startAppServer(seed, bundle);
  servers.push(seedServer);
  const created = await runSteps(seedServer.url, [
    { send: { type: "runtime_start", request_id: "rs-create", create_agent: { body: { name: "h1b", model: "profile/main" } }, create_conversation: {} }, until: "\"request_id\":\"rs-create\"" },
  ]);
  const createResponse = frameWith(created, "\"request_id\":\"rs-create\"");
  assert.equal(createResponse.success, true, JSON.stringify(createResponse));
  const agentId: string = createResponse.runtime.agent_id;
  const conversationId: string = createResponse.runtime.conversation_id;
  const updated = await runSteps(seedServer.url, [
    { send: { type: "runtime_start", request_id: "rs-override", agent_id: agentId, conversation_id: conversationId }, until: "\"request_id\":\"rs-override\"" },
    { send: { type: "update_model", request_id: "um-override", runtime: { agent_id: agentId, conversation_id: conversationId }, payload: { model_handle: "profile/fast" } }, until: "\"request_id\":\"um-override\"" },
  ]);
  assert.equal(frameWith(updated, "\"request_id\":\"um-override\"").success, true, updated.join("\n"));
  seedServer.stop();
  assert.equal(calls.length, 0, "creating and overriding asks no upstream");

  const baselineAgent = JSON.parse(readAgentFile(seed.backend, agentId));
  assert.equal(baselineAgent.model, "profile/main");
  const baselineConversations = readConversations(seed.backend);
  const baselineOverride = Object.values(baselineConversations).find((conversation) => conversation.id === conversationId);
  assert.ok(baselineOverride, "the override conversation was persisted");
  assert.equal(baselineOverride.model, "profile/fast");

  // Each node: plain copy of the store, its own binding and upstream, a turn on each conversation.
  const nodes: Record<string, NodeDirs> = {};
  for (const [name, binding, upstream] of [["nodeA", bindingA, upstreamA], ["nodeB", bindingB, upstreamB]] as const) {
    const node = layOutNode(root, name, binding, upstream.port, catalogFile);
    for (const part of ["agents", "conversations", "memfs"]) cpSync(join(seed.backend, part), join(node.backend, part), { recursive: true });
    const server = await startAppServer(node, bundle);
    servers.push(server);
    for (const [conversation, tag] of [["default", `${name}-default`], [conversationId, `${name}-override`]]) {
      const frames = await runSteps(server.url, turnSteps(agentId, conversation, tag));
      assert.match(frames.join("\n"), /"stop_reason":"end_turn"/, `${tag} completed:\n${frames.join("\n")}\n${server.stderr()}`);
    }
    server.stop();
    nodes[name] = node;
  }

  const turns = calls.filter((call) => call.kind === null || call.kind === "turn").map((call) => `${call.tag}:${call.model}`);
  assert.deepEqual(turns, ["nodeA:a-main", "nodeA:a-fast", "nodeB:b-main", "nodeB:b-fast"], "each turn reached its node's bound upstream and real model");

  const agentA = readAgentFile(nodes.nodeA.backend, agentId);
  const agentB = readAgentFile(nodes.nodeB.backend, agentId);
  assert.equal(agentA, agentB, "agent records are byte-equal across the two nodes");
  assert.equal(JSON.parse(agentA).model, "profile/main");
  assert.deepEqual(JSON.parse(agentA).model_settings, baselineAgent.model_settings);

  const conversationsA = readConversations(nodes.nodeA.backend);
  const conversationsB = readConversations(nodes.nodeB.backend);
  assert.deepEqual(Object.keys(conversationsA).sort(), Object.keys(conversationsB).sort());
  // Each node ran its own turn, so the timestamps and the ids of the new messages differ: that is
  // history, which replication merges. Everything else, the model fields included, must match.
  const volatile = ({ updated_at: _u, last_message_at: _l, in_context_message_ids: ids, ...rest }: Record<string, unknown>) =>
    ({ ...rest, in_context_message_count: Array.isArray(ids) ? ids.length : ids });
  for (const key of Object.keys(conversationsA)) {
    assert.deepEqual(volatile(conversationsA[key]), volatile(conversationsB[key]), `${key}: equal apart from this node's turn history`);
    const baseline = baselineConversations[key];
    if (!baseline) continue;
    for (const field of ["model", "model_settings", "context_window_limit"]) {
      assert.deepEqual(conversationsA[key][field], baseline[field], `${key}.${field} is unchanged by the turns`);
    }
  }
  for (const record of [agentA, ...Object.values(conversationsA).map((conversation) => JSON.stringify(conversation))]) {
    assert.doesNotMatch(record, /lmstudio|a-main|a-fast|b-main|b-fast|127\.0\.0\.1/, "no node binding leaks into replicated state");
  }
});
