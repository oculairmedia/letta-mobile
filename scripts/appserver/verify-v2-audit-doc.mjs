#!/usr/bin/env node
import { readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { generateReference, referencePath, repositoryRoot } from "./generate-protocol-reference.mjs";

export function verifyAuditDocumentation(root = repositoryRoot) {
  generateReference({ root, mode: "--check" });
  const document = readFileSync(join(root, "docs/architecture/app-server-v2-audit-spec.md"), "utf8");
  const deployment = readFileSync(join(root, "docs/architecture/lettashim-retirement-deployment-runbook.md"), "utf8");
  // Historical captures retain their original pins/hashes. Only their current-reference pointer
  // and durable architecture/runbook safeguards belong in this check.
  requireText(document, "../reference/current-app-server-protocol.md", "audit current-reference link");
  for (const heading of [
    "## State Ownership and Persistence", "## Cache Invalidation Matrix", "## Audit Procedure",
  ]) requireText(document, heading, `audit section ${heading}`);
  for (const heading of [
    "## Non-Negotiable Constraints", "## Current Deployment Caveat", "## Required Implementation Order",
    "## Pre-Merge Verification", "## Artifact Preparation", "## Wrapper Environment", "## Systemd Unit",
    "## Staging Deployment", "## Production Rollout", "## Go/No-Go Checklist", "## Rollback",
    "## Post-Deployment Cleanup", "## Handoff Deliverables",
  ]) requireText(deployment, heading, `deployment section ${heading}`);
  for (const requirement of [
    "Port 8291 must be closed", "Do not restart Letta App Server",
    "Ownership matrix has zero `shim_until_cutover` rows", "LETTA_LOCAL_BACKEND_DIR", "iroh-wrapper-cli:distZip",
  ]) requireText(deployment, requirement, `deployment safeguard ${requirement}`);
  return `Verified generated protocol/ownership reference (${referencePath}) and historical deployment safeguards.`;
}

function requireText(text, expected, label) {
  if (!text.includes(expected)) throw new Error(`Missing ${label}: ${expected}`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (process.argv.slice(2).some((arg) => arg !== "--check") || process.argv.length > 3) {
    console.error("Usage: node scripts/appserver/verify-v2-audit-doc.mjs [--check]");
    process.exitCode = 1;
  } else {
    try { console.log(verifyAuditDocumentation()); }
    catch (error) { console.error(error.message); process.exitCode = 1; }
  }
}
