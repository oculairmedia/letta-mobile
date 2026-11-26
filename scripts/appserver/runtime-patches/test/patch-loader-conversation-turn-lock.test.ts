import { test } from "node:test";
import assert from "node:assert/strict";
import { spawn, type ChildProcess } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { resolveDeployedLettaBundle } from "./bundle-fixtures.ts";
import { patchLettaCodeSourceResultForTest } from "../letta-code-patch-loader.mjs";

const MARK = "// lcp-conversation-turn-lock";
const HELPER_START = "globalThis.__lcpTurnLock ??= (() => {";
const HELPER_END = "\n})();\n";

// The stock lines the four sites rewrite. Each must occur exactly once in the bundle.
const SITES = {
  appServerTurn: "async function handleIncomingMessage(...args) {\n  const [msg] = args;\n  notifyTurnStarted(msg);\n",
  headlessStart: '  markMilestone("HEADLESS_CONVERSATION_READY");\n  setConversationId(conversationId);\n',
  backendTurn: "      throw new Error(`Conversation ${conversationId} already has an active run (${activeRun.id})`);\n    }\n",
  sendReceipt: '      (deps.spawnLocal ?? spawnDetached)(launcher.command, launcher.args);\n      return {\n        content: JSON.stringify({\n          status: "spawned",\n',
};

function bundleOrSkip(t: { skip: (reason: string) => void }): string | null {
  const path = resolveDeployedLettaBundle();
  if (!path) {
    t.skip("no deployed letta-code bundle on this host");
    return null;
  }
  return readFileSync(path, "utf8");
}

/** The injected helper as a standalone script that leaves `globalThis.__lcpTurnLock` behind. */
function helperSource(patched: string): string {
  const from = patched.indexOf(HELPER_START);
  assert.notEqual(from, -1, "the patched bundle carries the turn-lock helper");
  const to = patched.indexOf(HELPER_END, from);
  assert.notEqual(to, -1);
  return patched.slice(from, to + HELPER_END.length);
}

/** A process that loads the helper, takes the lock for one conversation and holds it until killed. */
function startHolder(helper: string, storageDir: string, tmp: string): Promise<ChildProcess> {
  const script = `${helper}
const backend = { getLocalStorageDir: () => ${JSON.stringify(storageDir)} };
globalThis.__lcpTurnLock.acquire(backend, "agent-a", "default", "holder test").then(() => {
  process.stdout.write("held\\n");
  setInterval(() => {}, 1000);
});`;
  const child = spawn(process.execPath, ["-e", script], { env: { PATH: process.env.PATH ?? "", TMPDIR: tmp }, stdio: ["ignore", "pipe", "inherit"] });
  return new Promise((resolve, reject) => {
    child.stdout!.once("data", () => resolve(child));
    child.once("exit", (code) => reject(new Error(`holder exited early with ${code}`)));
  });
}

/** Loads the helper in this process with its lock directory under [tmp]; returns the restore hook. */
function loadHelperHere(helper: string, tmp: string): { lock: TurnLock; restore: () => void } {
  const previous = process.env.TMPDIR;
  process.env.TMPDIR = tmp;
  delete (globalThis as { __lcpTurnLock?: unknown }).__lcpTurnLock;
  new Function(helper)();
  const lock = (globalThis as unknown as { __lcpTurnLock: TurnLock }).__lcpTurnLock;
  const restore = () => {
    delete (globalThis as { __lcpTurnLock?: unknown }).__lcpTurnLock;
    if (previous === undefined) delete process.env.TMPDIR;
    else process.env.TMPDIR = previous;
  };
  return { lock, restore };
}

type TurnLock = {
  acquire: (backend: unknown, agentId: string, conversationId: string, holder: string) => Promise<() => void>;
  holder: (backend: unknown, agentId: string, conversationId: string) => { pid: number } | null;
  assertNotHeldElsewhere: (backend: unknown, conversationId: string, agentId: string) => void;
};

test("lcp-conversation-turn-lock: one counted patch on the deployed bundle, every site rewritten once", (t) => {
  const bundle = bundleOrSkip(t);
  if (!bundle) return;
  for (const [name, token] of Object.entries(SITES)) {
    assert.equal(bundle.split(token).length - 1, 1, `stock anchor ${name} is unique`);
  }
  const patched = patchLettaCodeSourceResultForTest(bundle);
  assert.equal(patched.skippedPatches, 0, "no patch may fail open against the deployed bundle");
  assert.equal(patched.source.split(MARK).length - 1, 5, "four call sites and the helper carry the marker");
  assert.equal(patched.source.includes('status: "queued",\n          conversation_id: destination.conversationId'), true);

  // Drift: one anchor gone skips this patch only, and says so; the other patches are unchanged.
  const drifted = patchLettaCodeSourceResultForTest(bundle.replace(SITES.headlessStart, SITES.headlessStart.replace("setConversationId", "setConversationId2")));
  assert.equal(drifted.appliedPatches, patched.appliedPatches - 1, "exactly one counted patch");
  assert.equal(drifted.skippedPatches, 1, "a missing anchor is counted as skipped, not silently ignored");
  assert.equal(drifted.source.includes(MARK), false, "nothing is half-applied");
});

test("lcp-conversation-turn-lock: a busy conversation queues the message instead of reporting a fresh spawn", async (t) => {
  const bundle = bundleOrSkip(t);
  if (!bundle) return;
  const patched = patchLettaCodeSourceResultForTest(bundle).source;
  const tmp = mkdtempSync(join(tmpdir(), "lcp-turn-lock-"));
  const storageDir = join(tmp, "store");
  const holder = await startHolder(helperSource(patched), storageDir, tmp);
  const { lock, restore } = loadHelperHere(helperSource(patched), tmp);
  try {
    const backend = { getLocalStorageDir: () => storageDir };

    // The send site, run as patched against a backend whose conversation another process owns.
    const from = patched.indexOf("    if (!backend3.capabilities.environmentRouting) {\n      const launcher = resolveSubagentLauncher(");
    assert.notEqual(from, -1);
    const to = patched.indexOf("    const receipt = await (deps.enqueue ?? enqueueConversationMessage)(", from);
    const site = patched.slice(from, to);
    const spawned: string[][] = [];
    const run = new Function("backend3", "destination", "deps", "resolveSubagentLauncher", "buildLocalSendArgs", "buildAgentSendReminder", "sender", "args",
      `${site}\n return null;`);
    const receipt = (conversationId: string) => run(
      { ...backend, capabilities: { environmentRouting: false } },
      { agentId: "agent-a", conversationId },
      { spawnLocal: (command: string, args: string[]) => spawned.push([command, ...args]) },
      (args: string[]) => ({ command: "letta", args }),
      (_destination: unknown, message: string) => [message],
      () => "",
      {},
      { message: "hello" },
    );
    const busy = JSON.parse(receipt("default").content);
    assert.equal(busy.status, "queued", "the sender is told the message waits for the current turn");
    assert.equal(busy.delivery, "after_current_turn");
    const idle = JSON.parse(receipt("local-conv-idle").content);
    assert.equal(idle.status, "spawned", "an idle conversation is unchanged");
    assert.equal(spawned.length, 2, "both messages are still delivered");

    // A second writer cannot start a local turn while the holder lives.
    assert.throws(() => lock.assertNotHeldElsewhere(backend, "default", "agent-a"), /busy with another active run/);
    let acquired = false;
    const waiting = lock.acquire(backend, "agent-a", "default", "waiter").then((release) => { acquired = true; return release; });
    await new Promise((resolve) => setTimeout(resolve, 600));
    assert.equal(acquired, false, "the waiter does not start while the holder's turn is live");

    // The holder dies without releasing (SIGKILL): its lock is reclaimed and the waiter runs.
    holder.kill("SIGKILL");
    const release = await waiting;
    assert.equal(acquired, true);
    assert.equal(lock.holder(backend, "agent-a", "default")?.pid, process.pid);
    release();
    assert.equal(lock.holder(backend, "agent-a", "default"), null, "release frees the conversation");
  } finally {
    holder.kill("SIGKILL");
    restore();
    rmSync(tmp, { recursive: true, force: true });
  }
});

test("lcp-conversation-turn-lock: an App Server turn holds the lock for its whole duration and releases it after", async (t) => {
  const bundle = bundleOrSkip(t);
  if (!bundle) return;
  const patched = patchLettaCodeSourceResultForTest(bundle).source;
  const tmp = mkdtempSync(join(tmpdir(), "lcp-turn-lock-"));
  const storageDir = join(tmp, "store");
  const { lock, restore } = loadHelperHere(helperSource(patched), tmp);
  try {
    const backend = { getLocalStorageDir: () => storageDir };
    const from = patched.indexOf("async function handleIncomingMessage(...args) {");
    const to = patched.indexOf("\n}\n", from) + 3;
    let finishInner!: () => void;
    let observedDuringTurn: unknown = "unset";
    const handle = new Function("getBackend", "notifyTurnStarted", "notifyTurnFinished", "handleIncomingMessageInner",
      `${patched.slice(from, to)}\nreturn handleIncomingMessage;`)(
      () => backend,
      () => {},
      () => {},
      () => new Promise<void>((resolve) => {
        observedDuringTurn = lock.holder(backend, "agent-a", "local-conv-1");
        finishInner = resolve;
      }),
    );
    const turn = handle({ agentId: "agent-a", conversationId: "local-conv-1" });
    await new Promise((resolve) => setTimeout(resolve, 50));
    assert.ok(observedDuringTurn, "the conversation is locked while the turn runs");
    finishInner();
    await turn;
    assert.equal(lock.holder(backend, "agent-a", "local-conv-1"), null, "the lock is released when the turn ends");
  } finally {
    restore();
    rmSync(tmp, { recursive: true, force: true });
  }
});
