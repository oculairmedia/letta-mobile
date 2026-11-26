// Run: node --test scripts/appserver/letta-code-upgrade.test.mjs
import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";

import {
  SITES_FILE, bumpPinFile, dependencyDiff, dryRunLoader, embeddedPatchOrder, readPin, readSites,
  hostLoaderRequest, replaceGroup, rewriteSites, skippedPatchNames, surveyProblems, unionDiff,
} from "./letta-code-upgrade.mjs";
import { repositoryRoot } from "./verify-contract-baseline.mjs";

function sandbox(t) {
  const root = mkdtempSync(join(tmpdir(), "letta-code-upgrade-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  return root;
}

test("unionDiff reports every added and removed member, not the first mismatch", () => {
  assert.deepEqual(unionDiff(["a", "b", "c", "d"], ["a", "x", "c", "y"]), {
    pinned: 4, installed: 4, added: ["x", "y"], removed: ["b", "d"],
  });
});

test("dependencyDiff separates added, removed and changed, and flags a major range move", () => {
  const diff = dependencyDiff(
    { "pi-ai": "^0.99.1", sharp: "^0.34.5", electron: "0.35.3", ws: "^8.19.0", same: "1.0.0" },
    { "pi-ai": "^1.5.2", sharp: "^0.34.9", ws: "^8.20.0", fresh: "2.0.0", same: "1.0.0" },
  );
  assert.deepEqual(diff.added, [{ name: "fresh", to: "2.0.0" }]);
  assert.deepEqual(diff.removed, [{ name: "electron", from: "0.35.3" }]);
  assert.deepEqual(diff.changed, [
    { name: "pi-ai", from: "^0.99.1", to: "^1.5.2", major: true },
    { name: "sharp", from: "^0.34.5", to: "^0.34.9", major: false },
    { name: "ws", from: "^8.19.0", to: "^8.20.0", major: false },
  ]);
});

test("replaceGroup rewrites only group 1 and refuses an unexpected match count", () => {
  const pattern = /letta-code@(\d+\.\d+\.\d+)/g;
  assert.equal(replaceGroup("x @letta-ai/letta-code@0.1.2 y letta-code@0.1.2", pattern, "9.9.9", 2, "f"),
    "x @letta-ai/letta-code@9.9.9 y letta-code@9.9.9");
  assert.throws(() => replaceGroup("letta-code@0.1.2", pattern, "9.9.9", 2, "f"), /expected 2 match\(es\).*found 1/);
  assert.throws(() => replaceGroup("nothing", pattern, "9.9.9", undefined, "f"), /matched nothing/);
});

test("replaceGroup rewrites the captured span even when an earlier equal number precedes it", () => {
  // The README union-count site: after the command count was rewritten to 117, the message count
  // still reads 117 and must be the one that changes.
  const pattern = /\d+ commands, (\d+) messages\. Mirrors/g;
  assert.equal(replaceGroup("x 117 commands, 117 messages. Mirrors y", pattern, "118", 1, "f"), "x 117 commands, 118 messages. Mirrors y");
  const versions = /v(\d+\.\d+\.\d+) next (\d+\.\d+\.\d+)/g;
  assert.equal(replaceGroup("v1.2.3 next 1.2.3", /v\d+\.\d+\.\d+ next (\d+\.\d+\.\d+)/g, "9.9.9", 1, "f"), "v1.2.3 next 9.9.9");
  assert.equal(replaceGroup("v1.2.3 next 4.5.6", versions, "0.0.1", 1, "f"), "v0.0.1 next 4.5.6");
});

test("bumpPinFile moves version and integrity and bumps assetRevision", () => {
  const text = "# c\nlettaCodeVersion=0.34.39\nlettaCodeIntegrity=sha512-old==\nassetRevision=38\nlibnodeVersion=v18.20.4\n";
  assert.equal(bumpPinFile(text, "0.34.70", "sha512-new=="),
    "# c\nlettaCodeVersion=0.34.70\nlettaCodeIntegrity=sha512-new==\nassetRevision=39\nlibnodeVersion=v18.20.4\n");
});

test("every listed version site matches its declared count in the real tree, at the pin", () => {
  const pin = readPin();
  const { sites, unionCounts } = readSites();
  assert.ok(sites.length >= 15);
  for (const site of sites) {
    const found = [...readFileSync(join(repositoryRoot, site.path), "utf8").matchAll(site.pattern)].map((m) => m[1]);
    assert.deepEqual(found, Array(site.count).fill(pin), `${site.path} /${site.pattern.source}/`);
  }
  for (const site of unionCounts) {
    assert.equal([...readFileSync(join(repositoryRoot, site.path), "utf8").matchAll(site.pattern)].length, 1, site.path);
  }
});

test("rewriteSites rewrites the edit sites of a copied tree and leaves history lines alone", (t) => {
  const root = sandbox(t);
  const pin = readPin();
  const { sites } = readSites();
  for (const path of new Set([SITES_FILE, ...sites.map((site) => site.path)])) {
    mkdirSync(dirname(join(root, path)), { recursive: true });
    writeFileSync(join(root, path), readFileSync(join(repositoryRoot, path)));
  }
  const history = "restart-replay evidence was re-captured at 0.34.14";
  const claude = readFileSync(join(root, "CLAUDE.md"), "utf8");
  assert.ok(claude.includes(history), "CLAUDE.md carries a history line this test guards");

  const changed = rewriteSites(root, readSites(root).sites, "9.8.7", ["edit"]);
  assert.ok(changed.includes("CLAUDE.md") && changed.includes("AGENTS.md"));
  for (const site of sites) {
    const found = [...readFileSync(join(root, site.path), "utf8").matchAll(site.pattern)].map((m) => m[1]);
    assert.deepEqual(found, Array(site.count).fill(site.writer === "edit" ? "9.8.7" : pin), site.path);
  }
  assert.ok(readFileSync(join(root, "CLAUDE.md"), "utf8").includes(history));
});

test("embeddedPatchOrder reads the real staging task in pipeline order", () => {
  const source = readFileSync(join(repositoryRoot,
    "android-compose/build-logic/src/main/kotlin/com/letta/mobile/embedded/PrepareEmbeddedLettaCodeAssetsTask.kt"), "utf8");
  assert.deepEqual(embeddedPatchOrder(source).map(({ name, nodeArgs }) => [name, nodeArgs]), [
    ["transform-unicode-property.mjs", ["--max-old-space-size=8192"]],
    ["patch-vision-model-input.mjs", []],
    ["patch-image-url-shape.mjs", []],
    ["patch-provider-abort.mjs", []],
    ["patch-embedded-letta-code-tool-groups.cjs", []],
  ]);
});

test("skippedPatchNames names lcp patches and token-named patches", () => {
  assert.deepEqual(skippedPatchNames([
    "[letta-code-patch] WARN: lcp-reflection-controls anchors matched 1,0 (expected 1 each) in x — y",
    "[letta-code-patch] WARN: downgradeUnsupportedImages token not found in x — y",
  ]).map((entry) => entry.patch), ["lcp-reflection-controls", "downgradeUnsupportedImages"]);
});

test("dryRunLoader runs a scratch copy of the real loader and names each skipped patch", async (t) => {
  const root = sandbox(t);
  const bundle = join(root, "letta.js");
  writeFileSync(bundle, "#!/usr/bin/env node\nconsole.log('not letta');\n");
  const result = await dryRunLoader(join(repositoryRoot, "scripts/appserver/runtime-patches/letta-code-patch-loader.mjs"), bundle);
  assert.equal(result.applied, 0);
  assert.ok(result.skipped > 20, "an unrelated bundle skips every patch");
  assert.equal(result.skippedPatches.length, result.skipped, "every skip carries its own warning");
  assert.ok(result.skippedPatches.some((entry) => entry.patch === "lcp-reflection-controls"));
});

test("surveyProblems fails on a removed union member, a skipped patch or a failed embedded patch only", () => {
  const clean = {
    unions: { commands: unionDiff(["a"], ["a"]), messages: unionDiff(["m"], ["m", "n"]) },
    loaders: { repo: { applied: 36, skipped: 0 }, host: null },
    hostLoader: { path: "/opt/host-loader", explicit: false },
    embedded: [{ patch: "p", ok: true }],
  };
  assert.deepEqual(surveyProblems(clean), []);
  assert.deepEqual(surveyProblems({ ...clean, unions: { ...clean.unions, commands: unionDiff(["a", "b"], ["a"]) } }), ["commands union removed b"]);
  assert.deepEqual(surveyProblems({ ...clean, loaders: { repo: { applied: 34, skipped: 2 }, host: { applied: 35, skipped: 1 } } }),
    ["repo loader skipped 2 patch(es)", "host loader skipped 1 patch(es)"]);
  assert.deepEqual(surveyProblems({ ...clean, embedded: [{ patch: "p", ok: false }] }), ["embedded p failed"]);
});

test("surveyProblems fails a loader that applied no patch at all, even when none skipped", () => {
  const report = {
    unions: {},
    loaders: { repo: { applied: 0, skipped: 0 }, host: null },
    hostLoader: { path: "/opt/host-loader", explicit: false },
  };
  assert.deepEqual(surveyProblems(report), ["repo loader applied 0 patches"]);
  assert.deepEqual(surveyProblems({ ...report, loaders: { repo: { applied: 36, skipped: 0 }, host: { applied: 0, skipped: 0 } } }),
    ["host loader applied 0 patches"]);
});

test("surveyProblems fails a host loader that applies fewer patches than the repo loader on the same bundle", () => {
  const report = {
    unions: {},
    loaders: { repo: { applied: 36, skipped: 0 }, host: { applied: 28, skipped: 0 } },
    hostLoader: { path: "/opt/host-loader", explicit: true },
  };
  assert.deepEqual(surveyProblems(report), ["host loader applied 28 of the 36 patches the repo loader applies"]);
  assert.deepEqual(surveyProblems({ ...report, loaders: { repo: { applied: 36, skipped: 0 }, host: { applied: 36, skipped: 0 } } }), []);
});

test("surveyProblems fails an explicitly requested host loader that is missing and tolerates an absent default", () => {
  const loaders = { repo: { applied: 36, skipped: 0 }, host: null };
  assert.deepEqual(surveyProblems({ unions: {}, loaders, hostLoader: { path: "/srv/loader", explicit: false } }), []);
  assert.deepEqual(surveyProblems({ unions: {}, loaders, hostLoader: { path: "/srv/loader", explicit: true } }),
    ["host loader /srv/loader was requested with --host-loader and does not exist"]);
});

test("hostLoaderRequest is explicit only when --host-loader names a directory", () => {
  assert.deepEqual(hostLoaderRequest(["survey", "0.34.70"]), { dir: undefined, explicit: false });
  assert.deepEqual(hostLoaderRequest(["survey", "0.34.70", "--host-loader", "/x/y"]), { dir: "/x/y", explicit: true });
  assert.throws(() => hostLoaderRequest(["survey", "0.34.70", "--host-loader"]), /--host-loader needs a directory/);
  assert.throws(() => hostLoaderRequest(["survey", "0.34.70", "--host-loader", "--json"]), /--host-loader needs a directory/);
});
