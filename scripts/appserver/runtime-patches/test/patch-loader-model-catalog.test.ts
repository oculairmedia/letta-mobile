import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { runInNewContext } from "node:vm";
import { patchLettaCodeSourceResultForTest } from "../letta-code-patch-loader.mjs";

const anthropicAnchor = '  ANTHROPIC_MODELS = flattenChatModelCatalog("anthropic", anthropic_default);';
const openaiAnchor = '  OPENAI_MODELS = flattenChatModelCatalog("openai", openai_default);';
const googleAnchor = '  GOOGLE_MODELS = flattenChatModelCatalog("google", google_default);';
const fixture = `${anthropicAnchor}\n${openaiAnchor}\n${googleAnchor}`;

type CatalogEntry = { id: string; name: string; contextWindow: number; maxTokens: number; api: string; provider: string };
type Catalog = Record<string, CatalogEntry>;

function evaluate(source: string, anthropic: Catalog, openai: Catalog, google: Catalog = {}): { anthropic: Catalog; openai: Catalog; google: Catalog } {
  const context = {
    ANTHROPIC_MODELS: {}, OPENAI_MODELS: {}, GOOGLE_MODELS: {},
    anthropic_default: anthropic, openai_default: openai, google_default: google,
    flattenChatModelCatalog: (_provider: string, catalog: Catalog): Catalog => structuredClone(catalog),
  };
  runInNewContext(source, context);
  return { anthropic: context.ANTHROPIC_MODELS, openai: context.OPENAI_MODELS, google: context.GOOGLE_MODELS };
}

const haiku: CatalogEntry = { id: "claude-haiku-4-5", name: "Haiku 4.5", contextWindow: 200000, maxTokens: 64000, api: "anthropic-messages", provider: "anthropic" };
const sol: CatalogEntry = { id: "gpt-6.1-sol", name: "Sol", contextWindow: 272000, maxTokens: 128000, api: "openai-responses", provider: "openai" };

function assertOpenaiWindows(result: ReturnType<typeof evaluate>, openai: Catalog) {
  for (const id of [...Object.keys(openai), "gpt-6.1-terra"]) {
    assert.equal(result.openai[id]?.contextWindow, 872000, id);
    assert.equal(result.openai[id]?.maxTokens, 128000, id);
    assert.equal(result.openai[id]?.provider, "openai", id);
  }
}

test("lcp-model-catalog adds verified native models and corrects GPT-6 windows", () => {
  const { source, appliedPatches } = patchLettaCodeSourceResultForTest(fixture);
  assert.equal(appliedPatches, 1);
  const openai = Object.fromEntries(["gpt-6-astra", "gpt-6-sol", "gpt-6-luna", "gpt-6.1-sol"].map(id => [id, { ...sol, id }]));
  const result = evaluate(source, { [haiku.id]: haiku }, { ...openai, spark: { ...sol, id: "spark", contextWindow: 128000 } });
  assert.equal(result.anthropic["claude-haiku-5-5"]?.contextWindow, 200000);
  assert.equal(result.anthropic["claude-haiku-5-5"]?.maxTokens, 65536);
  assert.equal(result.anthropic["claude-haiku-5-5"]?.api, "anthropic-messages");
  assertOpenaiWindows(result, openai);
  assert.equal(result.openai["gpt-6.1-terra"]?.name, "GPT-6.1 Terra");
  assert.equal(result.openai["gpt-6.1-luna"], undefined);
  assert.equal(result.openai["spark"]?.contextWindow, 128000);
  assert.equal(result.anthropic[haiku.id]?.maxTokens, 64000);
});

test("lcp-model-catalog adds GPT-6.2 Sol and Astra from the Sol template without renaming it", () => {
  const { source } = patchLettaCodeSourceResultForTest(fixture);
  const result = evaluate(source, { [haiku.id]: haiku }, { [sol.id]: sol });
  for (const [id, name] of [["gpt-6.2-sol", "GPT-6.2 Sol"], ["gpt-6.2-astra", "GPT-6.2 Astra"]]) {
    const entry = result.openai[id];
    assert.equal(entry.id, id);
    assert.equal(entry.name, name);
    assert.equal(entry.contextWindow, 872000, id);
    assert.equal(entry.maxTokens, 128000, id);
    assert.equal(entry.api, "openai-responses", id);
  }
  assert.equal(result.openai[sol.id].id, sol.id);
  assert.equal(result.openai[sol.id].name, sol.name);
});

test("lcp-model-catalog keeps an upstream GPT-6.2 entry when a future bundle ships one", () => {
  const { source } = patchLettaCodeSourceResultForTest(fixture);
  const upstream = { ...sol, id: "gpt-6.2-sol", name: "Upstream Sol", contextWindow: 999000 };
  const result = evaluate(source, { [haiku.id]: haiku }, { [sol.id]: sol, [upstream.id]: upstream });
  assert.equal(result.openai[upstream.id]?.name, "Upstream Sol");
  assert.equal(result.openai[upstream.id]?.contextWindow, 999000);
});

test("lcp-model-catalog skips both sites when either anchor drifts or duplicates", () => {
  for (const input of [anthropicAnchor, openaiAnchor, `${anthropicAnchor}\n${openaiAnchor}`, `${fixture}\n${anthropicAnchor}`, `${fixture}\n${openaiAnchor}`, `${fixture}\n${googleAnchor}`]) {
    const result = patchLettaCodeSourceResultForTest(input);
    assert.equal(result.source, input);
    assert.equal(result.appliedPatches, 0);
  }
});

test("lcp-model-catalog keeps native entries' metadata, clamps Haiku 5.5 to the route, and tolerates absent templates", () => {
  const { source } = patchLettaCodeSourceResultForTest(fixture);
  // 0.34.70 ships claude-haiku-5-5 at 1M context / 128k output; our Anthropic route serves 200k / 65,536.
  const futureHaiku = { ...haiku, id: "claude-haiku-5-5", name: "Upstream Haiku", contextWindow: 1000000, maxTokens: 128000, reasoning: true };
  // 0.34.70 ships gpt-6.1-terra at the 272k default; the route's verified maximum is 872k.
  const futureTerra = { ...sol, id: "gpt-6.1-terra", name: "Upstream Terra", contextWindow: 272000 };
  const result = evaluate(source, { [futureHaiku.id]: futureHaiku }, { [futureTerra.id]: futureTerra });
  assert.equal(result.anthropic[futureHaiku.id]?.contextWindow, 200000);
  assert.equal(result.anthropic[futureHaiku.id]?.maxTokens, 65536);
  assert.equal((result.anthropic[futureHaiku.id] as Record<string, unknown> | undefined)?.["reasoning"], true, "upstream metadata other than the limits is kept");
  assert.equal(Object.keys(result.anthropic).length, 1, "no template is needed when upstream ships the entry");
  assert.equal(result.openai[futureTerra.id]?.contextWindow, 872000);
  assert.equal(result.openai[futureTerra.id]?.name, "Upstream Terra");
  assert.equal(Object.keys(evaluate(source, {}, {}).openai).length, 0);
});

const flash38: CatalogEntry = { id: "gemini-3.8-flash", name: "Gemini 3.8 Flash", contextWindow: 1048576, maxTokens: 65536, api: "google-generative-ai", provider: "google" };
const gemini4 = [
  ["gemini-4-neon-medium", "Gemini 4 Neon (Medium)", 65536],
  ["gemini-4-neon-high", "Gemini 4 Neon (High)", 1048576],
  ["gemini-4-argon-medium", "Gemini 4 Argon (Medium)", 65536],
  ["gemini-4-argon-high", "Gemini 4 Argon (High)", 1048576],
  ["gemini-4-hydrogen-medium", "Gemini 4 Hydrogen (Medium)", 262144],
  ["gemini-4-hydrogen-xhigh", "Gemini 4 Hydrogen (XHigh)", 1048576],
] as const;

test("lcp-model-catalog advertises the Antigravity Gemini 4 models under the Google provider", () => {
  const { source } = patchLettaCodeSourceResultForTest(fixture);
  const result = evaluate(source, { [haiku.id]: haiku }, { [sol.id]: sol }, { [flash38.id]: flash38 });
  for (const [id, name, maxTokens] of gemini4) {
    const entry = result.google[id];
    assert.ok(entry, id);
    assert.equal(entry.id, id);
    assert.equal(entry.name, name);
    assert.equal(entry.contextWindow, 1048576, id);
    assert.equal(entry.maxTokens, maxTokens, id);
    assert.equal(entry.api, "google-generative-ai", id);
    assert.equal(entry.provider, "google", id);
  }
  assert.equal(result.google[flash38.id].name, flash38.name);
});

test("lcp-model-catalog keeps an upstream Gemini 4 entry and tolerates a missing template", () => {
  const { source } = patchLettaCodeSourceResultForTest(fixture);
  const upstream = { ...flash38, id: "gemini-4-argon-medium", name: "Upstream Argon", contextWindow: 2000000 };
  const result = evaluate(source, {}, {}, { [flash38.id]: flash38, [upstream.id]: upstream });
  assert.equal(result.google[upstream.id].name, "Upstream Argon");
  assert.equal(result.google[upstream.id].contextWindow, 2000000);
  assert.equal(Object.keys(evaluate(source, {}, {}, {}).google).length, 0);
});

function findDeployedBundle(): string | undefined {
  const versioned = existsSync("/root") ? readdirSync("/root")
    .filter(name => name.startsWith("letta-code-") && !name.endsWith(".tgz"))
    .sort((a, b) => b.localeCompare(a, undefined, { numeric: true }))
    .map(name => `/root/${name}/node_modules/@letta-ai/letta-code/letta.js`) : [];
  return [process.env["LETTA_CLI_PATH_REAL"], ...versioned]
    .find((candidate): candidate is string => Boolean(candidate && existsSync(candidate)));
}

function assertContextWindow(entry: unknown, expected: number) {
  assert.ok(entry && typeof entry === "object" && "contextWindow" in entry);
  assert.equal(entry.contextWindow, expected);
}

function assertDeployedCatalog(chunks: string[], context: object) {
  const actual: unknown = runInNewContext(`${chunks.join("\n")}\ninit_anthropic_models(); init_openai_models(); init_google_models(); ({ haiku: ANTHROPIC_MODELS["claude-haiku-5-5"], terra: OPENAI_MODELS["gpt-6.1-terra"], sol: OPENAI_MODELS["gpt-6.1-sol"], neon: GOOGLE_MODELS["gemini-4-neon-medium"] });`, context);
  assert.ok(actual && typeof actual === "object" && "haiku" in actual && "terra" in actual && "sol" in actual && "neon" in actual);
  for (const [key, expected] of [["haiku", 200000], ["terra", 872000], ["sol", 872000], ["neon", 1048576]] as const) {
    assertContextWindow(actual[key], expected);
  }
}

test("lcp-model-catalog evaluates the actual deployed provider data and flattening sites", t => {
  // Same deployed-bundle preference as patch-loader.test.ts; CI without a
  // deployed CLI still runs all fixture behavior and drift tests above.
  const path = findDeployedBundle();
  if (!path) { t.skip("no deployed letta-code bundle available"); return; }
  const raw = readFileSync(path, "utf8");
  const result = patchLettaCodeSourceResultForTest(raw);
  assert.equal(result.skippedPatches, 0, "all existing patches must still land");
  const context = {
    __esm: (initialize: () => void) => initialize,
    flattenChatModelCatalog: (_provider: string, groups: Record<string, Catalog>): Catalog =>
      Object.fromEntries(Object.values(groups).flatMap(group => Object.values(group)).map(model => [model.id, model])),
    flattenImageModelCatalog: () => ({}), flattenClassifierModelCatalog: () => ({}),
  };
  const chunks = ["anthropic", "openai", "google"].map(provider => {
    const start = result.source.indexOf(`var ${provider}_default;`);
    const end = result.source.indexOf("// node_modules/", result.source.indexOf(`var init_${provider}_models`, start));
    assert.ok(start > 0 && end > start);
    return result.source.slice(start, end);
  });
  assertDeployedCatalog(chunks, context);
});
