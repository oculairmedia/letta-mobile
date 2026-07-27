#!/usr/bin/env node

import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const fixtureRoot = join(
  repositoryRoot,
  "android-compose/sharedLogic/src/jvmTest/resources/appserver",
);
const documentPath = join(
  repositoryRoot,
  "docs/architecture/app-server-v2-audit-spec.md",
);
const document = readFileSync(documentPath, "utf8");
const inventory = readJson(join(fixtureRoot, "installed-protocol-v2-inventory.json"));
const ownership = readJson(join(fixtureRoot, "iroh-admin-ownership-matrix.json"));

assertContains(
  `Upstream baseline: \`${inventory.source.package}@${inventory.source.version}\``,
  "pinned package baseline",
);
assertContains(inventory.source.protocol_sha256, "protocol declaration hash");

for (const command of inventory.commands) {
  assertContains(`\`${command}\``, `upstream command ${command}`);
}
for (const message of inventory.messages) {
  assertContains(`\`${message}\``, `upstream message ${message}`);
}

const ownerCounts = new Map();
for (const operation of ownership.operations) {
  ownerCounts.set(operation.owner, (ownerCounts.get(operation.owner) ?? 0) + 1);
}
for (const [owner, count] of ownerCounts) {
  assertContains(`| \`${owner}\` | ${count} |`, `admin owner count ${owner}`);
}

const fallbackCounts = new Map();
for (const operation of ownership.operations) {
  fallbackCounts.set(
    operation.fallback,
    (fallbackCounts.get(operation.fallback) ?? 0) + 1,
  );
}
for (const [fallback, count] of fallbackCounts) {
  assertContains(
    `${count} as \`${fallback}\``,
    `admin fallback count ${fallback}`,
  );
}

for (const heading of [
  "## Required End State: No LettaShim",
  "## Normative Sources",
  "## Complete Upstream Capability Inventory",
  "## Kotlin Typed Protocol Surface",
  "## Iroh Admin RPC Contract",
  "## LettaShim Retirement Ledger",
  "## State Ownership and Persistence",
  "## Cache Invalidation Matrix",
  "## Known Audit Findings",
  "## Audit Procedure",
]) {
  assertContains(heading, `required section ${heading}`);
}

console.log(
  `Verified v2 audit documentation: ${inventory.commands.length} commands, ` +
    `${inventory.messages.length} messages, ${ownership.operations.length} admin methods.`,
);

function readJson(path) {
  return JSON.parse(readFileSync(path, "utf8"));
}

function assertContains(expected, label) {
  if (!document.includes(expected)) {
    throw new Error(`Audit document is missing ${label}: ${expected}`);
  }
}
