import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { runInNewContext } from "node:vm";
import { patchLettaCodeSourceResultForTest } from "../letta-code-patch-loader.mjs";

const anthropicAnchor = '  ANTHROPIC_MODELS = flattenChatModelCatalog("anthropic", anthropic_default);';
const openaiAnchor = '  OPENAI_MODELS = flattenChatModelCatalog("openai", openai_default);';
const fixture = `${anthropicAnchor}\n${openaiAnchor}`;

type CatalogEntry = { id: string; name: string; contextWindow: number; maxTokens: number; api: string; provider: string };
type Catalog = Record<string, CatalogEntry>;

function evaluate(source: string, anthropic: Catalog, openai: Catalog): { anthropic: Catalog; openai: Catalog } {
  const context = {
    ANTHROPIC_MODELS: {}, OPENAI_MODELS: {}, anthropic_default: anthropic, openai_default: openai,
    flattenChatModelCatalog: (_provider: string, catalog: Catalog): Catalog => structuredClone(catalog),
  };
  runInNewContext(source, context);
  return { anthropic: context.ANTHROPIC_MODELS, openai: context.OPENAI_MODELS };
}

const haiku: CatalogEntry = { id: "claude-haiku-4-5", name: "Haiku 4.5", contextWindow: 200000, maxTokens: 64000, api: "anthropic-messages", provider: "anthropic" };
const sol: CatalogEntry = { id: "gpt-6.1-sol", name: "Sol", contextWindow: 272000, maxTokens: 128000, api: "openai-responses", provider: "openai" };

test("lcp-model-catalog adds verified native models and corrects GPT-6 windows", () => {
  const { source, appliedPatches } = patchLettaCodeSourceResultForTest(fixture);
  assert.equal(appliedPatches, 1);
  const openai = Object.fromEntries(["gpt-6-astra", "gpt-6-sol", "gpt-6-luna", "gpt-6.1-sol"].map(id => [id, { ...sol, id }]));
  const result = evaluate(source, { [haiku.id]: haiku }, { ...openai, spark: { ...sol, id: "spark", contextWindow: 128000 } });
  assert.equal(result.anthropic["claude-haiku-5-5"]?.contextWindow, 200000);
  assert.equal(result.anthropic["claude-haiku-5-5"]?.maxTokens, 65536);
  assert.equal(result.anthropic["claude-haiku-5-5"]?.api, "anthropic-messages");
  for (const id of [...Object.keys(openai), "gpt-6.1-terra"]) {
    assert.equal(result.openai[id]?.contextWindow, 872000, id);
    assert.equal(result.openai[id]?.maxTokens, 128000, id);
    assert.equal(result.openai[id]?.provider, "openai", id);
  }
  assert.equal(result.openai["gpt-6.1-terra"]?.name, "GPT-6.1 Terra");
  assert.equal(result.openai["gpt-6.1-luna"], undefined);
  assert.equal(result.openai["spark"]?.contextWindow, 128000);
  assert.equal(result.anthropic[haiku.id]?.maxTokens, 64000);
});

test("lcp-model-catalog skips both sites when either anchor drifts or duplicates", () => {
  for (const input of [anthropicAnchor, openaiAnchor, `${fixture}\n${anthropicAnchor}`, `${fixture}\n${openaiAnchor}`]) {
    const result = patchLettaCodeSourceResultForTest(input);
    assert.equal(result.source, input);
    assert.equal(result.appliedPatches, 0);
  }
});

test("lcp-model-catalog preserves native future entries and tolerates absent templates", () => {
  const { source } = patchLettaCodeSourceResultForTest(fixture);
  const futureHaiku = { ...haiku, id: "claude-haiku-5-5", maxTokens: 70000 };
  const futureTerra = { ...sol, id: "gpt-6.1-terra", contextWindow: 900000 };
  const result = evaluate(source, { [futureHaiku.id]: futureHaiku }, { [futureTerra.id]: futureTerra });
  assert.equal(result.anthropic[futureHaiku.id]?.maxTokens, 70000);
  assert.equal(result.openai[futureTerra.id]?.contextWindow, 900000);
  assert.equal(Object.keys(evaluate(source, {}, {}).openai).length, 0);
});

test("lcp-model-catalog evaluates the actual deployed provider data and flattening sites", t => {
  // Same deployed-bundle preference as patch-loader.test.ts; CI without a
  // deployed CLI still runs all fixture behavior and drift tests above.
  const versioned = existsSync("/root") ? readdirSync("/root")
    .filter(name => name.startsWith("letta-code-") && !name.endsWith(".tgz"))
    .sort((a, b) => b.localeCompare(a, undefined, { numeric: true }))
    .map(name => `/root/${name}/node_modules/@letta-ai/letta-code/letta.js`) : [];
  const path = [process.env["LETTA_CLI_PATH_REAL"], ...versioned]
    .find((candidate): candidate is string => Boolean(candidate && existsSync(candidate)));
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
  const chunks = ["anthropic", "openai"].map(provider => {
    const start = result.source.indexOf(`var ${provider}_default;`);
    const end = result.source.indexOf("// node_modules/", result.source.indexOf(`var init_${provider}_models`, start));
    assert.ok(start > 0 && end > start);
    return result.source.slice(start, end);
  });
  const actual: unknown = runInNewContext(`${chunks.join("\n")}\ninit_anthropic_models(); init_openai_models(); ({ haiku: ANTHROPIC_MODELS["claude-haiku-5-5"], terra: OPENAI_MODELS["gpt-6.1-terra"], sol: OPENAI_MODELS["gpt-6.1-sol"] });`, context);
  assert.ok(actual && typeof actual === "object" && "haiku" in actual && "terra" in actual && "sol" in actual);
  for (const [key, expected] of [["haiku", 200000], ["terra", 872000], ["sol", 872000]] as const) {
    const entry: unknown = actual[key];
    assert.ok(entry && typeof entry === "object" && "contextWindow" in entry);
    assert.equal(entry.contextWindow, expected);
  }
});
