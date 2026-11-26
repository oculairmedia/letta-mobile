import { test } from "node:test";
import assert from "node:assert/strict";
import { spawn, type ChildProcess } from "node:child_process";
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, utimesSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { resolveDeployedLettaBundle } from "./bundle-fixtures.ts";
import { patchLettaCodeSourceResultForTest } from "../letta-code-patch-loader.mjs";

const DEAD_PID_SCRIPT = "process.stdout.write(String(process.pid) + '\\n'); setInterval(() => {}, 1000);";
const MARK = "// lcp-conversation-turn-lock";
const HELPER_START = "globalThis.__lcpTurnLock ??= (() => {";
const HELPER_END = "\n})();\n";

// The stock lines the eight sites rewrite. Each must occur exactly once in the bundle.
const SITES = {
  appServerTry: "  try {\n    runtime.lastTerminalLoopErrorMessage = null;\n    runtime.lastTerminalLoopErrorRunId = null;\n",
  appServerWait: "    telemetry.setCurrentAgentId(agentId ?? null);\n    let turnToolContextId = null;\n    const setup = await prepareListenerTurn({\n",
  appServerRelease: "      releaseListenerTurnContext({ runtime, agentId, conversationId });\n    }\n    evictConversationRuntimeIfIdle(runtime);\n  }\n}\nvar init_turn = __esm(() => {\n",
  headlessStart: '  markMilestone("HEADLESS_CONVERSATION_READY");\n  setConversationId(conversationId);\n',
  backendTurn: "      throw new Error(`Conversation ${conversationId} already has an active run (${activeRun.id})`);\n    }\n",
  backendCancel: "    } else {\n      this.store.settleInterruptedToolCalls(conversationIdOrAgentId);\n    }\n    if (runId) {\n",
  syncRecovery: '  if (sameActiveScope && runtime.turnLifecycle.kind !== "idle") {\n    clearRecoveredApprovalState(runtime);\n    return;\n  }\n',
  sendReceipt: '      (deps.spawnLocal ?? spawnDetached)(launcher.command, launcher.args);\n      return {\n        content: JSON.stringify({\n          status: "spawned",\n',
};

/** What a child process runs and the temp dir that holds its lock directory. */
type ChildSpec = { script: string; tmp: string };
/** A scratch dir, its store and the helper source: enough to start a process that takes a lock. */
type HolderSpec = { helper: string; storageDir: string; tmp: string; agentId?: string };

type TurnLock = {
  acquire: (backend: unknown, agentId: string | null, conversationId: string, holder: string,
    options?: { signal?: AbortSignal; onWait?: (owner: unknown) => void }) => Promise<() => void>;
  holder: (backend: unknown, agentId: string | null, conversationId: string) => { pid: number } | null;
  heldElsewhere: (backend: unknown, conversationOrAgentId: string) => boolean;
  assertNotHeldElsewhere: (backend: unknown, conversationId: string, agentId: string) => void;
};

function bundleOrSkip(t: { skip: (reason: string) => void }): string | null {
  const path = resolveDeployedLettaBundle();
  if (!path) {
    t.skip("no deployed letta-code bundle on this host");
    return null;
  }
  return readFileSync(path, "utf8");
}

let patchedCache: string | null = null;
function patchedOrSkip(t: { skip: (reason: string) => void }): string | null {
  if (patchedCache) return patchedCache;
  const bundle = bundleOrSkip(t);
  if (!bundle) return null;
  patchedCache = patchLettaCodeSourceResultForTest(bundle).source;
  return patchedCache;
}

/** The injected helper as a standalone script that leaves `globalThis.__lcpTurnLock` behind. */
function helperSource({ patched }: { patched: string }): string {
  const from = patched.indexOf(HELPER_START);
  assert.notEqual(from, -1, "the patched bundle carries the turn-lock helper");
  const to = patched.indexOf(HELPER_END, from);
  assert.notEqual(to, -1);
  return patched.slice(from, to + HELPER_END.length);
}

// The real temp dir, read before any test points TMPDIR at a scratch dir.
const BASE_TMP = tmpdir();
// Every child and scratch dir this file creates; removed by each scratch's cleanup and again when
// the runner exits or is signalled (a timed-out run must not leave holders or lock files behind).
const children = new Set<ChildProcess>();
const scratchDirs = new Set<string>();
function reapAll() {
  for (const child of children) child.kill("SIGKILL");
  for (const dir of scratchDirs) rmSync(dir, { recursive: true, force: true });
}
process.on("exit", reapAll);
for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.once(signal, () => {
    reapAll();
    process.exit(128 + (signal === "SIGINT" ? 2 : 15));
  });
}

/** Starts the helper in a child with its lock directory under [tmp]; resolves on its first stdout line. */
function startChild({ script, tmp }: ChildSpec): { child: ChildProcess; firstLine: Promise<string> } {
  const child = spawn(process.execPath, ["-e", script], { env: { PATH: process.env.PATH ?? "", TMPDIR: tmp }, stdio: ["ignore", "pipe", "inherit"] });
  children.add(child);
  child.once("exit", () => children.delete(child));
  const firstLine = new Promise<string>((resolve, reject) => {
    child.stdout!.once("data", (data) => resolve(String(data).trim()));
    child.once("exit", (code) => reject(new Error(`child exited early with ${code}`)));
  });
  firstLine.catch(() => {});
  return { child, firstLine };
}

/** A process that loads the helper, takes the lock for one conversation and holds it until killed. */
function startHolder({ helper, storageDir, tmp, agentId = "agent-a" }: HolderSpec): Promise<ChildProcess> {
  const script = `${helper}
const backend = { getLocalStorageDir: () => ${JSON.stringify(storageDir)} };
globalThis.__lcpTurnLock.acquire(backend, ${JSON.stringify(agentId)}, "default", "holder test").then(() => {
  process.stdout.write("held\\n");
  setInterval(() => {}, 1000);
});`;
  const { child, firstLine } = startChild({ script, tmp });
  return firstLine.then(() => child);
}

/** Loads the helper in this process with its lock directory under [tmp]; returns the restore hook. */
function loadHelperHere({ helper, tmp }: { helper: string; tmp: string }): { lock: TurnLock; restore: () => void } {
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

/** A scratch lock directory, a store path and the helper loaded in this process. */
function scratch(t: { skip: (reason: string) => void }) {
  const patched = patchedOrSkip(t);
  if (!patched) return null;
  const tmp = mkdtempSync(join(BASE_TMP, "lcp-turn-lock-"));
  scratchDirs.add(tmp);
  const storageDir = join(tmp, "store");
  const helper = helperSource({ patched });
  const { lock, restore } = loadHelperHere({ helper, tmp });
  const backend = { getLocalStorageDir: () => storageDir };
  const lockDir = () => readdirSync(tmp).find((entry) => entry.startsWith("letta-turn-locks-"));
  const lockFiles = () => {
    const dir = lockDir();
    return dir ? readdirSync(join(tmp, dir)).filter((entry) => entry.endsWith(".lock")) : [];
  };
  return {
    patched, tmp, storageDir, helper, lock, backend, lockFiles,
    lockDirPath: () => join(tmp, lockDir() ?? "letta-turn-locks-x"),
    cleanup: () => {
      for (const child of children) child.kill("SIGKILL");
      restore();
      rmSync(tmp, { recursive: true, force: true });
      scratchDirs.delete(tmp);
    },
  };
}

/** The patched App Server turn function with its collaborators replaced. */
function appServerTurn(patched: string, deps: {
  backend: unknown;
  emitted: unknown[];
  prepare: () => Promise<unknown>;
  lifecycle: { abort: AbortController };
  finished: string[];
}) {
  const from = patched.indexOf("async function handleIncomingMessageInner(msg, socket, runtime");
  const to = patched.indexOf("var init_turn = __esm(() => {", from);
  assert.ok(from > 0 && to > from);
  const body = patched.slice(from, to);
  // The real finishTurn ends the lifecycle lease; the turn's finally checks it.
  let current = true;
  const names: Record<string, unknown> = {
    normalizeCwdAgentId: (id: string) => id || null,
    getConversationWorkingDirectory: () => "/tmp",
    getOrCreateConversationPermissionModeStateRef: () => ({}),
    createTurnCorrelation: () => ({ clientMessageIds: [] }),
    createTurnDurabilityOwnership: () => ({ record: () => ({}), recordInput: () => {}, durableInputIdentities: [], terminalConsumerIds: [] }),
    createBuffers: () => ({}),
    createTurnFinalizer: () => ({
      finalized: false,
      finishClaimedTurn: () => {},
      finishIfInterrupted: () => false,
      noteFinalization: () => {},
      finishTurn: (options: { stopReason: string }) => { current = false; deps.finished.push(options.stopReason); return { finished: true }; },
    }),
    setTurnLoopStatus: () => {},
    clearRecoveredApprovalStateUnlessRetained: () => {},
    emitRuntimeStateUpdates: () => {},
    telemetry: { setCurrentAgentId: () => {} },
    getBackend: () => deps.backend,
    emitStatusDelta: (_socket: unknown, _runtime: unknown, params: unknown) => deps.emitted.push(params),
    prepareListenerTurn: deps.prepare,
    trackBoundaryError: () => {},
    populateInterruptQueue: () => {},
    emitInterruptApprovals: () => {},
    getConsumerLoopErrorMessage: () => "error",
    getTranscriptLoopErrorMessage: () => "error",
    isDebugEnabled: () => false,
    runListenerTurnCleanup: async () => {},
    releaseListenerTurnContext: () => {},
    evictConversationRuntimeIfIdle: () => {},
    crypto: globalThis.crypto,
  };
  const fn = new Function(...Object.keys(names), `${body}\nreturn handleIncomingMessageInner;`)(...Object.values(names));
  const runtime = {
    activeRunId: null,
    activeConnectionId: null,
    listener: {},
    turnLifecycle: {
      begin: () => ({ id: "lease", signal: deps.lifecycle.abort.signal }),
      isCurrent: () => current,
    },
  };
  return (msg: Record<string, unknown>) => fn(msg, {}, runtime);
}

test("lcp-conversation-turn-lock: one counted patch on the deployed bundle, every site rewritten once", (t) => {
  const bundle = bundleOrSkip(t);
  if (!bundle) return;
  for (const [name, token] of Object.entries(SITES)) {
    assert.equal(bundle.split(token).length - 1, 1, `stock anchor ${name} is unique`);
  }
  const patched = patchLettaCodeSourceResultForTest(bundle);
  assert.equal(patched.skippedPatches, 0, "no patch may fail open against the deployed bundle");
  assert.equal(patched.source.split(MARK).length - 1, Object.keys(SITES).length + 1, "every site and the helper carry the marker");

  // Drift: one anchor gone skips this patch only, and says so; the other patches are unchanged.
  const drifted = patchLettaCodeSourceResultForTest(bundle.replace(SITES.headlessStart, SITES.headlessStart.replace("setConversationId", "setConversationId2")));
  assert.equal(drifted.appliedPatches, patched.appliedPatches - 1, "exactly one counted patch");
  assert.equal(drifted.skippedPatches, 1, "a missing anchor is counted as skipped, not silently ignored");
  assert.equal(drifted.source.includes(MARK), false, "nothing is half-applied");
});

test("lcp-conversation-turn-lock: the receipt says queued for a busy target and spawned for an idle one", async (t) => {
  const s = scratch(t);
  if (!s) return;
  const holder = await startHolder(s);
  try {
    const from = s.patched.indexOf("    if (!backend3.capabilities.environmentRouting) {\n      const launcher = resolveSubagentLauncher(");
    assert.notEqual(from, -1);
    const to = s.patched.indexOf("    const receipt = await (deps.enqueue ?? enqueueConversationMessage)(", from);
    const spawned: string[][] = [];
    const run = new Function("backend3", "destination", "deps", "resolveSubagentLauncher", "buildLocalSendArgs", "buildAgentSendReminder", "sender", "args",
      `${s.patched.slice(from, to)}\n return null;`);
    const receipt = (agentId: string, conversationId: string) => JSON.parse(run(
      { ...s.backend, capabilities: { environmentRouting: false } },
      { agentId, conversationId },
      { spawnLocal: (command: string, args: string[]) => spawned.push([command, ...args]) },
      (args: string[]) => ({ command: "letta", args }),
      (_destination: unknown, message: string) => [message],
      () => "",
      {},
      { message: "hello" },
    ).content);
    const busy = receipt("agent-a", "default");
    assert.equal(busy.status, "queued", "the sender is told the message waits for the current turn");
    assert.equal(busy.delivery, "after_current_turn");
    assert.equal(receipt("agent-b", "default").status, "spawned", "an idle default conversation is reported as spawned");
    assert.equal(receipt("agent-a", "local-conv-idle").status, "spawned", "an idle conversation is reported as spawned");
    assert.equal(spawned.length, 3, "every message is still delivered");
  } finally {
    holder.kill("SIGKILL");
    s.cleanup();
  }
});

test("lcp-conversation-turn-lock: a second writer waits for a live holder and takes over when it dies", async (t) => {
  const s = scratch(t);
  if (!s) return;
  const holder = await startHolder(s);
  try {
    assert.throws(() => s.lock.assertNotHeldElsewhere(s.backend, "default", "agent-a"), /busy with another active run/);
    assert.equal(s.lock.heldElsewhere(s.backend, "agent-a"), true, "a cancel by agent id sees the other process's turn");
    let acquired = false;
    const waiting = s.lock.acquire(s.backend, "agent-a", "default", "waiter").then((release) => { acquired = true; return release; });
    await new Promise((resolve) => setTimeout(resolve, 600));
    assert.equal(acquired, false, "the waiter does not start while the holder's turn is live");
    holder.kill("SIGKILL");
    const release = await waiting;
    assert.equal(s.lock.holder(s.backend, "agent-a", "default")?.pid, process.pid);
    release();
    assert.equal(s.lock.holder(s.backend, "agent-a", "default"), null, "release frees the conversation");
  } finally {
    holder.kill("SIGKILL");
    s.cleanup();
  }
});

test("lcp-conversation-turn-lock: an empty or garbage lock file is reclaimed once it is stale, not honoured for ever", async (t) => {
  const s = scratch(t);
  if (!s) return;
  try {
    // Learn the lock path, then leave a torn file in its place.
    const release = await s.lock.acquire(s.backend, "agent-a", "default", "probe");
    const [name] = s.lockFiles();
    assert.ok(name);
    release();
    const file = join(s.lockDirPath(), name!);
    for (const content of ["", "{\"pid\":"]) {
      writeFileSync(file, content);
      const probe = new AbortController();
      const fresh = s.lock.acquire(s.backend, "agent-a", "default", "fresh", { signal: probe.signal }).then(() => "acquired", () => "aborted");
      await new Promise((resolve) => setTimeout(resolve, 400));
      probe.abort();
      assert.equal(await fresh, "aborted", "a just-written unreadable file may be a writer mid-create, so it is honoured briefly");
      const old = new Date(Date.now() - 60_000);
      utimesSync(file, old, old);
      const timeout = new AbortController();
      const timer = setTimeout(() => timeout.abort(new Error(`a stale ${JSON.stringify(content)} lock file blocked the conversation`)), 3000);
      const releaseAfter = await s.lock.acquire(s.backend, "agent-a", "default", "after", { signal: timeout.signal });
      clearTimeout(timer);
      assert.equal(s.lock.holder(s.backend, "agent-a", "default")?.pid, process.pid);
      releaseAfter();
      assert.equal(existsSync(file), false);
    }
  } finally {
    s.cleanup();
  }
});

test("lcp-conversation-turn-lock: a garbage lock file with a future mtime is reclaimed, not honoured for ever", async (t) => {
  const s = scratch(t);
  if (!s) return;
  try {
    const release = await s.lock.acquire(s.backend, "agent-a", "default", "probe");
    const [name] = s.lockFiles();
    assert.ok(name);
    release();
    const file = join(s.lockDirPath(), name!);
    writeFileSync(file, "{garbage");
    const future = new Date(Date.now() + 24 * 60 * 60 * 1000);
    utimesSync(file, future, future);
    const timeout = new AbortController();
    const timer = setTimeout(() => timeout.abort(new Error("a future-mtime lock file blocked the conversation")), 3000);
    try {
      const releaseAfter = await s.lock.acquire(s.backend, "agent-a", "default", "after", { signal: timeout.signal });
      assert.equal(s.lock.holder(s.backend, "agent-a", "default")?.pid, process.pid);
      releaseAfter();
    } finally {
      clearTimeout(timer);
    }
    assert.equal(existsSync(file), false);
  } finally {
    s.cleanup();
  }
});

test("lcp-conversation-turn-lock: two contenders racing to reclaim one stale lock, only one acquires", async (t) => {
  const s = scratch(t);
  if (!s) return;
  try {
    // A lock left by a process that is gone: its pid answers ESRCH.
    const release = await s.lock.acquire(s.backend, "agent-a", "default", "probe");
    const [name] = s.lockFiles();
    release();
    const dead = startChild({ script: DEAD_PID_SCRIPT, tmp: s.tmp });
    const deadPid = Number(await dead.firstLine);
    dead.child.kill("SIGKILL");
    await new Promise((resolve) => dead.child.once("exit", resolve));
    writeFileSync(join(s.lockDirPath(), name!), JSON.stringify({ pid: deadPid, start: null, token: "dead-owner", holder: "dead" }));
    const contender = (label: string) => startChild({ tmp: s.tmp, script: `${s.helper}
const backend = { getLocalStorageDir: () => ${JSON.stringify(s.storageDir)} };
globalThis.__lcpTurnLock.acquire(backend, "agent-a", "default", ${JSON.stringify(label)}).then(() => {
  process.stdout.write("held\\n");
  setInterval(() => {}, 1000);
});` });
    const racers = [contender("racer 1"), contender("racer 2")];
    const held: number[] = [];
    racers.forEach((racer, index) => racer.firstLine.then(() => held.push(index), () => {}));
    await new Promise((resolve) => setTimeout(resolve, 2500));
    assert.equal(held.length, 1, "exactly one contender owns the reclaimed conversation");
    const owner = s.lock.holder(s.backend, "agent-a", "default");
    assert.equal(owner?.pid, racers[held[0]!]!.child.pid, "the lock file names the contender that acquired");
  } finally {
    s.cleanup();
  }
});

test("lcp-conversation-turn-lock: a contender that reclaims while another is mid-reclaim does not end up sharing the lock", async (t) => {
  const s = scratch(t);
  if (!s) return;
  // The node:fs object the helper gets from process.getBuiltinModule; its methods can be wrapped.
  const fs = process.getBuiltinModule("node:fs") as typeof import("node:fs");
  const realUnlink = fs.unlinkSync;
  const abort = new AbortController();
  try {
    const release = await s.lock.acquire(s.backend, "agent-a", "default", "probe");
    const [name] = s.lockFiles();
    release();
    const file = join(s.lockDirPath(), name!);
    const dead = startChild({ script: DEAD_PID_SCRIPT, tmp: s.tmp });
    const deadPid = Number(await dead.firstLine);
    dead.child.kill("SIGKILL");
    await new Promise((resolve) => dead.child.once("exit", resolve));
    writeFileSync(file, JSON.stringify({ pid: deadPid, start: null, token: "dead-owner", holder: "dead" }));
    // Two independent helper instances (two contenders), each with its own held-lock table.
    const first = s.lock;
    delete (globalThis as { __lcpTurnLock?: unknown }).__lcpTurnLock;
    new Function(s.helper)();
    const second = (globalThis as unknown as { __lcpTurnLock: TurnLock }).__lcpTurnLock;
    const acquired: string[] = [];
    // The worst interleaving: the second contender runs its whole reclaim and create in the
    // moment between the first contender's last check of the dead lock and its unlink.
    let interleaved = false;
    fs.unlinkSync = ((path: import("node:fs").PathLike) => {
      if (!interleaved && String(path) === file) {
        interleaved = true;
        void second.acquire(s.backend, "agent-a", "default", "second", { signal: abort.signal })
          .then((r) => { acquired.push("second"); return r; }, () => {});
      }
      return realUnlink(path);
    }) as typeof fs.unlinkSync;
    void first.acquire(s.backend, "agent-a", "default", "first", { signal: abort.signal })
      .then((r) => { acquired.push("first"); return r; }, () => {});
    await new Promise((resolve) => setTimeout(resolve, 800));
    assert.equal(interleaved, true, "the interleaving ran");
    assert.equal(acquired.length, 1, `exactly one contender owns the conversation (got ${acquired.join(", ") || "none"})`);
  } finally {
    fs.unlinkSync = realUnlink;
    abort.abort();
    s.cleanup();
  }
});

test("lcp-conversation-turn-lock: a release by a former owner cannot remove a lock another process took since", async (t) => {
  const s = scratch(t);
  if (!s) return;
  try {
    const staleRelease = await s.lock.acquire(s.backend, "agent-a", "default", "former owner");
    const [name] = s.lockFiles();
    const file = join(s.lockDirPath(), name!);
    // The lock was reclaimed from under the former owner and retaken by another process.
    rmSync(file);
    const holder = await startHolder(s);
    const retaken = readFileSync(file, "utf8");
    staleRelease();
    assert.equal(existsSync(file), true, "the former owner's release leaves the new owner's lock in place");
    assert.equal(readFileSync(file, "utf8"), retaken);
    assert.equal(s.lock.holder(s.backend, "agent-a", "default")?.pid, holder.pid);
  } finally {
    s.cleanup();
  }
});

test("lcp-conversation-turn-lock: the lock file is created complete, never empty", async (t) => {
  const s = scratch(t);
  if (!s) return;
  try {
    const release = await s.lock.acquire(s.backend, "agent-a", "local-conv-x", "probe");
    const [name] = s.lockFiles();
    const owner = JSON.parse(readFileSync(join(s.lockDirPath(), name!), "utf8"));
    assert.equal(owner.pid, process.pid);
    assert.deepEqual(readdirSync(s.lockDirPath()).filter((entry) => entry.endsWith(".tmp")), [], "no temp file is left behind");
    release();
    assert.equal(existsSync(join(s.lockDirPath(), name!)), false);
  } finally {
    s.cleanup();
  }
});

test("lcp-conversation-turn-lock: a cancelled wait ends at once and leaves no lock behind", async (t) => {
  const s = scratch(t);
  if (!s) return;
  const holder = await startHolder(s);
  try {
    const controller = new AbortController();
    const waits: unknown[] = [];
    const waiting = s.lock.acquire(s.backend, "agent-a", "default", "waiter", { signal: controller.signal, onWait: (owner) => waits.push(owner) });
    await new Promise((resolve) => setTimeout(resolve, 400));
    assert.equal(waits.length, 1, "the waiter reports once that the conversation is busy");
    // Abort just after a poll began its sleep, then look at the next tick: an abort that only
    // took effect at the next 250 ms poll (or never) has not settled the wait yet.
    await new Promise((resolve) => setTimeout(resolve, 30));
    let settled = false;
    waiting.then(() => { settled = true; }, () => { settled = true; });
    controller.abort(new Error("Cancelled by user"));
    await new Promise((resolve) => setImmediate(resolve));
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(settled, true, "the wait ends on the abort itself, not at the next poll or never");
    await assert.rejects(waiting, /Cancelled by user/);
    holder.kill("SIGKILL");
    await new Promise((resolve) => setTimeout(resolve, 300));
    assert.equal(s.lock.holder(s.backend, "agent-a", "default"), null, "the cancelled waiter never took the lock");
  } finally {
    holder.kill("SIGKILL");
    s.cleanup();
  }
});

test("lcp-conversation-turn-lock: an App Server turn waits inside its lifecycle, shows it, and Stop ends the wait", async (t) => {
  const s = scratch(t);
  if (!s) return;
  const holder = await startHolder(s);
  try {
    const emitted: unknown[] = [];
    const finished: string[] = [];
    const abort = new AbortController();
    let prepared = false;
    const turn = appServerTurn(s.patched, {
      backend: s.backend, emitted, finished, lifecycle: { abort },
      prepare: async () => { prepared = true; return { kind: "cancelled", reason: "x" }; },
    })({ agentId: "agent-a", conversationId: "default" });
    await new Promise((resolve) => setTimeout(resolve, 400));
    assert.equal(prepared, false, "the turn does not touch the conversation while another process owns it");
    assert.equal(emitted.length, 1, "the client is told the turn is waiting");
    assert.match(String((emitted[0] as { message: string }).message), /Waiting for the turn already running/);
    abort.abort(new Error("Cancelled by user"));
    await turn;
    assert.deepEqual(finished, ["cancelled"], "Stop finishes the waiting turn as cancelled");
    assert.equal(prepared, false);
    holder.kill("SIGKILL");
    await new Promise((resolve) => setTimeout(resolve, 300));
    assert.equal(s.lock.holder(s.backend, "agent-a", "default"), null, "a cancelled wait leaves no lock");
  } finally {
    holder.kill("SIGKILL");
    s.cleanup();
  }
});

test("lcp-conversation-turn-lock: an App Server turn holds the lock while it runs and releases it even when it throws", async (t) => {
  const s = scratch(t);
  if (!s) return;
  try {
    for (const outcome of ["returns", "throws"]) {
      let observed: unknown = "unset";
      const finished: string[] = [];
      await appServerTurn(s.patched, {
        backend: s.backend, emitted: [], finished, lifecycle: { abort: new AbortController() },
        prepare: async () => {
          observed = s.lock.holder(s.backend, "agent-a", "local-conv-1");
          if (outcome === "throws") throw new Error("turn failed");
          return { kind: "cancelled", reason: "done" };
        },
      })({ agentId: "agent-a", conversationId: "local-conv-1" });
      assert.ok(observed, `the conversation is locked while the turn runs (${outcome})`);
      assert.equal(s.lock.holder(s.backend, "agent-a", "local-conv-1"), null, `the lock is released after the turn (${outcome})`);
      assert.equal(finished.length, 1);
    }
  } finally {
    s.cleanup();
  }
});

test("lcp-conversation-turn-lock: a default-conversation turn without an agent id locks the backend's default agent", async (t) => {
  const s = scratch(t);
  if (!s) return;
  try {
    const backend = { ...s.backend, store: { resolveAgentIdForConversation: (id: string) => (id === "default" ? "agent-local-default" : null) } };
    const release = await s.lock.acquire(backend, null, "default", "no agent id");
    assert.equal(s.lock.holder(backend, "agent-local-default", "default")?.pid, process.pid, "same key as the backend's own default turn");
    release();
  } finally {
    s.cleanup();
  }
});

test("lcp-conversation-turn-lock: App Server sync does not turn another process's live tool call into a stale denial", async (t) => {
  const s = scratch(t);
  if (!s) return;
  const holder = await startHolder(s);
  try {
    const from = s.patched.indexOf("async function recoverApprovalStateForSync(runtime, scope, deps = {}, opts = {}) {");
    const to = s.patched.indexOf("  const initialEligibility = await getRecoveryEligibility(runtime);", from);
    assert.ok(from > 0 && to > from);
    let cleared = 0;
    let reachedRecovery = false;
    const run = (agentId: string) => {
      reachedRecovery = false;
      const fn = new Function("getBackend", "getResumeDataFromBackend", "readInterruptedTurn", "clearRecoveredApprovalState", "hasInterruptedCacheForScope", "markReached",
        `${s.patched.slice(from, to)}  markReached(); return "recovering";\n}\nreturn recoverApprovalStateForSync;`)(
        () => s.backend, () => ({}), () => null, () => { cleared += 1; }, () => false, () => { reachedRecovery = true; });
      return fn({ agentId, conversationId: "default", turnLifecycle: { kind: "idle" }, pendingApprovalResolvers: new Map(), listener: {} },
        { agent_id: agentId, conversation_id: "default" });
    };
    assert.equal(await run("agent-a"), undefined, "a conversation whose turn runs in another process is left alone");
    assert.equal(reachedRecovery, false, "no stale-approval recovery for another process's live turn");
    assert.equal(cleared, 1);
    assert.equal(await run("agent-b"), "recovering", "an idle conversation is recovered as before");
    assert.equal(reachedRecovery, true);
  } finally {
    holder.kill("SIGKILL");
    s.cleanup();
  }
});

test("lcp-conversation-turn-lock: a cancel with no local run leaves another process's tool calls alone", async (t) => {
  const s = scratch(t);
  if (!s) return;
  const holder = await startHolder(s);
  try {
    const from = s.patched.indexOf("  async cancelConversation(...args) {\n    const [conversationIdOrAgentId] = args;");
    assert.notEqual(from, -1);
    const to = s.patched.indexOf("  async cancelRun(...args) {", from);
    const settled: string[] = [];
    const Backend = new Function("localConversationKey", `return class { ${s.patched.slice(from, to)} };`)((c: string, a: string) => `${c}:${a}`);
    const backend = Object.assign(new Backend(), {
      ...s.backend,
      activeRunByConversation: new Map(),
      runs: new Map(),
      store: { resolveAgentIdForConversation: () => "agent-local-default", settleInterruptedToolCalls: (id: string) => settled.push(id) },
      findActiveRunByAgentId: () => undefined,
    });
    await backend.cancelConversation("agent-a");
    assert.deepEqual(settled, [], "the default conversation of agent-a is another process's live turn");
    await backend.cancelConversation("agent-b");
    assert.deepEqual(settled, ["agent-b"], "an idle conversation is settled as before");
  } finally {
    holder.kill("SIGKILL");
    s.cleanup();
  }
});
