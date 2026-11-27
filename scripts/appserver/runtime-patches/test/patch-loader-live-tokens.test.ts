import { resolveDeployedLettaBundle } from "./bundle-fixtures.ts";
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import { patchLettaCodeSourceResultForTest } from "../letta-code-patch-loader.mjs";

// lcp-live-tokens: a running LOCAL subagent reports its token total live. The child stream-json
// carries one usage_statistics message per model call with that call's tokens (step_count: 1),
// so the running total is their sum, and the final result envelope (cumulative usage) still
// wins at completion. The tests drive the real, runtime-patched subagent state module —
// registerSubagent, processStreamEvent, updateSubagent, completeSubagent and the completed-
// subagent cleanup — in a child process, the way the host-neutral tests do.

const LOADER_URL = new URL("../letta-code-patch-loader.mjs", import.meta.url).href;

// Replaces the CLI entry with the subagent-state module's own initialiser and exports its
// internals, so the child drives the real patched functions over the real store.
const EXPORT_HOOK_SOURCE = `
import { pathToFileURL } from "node:url";
const TARGET = process.env.LETTA_CLI_PATH_REAL ?? "";
const ENTRY = "await init_src5().then(() => exports_src2);";
export async function load(url, context, nextLoad) {
  const result = await nextLoad(url, context);
  if (!TARGET || url !== pathToFileURL(TARGET).href) return result;
  const source = typeof result.source === "string" ? result.source : Buffer.from(result.source).toString("utf8");
  if (source.split(ENTRY).length !== 2) throw new Error("live-tokens export hook: CLI entry not found exactly once");
  return {
    ...result,
    source: source.replace(ENTRY, () => "init_subagent_state();\\n" +
      "export { registerSubagent, updateSubagent, completeSubagent, clearCompletedSubagents, " +
      "processStreamEvent, store2, completedSubagentCleanupTimers, cachedSnapshot, " +
      "cachedLifecycleItems, stores4, streamEventListeners };"),
    shortCircuit: true,
  };
}
`;
const EXPORT_HOOK_URL = `data:text/javascript,${encodeURIComponent(EXPORT_HOOK_SOURCE)}`;

// The per-subagent stream state executeSubagent creates. Extracted from the patched bundle so
// the test always uses the real shape, including any field the patch adds.
const STATE_LITERAL_PATTERN = /const state2 = \{\n {6}agentId: existingAgentId \|\| null,[\s\S]*?\n {4}\};/;

const count = (source: string, token: string) => source.split(token).length - 1;

function deployedBundleOrSkip(t: { skip: (reason: string) => void }): string | null {
  const bundlePath = resolveDeployedLettaBundle();
  if (!bundlePath) {
    t.skip("no deployed letta-code bundle (>= 0.27) on this host");
    return null;
  }
  return bundlePath;
}

/** The deployed bundle patched by the current loader; computed once per test process. */
function patchedBundle(bundle: string): { source: string; appliedPatches: number; skippedPatches: number } {
  const cached = patchedBundleCached;
  if (cached?.bundle === bundle) return cached.result;
  const result = patchLettaCodeSourceResultForTest(bundle);
  patchedBundleCached = { bundle, result };
  return result;
}
let patchedBundleCached: { bundle: string; result: ReturnType<typeof patchedBundle> } | null = null;

/** Whether a parent environment variable may reach the child: not the loader hook and no LETTA_/LCP_ settings. */
function inherited(name: string): boolean {
  return name !== "NODE_OPTIONS" && !name.startsWith("LETTA_") && !name.startsWith("LCP_");
}

type Child = { status: number | null; stdout: string; stderr: string };

function runChild(bundlePath: string, stateLiteral: string, body: string): Child {
  const register = `import { register } from "node:module";\n` +
    `register(${JSON.stringify(LOADER_URL)});\n` +
    `register(${JSON.stringify(EXPORT_HOOK_URL)});\n`;
  const script = `
    import { pathToFileURL } from "node:url";
    const bundle = await import(pathToFileURL(process.env.LETTA_CLI_PATH_REAL).href);
    const makeState = new Function("existingAgentId", "existingConversationId",
      "return " + process.env.LCP_STATE_LITERAL);
    const out = {};
    const usageLine = (total, content) => JSON.stringify({
      type: "message", message_type: "usage_statistics", step_count: 1, total_tokens: total,
      ...content === undefined ? {} : { content },
    });
    const resultLine = (total) => JSON.stringify({
      type: "result", subtype: "success", is_error: false, result: "done", duration_ms: 7,
      usage: { total_tokens: total, step_count: 3 },
    });
    ${body}
    process.stdout.write("\\nRESULT " + JSON.stringify(out) + "\\n");
  `;
  const env: Record<string, string> = {};
  for (const [name, value] of Object.entries(process.env)) {
    if (value !== undefined && inherited(name)) env[name] = value;
  }
  return spawnSync(process.execPath, [
    "--import", `data:text/javascript,${encodeURIComponent(register)}`,
    "--input-type=module", "--eval", script,
  ], {
    encoding: "utf8",
    env: { ...env, LETTA_CLI_PATH_REAL: resolve(bundlePath), LCP_STATE_LITERAL: stateLiteral },
    timeout: 120_000,
  });
}

/** Drives the child and returns its RESULT object; fails the test on any child error. */
function childResult(t: { skip: (reason: string) => void }, bundlePath: string, patchedSource: string, body: string): Record<string, unknown> {
  const literal = STATE_LITERAL_PATTERN.exec(patchedSource);
  assert.ok(literal, "the per-subagent state literal is not in the patched bundle");
  const child = runChild(bundlePath, literal[0].slice("const state2 = ".length), body);
  assert.equal(child.status, 0, `child failed: ${child.stderr}\n${child.stdout}`);
  const line = child.stdout.split("\n").find((entry) => entry.startsWith("RESULT "));
  assert.ok(line, `child printed no RESULT line: ${child.stdout}\n${child.stderr}`);
  return JSON.parse(line.slice("RESULT ".length)) as Record<string, unknown>;
}

test("live tokens: usage_statistics steps report a running total that the final result still wins", (t) => {
  const bundlePath = deployedBundleOrSkip(t);
  if (!bundlePath) return;
  const bundle = readFileSync(bundlePath, "utf8");
  const patched = patchedBundle(bundle);
  const out = childResult(t, bundlePath, patched.source, `
    const tokens = (id) => bundle.store2.agents.get(id)?.totalTokens;
    const register = (id, toolCallId, description) =>
      bundle.registerSubagent(id, "task", description, toolCallId, false, false, undefined, "", false);

    // One lifecycle: live reporting from per-step usage, then the result envelope.
    const id1 = "subagent-live-1";
    register(id1, "toolu-1", "live tokens");
    const s1 = makeState(null, null);
    out.afterRegister = tokens(id1);
    bundle.processStreamEvent(usageLine(1200), s1, id1);
    out.liveAfterFirstStep = tokens(id1);
    bundle.processStreamEvent(usageLine(3400), s1, id1);
    out.liveAfterSecondStep = tokens(id1);
    bundle.processStreamEvent(resultLine(4999), s1, id1);
    out.afterResult = tokens(id1);
    bundle.completeSubagent(id1, { success: true });
    out.afterComplete = tokens(id1);

    // A retried subagent reuses the subagent id with a fresh state: its live total may not
    // lower what the store already reports, and its result envelope still wins at completion.
    const id2 = "subagent-live-2";
    register(id2, "toolu-2", "retry");
    const s2a = makeState(null, null);
    bundle.processStreamEvent(usageLine(8000), s2a, id2);
    bundle.processStreamEvent(usageLine(2000), s2a, id2);
    out.retryFirstAttemptTotal = tokens(id2);
    const s2b = makeState(null, null);
    bundle.processStreamEvent(usageLine(50), s2b, id2);
    out.retryNoRegression = tokens(id2);
    bundle.processStreamEvent(resultLine(60), s2b, id2);
    out.retryFinalWins = tokens(id2);

    // Junk step totals never poison the running sum.
    const id3 = "subagent-live-3";
    register(id3, "toolu-3", "junk steps");
    const s3 = makeState(null, null);
    bundle.processStreamEvent(JSON.stringify({ type: "message", message_type: "usage_statistics", total_tokens: "many" }), s3, id3);
    bundle.processStreamEvent(JSON.stringify({ type: "message", message_type: "usage_statistics" }), s3, id3);
    bundle.processStreamEvent(JSON.stringify({ type: "message", message_type: "usage_statistics", total_tokens: -5 }), s3, id3);
    bundle.processStreamEvent(usageLine(100), s3, id3);
    out.junkThenValid = tokens(id3);
    out.stateFields = Object.keys(s3);
  `);

  assert.equal(out.afterRegister, 0, "a fresh subagent reports no tokens");
  assert.equal(out.liveAfterFirstStep, 1200, "the first step's tokens are visible while the subagent runs");
  assert.equal(out.liveAfterSecondStep, 4600, "per-step usage_statistics sums (1200+3400), it is not cumulative");
  assert.equal(out.afterResult, 4999, "the final result envelope overwrites the live total");
  assert.equal(out.afterComplete, 4999, "completion keeps the result envelope's total");
  assert.equal(out.retryFirstAttemptTotal, 10000, "the first attempt reports live tokens too");
  assert.equal(out.retryNoRegression, 10000, "a fresh attempt never lowers the reported total");
  assert.equal(out.retryFinalWins, 60, "the final result envelope still wins at completion");
  assert.equal(out.junkThenValid, 100, "non-numeric, missing and negative step totals are ignored");
  assert.ok((out.stateFields as string[]).includes("usageTokens"), "the running total lives on the per-subagent state");
});

test("live tokens: one thousand lifecycles retain nothing and copy no message content", (t) => {
  const bundlePath = deployedBundleOrSkip(t);
  if (!bundlePath) return;
  const bundle = readFileSync(bundlePath, "utf8");
  const patched = patchedBundle(bundle);
  const markerHead = "LCP-LIVE-TOKENS-PAYLOAD";
  const iterations = 1000;
  const out = childResult(t, bundlePath, patched.source, `
    const markerHead = ${JSON.stringify(markerHead)};
    const payload = markerHead + "-".repeat(65536);
    const iterations = ${iterations};
    const sizes = () => ({
      agents: bundle.store2.agents.size,
      cleanupTimers: bundle.completedSubagentCleanupTimers.size,
      eventStores: bundle.stores4.size,
      listeners: bundle.store2.listeners.size,
      streamListeners: bundle.streamEventListeners.size,
    });
    let notified = 0;
    let streamed = 0;
    bundle.store2.listeners.add(() => { notified += 1; });
    bundle.streamEventListeners.add(() => { streamed += 1; });
    out.before = sizes();
    const states = [];
    for (let i = 0; i < iterations; i++) {
      const id = \`subagent-leak-\${i}\`;
      bundle.registerSubagent(id, "task", "leak", \`toolu-leak-\${i}\`, false, false, undefined, "", false);
      const state = makeState(null, null);
      states.push(state);
      bundle.processStreamEvent(usageLine(11, payload), state, id);
      bundle.processStreamEvent(usageLine(22, payload), state, id);
      bundle.processStreamEvent(resultLine(33), state, id);
      bundle.completeSubagent(id, { success: true });
    }
    out.retained = sizes();
    // Nothing outside the per-subagent state and the stock store may hold the events' content.
    out.markerInStore = JSON.stringify([...bundle.store2.agents.values()]).includes(markerHead)
      || JSON.stringify(bundle.cachedSnapshot).includes(markerHead)
      || JSON.stringify(bundle.cachedLifecycleItems).includes(markerHead)
      || [...bundle.completedSubagentCleanupTimers.keys()].some((key) => String(key).includes(markerHead))
      || [...bundle.stores4.keys()].some((key) => String(key).includes(markerHead));
    out.statesHavePayload = states.some((state) => JSON.stringify(state).includes(markerHead));
    out.stateBytes = states.map((state) => JSON.stringify(state).length);
    out.lastLiveTotal = states[states.length - 1].usageTokens;
    out.notified = notified;
    out.streamed = streamed;
    bundle.clearCompletedSubagents();
    out.afterCleanup = sizes();
    out.snapshotAgentsAfterCleanup = bundle.cachedSnapshot.agents.length;
  `);

  const before = out.before as Record<string, number>;
  const retained = out.retained as Record<string, number>;
  const after = out.afterCleanup as Record<string, number>;

  assert.equal(out.markerInStore, false, "no module-level collection retains the usage events' content");
  assert.equal(out.statesHavePayload, false, "the per-subagent state retains no message content");
  const stateBytes = out.stateBytes as number[];
  assert.equal(stateBytes.length, iterations, "one state object per subagent lifecycle");
  for (const size of stateBytes) assert.ok(size < 400, `a state object stays counts-only (got ${size} bytes)`);
  assert.equal(out.lastLiveTotal, 33, "the last lifecycle's running total is exactly its steps' sum");
  assert.equal(out.notified > 0, true, "the store listeners ran");
  assert.equal(out.streamed, iterations * 2, "every message event was forwarded to the existing listeners");

  // Growth is exactly the stock completed-subagent retention: one agent and one cleanup timer
  // per lifecycle, nothing per usage event, and both listener sets stay at their test sizes.
  assert.equal(before.agents, 0, "the store starts empty");
  assert.equal(before.cleanupTimers, 0, "no cleanup timers before the run");
  assert.equal(retained.agents, iterations, "one store entry per subagent, none extra");
  assert.equal(retained.cleanupTimers, iterations, "one stock cleanup timer per subagent, none extra");
  assert.equal(retained.eventStores, 0, "no task-event stores (no progress text was produced)");
  assert.equal(retained.listeners, 1, "no store listener was added by the run");
  assert.equal(retained.streamListeners, 1, "no stream listener was added by the run");

  // After the stock cleanup, nothing per-subagent survives anywhere.
  assert.equal(after.agents, 0, "no subagent state survives the cleanup");
  assert.equal(after.cleanupTimers, 0, "no cleanup timer survives the cleanup");
  assert.equal(after.eventStores, 0, "no event store survives the cleanup");
  assert.equal(after.listeners, 1, "listener count is unchanged by the cleanup");
  assert.equal(after.streamListeners, 1, "stream listener count is unchanged by the cleanup");
  assert.equal(out.snapshotAgentsAfterCleanup, 0, "the snapshot cache holds no subagent after the cleanup");
});

test("live tokens: three unique anchors, one counted patch, atomic on drift", (t) => {
  const bundlePath = deployedBundleOrSkip(t);
  if (!bundlePath) return;
  const bundle = readFileSync(bundlePath, "utf8");
  const anchors = [
    "    const state2 = {\n" +
      "      agentId: existingAgentId || null,\n" +
      "      conversationId: existingConversationId || null,\n" +
      "      finalResult: null,\n" +
      "      finalError: null,\n" +
      "      enqueueReceipt: null,\n" +
      "      resultStats: null,\n" +
      "      displayedToolCalls: new Set,\n" +
      "      toolCallStatuses: new Map\n" +
      "    };",
    "        if (event.message_type === \"assistant_message\" || event.message_type === \"reasoning_message\") {\n" +
      "          handleTextChunk(event, state2, subagentId);\n" +
      "        } else if (event.message_type !== \"usage_statistics\") {\n" +
      "          flushPendingProgressText(state2, subagentId);\n" +
      "        }",
    "function processStreamEvent(line, state2, subagentId) {",
  ];
  const drifts = [
    "      resultStats: null,",
    "          flushPendingProgressText(state2, subagentId);",
    "processStreamEvent(line, state2, subagentId)",
  ];
  for (const anchor of anchors) {
    assert.equal(count(bundle, anchor), 1, "each anchor is unique in the deployed bundle");
  }
  const result = patchedBundle(bundle);
  assert.equal(result.skippedPatches, 0, "no patch may fail-open against the deployed bundle");
  assert.equal(count(result.source, "// lcp-live-tokens"), 3, "one marker per site");
  assert.ok(result.source.includes("usageTokens: 0,"), "the running total is born with the per-subagent state");
  assert.ok(
    result.source.includes("function recordLiveUsageStatistics(event, state2, subagentId) {"),
    "the live-recording helper is injected next to processStreamEvent",
  );
  for (const drift of drifts) {
    const drifted = bundle.replace(drift, () => drift + " /*drift*/");
    assert.notEqual(drifted, bundle, "the drift applies");
    const driftedResult = patchLettaCodeSourceResultForTest(drifted);
    assert.equal(driftedResult.appliedPatches, result.appliedPatches - 1, "exactly one counted patch stops applying");
    assert.equal(driftedResult.skippedPatches, 1, "and the skip is counted, not silent");
    assert.equal(count(driftedResult.source, "// lcp-live-tokens"), 0, "nothing of the patch lands on drift");
  }
});
