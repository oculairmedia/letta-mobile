import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { patchLettaCodeSourceResultForTest } from "../letta-code-patch-loader.mjs";
import { resolveDeployedLettaBundle } from "./bundle-fixtures.ts";

const anchor = `  if (params.command.run_id && (!hasCurrentTurn || params.command.run_id !== lifecycleSnapshot.runId)) {
    return false;
  }
  if (!hasCurrentTurn && !hasPendingApprovals) {
    return false;
  }`;
const path = process.env.LETTA_CLI_PATH_REAL || resolveDeployedLettaBundle();

test("orphan cancellation and current turn protection", async () => {
  assert.ok(path);
  const source = patchLettaCodeSourceResultForTest(readFileSync(path, "utf8")).source;
  const start = source.indexOf("async function handleAbortMessageInput(listener, params, deps = {}) {");
  const end = source.indexOf("\nasync function handleCwdChange(", start);
  assert.ok(start >= 0 && end > start);
  const defaults = ["getActiveRuntime", "getPendingControlRequestCount", "getPendingControlRequests", "getOrCreateScopedRuntime", "getRecoveredApprovalStateForScope", "stashRecoveredApprovalInterrupts", "rejectPendingApprovalResolvers", "emitRuntimeStateUpdates", "emitInterruptedStatusDelta", "scheduleQueuePump"];
  const make = new Function(...defaults, "getBackend", `return (${source.slice(start, end)});`);
  const listener = {};
  let active: string | undefined;
  let defaultActive: string | undefined;
  let calls = 0;
  const backend = {
    async cancelConversationRun(conversation: string, requested?: string) {
      calls++;
      const id = requested ?? active;
      if (!id) return {};
      if (conversation !== "conv" || id !== active) return { [id]: "failed" };
      active = undefined;
      return { [id]: "cancelled" };
    },
    async cancelRun(agentId: string, runId: string) {
      calls++;
      if (agentId !== "agent" || runId !== defaultActive) return { [runId]: "failed" };
      defaultActive = undefined;
      return { [runId]: "cancelled" };
    },
    async cancelConversation(cancelId: string) {
      calls++;
      if (cancelId !== "agent" || !defaultActive) return {};
      const id = defaultActive;
      defaultActive = undefined;
      return { [id]: "cancelled" };
    },
  };
  const fn = make(...defaults.map(() => () => {}), () => backend);
  async function abort(runId?: string, current = false, conversationId = "conv", agentId = "agent") {
    const lifecycle = { snapshot: () => current ? { kind: "active", runId: "current" } : { kind: "idle" } };
    return fn(listener, { command: { runtime: { agent_id: agentId, conversation_id: conversationId }, run_id: runId } }, {
      getActiveRuntime: () => listener, getPendingControlRequestCount: () => 0,
      getOrCreateScopedRuntime: () => ({ turnLifecycle: lifecycle }),
    });
  }
  active = "orphan"; assert.equal(await abort("orphan"), true); assert.equal(active, undefined);
  active = "orphan"; assert.equal(await abort(), true); assert.equal(active, undefined);
  assert.equal(await abort(), false);
  active = "other"; assert.equal(await abort("orphan"), false); assert.equal(active, "other");
  defaultActive = "def-orphan"; assert.equal(await abort("def-orphan", false, "default", "other-agent"), false); assert.equal(defaultActive, "def-orphan");
  assert.equal(await abort("def-orphan", false, "default", "agent"), true); assert.equal(defaultActive, undefined);
  defaultActive = "def-orphan"; assert.equal(await abort(undefined, false, "default", "agent"), true); assert.equal(defaultActive, undefined);
  const before = calls; assert.equal(await abort("wrong", true), false); assert.equal(calls, before);
});

test("pinned anchors apply once and drift is atomic", () => {
  assert.ok(path);
  const raw = readFileSync(path, "utf8");
  assert.equal(raw.split(anchor).length, 2);
  const result = patchLettaCodeSourceResultForTest(raw);
  assert.equal(result.appliedPatches, 43); assert.equal(result.skippedPatches, 0);
  const drift = patchLettaCodeSourceResultForTest(raw.replace(anchor, anchor.replace("return false;", "return  false;")));
  assert.equal(drift.appliedPatches, 42); assert.equal(drift.skippedPatches, 1);
  assert.ok(!drift.source.includes("lcp-abort-orphaned-run"));
});
