import { resolveDeployedLettaBundle } from "./bundle-fixtures.ts";
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";

import { patchLettaCodeSourceResultForTest } from "../letta-code-patch-loader.mjs";

// lcp-conversation-list-mtime (letta-mobile-w4sqr). The behavioural tests drive the REAL
// LocalStore of the deployed letta.js in a child process: the production patch loader
// rewrites the bundle exactly as at App Server boot, local-store-export-hook.mjs exports the
// class, and a readFileSync spy (synced into the bundle's ESM bindings) counts every
// conversation.json read. Run with LCP_UNPATCHED=1 in the child to see the stock behaviour.

const LOADER_URL = new URL("../letta-code-patch-loader.mjs", import.meta.url).href;
const EXPORT_HOOK_URL = new URL("./local-store-export-hook.mjs", import.meta.url).href;
const PATCH_METHOD = "  lcpConversationRecordStat(recordPath) {";
const REFRESH_READ = "      const recordJson = readFileSync9(join22(conversationDir, \"conversation.json\"), \"utf8\");\n      const cachedJson";

type ChildResult = { result: Record<string, unknown>; stderr: string };

// A host that runs the App Server exports NODE_OPTIONS=--import=<deployed loader>; inheriting
// it would patch the bundle twice (deployed copy, then this one). Start the child clean.
/** Whether a parent environment variable may reach the child: not the loader hook and no LETTA_ settings. */
function inherited(name: string): boolean {
  return name !== "NODE_OPTIONS" && !name.startsWith("LETTA_");
}

function childEnv(bundle: string, storageDir: string): Record<string, string> {
  const env: Record<string, string> = {};
  for (const [name, value] of Object.entries(process.env)) {
    if (value === undefined || !inherited(name)) continue;
    env[name] = value;
  }
  return { ...env, LETTA_CLI_PATH_REAL: bundle, LCP_STORE_DIR: storageDir };
}

// Runs `body` with: LocalStore, storageDir, openStore(), listSummaries(store), takeReads()
// (conversation.json paths read since the last call), settle() (outlives the patch's racy
// window, so files just written become stampable), writeFromOtherProcess(path, text), out.
function runStore(t: { after: (fn: () => void) => void }, body: string, { patched = true } = {}): ChildResult {
  const bundle = resolveDeployedLettaBundle();
  assert.ok(bundle, "caller must skip when no deployed bundle exists");
  const storageDir = mkdtempSync(join(tmpdir(), "lcp-conv-mtime-"));
  t.after(() => rmSync(storageDir, { recursive: true, force: true }));
  const register = `import { register } from "node:module";\n` +
    (patched ? `register(${JSON.stringify(LOADER_URL)});\n` : "") +
    `register(${JSON.stringify(EXPORT_HOOK_URL)});\n`;
  const script = `
    import fs from "node:fs";
    import { syncBuiltinESMExports } from "node:module";
    import { spawnSync } from "node:child_process";
    import { join } from "node:path";
    import { pathToFileURL } from "node:url";
    const storageDir = process.env.LCP_STORE_DIR;
    const { LocalStore } = await import(pathToFileURL(process.env.LETTA_CLI_PATH_REAL).href);
    const realReadFileSync = fs.readFileSync;
    let reads = [];
    fs.readFileSync = function (path, ...rest) {
      if (String(path).endsWith("conversation.json")) reads.push(String(path));
      return realReadFileSync.call(this, path, ...rest);
    };
    syncBuiltinESMExports();
    const takeReads = () => { const taken = reads; reads = []; return taken; };
    const settle = () => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 2200);
    const openStore = () => new LocalStore("agent-1", { storageDir });
    const listSummaries = (store) => Object.fromEntries(store.listConversations({ agent_id: "agent-1", limit: 1000 }).map((c) => [c.id, c.summary]));
    const writeFromOtherProcess = (path, text) => {
      const child = spawnSync(process.execPath, ["-e", "require('fs').writeFileSync(process.argv[1], process.argv[2])", path, text]);
      if (child.status !== 0) throw new Error(String(child.stderr));
    };
    const out = {};
    ${body}
    process.stdout.write("\\nRESULT " + JSON.stringify(out) + "\\n");
  `;
  const child = spawnSync(process.execPath, [
    "--import", `data:text/javascript,${encodeURIComponent(register)}`,
    "--input-type=module", "--eval", script,
  ], { encoding: "utf8", env: childEnv(bundle, storageDir), timeout: 120_000 });
  assert.equal(child.status, 0, child.stderr);
  const line = child.stdout.split("\n").find((entry) => entry.startsWith("RESULT "));
  assert.ok(line, `child printed no RESULT line: ${child.stdout}\n${child.stderr}`);
  return { result: JSON.parse(line.slice("RESULT ".length)), stderr: child.stderr };
}

// Seeds `count` conversations through a first store (another App Server lifetime), waits out
// the racy window, then opens a fresh store as the one under test.
const SEED = (count: number) => `
  const writer = openStore();
  const ids = [];
  for (let i = 0; i < ${count}; i++) ids.push(writer.createConversation({ agent_id: "agent-1", summary: "seed-" + i }).id);
  settle();
  const store = openStore();
  const pathOf = (id) => join(store.conversationDirForKey(store.conversationKey(id, "agent-1")), "conversation.json");
  // The agent's "default" conversation has a record too; it is read but not listed.
  out.records = fs.readdirSync(join(storageDir, "conversations")).length;
  takeReads();
`;

function deployedBundleOrSkip(t: { skip: (reason: string) => void }): string | null {
  const bundlePath = resolveDeployedLettaBundle();
  if (!bundlePath) {
    t.skip("no deployed letta-code bundle (>= 0.27) on this host");
    return null;
  }
  return bundlePath;
}

test("lcp-conversation-list-mtime: applies exactly once on the deployed bundle and raises the boot count by one", (t) => {
  const bundlePath = deployedBundleOrSkip(t);
  if (!bundlePath) return;
  const bundle = readFileSync(bundlePath, "utf8");
  assert.equal(bundle.split(REFRESH_READ).length - 1, 1, "the stock refresh read is unique in the bundle");
  const patched = patchLettaCodeSourceResultForTest(bundle);
  assert.equal(patched.skippedPatches, 0, "no patch may fail-open against the deployed bundle");
  assert.equal(patched.source.split(PATCH_METHOD).length - 1, 1);
  assert.equal(patched.source.includes(REFRESH_READ), false, "the unconditional refresh read is gone");

  const drifted = patchLettaCodeSourceResultForTest(bundle.replace(REFRESH_READ, REFRESH_READ.replace("readFileSync9", "readFileSync10")));
  assert.equal(patched.appliedPatches, drifted.appliedPatches + 1, "this patch is exactly one counted patch");
  assert.equal(drifted.skippedPatches, 1);

  // The boot summary the deploy runbook checks comes from the real loader on the real file.
  const { stderr } = runStore(t, "out.loaded = typeof LocalStore;");
  assert.match(stderr, new RegExp(`lcp-patches applied=${patched.appliedPatches} skipped=0`));
});

test("lcp-conversation-list-mtime: a list of unchanged conversations reads no conversation.json after the first", (t) => {
  if (!deployedBundleOrSkip(t)) return;
  const body = SEED(40) + `
    out.first = Object.keys(listSummaries(store)).length;
    out.firstReads = takeReads().length;
    out.laterReads = [];
    for (let i = 0; i < 3; i++) { listSummaries(store); out.laterReads.push(takeReads().length); }
  `;
  const { result } = runStore(t, body);
  assert.equal(result["first"], 40);
  assert.equal(result["records"], 41, "40 seeded records plus the agent's default conversation");
  assert.equal(result["firstReads"], 41, "the first list reads each record once (the scan), not twice");
  assert.deepEqual(result["laterReads"], [0, 0, 0], "unchanged records must not be re-read");

  // Same scenario on the stock bundle: every list re-reads every record.
  const stock = runStore(t, body, { patched: false });
  assert.equal(stock.result["firstReads"], 82, "stock control: the scan, then the refresh again");
  assert.deepEqual(stock.result["laterReads"], [41, 41, 41], "stock control: the defect this patch removes");
});

test("lcp-conversation-list-mtime: a record changed by another process is re-read and listed, with stock eviction", (t) => {
  if (!deployedBundleOrSkip(t)) return;
  const { result } = runStore(t, SEED(5) + `
    listSummaries(store);
    takeReads();
    const key = store.conversationKey(ids[2], "agent-1");
    store.loadedConversationKeys.add(key);
    const record = JSON.parse(fs.readFileSync(pathOf(ids[2]), "utf8"));
    takeReads();
    writeFromOtherProcess(pathOf(ids[2]), JSON.stringify({ ...record, summary: "renamed elsewhere" }, null, 2) + "\\n");
    out.summary = listSummaries(store)[ids[2]];
    out.reads = takeReads().map((path) => path === pathOf(ids[2]));
    out.evicted = !store.loadedConversationKeys.has(key);
    out.recentReads = (listSummaries(store), takeReads().length);
    settle();
    listSummaries(store);
    takeReads();
    out.settledReads = (listSummaries(store), takeReads().length);
  `);
  assert.equal(result["summary"], "renamed elsewhere");
  assert.deepEqual(result["reads"], [true], "only the changed record is read");
  assert.equal(result["evicted"], true, "a real JSON change still evicts resident messages");
  assert.equal(result["recentReads"], 1, "a record written inside the racy window is not stamped yet");
  assert.equal(result["settledReads"], 0, "once outside the racy window it is stamped again");
});

test("lcp-conversation-list-mtime: writes by this process never leave a stale stamp", (t) => {
  if (!deployedBundleOrSkip(t)) return;
  const { result } = runStore(t, SEED(3) + `
    listSummaries(store);
    takeReads();
    store.updateConversation(ids[0], { summary: "renamed here" });
    out.ownWrite = listSummaries(store)[ids[0]];
    takeReads();
    settle();
    const record = JSON.parse(fs.readFileSync(pathOf(ids[0]), "utf8"));
    listSummaries(store);
    takeReads();
    writeFromOtherProcess(pathOf(ids[0]), JSON.stringify({ ...record, summary: "then renamed elsewhere" }, null, 2) + "\\n");
    out.afterExternal = listSummaries(store)[ids[0]];
  `);
  assert.equal(result["ownWrite"], "renamed here");
  assert.equal(result["afterExternal"], "then renamed elsewhere");
});

test("lcp-conversation-list-mtime: size catches a length change with identical timestamps; same-size residual is documented", (t) => {
  if (!deployedBundleOrSkip(t)) return;
  // Forge the stamp so mtime, ctime and inode all match the new file, i.e. a filesystem whose
  // timestamps did not move. Only the size differs in the first case; nothing differs in the second.
  const { result } = runStore(t, SEED(2) + `
    listSummaries(store);
    takeReads();
    const forge = (id) => {
      const key = store.conversationKey(id, "agent-1");
      const stats = fs.statSync(pathOf(id));
      const stamp = store.lcpConversationRecordStampByKey.get(key);
      store.lcpConversationRecordStampByKey.set(key, { ...stamp, mtimeMs: stats.mtimeMs, ctimeMs: stats.ctimeMs, ino: stats.ino });
    };
    const rewrite = (id, summary) => {
      const record = JSON.parse(fs.readFileSync(pathOf(id), "utf8"));
      fs.writeFileSync(pathOf(id), JSON.stringify({ ...record, summary }, null, 2) + "\\n");
    };
    rewrite(ids[0], "seed-0 grown");
    forge(ids[0]);
    rewrite(ids[1], "SEED-1");
    forge(ids[1]);
    takeReads();
    const listed = listSummaries(store);
    out.grown = listed[ids[0]];
    out.sameSize = listed[ids[1]];
  `);
  assert.equal(result["grown"], "seed-0 grown", "a length change is caught by size alone");
  // Residual risk: a same-size rewrite whose mtime AND ctime AND inode all equal the stamp is
  // not seen until the file changes again. The racy-window rule makes that need a clock step back.
  assert.equal(result["sameSize"], "seed-1");
});

test("lcp-conversation-list-mtime: a failing stat falls back to the stock read", (t) => {
  if (!deployedBundleOrSkip(t)) return;
  const { result } = runStore(t, SEED(4) + `
    listSummaries(store);
    takeReads();
    const realStatSync = fs.statSync;
    fs.statSync = function (path, ...rest) {
      if (String(path).endsWith("conversation.json")) throw new Error("EIO");
      return realStatSync.call(this, path, ...rest);
    };
    syncBuiltinESMExports();
    out.count = Object.keys(listSummaries(store)).length;
    out.reads = takeReads().length;
  `);
  assert.equal(result["count"], 4);
  assert.equal(result["reads"], result["records"], "no stat, no skip: every record is read");
});

test("lcp-conversation-list-mtime: a drifted or duplicated anchor is skipped, never misapplied", (t) => {
  const bundlePath = deployedBundleOrSkip(t);
  if (!bundlePath) return;
  const bundle = readFileSync(bundlePath, "utf8");
  const start = bundle.indexOf("  loadConversationRecordFromDir(conversationDir) {");
  const end = bundle.indexOf("        if (!statSync5(conversationDir).isDirectory())", start);
  assert.ok(start > 0 && end > start);
  const anchor = bundle.slice(start, end + "        if (!statSync5(conversationDir).isDirectory())".length);
  const baseline = patchLettaCodeSourceResultForTest(anchor);
  assert.equal(baseline.appliedPatches, 1);
  for (const input of [
    anchor.replace("statSync5(conversationDir)", "statSync6(conversationDir)"),
    anchor.replace("      if (existing && cachedJson === recordJson)\n", "      if (cachedJson === recordJson)\n"),
    anchor + "\n" + anchor,
  ]) {
    const result = patchLettaCodeSourceResultForTest(input);
    assert.equal(result.source, input, "nothing of the patch may land on drift");
    assert.equal(result.appliedPatches, 0);
    assert.equal(result.skippedPatches, baseline.skippedPatches + 1);
  }
});
