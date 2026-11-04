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


// ---------------------------------------------------------------------------
// lcp-aioi8-p1 / lcp-aioi8-p2 — system-prompt dirty-check + clone-map
// dehydration. Anchors are byte-exact copies of letta-code 0.27.22; keep in
// sync with letta-code-patch-loader.mjs.
// ---------------------------------------------------------------------------

const SYS_PROMPT_ANCHOR =
  "  persistCompiledSystemPrompt(conversationId, agentId) {\n" +
  "    if (!this.storageDir)\n" +
  "      return;\n" +
  "    const key = this.conversationKey(conversationId, agentId);\n" +
  "    const prompt = this.compiledSystemPromptByConversationKey.get(key);\n" +
  "    if (!prompt)\n" +
  "      return;\n" +
  "    const conversationDir = join39(this.storageDir, \"conversations\", encodePathSegment(key));\n" +
  "    mkdirSync23(conversationDir, { recursive: true });\n" +
  "    writeFileSync17(join39(conversationDir, \"system-prompt.json\"), `${JSON.stringify(prompt, null, 2)}\n" +
  "`);\n" +
  "  }";

const CLONE_MAP_BULK_ANCHOR =
  "    this.persistedMessageByMessageIdByConversationKey.set(key, new Map(Array.from(transcript.messageById, ([messageId, message]) => [\n" +
  "      messageId,\n" +
  "      cloneLocalMessage(message)\n" +
  "    ])));";

const CLONE_MAP_APPEND_ANCHOR = ".set(entry.message.id, cloneLocalMessage(entry.message));";

const CLONE_MAP_COMPARE_ANCHOR = "localMessagesHaveSameSnapshot(persistedMessage, message)";

test("patch-loader: deployed letta.js bundle receives dirty-check + clone-map dehydration", (t) => {
  const bundlePath = resolveDeployedLettaBundle();
  if (!bundlePath) {
    t.skip("no letta-code bundle with persistCompiledSystemPrompt (>= 0.27) available on this host");
    return;
  }
  const bundle = readFileSync(bundlePath, "utf8");

  // Unique-match assertion holds on the real bundle (drift alarm on update).
  const sysPromptSites = [...bundle.matchAll(/  persistCompiledSystemPrompt\(conversationId, agentId\) \{/g)];
  assert.equal(sysPromptSites.length, 1, "P1 anchor must be unique");
  // letta-code >= 0.32.17 renamed the local `transcript` -> `transcript2`; the
  // loader accepts either variant, so the drift alarm counts both.
  const bulkAnchorV2 = CLONE_MAP_BULK_ANCHOR.replaceAll("transcript.messageById", "transcript2.messageById");
  assert.equal(
    bundle.split(CLONE_MAP_BULK_ANCHOR).length - 1 + bundle.split(bulkAnchorV2).length - 1,
    1,
    "P2 bulk anchor must be unique",
  );
  assert.equal(bundle.split(CLONE_MAP_APPEND_ANCHOR).length - 1, 1, "P2 append anchor must be unique");
  assert.equal(bundle.split(CLONE_MAP_COMPARE_ANCHOR).length - 1, 1, "P2 compare anchor must be unique");

  const { source: patched, skippedPatches } = patchLettaCodeSourceResultForTest(bundle);
  assert.equal(skippedPatches, 0, "no patch may fail-open against the pinned bundle");

  // P1: dirty-check in place, memo helper injected, unconditional write gone.
  assert.ok(patched.includes("if (globalThis.__lcpSysPromptJson.get(key) === json)"));
  assert.ok(patched.includes("globalThis.__lcpSysPromptJson = globalThis.__lcpSysPromptJson || new Map();"));
  // Shape, not binding numbers: the minifier renumbers those on most releases and the anchor
  // is built to tolerate it, so freezing them here would fail the next upgrade for no reason.
  assert.ok(
    /writeFileSync\d+\(join\d+\(conversationDir, "system-prompt\.json"\), json \+ "\\n"\);/.test(patched),
    "the memoized write must be in place",
  );
  assert.ok(
    !/writeFileSync\d+\(join\d+\(conversationDir, "system-prompt\.json"\), `\$\{JSON\.stringify/.test(patched),
    "the unconditional write must be gone",
  );

  // P2: all three sites dehydrated atomically.
  assert.ok(patched.includes(".set(entry.message.id, JSON.stringify(entry.message));"));
  assert.ok(patched.includes("persistedMessage === JSON.stringify(message)"));
  assert.ok(!patched.includes(CLONE_MAP_BULK_ANCHOR));
  assert.ok(!patched.includes("cloneLocalMessage(entry.message)"));
  // The other cloneLocalMessage call sites (non-anchored) must be untouched.
  assert.ok(patched.includes("cloneLocalMessage("));
});

test("patch-loader: lcp-aioi8-p1 fail-open on structural anchor drift", () => {
  // Renumbered bindings (mkdirSync23 -> mkdirSync24) are what the anchor now tolerates by
  // design, so drift has to be structural to prove fail-open: the method itself is renamed.
  // Nothing may be patched and the source must come back byte-identical.
  const drifted = "const before = 1;\n" +
    SYS_PROMPT_ANCHOR.replace("persistCompiledSystemPrompt", "persistCompiledSystemPromptV2") +
    "\nconst after = 2;\n";

  const { source, appliedPatches } = patchLettaCodeSourceResultForTest(drifted);
  assert.equal(source, drifted, "drifted anchor must leave source untouched");
  assert.equal(appliedPatches, 0);
});

test("patch-loader: lcp-aioi8-p1 unique-match assertion skips duplicated anchors", () => {
  const duplicated = "class A {\n" + SYS_PROMPT_ANCHOR + "\n}\nclass B {\n" + SYS_PROMPT_ANCHOR + "\n}\n";

  const { source, appliedPatches } = patchLettaCodeSourceResultForTest(duplicated);
  assert.equal(source, duplicated, "double-matched anchor must not be patched anywhere");
  assert.equal(appliedPatches, 0);

  // Sanity: the same anchor occurring exactly once IS patched.
  const single = "class A {\n" + SYS_PROMPT_ANCHOR + "\n}\n";
  const patchedSingle = patchLettaCodeSourceForTest(single);
  assert.ok(patchedSingle.includes("globalThis.__lcpSysPromptJson.get(key) === json"));
});

test("patch-loader: lcp-aioi8-p2 applies atomically or not at all", () => {
  // Only two of the three anchors present (bulk-rebuild missing): NONE of the
  // clone-map replacements may land, or the map would mix value types.
  const partial =
    "if (persistedMessage && " + CLONE_MAP_COMPARE_ANCHOR + ") {\n  return;\n}\n" +
    "this.persistedMessagesByMessageId(key)" + CLONE_MAP_APPEND_ANCHOR + "\n";

  const { source, appliedPatches } = patchLettaCodeSourceResultForTest(partial);
  assert.equal(source, partial, "partial anchor set must not be patched");
  assert.equal(appliedPatches, 0);

  // All three present exactly once → all three replaced.
  const complete = partial + CLONE_MAP_BULK_ANCHOR + "\n";
  const patchedComplete = patchLettaCodeSourceForTest(complete);
  assert.ok(patchedComplete.includes("persistedMessage === JSON.stringify(message)"));
  assert.ok(patchedComplete.includes(".set(entry.message.id, JSON.stringify(entry.message));"));
  assert.ok(patchedComplete.includes("      JSON.stringify(message)\n    ])));"));
  assert.ok(!patchedComplete.includes("cloneLocalMessage"));
});

test("patch-loader: lcp-aioi8-p1 dirty-check skips unchanged system-prompt writes", () => {
  // Build a runnable fixture around the byte-exact anchor, patch it, eval it,
  // and drive persistCompiledSystemPrompt twice with an unchanged prompt.
  const fixture = [
    "var writes = [];",
    "function join39(...parts) { return parts.join(\"/\"); }",
    "function encodePathSegment(value) { return value; }",
    "function mkdirSync23() {}",
    "function writeFileSync17(path, data) { writes.push({ path, data }); }",
    "class FixtureStore {",
    "  constructor() {",
    "    this.storageDir = \"/fixture\";",
    "    this.compiledSystemPromptByConversationKey = new Map();",
    "  }",
    "  conversationKey(conversationId, agentId) { return `${conversationId}:${agentId}`; }",
    SYS_PROMPT_ANCHOR,
    "}",
    "({ FixtureStore, writes });",
  ].join("\n");

  const patched = patchLettaCodeSourceForTest(fixture);
  assert.ok(patched.includes("globalThis.__lcpSysPromptJson.get(key) === json"));

  const globals = globalThis as typeof globalThis & { __lcpSysPromptJson?: Map<string, string> };
  const previousMemo = globals.__lcpSysPromptJson;
  globals.__lcpSysPromptJson = undefined;
  try {
    const evaluated = eval(patched) as {
      FixtureStore: new () => {
        storageDir: string;
        compiledSystemPromptByConversationKey: Map<string, unknown>;
        persistCompiledSystemPrompt(conversationId: string, agentId: string): void;
      };
      writes: Array<{ path: string; data: string }>;
    };
    const store = new evaluated.FixtureStore();
    store.compiledSystemPromptByConversationKey.set("conv-1:agent-1", { system: "alpha" });

    store.persistCompiledSystemPrompt("conv-1", "agent-1");
    store.persistCompiledSystemPrompt("conv-1", "agent-1");
    assert.equal(evaluated.writes.length, 1, "unchanged prompt must write exactly once");
    assert.equal(evaluated.writes[0]?.data, JSON.stringify({ system: "alpha" }, null, 2) + "\n");

    store.compiledSystemPromptByConversationKey.set("conv-1:agent-1", { system: "beta" });
    store.persistCompiledSystemPrompt("conv-1", "agent-1");
    assert.equal(evaluated.writes.length, 2, "changed prompt must write again");
    assert.equal(evaluated.writes[1]?.data, JSON.stringify({ system: "beta" }, null, 2) + "\n");
  } finally {
    globals.__lcpSysPromptJson = previousMemo;
  }
});

// lcp-otid — the streamed assistant otid has to survive the durable write, or a
// client cannot pair the streamed row with the settled one except by matching
// their text, which duplicates a reply whenever a stream drops its tail.
