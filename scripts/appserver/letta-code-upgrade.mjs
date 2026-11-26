#!/usr/bin/env node
// Moves letta-mobile to a new @letta-ai/letta-code release (docs/deploy/letta-code-upgrade.md).
//
//   node scripts/appserver/letta-code-upgrade.mjs survey <version> [--package-root <dir>] [--host-loader <dir>] [--json]
//   node scripts/appserver/letta-code-upgrade.mjs apply  <version> [--package-root <dir>]
//
// survey is read-only: it installs the release to a scratch directory without lifecycle scripts
// (unless --package-root names an install), then reports engines, the dependency diff against the
// pinned release, the npm integrity, the full command/message union diff, a dry run of the repo
// runtime-patch loader and of the host loader (read-only, default /opt/meridian/runtime-patches/current)
// naming every skipped patch, and a dry run of the Android embedded letta.js patches in pipeline
// order. It exits 1 when any patch skips or fails, a union member was removed, a loader applied
// no patch, the host loader applied fewer patches than the repo loader on the same bundle, or a
// host loader named with --host-loader does not exist. Without --host-loader an absent default host
// loader is only printed as `host loader: absent` (a developer machine has none). The `applied=N`
// of a READY survey is the number to pass to the host cutover as EXPECTED_PATCHES.
//
// apply rewrites every version site listed in android-compose/letta-code-runtime/letta-code-version-sites.json
// (the list LettaCodeVersionPinsTest reads), regenerates desktop/runtime/package-lock.json with npm,
// re-captures the contract matrix, inventory, CLI probe fixtures and union counts from the installed
// package, regenerates the protocol reference and runs verify-contract-baseline. It never touches
// the host. New union members are captured but must be classified by hand in the inventory's
// capabilities (AppServerContractBaselineTest fails until they are); removed members stop apply.
import { execFileSync } from "node:child_process";
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import { generateReference } from "./generate-protocol-reference.mjs";
import {
  fixtureRoot, installedProtocolSource, installedUnions, readJson, repositoryRoot,
  resolveInstalledPackageRoot, runCliProbe,
} from "./verify-contract-baseline.mjs";

export const PACKAGE = "@letta-ai/letta-code";
export const SITES_FILE = "android-compose/letta-code-runtime/letta-code-version-sites.json";
const PIN_FILE = "android-compose/letta-code-runtime/letta-code-runtime.properties";
const MATRIX = join(fixtureRoot, "app-server-v2-contract-matrix.json");
const INVENTORY = join(fixtureRoot, "installed-protocol-v2-inventory.json");
const REPO_LOADER = "scripts/appserver/runtime-patches/letta-code-patch-loader.mjs";
const HOST_LOADER_DIR = "/opt/meridian/runtime-patches/current";
const EMBEDDED_PATCH_DIR = "android-compose/letta-code-runtime/patches";
const EMBEDDED_TASK = "android-compose/build-logic/src/main/kotlin/com/letta/mobile/embedded/PrepareEmbeddedLettaCodeAssetsTask.kt";
const TOOL_GROUP_PATCH = "scripts/patch-embedded-letta-code-tool-groups.cjs";
const CURSOR_IMPORT_CHECK = "check-no-static-cursor-import.mjs";
// The babel packages the embedded pipeline installs next to the patches (PrepareEmbeddedLettaCodeAssetsTask).
const EMBEDDED_TOOLCHAIN = ["@babel/core@7", "@babel/plugin-transform-unicode-property-regex@7", "regexpu-core@6"];
const VERSION = /^\d+\.\d+\.\d+$/;

// ---------------------------------------------------------------------------------------------
// Pure helpers (unit-tested in letta-code-upgrade.test.mjs)

/** The current pin, from the pin file under `root`. */
export function readPin(root = repositoryRoot) {
  const match = readFileSync(join(root, PIN_FILE), "utf8").match(/^lettaCodeVersion=(\S+)$/m);
  if (!match) throw new Error(`${PIN_FILE} has no lettaCodeVersion`);
  return match[1];
}

/** Added and removed members of one union, each in the order it is declared. */
export function unionDiff(pinned, installed) {
  return {
    pinned: pinned.length,
    installed: installed.length,
    added: installed.filter((member) => !pinned.includes(member)),
    removed: pinned.filter((member) => !installed.includes(member)),
  };
}

/** The leading version number a dependency range pins (`^1.5.2` -> 1, `^0.35.5` -> 0.35). */
function majorLine(range) {
  const parts = String(range).replace(/^[^\d]*/, "").split(".").map(Number);
  return parts[0] === 0 ? `0.${parts[1]}` : String(parts[0]);
}

/** Added, removed and changed dependencies between two `dependencies` maps; `major` marks a breaking range move. */
export function dependencyDiff(before = {}, after = {}) {
  const names = [...new Set([...Object.keys(before), ...Object.keys(after)])].sort((left, right) => left.localeCompare(right));
  return {
    added: names.filter((name) => !(name in before)).map((name) => ({ name, to: after[name] })),
    removed: names.filter((name) => !(name in after)).map((name) => ({ name, from: before[name] })),
    changed: names.filter((name) => name in before && name in after && before[name] !== after[name])
      .map((name) => ({ name, from: before[name], to: after[name], major: majorLine(before[name]) !== majorLine(after[name]) })),
  };
}

/** The version sites of [SITES_FILE] under `root`, as `{ path, pattern: RegExp, count, writer }`. */
export function readSites(root = repositoryRoot) {
  const list = JSON.parse(readFileSync(join(root, SITES_FILE), "utf8"));
  const compile = (site) => ({ ...site, pattern: new RegExp(site.pattern, "g") });
  return { sites: list.sites.map(compile), unionCounts: list.unionCounts.map(compile) };
}

/** `text` with group 1 of every `pattern` match replaced by `value`; throws unless it matched `count` times. */
export function replaceGroup(text, pattern, value, count, label) {
  // The `d` flag records where group 1 really sits; searching the match for its text would find an
  // earlier equal span (for example the command count when it equals the message count).
  const indexed = new RegExp(pattern.source, `${pattern.flags.replace(/[dg]/g, "")}dg`);
  const matches = [...text.matchAll(indexed)];
  if (count !== undefined && matches.length !== count) throw new Error(`${label}: expected ${count} match(es) of /${pattern.source}/, found ${matches.length}`);
  if (matches.length === 0) throw new Error(`${label}: /${pattern.source}/ matched nothing`);
  let result = "";
  let cursor = 0;
  for (const match of matches) {
    const [start, end] = match.indices[1];
    result += text.slice(cursor, start) + value;
    cursor = end;
  }
  return result + text.slice(cursor);
}

/** Rewrites every site of `writers` under `root` to `version`. Returns the paths it changed. */
export function rewriteSites(root, sites, version, writers = ["edit"]) {
  const changed = new Set();
  for (const site of sites.filter((candidate) => writers.includes(candidate.writer))) {
    const path = join(root, site.path);
    const before = readFileSync(path, "utf8");
    const after = replaceGroup(before, site.pattern, version, site.count, site.path);
    if (after !== before) { writeFileSync(path, after); changed.add(site.path); }
  }
  return [...changed];
}

/** The pin file text moved to `version`/`integrity`, with assetRevision bumped so devices re-extract. */
export function bumpPinFile(text, version, integrity) {
  const revision = Number(text.match(/^assetRevision=(\d+)$/m)?.[1]);
  if (!Number.isInteger(revision)) throw new Error(`${PIN_FILE} has no assetRevision`);
  return text
    .replace(/^lettaCodeVersion=\S+$/m, `lettaCodeVersion=${version}`)
    .replace(/^lettaCodeIntegrity=\S+$/m, `lettaCodeIntegrity=${integrity}`)
    .replace(/^assetRevision=\d+$/m, `assetRevision=${revision + 1}`);
}

/** Patch scripts of the embedded pipeline, in order, read from LETTA_JS_PATCHES in the staging task. */
export function embeddedPatchOrder(kotlinSource) {
  const block = kotlinSource.match(/val LETTA_JS_PATCHES: List<LettaJsPatch> = listOf\(([\s\S]*?)\n\)/);
  if (!block) throw new Error("LETTA_JS_PATCHES not found in the staging task");
  return block[1].split(/(?=LettaJsPatch\.)/).filter((entry) => entry.startsWith("LettaJsPatch.")).map((entry) => {
    if (entry.startsWith("LettaJsPatch.ToolGroups")) return { kind: "toolGroups", name: basename(TOOL_GROUP_PATCH), nodeArgs: [] };
    const nodeArgs = entry.match(/nodeArgs = listOf\(([^)]*)\)/)?.[1] ?? "";
    return { kind: "script", name: entry.match(/Script\(\s*"([^"]+)"/)[1], nodeArgs: [...nodeArgs.matchAll(/"([^"]+)"/g)].map((m) => m[1]) };
  });
}

/** The patch-loader warnings, one per skipped patch, as `{ patch, warning }`. */
export function skippedPatchNames(warnings) {
  return warnings.map((warning) => {
    const named = warning.match(/\b(lcp-[a-z0-9-]+)\b/);
    const token = warning.match(/WARN: (.+?) (?:anchors? |token |literal )?(?:matched|not found)/);
    return { patch: named?.[1] ?? token?.[1] ?? "unknown", warning: warning.replace(/^\[letta-code-patch\] /, "") };
  });
}

/** Dry-runs the loader at `loaderPath` on `bundlePath` from a scratch copy, collecting its warnings. */
export async function dryRunLoader(loaderPath, bundlePath) {
  const dir = mkdtempSync(join(tmpdir(), "lcp-dry-run-"));
  try {
    const copy = join(dir, "letta-code-patch-loader.mjs");
    writeFileSync(copy, `${readFileSync(loaderPath, "utf8")}\nexport { patchLettaCodeSource as __upgradeDryRun };\n`);
    const { __upgradeDryRun: patch } = await import(pathToFileURL(copy).href);
    const warnings = [];
    const write = process.stderr.write;
    process.stderr.write = (chunk) => { warnings.push(String(chunk).trim()); return true; };
    let result;
    try {
      result = patch(readFileSync(bundlePath, "utf8"), bundlePath, true);
    } finally {
      process.stderr.write = write;
    }
    const skipped = skippedPatchNames(warnings.filter((line) => line.includes("WARN")));
    return { loader: loaderPath, applied: result.appliedPatches, skipped: result.skippedPatches, skippedPatches: skipped };
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

// ---------------------------------------------------------------------------------------------
// npm and filesystem effects

function npm(args, options = {}) {
  const sibling = join(dirname(process.execPath), "npm");
  return execFileSync(existsSync(sibling) ? sibling : "npm", args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"], ...options });
}

function npmView(spec, fields) {
  const out = npm(["view", spec, ...fields, "--json"]).trim();
  const parsed = out ? JSON.parse(out) : {};
  return fields.length === 1 ? { [fields[0]]: parsed } : parsed;
}

/** The installed package root for `version`: `packageRoot` if given, else a fresh scratch install without scripts. */
function installPackage(version, packageRoot) {
  if (packageRoot) return { root: resolveInstalledPackageRoot(resolve(packageRoot), PACKAGE), scratch: null };
  const scratch = mkdtempSync(join(tmpdir(), `letta-code-${version}-`));
  npm(["install", "--no-audit", "--no-fund", "--ignore-scripts", "--prefix", scratch, `${PACKAGE}@${version}`]);
  return { root: resolveInstalledPackageRoot(scratch, PACKAGE), scratch };
}

/** Runs the Android embedded letta.js patches on a copy of the bundle, in pipeline order. */
function dryRunEmbedded(bundlePath) {
  const work = mkdtempSync(join(tmpdir(), "embedded-letta-code-"));
  try {
    npm(["install", "--no-audit", "--no-fund", "--prefix", work, ...EMBEDDED_TOOLCHAIN]);
    cpSync(join(repositoryRoot, EMBEDDED_PATCH_DIR), work, { recursive: true });
    cpSync(join(repositoryRoot, TOOL_GROUP_PATCH), join(work, basename(TOOL_GROUP_PATCH)));
    // The patches refuse a letta.js outside their own directory (patch-target.mjs).
    const lettaJs = join(work, "node_modules", ...PACKAGE.split("/"), "letta.js");
    mkdirSync(dirname(lettaJs), { recursive: true });
    cpSync(bundlePath, lettaJs);
    const steps = [...embeddedPatchOrder(readFileSync(join(repositoryRoot, EMBEDDED_TASK), "utf8")),
      { kind: "check", name: CURSOR_IMPORT_CHECK, nodeArgs: [] }];
    return steps.map((step) => {
      try {
        execFileSync(process.execPath, [...step.nodeArgs, join(work, step.name), lettaJs], { cwd: work, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
        return { patch: step.name, ok: true };
      } catch (error) {
        return { patch: step.name, ok: false, error: String(error.stderr || error.message).trim().split("\n").find((line) => /Error/.test(line)) ?? "failed" };
      }
    });
  } finally {
    rmSync(work, { recursive: true, force: true });
  }
}

// ---------------------------------------------------------------------------------------------
// survey

/** The host loader directory a command line asks for; `explicit` when `--host-loader` names it, so a missing one is an error. */
export function hostLoaderRequest(args) {
  const index = args.indexOf("--host-loader");
  if (index < 0) return { dir: undefined, explicit: false };
  const dir = args[index + 1];
  if (!dir || dir.startsWith("--")) throw new Error("--host-loader needs a directory");
  return { dir, explicit: true };
}

export async function survey(version, { packageRoot, hostLoader = HOST_LOADER_DIR, hostLoaderExplicit = false, embedded = true } = {}) {
  const pinned = readPin();
  const install = installPackage(version, packageRoot);
  try {
    const packageJson = readJson(join(install.root, "package.json"));
    if (packageJson.version !== version) throw new Error(`${install.root} is ${packageJson.version}, not ${version}`);
    const before = npmView(`${PACKAGE}@${pinned}`, ["dependencies", "engines"]);
    const integrity = npmView(`${PACKAGE}@${version}`, ["dist.integrity"])["dist.integrity"];
    const inventory = readJson(INVENTORY);
    const unions = installedUnions(install.root, inventory.source.protocol_declaration);
    const bundle = join(install.root, "letta.js");
    const hostLoaderPath = join(hostLoader, "letta-code-patch-loader.mjs");
    return {
      version,
      pinned,
      hostLoader: { path: hostLoaderPath, explicit: hostLoaderExplicit },
      packageRoot: install.root,
      integrity,
      engines: { pinned: before.engines?.node ?? null, candidate: packageJson.engines?.node ?? null },
      dependencies: dependencyDiff(before.dependencies, packageJson.dependencies),
      unions: { commands: unionDiff(inventory.commands, unions.commands), messages: unionDiff(inventory.messages, unions.messages) },
      loaders: {
        repo: await dryRunLoader(join(repositoryRoot, REPO_LOADER), bundle),
        host: existsSync(hostLoaderPath) ? await dryRunLoader(hostLoaderPath, bundle) : null,
      },
      embedded: embedded ? dryRunEmbedded(bundle) : null,
    };
  } finally {
    if (install.scratch) rmSync(install.scratch, { recursive: true, force: true });
  }
}

/** Why `report` must not ship as is; empty when the survey passes. */
export function surveyProblems(report) {
  const problems = [];
  for (const [name, diff] of Object.entries(report.unions)) {
    if (diff.removed.length) problems.push(`${name} union removed ${diff.removed.join(", ")}`);
  }
  for (const [name, loader] of Object.entries(report.loaders)) {
    if (loader?.skipped) problems.push(`${name} loader skipped ${loader.skipped} patch(es)`);
    else if (loader && loader.applied === 0) problems.push(`${name} loader applied 0 patches`);
  }
  // The loader does not expose how many patches it defines (a step counts one to five applied sites
  // and a half-applied step shows up in neither counter), so the repo loader on the same bundle is
  // the yardstick: a host loader that applies fewer is running with patches missing.
  const { repo, host } = report.loaders;
  if (host && repo && host.applied > 0 && !host.skipped && host.applied < repo.applied) {
    problems.push(`host loader applied ${host.applied} of the ${repo.applied} patches the repo loader applies`);
  }
  if (!host && report.hostLoader?.explicit) {
    problems.push(`host loader ${report.hostLoader.path} was requested with --host-loader and does not exist`);
  }
  for (const step of report.embedded ?? []) {
    if (!step.ok) problems.push(`embedded ${step.patch} failed`);
  }
  return problems;
}

function printSurvey(report) {
  const lines = [
    `letta-code ${report.pinned} -> ${report.version} (${report.packageRoot})`,
    `integrity: ${report.integrity}`,
    `engines:   ${report.engines.pinned} -> ${report.engines.candidate}`,
    "dependencies:",
    ...report.dependencies.added.map((d) => `  + ${d.name} ${d.to}`),
    ...report.dependencies.removed.map((d) => `  - ${d.name} ${d.from}`),
    ...report.dependencies.changed.map((d) => `  ~ ${d.name} ${d.from} -> ${d.to}${d.major ? " (major)" : ""}`),
  ];
  for (const [name, diff] of Object.entries(report.unions)) {
    lines.push(`${name} union: ${diff.pinned} -> ${diff.installed}; added [${diff.added.join(", ")}]; removed [${diff.removed.join(", ")}]`);
  }
  for (const [name, loader] of Object.entries(report.loaders)) {
    if (!loader) { lines.push(`${name} loader: absent`); continue; }
    lines.push(`${name} loader (${loader.loader}): applied=${loader.applied} skipped=${loader.skipped}`);
    lines.push(...loader.skippedPatches.map((s) => `  skipped ${s.patch}: ${s.warning}`));
  }
  if (report.embedded) {
    lines.push("embedded patches:", ...report.embedded.map((s) => `  ${s.ok ? "ok  " : "FAIL"} ${s.patch}${s.ok ? "" : `: ${s.error}`}`));
  }
  const problems = surveyProblems(report);
  lines.push(problems.length ? `NOT READY: ${problems.join("; ")}` : "READY");
  console.log(lines.join("\n"));
}

// ---------------------------------------------------------------------------------------------
// apply

function writeJson(path, value) {
  writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`);
}

/** Re-captures matrix, inventory and CLI fixtures from `packageRoot`; returns the union diff. */
function recaptureContract(packageRoot, version) {
  const matrix = readJson(MATRIX);
  const inventory = readJson(INVENTORY);
  if (process.version !== matrix.baseline.node) {
    throw new Error(`re-capture needs Node ${matrix.baseline.node} (the matrix pin), running ${process.version}`);
  }
  const packageJson = readJson(join(packageRoot, "package.json"));
  const source = installedProtocolSource(packageRoot, inventory.source.protocol_declaration);
  const unions = installedUnions(packageRoot, inventory.source.protocol_declaration);
  const diff = { commands: unionDiff(inventory.commands, unions.commands), messages: unionDiff(inventory.messages, unions.messages) };
  const removed = [...diff.commands.removed, ...diff.messages.removed];
  if (removed.length) throw new Error(`union members removed upstream (${removed.join(", ")}): a product decision, not an automatic re-capture`);

  const previous = matrix.baseline.version;
  Object.assign(matrix.baseline, { version, package_engine: packageJson.engines?.node ?? matrix.baseline.package_engine, ...source,
    captured_at: new Date().toISOString().slice(0, 10) });
  matrix.observed_installed_contract.source = matrix.observed_installed_contract.source.replace(previous, version);
  Object.assign(inventory.source, { version, ...source });
  inventory.commands = unions.commands;
  inventory.messages = unions.messages;
  writeJson(MATRIX, matrix);
  writeJson(INVENTORY, inventory);
  for (const probe of matrix.cli_probes) {
    writeFileSync(join(fixtureRoot, probe.fixture), runCliProbe(probe.classification, join(packageRoot, "letta.js")));
  }
  return { diff, counts: { commands: unions.commands.length, messages: unions.messages.length } };
}

export async function apply(version, { packageRoot } = {}) {
  const install = installPackage(version, packageRoot);
  try {
    const installedVersion = readJson(join(install.root, "package.json")).version;
    if (installedVersion !== version) throw new Error(`${install.root} is ${installedVersion}, not ${version}`);
    const integrity = npmView(`${PACKAGE}@${version}`, ["dist.integrity"])["dist.integrity"];
    const { sites, unionCounts } = readSites();

    const contract = recaptureContract(install.root, version);
    const pinPath = join(repositoryRoot, PIN_FILE);
    writeFileSync(pinPath, bumpPinFile(readFileSync(pinPath, "utf8"), version, integrity));
    rewriteSites(repositoryRoot, sites, version, ["edit"]);
    for (const site of unionCounts) {
      const path = join(repositoryRoot, site.path);
      writeFileSync(path, replaceGroup(readFileSync(path, "utf8"), site.pattern, String(contract.counts[site.union]), 1, site.path));
    }
    npm(["install", "--package-lock-only", "--ignore-scripts", "--no-audit", "--no-fund"], { cwd: join(repositoryRoot, "android-compose/desktop/runtime") });
    generateReference({ mode: "--write" });
    execFileSync(process.execPath, [join(repositoryRoot, "scripts/appserver/verify-contract-baseline.mjs"), "--package-root", install.root], { stdio: "inherit" });

    const stale = sites.filter((site) => [...readFileSync(join(repositoryRoot, site.path), "utf8").matchAll(site.pattern)].some((m) => m[1] !== version));
    if (stale.length) throw new Error(`sites still not at ${version}: ${stale.map((site) => site.path).join(", ")}`);
    const added = [...contract.diff.commands.added, ...contract.diff.messages.added];
    console.log([
      `Applied letta-code ${version}: ${sites.length} version sites, contract re-captured (${contract.counts.commands} commands, ${contract.counts.messages} messages).`,
      added.length
        ? `Classify the new union members in ${INVENTORY.replace(`${repositoryRoot}/`, "")} capabilities (AppServerContractBaselineTest fails until you do): ${added.join(", ")}`
        : "No new union members.",
      "Still by hand: runtime patches that skip (survey), the Android embedded size and Node 18 smoke, the host cutover.",
    ].join("\n"));
  } finally {
    if (install.scratch) rmSync(install.scratch, { recursive: true, force: true });
  }
}

// ---------------------------------------------------------------------------------------------

function option(args, name) {
  const index = args.indexOf(name);
  return index >= 0 ? args[index + 1] : undefined;
}

async function main(args) {
  const [mode, version] = args;
  if (!["survey", "apply"].includes(mode) || !VERSION.test(version ?? "")) {
    console.error("usage: letta-code-upgrade.mjs survey|apply <x.y.z> [--package-root <dir>] [--host-loader <dir>] [--json] [--no-embedded]");
    return 64;
  }
  const packageRoot = option(args, "--package-root");
  if (mode === "apply") { await apply(version, { packageRoot }); return 0; }
  let request;
  try { request = hostLoaderRequest(args); } catch (error) { console.error(error.message); return 64; }
  const report = await survey(version, { packageRoot, hostLoader: request.dir ?? HOST_LOADER_DIR, hostLoaderExplicit: request.explicit, embedded: !args.includes("--no-embedded") });
  if (args.includes("--json")) console.log(JSON.stringify({ ...report, problems: surveyProblems(report) }, null, 2));
  else printSurvey(report);
  return surveyProblems(report).length ? 1 : 0;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exitCode = await main(process.argv.slice(2));
}
