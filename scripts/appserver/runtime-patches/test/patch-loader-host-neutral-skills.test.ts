import { resolveDeployedLettaBundle } from "./bundle-fixtures.ts";
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { spawnSync } from "node:child_process";

import { patchLettaCodeSourceResultForTest } from "../letta-code-patch-loader.mjs";

// Host-neutral skill paths and system prompt tracking: lcp-memory-dir-expand, lcp-skill-dir and
// lcp-system-prompt-tracking. The static tests check each anchor against the deployed bundle; the
// behavioural tests drive the real, runtime-patched functions in a child process, the way
// patch-loader-host-neutral.test.ts does.

const LOADER_URL = new URL("../letta-code-patch-loader.mjs", import.meta.url).href;

// Swaps the CLI entry for the module inits these tests need and exports the functions they call.
const EXPORT_HOOK_SOURCE = `
import { pathToFileURL } from "node:url";
const TARGET = process.env.LETTA_CLI_PATH_REAL ?? "";
const ENTRY = "await init_src5().then(() => exports_src2);";
export async function load(url, context, nextLoad) {
  const result = await nextLoad(url, context);
  if (!TARGET || url !== pathToFileURL(TARGET).href) return result;
  const source = typeof result.source === "string" ? result.source : Buffer.from(result.source).toString("utf8");
  if (source.split(ENTRY).length !== 2) throw new Error("skills export hook: CLI entry not found exactly once");
  return {
    ...result,
    source: source.replace(ENTRY, () =>
      "init_settings_manager();\\ninit_backend2();\\ninit_system_prompt_versioning();\\ninit_skill();\\ninit_file_path_args();\\n" +
      "export { settingsManager, getBackend, decideManagedSystemPromptUpdate, hashSystemPrompt, renderSkillContent, expandPathArgs, LETTA_CODE_ORIGIN_TAG };"),
    shortCircuit: true,
  };
}
`;
const EXPORT_HOOK_URL = `data:text/javascript,${encodeURIComponent(EXPORT_HOOK_SOURCE)}`;

// `drift` is a substring of stock[0] that a test corrupts to simulate an upstream change.
// `kept` marks a site whose replacement still contains the stock text (an insertion after it).
// `window` is how much context around stock[0] the duplicate-anchor test copies.
type Site = { id: string; stock: string[]; patched: string[]; drift: string; kept?: boolean; window?: number };

const SITES: Site[] = [
  {
    id: "lcp-memory-dir-expand",
    drift: "return toolArgs;",
    kept: true,
    stock: [
      "function expandPathArgs(toolName2, toolArgs, workingDirectory, env6 = process.env, homeDir) {\n" +
        "  if (!CWD_FILE_PATH_TOOLS.has(canonicalToolName(toolName2)))\n    return toolArgs;\n",
    ],
    patched: [
      "function __lcpWithMemoryDirEnv(env) {",
      "    return toolArgs;\n  env6 = __lcpWithMemoryDirEnv(env6); // lcp-memory-dir-expand\n",
    ],
  },
  {
    id: "lcp-skill-dir",
    drift: ".replace(/<SKILL_DIR>/g, skillDir)",
    stock: [
      "  const withSkillDir = skillContent.replace(/<SKILL_DIR>/g, skillDir).replace(/\\$\\{CLAUDE_SKILL_DIR\\}/g, skillDir);",
      "Skill directory: ${skillDir}\nRelative paths",
    ],
    patched: [
      "function __lcpHostNeutralSkillDir(skillDir, agentId) {",
      "  const lcpSkillDir = __lcpHostNeutralSkillDir(skillDir, options.agentId); // lcp-skill-dir\n",
      "Skill directory: ${lcpSkillDir}\nRelative paths",
    ],
  },
  {
    id: "lcp-system-prompt-tracking",
    drift: "?.systemPromptPreset;",
    // The getter/setter anchor spans about 1 KB after stock[0].
    window: 1500,
    stock: [
      "    return this.getAgentSettings(agentId)?.systemPromptPreset;\n",
      "    ...compactionSettings !== undefined ? { compaction_settings: compactionSettings } : {}\n  };\n}",
    ],
    patched: [
      "function __lcpSystemPromptTracking(manager, agentId) {",
      "    return __lcpSystemPromptTracking(this, agentId)?.systemPromptPreset; // lcp-system-prompt-tracking\n",
      "{ system_prompt_tracking: { ...value.system_prompt_tracking } } : {} // lcp-system-prompt-tracking\n",
    ],
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

test("host-neutral skills: each patch applies exactly once and replaces the stock form", (t) => {
  const bundlePath = deployedBundleOrSkip(t);
  if (!bundlePath) return;
  const bundle = readFileSync(bundlePath, "utf8");
  const result = patchLettaCodeSourceResultForTest(bundle);
  assert.equal(result.skippedPatches, 0, "no patch may fail-open against the deployed bundle");
  for (const site of SITES) {
    for (const stock of site.stock) {
      assert.equal(count(bundle, stock), 1, `${site.id}: stock form is unique in the bundle`);
      assert.equal(count(result.source, stock), site.kept ? 1 : 0, `${site.id}: stock form is ${site.kept ? "kept once" : "gone"} after patching`);
    }
    for (const patched of site.patched) {
      assert.equal(count(result.source, patched), 1, `${site.id}: patched form lands exactly once`);
    }
  }
  assert.equal(count(result.source, "Skill directory: ${skillDir}"), 0, "no skill footer prints the raw skill dir");
});

test("host-neutral skills: each is one counted patch and fails open alone on drift", (t) => {
  const bundlePath = deployedBundleOrSkip(t);
  if (!bundlePath) return;
  const bundle = readFileSync(bundlePath, "utf8");
  const baseline = patchLettaCodeSourceResultForTest(bundle);
  for (const site of SITES) {
    const drifted = bundle.replace(site.stock[0], () => site.stock[0].replace(site.drift, (match) => `${match} /*drift*/`));
    assert.notEqual(drifted, bundle, `${site.id}: the drift applies`);
    const result = patchLettaCodeSourceResultForTest(drifted);
    // lcp-skill-dir requires lcp-memory-dir-expand, so drift in the expansion site takes both out.
    const lost = site.id === "lcp-memory-dir-expand" ? 2 : 1;
    assert.equal(result.appliedPatches, baseline.appliedPatches - lost, `${site.id}: only the affected patch stops applying`);
    assert.equal(result.skippedPatches, lost, `${site.id}: and is counted as skipped`);
    for (const patched of site.patched) {
      assert.equal(count(result.source, patched), 0, `${site.id}: nothing of it lands`);
    }
  }
});

test("lcp-skill-dir: drift in the expansion site keeps absolute skill dirs (never a $MEMORY_DIR path tools cannot open)", (t) => {
  const bundlePath = deployedBundleOrSkip(t);
  if (!bundlePath) return;
  const bundle = readFileSync(bundlePath, "utf8");
  const expandSite = SITES[0];
  const drifted = bundle.replace(expandSite.stock[0], () => expandSite.stock[0].replace(expandSite.drift, (m) => `${m} /*drift*/`));
  const result = patchLettaCodeSourceResultForTest(drifted);
  assert.equal(count(result.source, "__lcpHostNeutralSkillDir"), 0);
  assert.equal(count(result.source, "Skill directory: ${skillDir}"), 1, "the stock footer stays");
});

test("host-neutral skills: a duplicated anchor is refused, not patched twice", (t) => {
  const bundlePath = deployedBundleOrSkip(t);
  if (!bundlePath) return;
  const bundle = readFileSync(bundlePath, "utf8");
  const baseline = patchLettaCodeSourceResultForTest(bundle);
  for (const site of SITES) {
    const at = bundle.indexOf(site.stock[0]);
    const span = site.window ?? 200;
    const window = bundle.slice(Math.max(0, at - span), at + site.stock[0].length + span);
    const duplicated = `${bundle}\n/*\n${window}\n*/\n`;
    const result = patchLettaCodeSourceResultForTest(duplicated);
    const lost = site.id === "lcp-memory-dir-expand" ? 2 : 1;
    assert.equal(result.appliedPatches, baseline.appliedPatches - lost, `${site.id}: refused on a second match`);
  }
});

/** Whether a parent environment variable may reach the child: not the loader hook and no LETTA_ settings. */
function inherited(name: string): boolean {
  return name !== "NODE_OPTIONS" && !name.startsWith("LETTA_") && name !== "MEMORY_DIR";
}

function runChild(
  t: { after: (fn: () => void) => void },
  workDir: string,
  body: string,
  extraEnv: Record<string, string> = {},
) {
  const bundle = resolveDeployedLettaBundle();
  assert.ok(bundle, "caller must skip when no deployed bundle exists");
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
    import { pathToFileURL } from "node:url";
    const workDir = process.env.LCP_WORK_DIR;
    const bundle = await import(pathToFileURL(process.env.LETTA_CLI_PATH_REAL).href);
    const out = {};
    ${body}
    process.stdout.write("\\nRESULT " + JSON.stringify(out) + "\\n");
    process.exit(0);
  `;
  const child = spawnSync(process.execPath, [
    "--import", `data:text/javascript,${encodeURIComponent(register)}`,
    "--input-type=module", "--eval", script,
  ], {
    encoding: "utf8",
    env: {
      ...env,
      HOME: join(workDir, "home"),
      USERPROFILE: join(workDir, "home"),
      LETTA_LOCAL_BACKEND_EXPERIMENTAL: "1",
      LETTA_LOCAL_BACKEND_DIR: join(workDir, "store"),
      LETTA_CONFIG_DIR: join(workDir, "config"),
      LETTA_CLI_PATH_REAL: resolve(bundle),
      LCP_WORK_DIR: workDir,
      ...extraEnv,
    },
    timeout: 120_000,
  });
  assert.equal(child.status, 0, child.stderr);
  const line = child.stdout.split("\n").find((entry) => entry.startsWith("RESULT "));
  assert.ok(line, `child printed no RESULT line: ${child.stdout}\n${child.stderr}`);
  return JSON.parse(line.slice("RESULT ".length));
}

function workDirFor(t: { after: (fn: () => void) => void }): string {
  const workDir = mkdtempSync(join(tmpdir(), "lcp-host-neutral-skills-"));
  t.after(() => rmSync(workDir, { recursive: true, force: true }));
  return workDir;
}

const AGENT = "agent-local-skills-test";

test("lcp-skill-dir: a memfs skill renders as $MEMORY_DIR/skills/<name>; a skill elsewhere keeps its path", (t) => {
  if (!deployedBundleOrSkip(t)) return;
  const workDir = workDirFor(t);
  const out = runChild(t, workDir, `
    const skillBody = "---\\nname: tidy\\ndescription: d\\n---\\nRun <SKILL_DIR>/scripts/run.sh, then read \${CLAUDE_SKILL_DIR}/ref.md.\\n";
    const write = (dir) => {
      fs.mkdirSync(join(dir, "scripts"), { recursive: true });
      fs.writeFileSync(join(dir, "SKILL.md"), skillBody);
      fs.writeFileSync(join(dir, "scripts", "run.sh"), "echo hi\\n");
      fs.writeFileSync(join(dir, "ref.md"), "ref\\n");
      return join(dir, "SKILL.md");
    };
    const memfsSkill = write(join(workDir, "store", "memfs", ${JSON.stringify(AGENT)}, "memory", "skills", "tidy"));
    const otherSkill = write(join(workDir, "global", "skills", "tidy"));
    out.memfs = bundle.renderSkillContent("tidy", skillBody, memfsSkill, { agentId: ${JSON.stringify(AGENT)} });
    out.other = bundle.renderSkillContent("tidy", skillBody, otherSkill, { agentId: ${JSON.stringify(AGENT)} });
    out.otherDir = join(workDir, "global", "skills", "tidy");
    out.workDir = workDir;
  `);
  assert.match(out.memfs, /Run \$MEMORY_DIR\/skills\/tidy\/scripts\/run\.sh, then read \$MEMORY_DIR\/skills\/tidy\/ref\.md\./);
  assert.match(out.memfs, /^Skill directory: \$MEMORY_DIR\/skills\/tidy$/m);
  assert.match(out.memfs, /<file>ref\.md<\/file>/, "resources stay relative to the skill dir");
  assert.match(out.memfs, /<file>scripts\/run\.sh<\/file>/);
  const slashes = (value: string) => value.replace(/\\/g, "/");
  assert.equal(slashes(out.memfs).includes(slashes(out.workDir)), false, "no host path in persisted skill content");
  assert.ok(out.other.includes(`Skill directory: ${out.otherDir}`), "a skill outside the memory root keeps its install path");
});

test("lcp-memory-dir-expand: file tools expand $MEMORY_DIR to the running agent's memory root", (t) => {
  if (!deployedBundleOrSkip(t)) return;
  const workDir = workDirFor(t);
  const out = runChild(t, workDir, `
    const env = { ...process.env, LETTA_AGENT_ID: ${JSON.stringify(AGENT)} };
    delete env.MEMORY_DIR;
    delete env.LETTA_MEMORY_DIR;
    out.read = bundle.expandPathArgs("Read", { file_path: "$MEMORY_DIR/skills/tidy/SKILL.md" }, workDir, env).file_path;
    out.braced = bundle.expandPathArgs("Edit", { file_path: "\${MEMORY_DIR}/persona.md" }, workDir, env).file_path;
    out.glob = bundle.expandPathArgs("Glob", { path: "$LETTA_MEMORY_DIR/skills" }, workDir, env).path;
    out.explicit = bundle.expandPathArgs("Read", { file_path: "$MEMORY_DIR/x.md" }, workDir, { ...env, MEMORY_DIR: join(workDir, "explicit") }).file_path;
    out.noAgent = bundle.expandPathArgs("Read", { file_path: "$MEMORY_DIR/x.md" }, workDir, { HOME: env.HOME }).file_path;
    out.root = join(workDir, "store", "memfs", ${JSON.stringify(AGENT)}, "memory");
    out.workDir = workDir;
  `);
  assert.equal(resolve(out.read), resolve(out.root, "skills/tidy/SKILL.md"));
  assert.equal(resolve(out.braced), resolve(out.root, "persona.md"));
  assert.equal(resolve(out.glob), resolve(out.root, "skills"));
  assert.equal(resolve(out.explicit), resolve(out.workDir, "explicit", "x.md"), "an environment that sets MEMORY_DIR wins");
  assert.ok(out.noAgent.includes("$MEMORY_DIR"), "with no agent in scope the path is left as written");
});

// A managed prompt from an older letta-code version: the node that tracked it upgrades it, a node
// with no tracking freezes it as custom. Tracking in the agent record makes both nodes agree.
const TRACKING_SETUP = `
  await bundle.settingsManager.initialize();
  const store = bundle.getBackend().store;
  const agentsDir = join(workDir, "store", "agents");
  const readRecord = () => {
    for (const name of fs.readdirSync(agentsDir)) {
      const record = JSON.parse(fs.readFileSync(join(agentsDir, name), "utf8"));
      if (record.id === process.env.LCP_AGENT_ID) return record;
    }
    return null;
  };
  const decide = (storedPreset, storedHash, storedVersion) => bundle.decideManagedSystemPromptUpdate({
    agent: { id: process.env.LCP_AGENT_ID, system: "OLD MANAGED PROMPT", tags: [bundle.LETTA_CODE_ORIGIN_TAG] },
    memoryMode: "local-memfs", isLettaCloud: false, preserveCloudSystemPrompt: false,
    storedPreset, storedHash, storedVersion,
  }).kind;
  const sm = bundle.settingsManager;
`;

test("lcp-system-prompt-tracking: tracking replicates with the agent record and a fresh node reaches the same decision", (t) => {
  if (!deployedBundleOrSkip(t)) return;
  const workDir = workDirFor(t);
  const nodeA = runChild(t, workDir, `
    ${TRACKING_SETUP}
    const agent = store.createAgent({ name: "tracked", system: "OLD MANAGED PROMPT", tags: ["memfs:disabled"] });
    out.agentId = agent.id;
    process.env.LCP_AGENT_ID = agent.id;
    sm.setManagedSystemPrompt(agent.id, { preset: "default", hash: bundle.hashSystemPrompt("OLD MANAGED PROMPT"), version: "0.0.1" });
    out.record = readRecord();
    out.decision = decide(sm.getSystemPromptPreset(agent.id), sm.getSystemPromptHash(agent.id), sm.getSystemPromptVersion(agent.id));
    out.untracked = decide(undefined, undefined, undefined);
  `);
  assert.deepEqual(nodeA.record.system_prompt_tracking, {
    preset: "default",
    hash: nodeA.record.system_prompt_tracking.hash,
    version: "0.0.1",
  });
  assert.match(nodeA.record.system_prompt_tracking.hash, /^sha256:/);
  assert.equal(nodeA.decision, "update", "the tracking node upgrades the old managed prompt");
  assert.equal(nodeA.untracked, "custom", "stock behaviour without tracking: frozen as custom");

  // Node B: same replicated store, its own empty settings.json.
  const nodeB = runChild(t, workDir, `
    ${TRACKING_SETUP}
    out.settingsEntry = (sm.getSettings().agents ?? []).find((entry) => entry.agentId === process.env.LCP_AGENT_ID) ?? null;
    out.preset = sm.getSystemPromptPreset(process.env.LCP_AGENT_ID);
    out.decision = decide(sm.getSystemPromptPreset(process.env.LCP_AGENT_ID), sm.getSystemPromptHash(process.env.LCP_AGENT_ID), sm.getSystemPromptVersion(process.env.LCP_AGENT_ID));
    sm.setSystemPromptCustom(process.env.LCP_AGENT_ID);
    out.afterCustom = readRecord().system_prompt_tracking;
    sm.clearSystemPromptPreset(process.env.LCP_AGENT_ID);
    out.afterClear = readRecord().system_prompt_tracking;
    out.presetAfterClear = sm.getSystemPromptPreset(process.env.LCP_AGENT_ID) ?? null;
  `, { LETTA_CONFIG_DIR: join(workDir, "config-b"), LCP_AGENT_ID: nodeA.agentId });
  assert.equal(nodeB.settingsEntry, null, "node B has no settings.json entry for the agent");
  assert.equal(nodeB.preset, "default", "node B reads the tracking from the replicated record");
  assert.equal(nodeB.decision, nodeA.decision, "both nodes reach the same decision");
  assert.deepEqual(nodeB.afterCustom, { preset: "custom" });
  assert.deepEqual(nodeB.afterClear, { preset: "" }, "a cleared preset is recorded, so stale settings cannot revive it");
  assert.equal(nodeB.presetAfterClear, null);
});

test("lcp-system-prompt-tracking: a settings-only entry is copied into the record on first read", (t) => {
  if (!deployedBundleOrSkip(t)) return;
  const workDir = workDirFor(t);
  const out = runChild(t, workDir, `
    ${TRACKING_SETUP}
    const agent = store.createAgent({ name: "legacy", system: "OLD MANAGED PROMPT", tags: ["memfs:disabled"] });
    process.env.LCP_AGENT_ID = agent.id;
    // Seed settings.json the way an unpatched letta-code would have, without touching the record.
    sm.upsertAgentSettings(agent.id, { systemPromptPreset: "default", systemPromptHash: "sha256:legacy", systemPromptVersion: "0.0.1" });
    out.before = readRecord().system_prompt_tracking ?? null;
    out.preset = sm.getSystemPromptPreset(agent.id);
    out.after = readRecord().system_prompt_tracking ?? null;
    // The field survives a reload through normalizeAgentRecord and a later updateAgent.
    store.updateAgent(agent.id, { name: "legacy-renamed" });
    out.afterUpdate = readRecord().system_prompt_tracking ?? null;
  `);
  assert.equal(out.before, null);
  assert.equal(out.preset, "default");
  assert.deepEqual(out.after, { preset: "default", hash: "sha256:legacy", version: "0.0.1" });
  assert.deepEqual(out.afterUpdate, out.after);
});

test("lcp-skill-dir: a skill directory name with replacement tokens ($&, $1, $$) is inserted literally", (t) => {
  if (!deployedBundleOrSkip(t)) return;
  const workDir = workDirFor(t);
  const out = runChild(t, workDir, `
    const skillBody = "---\\nname: tidy\\ndescription: d\\n---\\nRun <SKILL_DIR>/run.sh, then read \${CLAUDE_SKILL_DIR}/ref.md.\\n";
    const dir = join(workDir, "store", "memfs", ${JSON.stringify(AGENT)}, "memory", "skills", "t$&i$1d$$y");
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(join(dir, "SKILL.md"), skillBody);
    out.memfs = bundle.renderSkillContent("tidy", skillBody, join(dir, "SKILL.md"), { agentId: ${JSON.stringify(AGENT)} });
  `);
  const dir = "$MEMORY_DIR/skills/t$&i$1d$$y";
  assert.ok(out.memfs.includes(`Run ${dir}/run.sh, then read ${dir}/ref.md.`), out.memfs);
  assert.ok(out.memfs.includes(`Skill directory: ${dir}\n`), out.memfs);
});

test("lcp-system-prompt-tracking: a failed record write is retried by the next identical write", (t) => {
  if (!deployedBundleOrSkip(t)) return;
  const workDir = workDirFor(t);
  const out = runChild(t, workDir, `
    ${TRACKING_SETUP}
    const agent = store.createAgent({ name: "retry", system: "OLD MANAGED PROMPT", tags: ["memfs:disabled"] });
    process.env.LCP_AGENT_ID = agent.id;
    sm.setManagedSystemPrompt(agent.id, { preset: "default", hash: "sha256:managed", version: "0.0.1" });
    const recordFile = fs.readdirSync(agentsDir).map((name) => join(agentsDir, name))
      .find((file) => JSON.parse(fs.readFileSync(file, "utf8")).id === agent.id);
    out.before = readRecord().system_prompt_tracking;
    // The real persistAgent write fails (EISDIR) while the record path is a directory.
    fs.rmSync(recordFile);
    fs.mkdirSync(recordFile);
    sm.setSystemPromptCustom(agent.id);
    fs.rmdirSync(recordFile);
    out.afterFailure = fs.existsSync(recordFile);
    sm.setSystemPromptCustom(agent.id);
    out.afterRetry = fs.existsSync(recordFile) ? JSON.parse(fs.readFileSync(recordFile, "utf8")).system_prompt_tracking ?? null : "missing";
  `);
  assert.deepEqual(out.before, { preset: "default", hash: "sha256:managed", version: "0.0.1" });
  assert.equal(out.afterFailure, false, "the failed write left no record file");
  assert.deepEqual(out.afterRetry, { preset: "custom" }, "the identical retry reaches the file");
});

test("lcp-system-prompt-tracking: the settings.json migration never overwrites tracking a peer wrote meanwhile", (t) => {
  if (!deployedBundleOrSkip(t)) return;
  const workDir = workDirFor(t);
  const out = runChild(t, workDir, `
    ${TRACKING_SETUP}
    const agent = store.createAgent({ name: "race", system: "OLD MANAGED PROMPT", tags: ["memfs:disabled"] });
    process.env.LCP_AGENT_ID = agent.id;
    sm.upsertAgentSettings(agent.id, { systemPromptPreset: "default", systemPromptHash: "sha256:stale", systemPromptVersion: "0.0.1" });
    const recordFile = fs.readdirSync(agentsDir).map((name) => join(agentsDir, name))
      .find((file) => JSON.parse(fs.readFileSync(file, "utf8")).id === agent.id);
    // Another node replicates tracking into the record right after this node's first read.
    const realRefresh = store.refreshAgentRecordFromStorage.bind(store);
    let reads = 0;
    store.refreshAgentRecordFromStorage = (id) => {
      const record = realRefresh(id);
      if (++reads === 1) {
        const peer = { ...JSON.parse(fs.readFileSync(recordFile, "utf8")), system_prompt_tracking: { preset: "custom" } };
        fs.writeFileSync(recordFile, JSON.stringify(peer, null, 2) + "\\n");
        const later = new Date(Date.now() + 60_000);
        fs.utimesSync(recordFile, later, later);
      }
      return record;
    };
    out.preset = sm.getSystemPromptPreset(agent.id);
    out.reads = reads;
    out.file = JSON.parse(fs.readFileSync(recordFile, "utf8")).system_prompt_tracking ?? null;
  `);
  assert.ok(out.reads >= 2, `the migration re-reads the record before writing (reads=${out.reads})`);
  assert.deepEqual(out.file, { preset: "custom" }, "the peer's tracking survives the lazy migration");
});
