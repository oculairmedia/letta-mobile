import { resolveDeployedLettaBundle } from "./bundle-fixtures.ts";
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { spawnSync } from "node:child_process";

import { patchLettaCodeSourceResultForTest } from "../letta-code-patch-loader.mjs";

// Host-neutral agent store: lcp-conversation-uuid, lcp-ui-msg-uuid, lcp-memory-lf,
// lcp-session-cwd, lcp-agent-description and lcp-memory-dir-token. The static tests check each
// anchor against the deployed bundle; the behavioural tests drive the real, runtime-patched
// LocalStore and memory-repo helpers in a child process, the way the conversation-list-mtime
// tests do.

const LOADER_URL = new URL("../letta-code-patch-loader.mjs", import.meta.url).href;

// Like local-store-export-hook.mjs, but it also initialises the memory-git module and exports
// the helpers the line-ending and session-line tests call.
const EXPORT_HOOK_SOURCE = `
import { pathToFileURL } from "node:url";
const TARGET = process.env.LETTA_CLI_PATH_REAL ?? "";
const ENTRY = "await init_src5().then(() => exports_src2);";
export async function load(url, context, nextLoad) {
  const result = await nextLoad(url, context);
  if (!TARGET || url !== pathToFileURL(TARGET).href) return result;
  const source = typeof result.source === "string" ? result.source : Buffer.from(result.source).toString("utf8");
  if (source.split(ENTRY).length !== 2) throw new Error("host-neutral export hook: CLI entry not found exactly once");
  return {
    ...result,
    source: source.replace(ENTRY, () => "init_memory_git();\\ninit_local_store();\\n" +
      "export { LocalStore, initializeLocalMemoryRepo, prepareLocalOnlyMemoryRepoForGitOps, createLocalTranscriptSessionHeader, writeSessionEntryJsonl };"),
    shortCircuit: true,
  };
}
`;
const EXPORT_HOOK_URL = `data:text/javascript,${encodeURIComponent(EXPORT_HOOK_SOURCE)}`;

// `drift` is a substring of stock[0] that a test corrupts to simulate an upstream change.
type Site = { id: string; stock: string[]; patched: string[]; drift: string };

const SITES: Site[] = [
  {
    id: "lcp-conversation-uuid",
    drift: "${this.conversationSeq}",
    stock: ["      const conversationId = `${this.conversationIdPrefix}${this.conversationSeq}`;"],
    patched: ["      const conversationId = `${this.conversationIdPrefix}${randomUUID8()}`; // lcp-conversation-uuid"],
  },
  {
    id: "lcp-ui-msg-uuid",
    drift: "${this.localMessageSeq}",
    stock: ["    return `${this.localMessageIdPrefix}${this.localMessageSeq}`;"],
    patched: ["    return `${this.localMessageIdPrefix}${randomUUID8()}`; // lcp-ui-msg-uuid"],
  },
  {
    id: "lcp-memory-lf",
    drift: "\"commit.gpgsign\", \"false\"",
    stock: [
      '  if (await getLocalGitConfig(memoryDir, "commit.gpgsign") === null) {\n' +
        '    await setLocalGitConfig(memoryDir, "commit.gpgsign", "false");\n  }\n}',
      '  await prepareLocalOnlyMemoryRepoForGitOps(params.memoryDir, author);\n  if (pathspecs.length > 0) {',
    ],
    patched: [
      "async function __lcpEnsureMemoryLineEndings(memoryDir, author) {",
      "  await __lcpEnsureMemoryLineEndings(memoryDir, author);\n}",
      'pathspecs.push(".gitattributes"); // lcp-memory-lf\n  if (pathspecs.length > 0) {',
    ],
  },
  {
    id: "lcp-session-cwd",
    drift: "cwd: process.cwd()",
    stock: [
      "    timestamp: conversation.created_at ?? currentIsoTimestamp(),\n    cwd: process.cwd()\n  };",
      "      timestamp: input.createdAt ?? new Date().toISOString(),\n      cwd: process.cwd()\n    },",
    ],
    patched: [
      "    timestamp: conversation.created_at ?? currentIsoTimestamp(),\n    ...process.env.LETTA_WORKSPACE_ID ? { cwd: process.env.LETTA_WORKSPACE_ID } : {} // lcp-session-cwd\n  };",
      "      timestamp: input.createdAt ?? new Date().toISOString(),\n      ...process.env.LETTA_WORKSPACE_ID ? { cwd: process.env.LETTA_WORKSPACE_ID } : {} // lcp-session-cwd\n    },",
    ],
  },
  {
    id: "lcp-agent-description",
    drift: "created in",
    stock: ["options.description ?? `Letta Code agent created in ${process.cwd()}`;"],
    patched: ['options.description ?? "Letta Code agent"; // lcp-agent-description'],
  },
  {
    id: "lcp-memory-dir-token",
    drift: "getScopedMemoryFilesystemRoot(agentInfo.id)",
    stock: ["env var)**: \\`${getScopedMemoryFilesystemRoot(agentInfo.id)}\\``"],
    patched: ["- **Memory directory (also stored in \\`MEMORY_DIR\\` env var)**: \\`$MEMORY_DIR\\``"],
  },
];

const count = (source: string, token: string) => source.split(token).length - 1;

function deployedBundleOrSkip(t: { skip: (reason: string) => void }): string | null {
  const bundlePath = resolveDeployedLettaBundle();
  if (!bundlePath) {
    t.skip("no deployed letta-code bundle (>= 0.27) on this host");
    return null;
  }
  return bundlePath;
}

test("host-neutral patches: each applies exactly once and replaces the stock form", (t) => {
  const bundlePath = deployedBundleOrSkip(t);
  if (!bundlePath) return;
  const bundle = readFileSync(bundlePath, "utf8");
  const result = patchLettaCodeSourceResultForTest(bundle);
  assert.equal(result.skippedPatches, 0, "no patch may fail-open against the deployed bundle");
  for (const site of SITES) {
    for (const stock of site.stock) {
      assert.equal(count(bundle, stock), 1, `${site.id}: stock form is unique in the bundle`);
      assert.equal(count(result.source, stock), 0, `${site.id}: stock form is gone after patching`);
    }
    for (const patched of site.patched) {
      assert.equal(count(result.source, patched), 1, `${site.id}: patched form lands exactly once`);
    }
  }
  assert.equal(count(result.source, "cwd: process.cwd()\n  };"), 0, "no session header keeps process.cwd()");
  assert.equal(count(result.source, "Letta Code agent created in"), 0);
});

test("host-neutral patches: each is one counted patch and fails open alone on drift", (t) => {
  const bundlePath = deployedBundleOrSkip(t);
  if (!bundlePath) return;
  const bundle = readFileSync(bundlePath, "utf8");
  const baseline = patchLettaCodeSourceResultForTest(bundle);
  for (const site of SITES) {
    const drifted = bundle.replace(site.stock[0], () => site.stock[0].replace(site.drift, (match) => `${match} /*drift*/`));
    assert.notEqual(drifted, bundle, `${site.id}: the drift applies`);
    const result = patchLettaCodeSourceResultForTest(drifted);
    assert.equal(result.appliedPatches, baseline.appliedPatches - 1, `${site.id}: exactly one patch stops applying`);
    assert.equal(result.skippedPatches, 1, `${site.id}: and is counted as skipped`);
    for (const patched of site.patched) {
      assert.equal(count(result.source, patched), 0, `${site.id}: nothing of it lands`);
    }
  }
});

test("host-neutral patches: a duplicated anchor is refused, not patched twice", (t) => {
  const bundlePath = deployedBundleOrSkip(t);
  if (!bundlePath) return;
  const bundle = readFileSync(bundlePath, "utf8");
  const baseline = patchLettaCodeSourceResultForTest(bundle);
  for (const site of SITES) {
    // Duplicate a window around the site so the whole anchor, not just its stock line, repeats.
    const at = bundle.indexOf(site.stock[0]);
    const window = bundle.slice(Math.max(0, at - 200), at + site.stock[0].length + 200);
    const duplicated = `${bundle}\n/*\n${window}\n*/\n`;
    const result = patchLettaCodeSourceResultForTest(duplicated);
    assert.equal(result.appliedPatches, baseline.appliedPatches - 1, `${site.id}: refused on a second match`);
  }
});

/** Whether a parent environment variable may reach the child: not the loader hook and no LETTA_ settings. */
function inherited(name: string): boolean {
  return name !== "NODE_OPTIONS" && !name.startsWith("LETTA_");
}

function runChild(t: { after: (fn: () => void) => void }, body: string, extraEnv: Record<string, string> = {}) {
  const bundle = resolveDeployedLettaBundle();
  assert.ok(bundle, "caller must skip when no deployed bundle exists");
  const workDir = mkdtempSync(join(tmpdir(), "lcp-host-neutral-"));
  t.after(() => rmSync(workDir, { recursive: true, force: true }));
  const env: Record<string, string> = {};
  for (const [name, value] of Object.entries(process.env)) {
    if (value !== undefined && inherited(name)) env[name] = value;
  }
  const register = `import { register } from "node:module";\n` +
    `register(${JSON.stringify(LOADER_URL)});\n` +
    `register(${JSON.stringify(EXPORT_HOOK_URL)});\n`;
  const script = `
    import fs from "node:fs";
    import { join } from "node:path";
    import { execFileSync } from "node:child_process";
    import { pathToFileURL } from "node:url";
    const workDir = process.env.LCP_WORK_DIR;
    const bundle = await import(pathToFileURL(process.env.LETTA_CLI_PATH_REAL).href);
    const git = (dir, ...args) => execFileSync("git", args, { cwd: dir, encoding: "utf8" }).trim();
    const out = {};
    ${body}
    process.stdout.write("\\nRESULT " + JSON.stringify(out) + "\\n");
  `;
  const child = spawnSync(process.execPath, [
    "--import", `data:text/javascript,${encodeURIComponent(register)}`,
    "--input-type=module", "--eval", script,
  ], {
    encoding: "utf8",
    // The loader compares fileURLToPath(url) with LETTA_CLI_PATH_REAL verbatim, so hand it the
    // platform form of the path (backslashes on Windows).
    env: { ...env, LETTA_CLI_PATH_REAL: resolve(bundle), LCP_WORK_DIR: workDir, ...extraEnv },
    timeout: 120_000,
  });
  assert.equal(child.status, 0, child.stderr);
  const line = child.stdout.split("\n").find((entry) => entry.startsWith("RESULT "));
  assert.ok(line, `child printed no RESULT line: ${child.stdout}\n${child.stderr}`);
  return JSON.parse(line.slice("RESULT ".length));
}

const UUID_SUFFIX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

test("lcp-conversation-uuid + lcp-ui-msg-uuid: minted ids are uuids, distinct across stores", (t) => {
  if (!deployedBundleOrSkip(t)) return;
  const out = runChild(t, `
    const storageDir = join(workDir, "store");
    const a = new bundle.LocalStore("agent-1", { storageDir, conversationIdPrefix: "local-conv-", localMessageIdPrefix: "ui-msg-" });
    const b = new bundle.LocalStore("agent-1", { storageDir: join(workDir, "other"), conversationIdPrefix: "local-conv-", localMessageIdPrefix: "ui-msg-" });
    out.conversations = [a.nextConversationId("agent-1"), a.nextConversationId("agent-1"), b.nextConversationId("agent-1")];
    out.created = a.createConversation({ agent_id: "agent-1" }).id;
    out.messages = [a.nextLocalMessageId(), a.nextLocalMessageId(), b.nextLocalMessageId()];
  `);
  const ids = [...out.conversations, out.created];
  assert.equal(new Set(ids).size, ids.length, "conversation ids are distinct, also across two stores");
  for (const id of ids) {
    assert.ok(id.startsWith("local-conv-"), id);
    assert.match(id.slice("local-conv-".length), UUID_SUFFIX, `${id} has no numeric counter suffix`);
  }
  assert.equal(new Set(out.messages).size, out.messages.length, "message ids are distinct, also across two stores");
  for (const id of out.messages) {
    assert.ok(id.startsWith("ui-msg-"), id);
    assert.match(id.slice("ui-msg-".length), UUID_SUFFIX, `${id} has no numeric counter suffix`);
    assert.doesNotMatch(id, /ui-msg-(\d+)$/, "the legacy-transcript numericId() parser reads it as 0");
  }
});

test("lcp-session-cwd: session lines carry LETTA_WORKSPACE_ID, or no cwd at all", (t) => {
  if (!deployedBundleOrSkip(t)) return;
  const body = `
    out.header = bundle.createLocalTranscriptSessionHeader({ id: "local-conv-x", created_at: "2026-01-01T00:00:00.000Z" });
    const path = join(workDir, "messages.jsonl");
    bundle.writeSessionEntryJsonl(path, [], { conversationId: "local-conv-x" });
    out.migrated = JSON.parse(fs.readFileSync(path, "utf8").split("\\n")[0]);
  `;
  const without = runChild(t, body);
  assert.equal("cwd" in without.header, false, "no absolute host path in the header");
  assert.equal("cwd" in without.migrated, false, "no absolute host path in a migrated transcript");
  assert.equal(without.header.id, "local-conv-x");
  const withWorkspace = runChild(t, body, { LETTA_WORKSPACE_ID: "ws-test" });
  assert.equal(withWorkspace.header.cwd, "ws-test");
  assert.equal(withWorkspace.migrated.cwd, "ws-test");
});

const AUTHOR = `{ agentId: "agent-local-test", authorName: "Test Agent", authorEmail: "agent-local-test@letta.com" }`;

test("lcp-memory-lf: a new memory repo starts with autocrlf=false and a committed .gitattributes", (t) => {
  if (!deployedBundleOrSkip(t)) return;
  const out = runChild(t, `
    const memoryDir = join(workDir, "memory");
    await bundle.initializeLocalMemoryRepo({ memoryDir, agentId: "agent-local-test", authorName: "Test Agent", files: [] });
    out.autocrlf = git(memoryDir, "config", "--local", "--get", "core.autocrlf");
    out.attributes = git(memoryDir, "show", "HEAD:.gitattributes");
    out.commits = git(memoryDir, "rev-list", "--count", "HEAD");
    out.status = git(memoryDir, "status", "--porcelain");
  `);
  assert.equal(out.autocrlf, "false");
  assert.equal(out.attributes, "* text=auto eol=lf");
  assert.equal(out.commits, "1", "carried in the init commit, not an extra one");
  assert.equal(out.status, "");
});

test("lcp-memory-lf: an existing repo converges once and stays put", (t) => {
  if (!deployedBundleOrSkip(t)) return;
  const out = runChild(t, `
    const memoryDir = join(workDir, "memory");
    fs.mkdirSync(memoryDir, { recursive: true });
    git(memoryDir, "init", "-q");
    git(memoryDir, "config", "core.autocrlf", "true");
    git(memoryDir, "-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "--allow-empty", "-m", "seed");
    await bundle.prepareLocalOnlyMemoryRepoForGitOps(memoryDir, ${AUTHOR});
    out.autocrlf = git(memoryDir, "config", "--local", "--get", "core.autocrlf");
    out.attributes = git(memoryDir, "show", "HEAD:.gitattributes");
    out.subject = git(memoryDir, "log", "-1", "--format=%s");
    out.commits = git(memoryDir, "rev-list", "--count", "HEAD");
    await bundle.prepareLocalOnlyMemoryRepoForGitOps(memoryDir, ${AUTHOR});
    out.commitsAfterRerun = git(memoryDir, "rev-list", "--count", "HEAD");

    // A repo that already has its own .gitattributes keeps it untouched.
    const ownDir = join(workDir, "own");
    fs.mkdirSync(ownDir, { recursive: true });
    git(ownDir, "init", "-q");
    fs.writeFileSync(join(ownDir, ".gitattributes"), "*.md text\\n");
    git(ownDir, "add", ".gitattributes");
    git(ownDir, "-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "-m", "seed");
    await bundle.prepareLocalOnlyMemoryRepoForGitOps(ownDir, ${AUTHOR});
    out.ownAttributes = fs.readFileSync(join(ownDir, ".gitattributes"), "utf8");
    out.ownCommits = git(ownDir, "rev-list", "--count", "HEAD");
  `);
  assert.equal(out.autocrlf, "false");
  assert.equal(out.attributes, "* text=auto eol=lf");
  assert.equal(out.subject, "chore: normalize memory line endings to LF");
  assert.equal(out.commits, "2");
  assert.equal(out.commitsAfterRerun, "2", "idempotent: no second commit");
  assert.equal(out.ownAttributes, "*.md text\n");
  assert.equal(out.ownCommits, "1");
});
