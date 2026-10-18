import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { generateReference, inputPaths, loadInputs, markdown, referencePath, renderReference, repositoryRoot } from "./generate-protocol-reference.mjs";
import { verifyAuditDocumentation } from "./verify-v2-audit-doc.mjs";

const pinned = loadInputs();
const clone = () => structuredClone(pinned);

function sandbox(t) {
  const root = mkdtempSync(join(tmpdir(), "letta-protocol-reference-"));
  t.after(() => {
    assert.equal(dirname(resolve(root)), resolve(tmpdir()));
    assert.ok(root.includes("letta-protocol-reference-"));
    rmSync(root, { recursive: true, force: true });
  });
  for (const path of [...inputPaths, "docs/architecture/app-server-v2-audit-spec.md", "docs/architecture/lettashim-retirement-deployment-runbook.md"]) {
    mkdirSync(dirname(join(root, path)), { recursive: true });
    writeFileSync(join(root, path), readFileSync(join(repositoryRoot, path)));
  }
  return root;
}

test("render is deterministic and list/operation ordering does not change semantic output", () => {
  const reversed = clone();
  reversed.inventory.commands.reverse();
  reversed.inventory.messages.reverse();
  reversed.inventory.source.protocol_corpus_files.reverse();
  reversed.ownership.operations.reverse();
  assert.equal(renderReference(reversed), renderReference(pinned));
  assert.equal(renderReference(pinned), renderReference(pinned));
});

test("the reference exposes actual pins, fixture identities and missing upstream commit honestly", () => {
  const text = renderReference(pinned);
  assert.ok(text.includes(`| Version | ${pinned.inventory.source.version} |`));
  assert.ok(text.includes(pinned.inventory.source.protocol_sha256));
  assert.ok(text.includes("Unavailable: the inventory fixture does not record an upstream source Git SHA."));
  for (const { sha256, gitBlobSha } of pinned.provenance) {
    assert.ok(text.includes(sha256));
    assert.ok(text.includes(gitBlobSha));
  }
  const recorded = clone();
  recorded.inventory.source.git_sha = "a".repeat(40);
  assert.ok(renderReference(recorded).includes(`| Upstream source Git SHA | ${"a".repeat(40)} |`));
});

test("fixture command and admin changes update inventory, totals and owner/fallback rows", () => {
  const changed = clone();
  changed.inventory.commands.push("new_probe");
  const operation = { ...changed.ownership.operations[0], method: "new.probe" };
  changed.ownership.operations.push(operation);
  const text = renderReference(changed);
  assert.ok(text.includes(`## Upstream commands (${pinned.inventory.commands.length + 1})`));
  assert.ok(text.includes("new&#95;probe"));
  assert.ok(text.includes(`## Admin ownership (${pinned.ownership.operations.length + 1} methods)`));
  assert.ok(text.includes(`| new.probe | ${markdown(operation.owner)} | ${markdown(operation.fallback)} |`));
  for (const field of ["owner", "fallback"]) {
    const expected = changed.ownership.operations.filter((row) => row[field] === operation[field]).length;
    assert.ok(text.includes(`| ${markdown(operation[field])} | ${expected} |`));
  }
});

test("invalid/inconsistent pins, duplicate declarations and invalid ownership fail closed", () => {
  for (const mutate of [
    (x) => { x.inventory.source.version = "different"; },
    (x) => { x.inventory.source.protocol_sha256 = "not-a-hash"; },
    (x) => { x.inventory.source.protocol_corpus_files = []; },
    (x) => { x.inventory.source.git_sha = "short"; },
    (x) => { x.inventory.messages.push(x.inventory.messages[0]); },
    (x) => { x.inventory.commands = [42]; },
    (x) => { x.ownership.operations.push(x.ownership.operations[0]); },
    (x) => { x.ownership.operations[0].owner = "unreviewed-owner"; },
    (x) => { x.ownership.operations[0].fallback = "new-hidden-proxy"; },
    (x) => { delete x.ownership.operations[0].public_contract; },
  ]) {
    const input = clone();
    mutate(input);
    assert.throws(() => renderReference(input), /Invalid protocol reference input/);
  }
});

test("fixture text cannot inject Markdown table columns, links, HTML or new lines", () => {
  const input = clone();
  input.ownership.operations[0].public_contract = "x | [link](bad) `code` <script> & *text*\nnew\\path";
  const text = renderReference(input);
  assert.ok(text.includes("x &#124; &#91;link&#93;(bad) &#96;code&#96; &lt;script&gt; &amp; &#42;text&#42;&#10;new&#92;path"));
  assert.ok(!text.includes("<script>"));
});

test("offline write/check rejects missing/stale output and recovers after fixture bump", (t) => {
  const root = sandbox(t);
  assert.throws(() => generateReference({ root }), /Missing generated reference/);
  generateReference({ root, mode: "--write" });
  generateReference({ root, mode: "--check" });
  const original = readFileSync(join(root, referencePath), "utf8");
  writeFileSync(join(root, referencePath), original + "hand edit\n");
  assert.throws(() => generateReference({ root }), /Stale generated reference/);
  generateReference({ root, mode: "--write" });
  const inventory = JSON.parse(readFileSync(join(root, inputPaths[0]), "utf8"));
  inventory.commands.push("fixture_bump");
  writeFileSync(join(root, inputPaths[0]), JSON.stringify(inventory));
  assert.throws(() => generateReference({ root }), /Stale generated reference/);
  generateReference({ root, mode: "--write" });
  generateReference({ root });
  assert.notEqual(readFileSync(join(root, referencePath), "utf8"), original);
  assert.throws(() => generateReference({ root, mode: "--anything" }), /use --write or --check/);
});

test("audit verification leaves historical pins alone but preserves deployment safeguards", (t) => {
  const root = sandbox(t);
  generateReference({ root, mode: "--write" });
  const auditPath = join(root, "docs/architecture/app-server-v2-audit-spec.md");
  writeFileSync(auditPath, readFileSync(auditPath, "utf8") + "\nHistorical pin: 0.0.1\n");
  assert.match(verifyAuditDocumentation(root), /Verified generated/);
  const deployment = join(root, "docs/architecture/lettashim-retirement-deployment-runbook.md");
  writeFileSync(deployment, readFileSync(deployment, "utf8").replaceAll("Do not restart Letta App Server", "Removed safeguard"));
  assert.throws(() => verifyAuditDocumentation(root), /deployment safeguard/);
});

test("truncated JSON fails before writing documentation", (t) => {
  const root = sandbox(t);
  writeFileSync(join(root, inputPaths[0]), "{");
  assert.throws(() => generateReference({ root, mode: "--write" }), SyntaxError);
});

test("Git text checkout line endings do not change provenance or output checks", (t) => {
  const root = sandbox(t);
  const before = renderReference(loadInputs(root));
  for (const path of inputPaths) {
    const target = join(root, path);
    writeFileSync(target, readFileSync(target, "utf8").replace(/\r\n/g, "\n").replace(/\n/g, "\r\n"));
  }
  assert.equal(renderReference(loadInputs(root)), before);
  generateReference({ root, mode: "--write" });
  const target = join(root, referencePath);
  writeFileSync(target, readFileSync(target, "utf8").replace(/\n/g, "\r\n"));
  generateReference({ root, mode: "--check" });
});
