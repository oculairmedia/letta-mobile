import { resolveLettaBundle, resolveDeployedLettaBundle } from "./bundle-fixtures.ts";
import { test } from "node:test";
import assert from "node:assert/strict";
import { appendFileSync, existsSync, mkdtempSync, readFileSync, readdirSync, rmSync } from "node:fs";
import { dirname, join } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath, pathToFileURL } from "node:url";

import {
  patchLettaCodeSourceForTest,
  patchLettaCodeSourceResultForTest,
  CONVERSATION_LAST_RUN_HELPER_DEFINITION,
} from "../letta-code-patch-loader.mjs";
// Frozen copy of admin-shim lib/model-catalog.ts VISION_MODEL_PATTERNS at 6354a12. The shim (and its
// LETTA_VISION_MODELS export) is retired, so the loader's fallback regex must cover every entry here.
const VISION_MODEL_PATTERNS: readonly string[] = [
  "llava", "vision", "opus", "sonnet", "haiku", "claude", "fable", "gpt-", "gpt5",
  "gemini", "grok", "minimax", "qwen-vl", "qwen2-vl", "qwen2.5-vl",
];

type ThinkingPayload = {
  model?: string;
  thinking?: { type: string; budget_tokens?: number };
};

type ModelSettingsPayload = {
  provider_type?: string;
  thinking?: { type: string; budget_tokens?: number };
};

declare global {
  var __lcpFixThinking: ((payload: ThinkingPayload) => ThinkingPayload) | undefined;
  var __lcpFixModelSettings:
    | ((payload: ModelSettingsPayload) => ModelSettingsPayload)
    | undefined;
  var __lcpFixLocalVisionInput:
    | ((providerName: string, modelId: string, input: string[]) => string[])
    | undefined;
  var __lcpCoerceToolReturnContent: ((value: unknown) => unknown) | undefined;
  var __lcpAddGenerateImageTool:
    | ((
        toolDefinitions: Record<string, unknown>,
        defineToolFn: (cfg: { schema: unknown; description: unknown; impl: unknown }) => unknown,
      ) => Record<string, unknown>)
    | undefined;
}

function readInjectedThinkingHelper(): unknown {
  return (globalThis as typeof globalThis & { __lcpFixThinking?: unknown }).__lcpFixThinking;
}

function readInjectedModelSettingsHelper(): unknown {
  return (globalThis as typeof globalThis & { __lcpFixModelSettings?: unknown })
    .__lcpFixModelSettings;
}

function readInjectedLocalVisionInputHelper(): unknown {
  return (globalThis as typeof globalThis & { __lcpFixLocalVisionInput?: unknown })
    .__lcpFixLocalVisionInput;
}

function readInjectedToolReturnContentHelper(): unknown {
  return (globalThis as typeof globalThis & { __lcpCoerceToolReturnContent?: unknown })
    .__lcpCoerceToolReturnContent;
}

test("patch-loader normalizes thinking requests and model_settings inheritance", () => {
  const source = [
    "#!/usr/bin/env node",
    "this.store.settleInterruptedToolCalls(conversationId, {",
    "        reason: TURN_DID_NOT_COMPLETE",
    "      });",
    "thinking = {",
    "        type: updateArgs?.enable_reasoner === false ? \"disabled\" : \"enabled\",",
    "        ...typeof updateArgs?.max_reasoning_tokens === \"number\" && {",
    "          budget_tokens: updateArgs.max_reasoning_tokens",
    "        }",
    "      };",
    "  if (options3?.metadata) {",
    "    const userId = options3.metadata.user_id;",
    "client.beta.messages.create({ ...params, stream: true });",
    "client.beta.messages.stream({ ...params });",
    "function buildModelSettings() {",
    "  return modelSettings;",
    "}",
    "const effectiveAgent = {",
    "    model_settings: {",
    "      ...agent2.model_settings,",
    "      ...conversationModelSettings2 ?? {},",
    "      ...typeof conversationRecord.context_window_limit === \"number\" ? { context_window_limit: conversationRecord.context_window_limit } : {}",
    "    }",
    "};",
    "",
  ].join("\n");

  const patched = patchLettaCodeSourceForTest(source);

  assert.ok(patched.startsWith("#!/usr/bin/env node\nglobalThis.__lcpFixModelSettings"));
  assert.match(patched, /agentId: body\?\.agent_id \?\? this\.store\?\.resolveAgentIdForConversation/);
  assert.match(patched, /LETTA_CODE_THINKING_BUDGET_TOKENS/u);
  assert.match(patched, /messages\.create\(\{ \.\.\.globalThis\.__lcpFixThinking\(params\),/);
  assert.match(patched, /messages\.stream\(\{ \.\.\.globalThis\.__lcpFixThinking\(params\)/);
  assert.match(patched, /return globalThis\.__lcpFixModelSettings\(modelSettings\);/);
  assert.match(patched, /model_settings: globalThis\.__lcpFixModelSettings\(\{/);
});

test("patch-loader: normalizes Anthropic create and stream chokepoints", () => {
  const input = [
    "#!/usr/bin/env node",
    "const createResponse = await client.messages.create({ ...params, stream: true }, requestOptions);",
    "const stream = this.client.beta.messages.stream({ ...params }, options);",
  ].join("\n");

  const patched = patchLettaCodeSourceForTest(input);

  assert.ok(patched.startsWith("#!/usr/bin/env node\n"), "keeps the shebang on line 1");
  assert.match(patched, /globalThis\.__lcpFixThinking = globalThis\.__lcpFixThinking/);
  assert.match(
    patched,
    /client\.messages\.create\(\{ \.\.\.globalThis\.__lcpFixThinking\(params\), stream: true \}/,
  );
  assert.match(
    patched,
    /this\.client\.beta\.messages\.stream\(\{ \.\.\.globalThis\.__lcpFixThinking\(params\) \}/,
  );
});

test("patch-loader: enabled thinking without budget receives configured default", () => {
  const previousBudget = process.env["LETTA_CODE_THINKING_BUDGET_TOKENS"];
  process.env["LETTA_CODE_THINKING_BUDGET_TOKENS"] = "7777";
  try {
    const input = "const stream = this.client.beta.messages.stream({ ...params }, options);";
    const patched = patchLettaCodeSourceForTest(input);

    const helperStart = patched.indexOf("globalThis.__lcpFixThinking =");
    const helperEnd = patched.indexOf("\nconst stream", helperStart);
    assert.notEqual(helperStart, -1);
    assert.notEqual(helperEnd, -1);

    const helperSource = patched.slice(helperStart, helperEnd);
    globalThis.__lcpFixThinking = undefined;
    eval(helperSource);

    const fixThinking = readInjectedThinkingHelper();
    if (typeof fixThinking !== "function") {
      assert.fail("expected __lcpFixThinking helper to be installed");
    }
    const normalized = fixThinking({
      model: "claude-sonnet",
      thinking: { type: "enabled" },
    });

    assert.deepEqual(normalized.thinking, { type: "enabled", budget_tokens: 7777 });
  } finally {
    if (previousBudget === undefined) {
      delete process.env["LETTA_CODE_THINKING_BUDGET_TOKENS"];
    } else {
      process.env["LETTA_CODE_THINKING_BUDGET_TOKENS"] = previousBudget;
    }
    globalThis.__lcpFixThinking = undefined;
  }
});

test("patch-loader: model settings patch adds enabled thinking budget fallback", () => {
  const input = [
    "let thinking;",
    "thinking = {",
    "        type: updateArgs?.enable_reasoner === false ? \"disabled\" : \"enabled\",",
    "        ...typeof updateArgs?.max_reasoning_tokens === \"number\" && {",
    "          budget_tokens: updateArgs.max_reasoning_tokens",
    "        }",
    "      };",
  ].join("\n");

  const patched = patchLettaCodeSourceForTest(input);

  assert.match(patched, /updateArgs\?\.enable_reasoner !== false && \{/);
  assert.match(patched, /Math\.floor\(Number\(process\.env\.LETTA_CODE_THINKING_BUDGET_TOKENS \|\| 10000\)\)/);
  assert.doesNotMatch(patched, /type: updateArgs\?\.enable_reasoner === false[\s\S]*\.\.\.typeof updateArgs\?\.max_reasoning_tokens/);
});

test("patch-loader: normalizes persisted model_settings thinking", () => {
  const previousBudget = process.env["LETTA_CODE_THINKING_BUDGET_TOKENS"];
  process.env["LETTA_CODE_THINKING_BUDGET_TOKENS"] = "8888";
  try {
    const input = [
      "function supportedModelSettingsFromBody(bodyRecord) {",
      "  const modelSettings = isRecord(bodyRecord.model_settings) ? { ...bodyRecord.model_settings } : {};",
      "  return modelSettings;",
      "}",
      "function effectiveAgentForConversation(agent2, conversation) {",
      "  const conversationRecord = conversation;",
      "  const conversationModelSettings2 = isRecord(conversationRecord.model_settings) ? conversationRecord.model_settings : undefined;",
      "  return {",
      "    ...agent2,",
      "    model_settings: {",
      "      ...agent2.model_settings,",
      "      ...conversationModelSettings2 ?? {},",
      "      ...typeof conversationRecord.context_window_limit === \"number\" ? { context_window_limit: conversationRecord.context_window_limit } : {}",
      "    }",
      "  };",
      "}",
    ].join("\n");

    const patched = patchLettaCodeSourceForTest(input);

    assert.match(patched, /globalThis\.__lcpFixModelSettings = globalThis\.__lcpFixModelSettings/);
    assert.match(patched, /return globalThis\.__lcpFixModelSettings\(modelSettings\);/);
    assert.match(patched, /model_settings: globalThis\.__lcpFixModelSettings\(\{/);

    const helperStart = patched.indexOf("globalThis.__lcpFixModelSettings =");
    const helperEnd = patched.indexOf("\nfunction supportedModelSettingsFromBody", helperStart);
    assert.notEqual(helperStart, -1);
    assert.notEqual(helperEnd, -1);

    const helperSource = patched.slice(helperStart, helperEnd);
    globalThis.__lcpFixModelSettings = undefined;
    eval(helperSource);

    const fixModelSettings = readInjectedModelSettingsHelper();
    if (typeof fixModelSettings !== "function") {
      assert.fail("expected __lcpFixModelSettings helper to be installed");
    }

    assert.deepEqual(
      fixModelSettings({ provider_type: "anthropic", thinking: { type: "enabled" } }).thinking,
      { type: "enabled", budget_tokens: 8888 },
    );
    assert.deepEqual(
      fixModelSettings({ provider_type: "anthropic", thinking: { type: "disabled", budget_tokens: 8888 } }).thinking,
      { type: "disabled" },
    );
  } finally {
    if (previousBudget === undefined) {
      delete process.env["LETTA_CODE_THINKING_BUDGET_TOKENS"];
    } else {
      process.env["LETTA_CODE_THINKING_BUDGET_TOKENS"] = previousBudget;
    }
    globalThis.__lcpFixModelSettings = undefined;
  }
});

test("patch-loader: adds local vision input for discovered Claude-style models", () => {
  const previousExperimental = process.env["LETTA_LOCAL_BACKEND_EXPERIMENTAL"];
  const input = [
    "function registeredModelToPiModel(input) {",
    "  return {",
    "    id: input.model.id,",
    "    input: input.model.input,",
    "  };",
    "}",
  ].join("\n");

  try {
    process.env["LETTA_LOCAL_BACKEND_EXPERIMENTAL"] = "1";
    const patched = patchLettaCodeSourceForTest(input);

    assert.match(patched, /globalThis\.__lcpFixLocalVisionInput =/);
    assert.match(
      patched,
      /input: globalThis\.__lcpFixLocalVisionInput\(input\.providerName, input\.model\.id, input\.model\.input\),/,
    );

    const helperStart = patched.indexOf("globalThis.__lcpFixLocalVisionInput =");
    const helperEnd = patched.indexOf("\nfunction registeredModelToPiModel", helperStart);
    assert.notEqual(helperStart, -1);
    assert.notEqual(helperEnd, -1);

    const helperSource = patched.slice(helperStart, helperEnd);
    globalThis.__lcpFixLocalVisionInput = undefined;
    eval(helperSource);

    const fixInput = readInjectedLocalVisionInputHelper();
    if (typeof fixInput !== "function") {
      assert.fail("expected __lcpFixLocalVisionInput helper to be installed");
    }

    assert.deepEqual(fixInput("lmstudio", "claude-opus-4-8", ["text"]), ["text", "image"]);
    assert.deepEqual(fixInput("lmstudio", "plain-text-model", ["text"]), ["text"]);
    assert.deepEqual(fixInput("lmstudio", "claude-opus-4-8", ["text", "image"]), ["text", "image"]);
  } finally {
    if (previousExperimental === undefined) {
      delete process.env["LETTA_LOCAL_BACKEND_EXPERIMENTAL"];
    } else {
      process.env["LETTA_LOCAL_BACKEND_EXPERIMENTAL"] = previousExperimental;
    }
    globalThis.__lcpFixLocalVisionInput = undefined;
  }
});

// lcp-9d76: REGRESSION GUARD — the vision image flag must NOT depend on
// LETTA_VISION_MODELS being present in the environment. The shim sets that
// env var at runtime (server.ts, from VISION_MODEL_PATTERNS), but if it is
// ever dropped from the service environment (as happened 2026-06-21 when the
// systemd unit lacked it and the runtime process.env assignment did not
// propagate to the spawned letta.js child), images would be silently stripped
// with "(image omitted: model does not support images)". The patch-loader
// helper MUST fall back to a hardcoded vision-model regex so an unset/empty
// LETTA_VISION_MODELS still enables image input for known vision families.
// This test FAILS if someone removes that fallback and makes the helper
// rely solely on the env var.
test("patch-loader: vision input is default-safe when LETTA_VISION_MODELS is unset", () => {
  const previousExperimental = process.env["LETTA_LOCAL_BACKEND_EXPERIMENTAL"];
  const previousVisionModels = process.env["LETTA_VISION_MODELS"];
  const input = [
    "function registeredModelToPiModel(input) {",
    "  return {",
    "    id: input.model.id,",
    "    input: input.model.input,",
    "  };",
    "}",
  ].join("\n");

  try {
    process.env["LETTA_LOCAL_BACKEND_EXPERIMENTAL"] = "1";
    // The exact failure mode from lcp-9d76: env var absent entirely.
    delete process.env["LETTA_VISION_MODELS"];

    const patched = patchLettaCodeSourceForTest(input);
    const helperStart = patched.indexOf("globalThis.__lcpFixLocalVisionInput =");
    const helperEnd = patched.indexOf("\nfunction registeredModelToPiModel", helperStart);
    assert.notEqual(helperStart, -1);
    assert.notEqual(helperEnd, -1);

    const helperSource = patched.slice(helperStart, helperEnd);
    globalThis.__lcpFixLocalVisionInput = undefined;
    eval(helperSource);

    const fixInput = readInjectedLocalVisionInputHelper();
    if (typeof fixInput !== "function") {
      assert.fail("expected __lcpFixLocalVisionInput helper to be installed");
    }

    // Known vision families must still get "image" with NO env list present.
    assert.deepEqual(
      fixInput("lmstudio", "opus-4-8", ["text"]),
      ["text", "image"],
      "opus must be vision-capable even with LETTA_VISION_MODELS unset",
    );
    assert.deepEqual(
      fixInput("lmstudio", "claude-sonnet-4-5", ["text"]),
      ["text", "image"],
      "claude/sonnet must be vision-capable even with LETTA_VISION_MODELS unset",
    );
    assert.deepEqual(
      fixInput("lmstudio", "minimax-m3", ["text"]),
      ["text", "image"],
      "minimax must be vision-capable even with LETTA_VISION_MODELS unset",
    );
    // Non-vision model must NOT be promoted.
    assert.deepEqual(
      fixInput("lmstudio", "plain-text-model", ["text"]),
      ["text"],
      "non-vision model must not get image input",
    );
  } finally {
    if (previousExperimental === undefined) {
      delete process.env["LETTA_LOCAL_BACKEND_EXPERIMENTAL"];
    } else {
      process.env["LETTA_LOCAL_BACKEND_EXPERIMENTAL"] = previousExperimental;
    }
    if (previousVisionModels === undefined) {
      delete process.env["LETTA_VISION_MODELS"];
    } else {
      process.env["LETTA_VISION_MODELS"] = previousVisionModels;
    }
    globalThis.__lcpFixLocalVisionInput = undefined;
  }
});

// lcp-9d76: the patch-loader's hardcoded fallback regex (used when
// LETTA_VISION_MODELS is unset) must stay in sync with the canonical
// VISION_MODEL_PATTERNS in lib/model-catalog.ts. If a new vision family is
// added to VISION_MODEL_PATTERNS but not to the fallback regex, an env-less
// process would silently strip images for that family. This test FAILS on
// that drift.
test("patch-loader: fallback regex covers every VISION_MODEL_PATTERNS entry", () => {
  const previousExperimental = process.env["LETTA_LOCAL_BACKEND_EXPERIMENTAL"];
  const previousVisionModels = process.env["LETTA_VISION_MODELS"];
  // NOTE: this must match the patch-loader's LOCAL_VISION_INPUT_TOKEN exactly
  // (`input: input.model.input,` — trailing comma, on its own line) so the
  // helper is actually injected. A single-line body without the trailing
  // comma does NOT match and the helper is never installed.
  const input = [
    "function registeredModelToPiModel(input) {",
    "  return {",
    "    id: input.model.id,",
    "    input: input.model.input,",
    "  };",
    "}",
  ].join("\n");
  try {
    process.env["LETTA_LOCAL_BACKEND_EXPERIMENTAL"] = "1";
    delete process.env["LETTA_VISION_MODELS"];
    const patched = patchLettaCodeSourceForTest(input);
    const helperStart = patched.indexOf("globalThis.__lcpFixLocalVisionInput =");
    const helperEnd = patched.indexOf("\nfunction registeredModelToPiModel", helperStart);
    assert.notEqual(helperStart, -1, "vision helper must be injected");
    assert.notEqual(helperEnd, -1);
    const helperSource = patched.slice(helperStart, helperEnd);
    globalThis.__lcpFixLocalVisionInput = undefined;
    eval(helperSource);
    const fixInput = readInjectedLocalVisionInputHelper() as (
      p: string,
      m: string,
      i: string[],
    ) => string[];
    if (typeof fixInput !== "function") {
      assert.fail("expected __lcpFixLocalVisionInput helper to be installed");
    }

    for (const pattern of VISION_MODEL_PATTERNS) {
      // Build a model id that contains the pattern verbatim (lowercased).
      const modelId = `test-${pattern}-model`;
      assert.deepEqual(
        fixInput("lmstudio", modelId, ["text"]),
        ["text", "image"],
        `fallback regex must match VISION_MODEL_PATTERNS entry "${pattern}" (model id "${modelId}")`,
      );
    }
  } finally {
    if (previousExperimental === undefined) {
      delete process.env["LETTA_LOCAL_BACKEND_EXPERIMENTAL"];
    } else {
      process.env["LETTA_LOCAL_BACKEND_EXPERIMENTAL"] = previousExperimental;
    }
    if (previousVisionModels === undefined) {
      delete process.env["LETTA_VISION_MODELS"];
    } else {
      process.env["LETTA_VISION_MODELS"] = previousVisionModels;
    }
    globalThis.__lcpFixLocalVisionInput = undefined;
  }
});

test("patch-loader: preserves raw multimodal Read tool returns on stream chunks", () => {
  const input = [
    "const toolResult = await executeTool(decision.approval.toolName, parsedArgs, {});",
    "onChunk({",
    "  message_type: \"tool_return_message\",",
    "  tool_return: getDisplayableToolReturn(toolResult.toolReturn),",
    "  status: toolResult.status,",
    "});",
    "return {",
    "  type: \"tool\",",
    "  tool_return: toolResult.toolReturn,",
    "};",
  ].join("\n");

  const patched = patchLettaCodeSourceForTest(input);

  assert.match(patched, /tool_return: toolResult\.toolReturn,/);
  assert.doesNotMatch(patched, /tool_return: getDisplayableToolReturn\(toolResult\.toolReturn\),/);
});

test("patch-loader: converts legacy Read image tool returns before approval normalization", () => {
  const input = [
    "function normalizeToolReturnText(value) {",
    "  if (Array.isArray(value)) return value.filter((part) => part.type === \"text\").map((part) => part.text).join(\"\\n\").trim();",
    "  return typeof value === \"string\" ? value : JSON.stringify(value);",
    "}",
    "function isToolReturnContent(value) {",
    "  if (typeof value === \"string\")",
    "    return true;",
    "  if (!Array.isArray(value))",
    "    return false;",
    "  return value.every((part) => !!part && typeof part === \"object\" && (\"type\" in part) && (part.type === \"text\" && (\"text\" in part) && typeof part.text === \"string\" || part.type === \"image\" && (\"data\" in part) && typeof part.data === \"string\" && (\"mimeType\" in part) && typeof part.mimeType === \"string\"));",
    "}",
    "function coerceToolReturnContent(value) {",
    "  if (isToolReturnContent(value))",
    "    return value;",
    "  return normalizeToolReturnText(value);",
    "}",
  ].join("\n");

  const patched = patchLettaCodeSourceForTest(input);

  assert.match(patched, /globalThis\.__lcpCoerceToolReturnContent =/);
  assert.match(patched, /return globalThis\.__lcpCoerceToolReturnContent\(value\);/);

  const helperStart = patched.indexOf("globalThis.__lcpCoerceToolReturnContent =");
  const helperEnd = patched.indexOf("\nfunction normalizeToolReturnText", helperStart);
  assert.notEqual(helperStart, -1);
  assert.notEqual(helperEnd, -1);

  const helperSource = patched.slice(helperStart, helperEnd);
  globalThis.__lcpCoerceToolReturnContent = undefined;
  eval(helperSource);

  const coerce = readInjectedToolReturnContentHelper();
  if (typeof coerce !== "function") {
    assert.fail("expected __lcpCoerceToolReturnContent helper to be installed");
  }

  assert.deepEqual(
    coerce([
      { type: "text", text: "[Image: strict-png.png]" },
      {
        type: "image",
        source: { type: "base64", media_type: "image/png", data: "abc123" },
      },
    ]),
    [
      { type: "text", text: "[Image: strict-png.png]" },
      {
        type: "image",
        source: { type: "base64", media_type: "image/png", data: "abc123" },
      },
    ],
  );

  globalThis.__lcpCoerceToolReturnContent = undefined;
});

test("patch-loader: registers native generate_image tool in built-in registries", () => {
  const input = [
    "#!/usr/bin/env node",
    "const toolDefinitions = {};",
    "  TOOL_DEFINITIONS = toolDefinitions;",
    "});",
    "var ANTHROPIC_DEFAULT_TOOLS2 = [",
    "  \"TaskUpdate\",",
    "  \"Write\"",
    "];",
    "var OPENAI_PASCAL_TOOLS2 = [",
    "  \"ApplyPatch\",",
    "  \"UpdatePlan\"",
    "];",
  ].join("\n");

  const patched = patchLettaCodeSourceForTest(input);

  assert.match(patched, /globalThis\.__lcpAddGenerateImageTool =/);
  assert.match(patched, /TOOL_DEFINITIONS = globalThis\.__lcpAddGenerateImageTool\(toolDefinitions, defineTool\);/);
  assert.match(patched, /model = typeof args\?\.model === "string"[\s\S]*: "gpt-image-2";/);
  assert.doesNotMatch(patched, /gpt-image-2-medium/);
  assert.match(
    patched,
    /var ANTHROPIC_DEFAULT_TOOLS2 = \[[\s\S]*"Write",\n  "generate_image"\n\];/,
  );
  assert.match(
    patched,
    /var OPENAI_PASCAL_TOOLS2 = \[[\s\S]*"UpdatePlan",\n  "generate_image"\n\];/,
  );
});

test("patch-loader: current letta.js bundle receives generate_image registration", () => {
  // The shim's pinned node_modules dev dependency no longer exists after the move; fall back to the
  // deployed bundle (the same source the production patch loader rewrites).
  const bundlePath = resolveLettaBundle() ?? resolveDeployedLettaBundle();
  assert.ok(
    bundlePath,
    "could not resolve @letta-ai/letta-code bundle — generate_image registration cannot be verified against the real bundle",
  );
  const bundle = readFileSync(bundlePath, "utf8");
  const patched = patchLettaCodeSourceForTest(bundle);

  assert.match(patched, /globalThis\.__lcpAddGenerateImageTool =/);
  assert.match(patched, /TOOL_DEFINITIONS = globalThis\.__lcpAddGenerateImageTool\(toolDefinitions, defineTool\);/);
  // letta-code >= 0.32.17 replaced the flat ANTHROPIC_DEFAULT_TOOLS2 / OPENAI_PASCAL_TOOLS2 arrays with
  // TOOLSET_CATALOG.<id>.tools (lcp-htu3); the deployed bundle has no flat arrays. Then require
  // generate_image in each of the three default toolsets (default, codex, letta).
  if (/var ANTHROPIC_DEFAULT_TOOLS2 = \[/.test(bundle)) {
    assert.match(patched, /var ANTHROPIC_DEFAULT_TOOLS2 = \[[\s\S]*"Write",\n  "generate_image"\n\];/);
    assert.match(patched, /var OPENAI_PASCAL_TOOLS2 = \[[\s\S]*"UpdatePlan",\n  "generate_image"\n\];/);
  } else {
    assert.equal((patched.match(/"generate_image"/g) ?? []).length - (bundle.match(/"generate_image"/g) ?? []).length, 3);
  }
});

async function verifyGenerateImageImplementation() {
  const input = [
    "#!/usr/bin/env node",
    "const toolDefinitions = {};",
    "  TOOL_DEFINITIONS = toolDefinitions;",
    "});",
  ].join("\n");

  const patched = patchLettaCodeSourceForTest(input);
  const helperStart = patched.indexOf("globalThis.__lcpAddGenerateImageTool =");
  const helperEnd = patched.indexOf("\n  TOOL_DEFINITIONS = globalThis.__lcpAddGenerateImageTool");
  assert.notEqual(helperStart, -1, "__lcpAddGenerateImageTool definition missing");
  assert.notEqual(helperEnd, -1, "helperEnd missing");

  // Isolate the function definition and eval it
  const helperSource = patched.slice(helperStart, helperEnd);
  globalThis.__lcpAddGenerateImageTool = undefined;
  eval(helperSource);

  type AddGenerateImageTool = (
    toolDefinitions: Record<string, unknown>,
    defineToolFn: (cfg: { schema: unknown; description: unknown; impl: unknown }) => unknown,
  ) => Record<string, unknown>;
  const addGenerateImageTool = globalThis.__lcpAddGenerateImageTool as AddGenerateImageTool | undefined;
  if (typeof addGenerateImageTool !== "function") {
    throw new Error("__lcpAddGenerateImageTool was not injected");
  }
  const invokeAddGenerateImageTool: AddGenerateImageTool = addGenerateImageTool;

  const mockToolDefinitions: Record<string, unknown> = { existing: true };
  const mockDefineToolFn = (cfg: { schema: unknown; description: unknown; impl: unknown }) => cfg.impl;
  const result = invokeAddGenerateImageTool(mockToolDefinitions, mockDefineToolFn);

  assert.equal(result["existing"], true);
  const generateImage = result["generate_image"];
  assert.equal(typeof generateImage, "function");
  if (typeof generateImage !== "function") {
    throw new Error("generate_image tool was not added");
  }
  const invokeGenerateImage = generateImage as (args: { prompt: string; output_dir: string }) => Promise<{ details: { model: string } }>;

  const originalFetch = globalThis.fetch;
  const originalEnv = { ...process.env };

  try {
    // Test 1: Default model selection
    let capturedBody: any;
    globalThis.fetch = async (url: string | URL | Request, options?: RequestInit) => {
      capturedBody = JSON.parse(options?.body as string);
      return new Response(JSON.stringify({ data: [{ b64_json: "fakebase64" }] }), { status: 200, headers: { "content-type": "application/json" } });
    };

    const args = { prompt: "A test image", output_dir: "/tmp/letta-test-output-dir-img" };
    const response = await invokeGenerateImage(args);

    assert.equal(capturedBody.model, "gpt-image-2", "Should default to gpt-image-2");
    assert.equal(capturedBody.prompt, "A test image");
    assert.equal(response.details.model, "gpt-image-2");

    // Test 2: Error handling (non-200 status)
    globalThis.fetch = async () => {
      return new Response("Provider overloaded", { status: 503 });
    };

    await assert.rejects(
      async () => { await invokeGenerateImage({ prompt: "Another test", output_dir: "/tmp/letta-test-output-dir-img" }); },
      (err: Error) => {
        assert.match(err.message, /image generation failed: HTTP 503 Provider overloaded/);
        return true;
      }
    );
  } finally {
    globalThis.fetch = originalFetch;
    process.env = originalEnv;
    globalThis.__lcpAddGenerateImageTool = undefined;
  }
}

test("patch-loader: generate_image impl applies default model and handles HTTP errors", verifyGenerateImageImplementation);

test("patch-loader leaves unrelated source untouched", () => {
  const source = "export const untouched = true;\n";

  assert.equal(patchLettaCodeSourceForTest(source), source);
});
