import { test } from "node:test";
import assert from "node:assert/strict";
import { chmodSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { spawnSync } from "node:child_process";

// lcp-path-parity: the loader must never let a letta-code bundle other than the pinned
// LETTA_CLI_PATH_REAL load silently. The service env pins one bundle and the runtime patches
// apply to exactly that file, so a child that resolves `letta` to a self-updated global install
// (or any other copy) runs UNPATCHED with no boot summary at all. The guard is exercised the
// way production runs it: NODE_OPTIONS=--import=<register> with the bundle as the main entry,
// so the load hook sees the main-entry URL, not an import from the test.

const REGISTER = fileURLToPath(new URL("../letta-code-patch-register.mjs", import.meta.url));
const ENTRY_SOURCE = "console.log('entry ok');\n";
const BUNDLE_SEGMENTS = join("node_modules", "@letta-ai", "letta-code", "letta.js");

type Sandbox = { root: string; bundle: (name: string) => string };

function sandbox(t: { after: (fn: () => void) => void }): Sandbox {
  const root = mkdtempSync(join(tmpdir(), "lcp-bundle-mismatch-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const bundle = (name: string) => {
    // The real bundle is ESM ("type": "module" in @letta-ai/letta-code/package.json); without it a
    // .js entry loads as CommonJS, whose source the loader hook never receives.
    const dir = dirname(join(root, name, BUNDLE_SEGMENTS));
    mkdirSync(dir, { recursive: true });
    writeFileSync(join(dir, "package.json"), JSON.stringify({ name: "@letta-ai/letta-code", version: name.replace(/^letta-code-/, ""), type: "module" }));
    const path = join(dir, "letta.js");
    writeFileSync(path, ENTRY_SOURCE);
    chmodSync(path, 0o755);
    return path;
  };
  return { root, bundle };
}

/** Runs `node <entry>` with the real register in NODE_OPTIONS, the way the service env does. */
function runWithRegister(entry: string, env: Record<string, string>) {
  return spawnSync(process.execPath, [entry], {
    encoding: "utf8",
    env: { PATH: process.env.PATH ?? "", NODE_OPTIONS: `--import=${pathToFileURL(REGISTER).href}`, ...env },
    timeout: 60_000,
  });
}

test("bundle mismatch: a letta.js other than the pin loads with a loud ERROR naming both paths", (t) => {
  const { bundle } = sandbox(t);
  const pinned = bundle("letta-code-1.0.0");
  const stray = bundle("letta-code-9.9.9");
  const child = runWithRegister(stray, { LETTA_CLI_PATH_REAL: pinned });
  assert.equal(child.status, 0, child.stderr);
  assert.match(child.stderr, /\[letta-code-patch\] ERROR: bundle mismatch/);
  assert.ok(child.stderr.includes(stray), `the ERROR must name the loaded bundle: ${child.stderr}`);
  assert.ok(child.stderr.includes(pinned), `the ERROR must name the pinned bundle: ${child.stderr}`);
  assert.match(child.stderr, /unpatched|UNPATCHED/);
});

test("bundle mismatch: LETTA_PATCH_STRICT_PATH=1 refuses to run the unpinned bundle at all", (t) => {
  const { bundle } = sandbox(t);
  const pinned = bundle("letta-code-1.0.0");
  const stray = bundle("letta-code-9.9.9");
  const child = runWithRegister(stray, { LETTA_CLI_PATH_REAL: pinned, LETTA_PATCH_STRICT_PATH: "1" });
  assert.notEqual(child.status, 0, `the stray bundle must not run in strict mode: ${child.stdout}`);
  assert.match(child.stderr, /\[letta-code-patch\] ERROR: bundle mismatch/);
});

test("bundle mismatch: the pinned bundle itself loads with no mismatch ERROR and a boot summary", (t) => {
  const { bundle } = sandbox(t);
  const pinned = bundle("letta-code-1.0.0");
  const child = runWithRegister(pinned, { LETTA_CLI_PATH_REAL: pinned });
  assert.equal(child.status, 0, child.stderr);
  assert.doesNotMatch(child.stderr, /bundle mismatch/, `the pin itself must not be reported: ${child.stderr}`);
  assert.match(child.stderr, /\[letta-code-patch\] lcp-patches applied=\d+ skipped=\d+/);
});

test("bundle mismatch: a module that is not a letta-code bundle is never reported", (t) => {
  const { root, bundle } = sandbox(t);
  const pinned = bundle("letta-code-1.0.0");
  const plain = join(root, "plain.mjs");
  writeFileSync(plain, ENTRY_SOURCE);
  const child = runWithRegister(plain, { LETTA_CLI_PATH_REAL: pinned });
  assert.equal(child.status, 0, child.stderr);
  assert.doesNotMatch(child.stderr, /bundle mismatch/, `an unrelated module must not be reported: ${child.stderr}`);
  assert.doesNotMatch(child.stderr, /lcp-patches/, `an unrelated module is not patched and gets no boot summary: ${child.stderr}`);
});

test("bundle mismatch: no pin set means no parity expectation and no ERROR", (t) => {
  const { bundle } = sandbox(t);
  const stray = bundle("letta-code-9.9.9");
  const child = runWithRegister(stray, { LETTA_CLI_PATH_REAL: "" });
  assert.equal(child.status, 0, child.stderr);
  assert.doesNotMatch(child.stderr, /bundle mismatch/, `without a pin there is nothing to mismatch against: ${child.stderr}`);
  assert.doesNotMatch(child.stderr, /lcp-patches/);
});
