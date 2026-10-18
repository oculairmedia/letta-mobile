#!/usr/bin/env node
import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
export const referencePath = "docs/reference/current-app-server-protocol.md";
const fixtures = "android-compose/sharedLogic/src/jvmTest/resources/appserver/";
export const inputPaths = [
  `${fixtures}installed-protocol-v2-inventory.json`,
  `${fixtures}iroh-admin-ownership-matrix.json`,
  `${fixtures}app-server-v2-contract-matrix.json`,
];

function requireValue(condition, message) {
  if (!condition) throw new Error(`Invalid protocol reference input: ${message}`);
}
function nonempty(value, label) {
  requireValue(typeof value === "string" && value.trim().length > 0, `${label} must be a nonempty string`);
}
function strings(value, label) {
  requireValue(Array.isArray(value) && value.length > 0, `${label} must be a nonempty array`);
  value.forEach((entry) => nonempty(entry, label));
  requireValue(new Set(value).size === value.length, `${label} contains duplicates`);
}
const order = (a, b) => a < b ? -1 : a > b ? 1 : 0;
const sorted = (values) => [...values].sort(order);

/** Escape fixture data, including Markdown and HTML, without trusting it as document markup. */
export function markdown(value) {
  return String(value).replace(/[&<>`|\\\[\]*_#\r\n]/g, (character) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "`": "&#96;", "|": "&#124;",
    "\\": "&#92;", "[": "&#91;", "]": "&#93;", "*": "&#42;", "_": "&#95;",
    "#": "&#35;", "\r": "&#13;", "\n": "&#10;",
  })[character]);
}
const table = (headings, rows) => [
  `| ${headings.join(" | ")} |`,
  `| ${headings.map(() => "---").join(" | ")} |`,
  ...rows.map((row) => `| ${row.map(markdown).join(" | ")} |`),
].join("\n");

export function validateInputs({ inventory, ownership, contract }) {
  requireValue(inventory && ownership && contract, "all three fixtures are required");
  const source = inventory.source;
  requireValue(source && contract.baseline, "inventory.source and contract.baseline are required");
  for (const key of ["package", "version", "node_version", "protocol_declaration", "public_export", "reproduction"]) {
    nonempty(source[key], `source.${key}`);
  }
  for (const key of ["protocol_sha256", "protocol_corpus_sha256"]) {
    requireValue(/^[a-f0-9]{64}$/.test(source[key]), `source.${key} must be a lowercase SHA-256`);
    requireValue(source[key] === contract.baseline[key], `${key} differs between inventory and contract`);
  }
  for (const key of ["package", "version"]) {
    requireValue(source[key] === contract.baseline[key], `${key} differs between inventory and contract`);
  }
  requireValue(source.node_version === contract.baseline.node, "Node pin differs between inventory and contract");
  strings(source.protocol_corpus_files, "source.protocol_corpus_files");
  strings(contract.baseline.protocol_corpus_files, "baseline.protocol_corpus_files");
  requireValue(JSON.stringify(sorted(source.protocol_corpus_files)) === JSON.stringify(sorted(contract.baseline.protocol_corpus_files)), "protocol corpus files differ");
  strings(inventory.commands, "commands");
  strings(inventory.messages, "messages");
  requireValue(ownership.enums && Array.isArray(ownership.operations) && ownership.operations.length > 0, "ownership enums and nonempty operations are required");
  for (const key of ["owners", "fallbacks", "authorization_classes"]) strings(ownership.enums[key], `enums.${key}`);
  const methods = new Set();
  for (const operation of ownership.operations) {
    requireValue(operation && typeof operation === "object", "operation must be an object");
    for (const key of ["method", "owner", "fallback", "handler", "authorization_class", "public_contract"]) nonempty(operation[key], `operation.${key}`);
    requireValue(!methods.has(operation.method), `duplicate method ${operation.method}`);
    methods.add(operation.method);
    for (const [field, values] of [["owner", "owners"], ["fallback", "fallbacks"], ["authorization_class", "authorization_classes"]]) {
      requireValue(ownership.enums[values].includes(operation[field]), `unknown ${field} ${operation[field]}`);
    }
  }
  const upstreamSha = source.git_sha ?? source.git_commit ?? source.commit_sha;
  if (upstreamSha !== undefined) requireValue(/^[a-fA-F0-9]{40}$|^[a-fA-F0-9]{64}$/.test(upstreamSha), "source Git SHA must be a full SHA");
}

export function loadInputs(root = repositoryRoot) {
  // Git's text checkout conversion must not make Windows and CI render different provenance.
  const files = inputPaths.map((path) => ({
    path, bytes: Buffer.from(readFileSync(join(root, path), "utf8").replace(/\r\n/g, "\n"), "utf8"),
  }));
  return {
    inventory: JSON.parse(files[0].bytes.toString("utf8")),
    ownership: JSON.parse(files[1].bytes.toString("utf8")),
    contract: JSON.parse(files[2].bytes.toString("utf8")),
    provenance: files.map(({ path, bytes }) => ({
      path,
      sha256: createHash("sha256").update(bytes).digest("hex"),
      gitBlobSha: createHash("sha1").update(`blob ${bytes.length}\0`).update(bytes).digest("hex"),
    })),
  };
}

function counts(operations, field) {
  const result = new Map();
  for (const operation of operations) result.set(operation[field], (result.get(operation[field]) ?? 0) + 1);
  return sorted(result.keys()).map((key) => [key, result.get(key)]);
}

export function renderReference(inputs) {
  validateInputs(inputs);
  const { inventory, ownership, contract, provenance = [] } = inputs;
  const source = inventory.source;
  const sha = source.git_sha ?? source.git_commit ?? source.commit_sha;
  const sections = [
    "# Current App Server protocol and admin ownership reference",
    "<!-- GENERATED by scripts/appserver/generate-protocol-reference.mjs. Do not edit by hand. -->",
    "**Status:** Generated offline from pinned repository fixtures. These fixtures are the authority for this reference; generation does not query a live host, execute upstream code, or prove deployed behavior.",
    "Regenerate with `node scripts/appserver/generate-protocol-reference.mjs --write`; verify with `node scripts/appserver/generate-protocol-reference.mjs --check`.",
    "## Package and declaration provenance",
    table(["Field", "Pinned value"], [
      ["Package", source.package], ["Version", source.version], ["Node", source.node_version],
      ["Upstream source Git SHA", sha ?? "Unavailable: the inventory fixture does not record an upstream source Git SHA."],
      ["Capture date (contract baseline)", contract.baseline.captured_at ?? "Not recorded in fixture"],
      ["Public declaration export", source.public_export], ["Protocol declaration", source.protocol_declaration],
      ["Protocol SHA-256", source.protocol_sha256], ["Declaration corpus SHA-256", source.protocol_corpus_sha256],
      ["Upstream recapture command", source.reproduction],
    ]),
    "The upstream source commit is distinct from the Git blob identities below. Hashes use UTF-8 fixture bytes with CRLF normalized to LF, matching Git text normalization, so Windows and CI render identical provenance. They include historical notes but do not identify the upstream package commit or a deployed release. No current checkout HEAD is embedded, so regeneration remains deterministic across commits.",
    table(["Authoritative fixture", "SHA-256 (LF-normalized bytes)", "Git blob SHA-1 (LF-normalized bytes)"], provenance.map(({ path, sha256, gitBlobSha }) => [path, sha256, gitBlobSha])),
    `Sources: [installed protocol inventory](../../${inputPaths[0]}), [admin ownership matrix](../../${inputPaths[1]}) and [contract matrix](../../${inputPaths[2]}). These source files may contain dated annotations; this generator does not upgrade their evidence dates.`,
    `## Declaration corpus (${source.protocol_corpus_files.length} files)`,
    table(["Package-relative file"], sorted(source.protocol_corpus_files).map((name) => [name])),
    `## Upstream commands (${inventory.commands.length})`,
    table(["Discriminant"], sorted(inventory.commands).map((name) => [name])),
    `## Upstream messages (${inventory.messages.length})`,
    table(["Discriminant"], sorted(inventory.messages).map((name) => [name])),
    `## Admin ownership (${ownership.operations.length} methods)`,
    "These are declared ownership decisions, not newly observed production execution. Method-registration parity remains enforced by [IrohAdminOwnershipMatrixTest](../../android-compose/sharedLogic/src/jvmTest/kotlin/com/letta/mobile/data/controller/node/iroh/IrohAdminOwnershipMatrixTest.kt); the generator does not parse Kotlin or replace that test.",
    table(["Owner", "Method count"], counts(ownership.operations, "owner")),
    table(["Fallback", "Method count"], counts(ownership.operations, "fallback")),
    table(["Method", "Owner", "Fallback", "Authorization", "Handler", "Public contract"], [...ownership.operations].sort((a, b) => order(a.method, b.method)).map((op) => [op.method, op.owner, op.fallback, op.authorization_class, op.handler, op.public_contract])),
    "## Interpretation and historical evidence",
    "An upstream discriminant is not proof that the Kotlin client adopts it or that a particular host permits it. The generated [Kotlin command API](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.transport.appserver/-app-server-command/index.html) describes the typed client surface. Architecture, retry semantics, state ownership rationale and dated captures remain in the [historical system audit](../architecture/app-server-v2-audit-spec.md), [migration ownership record](../architecture/iroh-admin-ownership-matrix.md) and [deployment runbook](../architecture/lettashim-retirement-deployment-runbook.md).",
  ];
  return sections.join("\n\n") + "\n";
}

export function generateReference({ root = repositoryRoot, mode = "--check" } = {}) {
  requireValue(["--write", "--check"].includes(mode), "use --write or --check");
  const expected = renderReference(loadInputs(root));
  const output = join(root, referencePath);
  if (mode === "--write") {
    mkdirSync(dirname(output), { recursive: true });
    writeFileSync(output, expected, "utf8");
  } else {
    let actual;
    try { actual = readFileSync(output, "utf8").replace(/\r\n/g, "\n"); }
    catch (error) {
      if (error.code !== "ENOENT") throw error;
      throw new Error(`Missing generated reference ${referencePath}; run node scripts/appserver/generate-protocol-reference.mjs --write`);
    }
    if (actual !== expected) throw new Error(`Stale generated reference ${referencePath}; run node scripts/appserver/generate-protocol-reference.mjs --write`);
  }
  return output;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  if (args.length !== 1 || !["--write", "--check"].includes(args[0])) {
    console.error("Usage: node scripts/appserver/generate-protocol-reference.mjs --write|--check");
    process.exitCode = 1;
  } else {
    try { console.log(`${args[0]}: ${generateReference({ mode: args[0] })}`); }
    catch (error) { console.error(error.message); process.exitCode = 1; }
  }
}
