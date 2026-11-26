import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolveDeployedLettaBundle } from "./bundle-fixtures.ts";
import { patchLettaCodeSourceResultForTest } from "../letta-code-patch-loader.mjs";

// lcp-qi2f: the two inbound user-image gates (downgradeUnsupportedImages and the chat payload's
// toChatMessages supportsImages) must honour the vision-model id check, so a discovered
// lmstudio/claude-* model whose resolved input lacks "image" still receives the user's image.
// The functions run from the PATCHED deployed bundle; their model parameter is `model` in 0.34.39
// and `model2` in 0.34.70, which the loader handles as two variants of one patch.

const PLACEHOLDER = "(image omitted: model does not support images)";

function bundle(t: { skip: (reason: string) => void }): string | null {
  const path = resolveDeployedLettaBundle();
  if (!path) { t.skip("deployed bundle unavailable"); return null; }
  return readFileSync(path, "utf8");
}

function section(source: string, start: string, end: string): string {
  const from = source.indexOf(start);
  assert.notEqual(from, -1, `missing ${start}`);
  const to = source.indexOf(end, from);
  assert.notEqual(to, -1, `missing ${end}`);
  return source.slice(from, to);
}

function visionHelper(source: string): (provider: unknown, id: string, input: string[]) => string[] {
  const start = "globalThis.__lcpFixLocalVisionInput = globalThis.__lcpFixLocalVisionInput || function";
  const body = section(source, start, "\n};\n") + "\n};\n";
  const sandbox = { process: { env: { LETTA_LOCAL_BACKEND_EXPERIMENTAL: "1" } } } as Record<string, unknown>;
  new Function("globalThis", "process", body)(sandbox, sandbox["process"]);
  return sandbox["__lcpFixLocalVisionInput"] as (provider: unknown, id: string, input: string[]) => string[];
}

const userImage = [{ role: "user", content: [{ type: "text", text: "what is this" }, { type: "image", data: "AAAA", mimeType: "image/png" }] }];

test("downgradeUnsupportedImages keeps a vision model's image and still strips a text-only model's", (t) => {
  const raw = bundle(t);
  if (!raw) return;
  const { source } = patchLettaCodeSourceResultForTest(raw);
  const fix = visionHelper(source);
  const downgrade = section(source, "function downgradeUnsupportedImages(", "\nfunction transformMessages(");
  const replace = section(source, "function replaceImagesWithPlaceholder(", "\nfunction downgradeUnsupportedImages(");
  const run = new Function("globalThis", "NON_VISION_USER_IMAGE_PLACEHOLDER", "NON_VISION_TOOL_IMAGE_PLACEHOLDER",
    `${replace}\n${downgrade}\nreturn downgradeUnsupportedImages;`)({ __lcpFixLocalVisionInput: fix }, PLACEHOLDER, "(tool image omitted)");

  const vision = run(userImage, { id: "claude-opus-4-8", input: ["text"] });
  assert.equal(vision[0].content[1].type, "image", "a vision-capable id keeps the user image");
  const textOnly = run(userImage, { id: "plain-text-model", input: ["text"] });
  assert.equal(JSON.stringify(textOnly).includes(PLACEHOLDER), true, "a text-only model still gets the placeholder");
  assert.equal(JSON.stringify(textOnly).includes("AAAA"), false);
});

test("the chat payload's toChatMessages gate passes supportsImages for a vision model id", (t) => {
  const raw = bundle(t);
  if (!raw) return;
  const { source } = patchLettaCodeSourceResultForTest(raw);
  const fix = visionHelper(source);
  const build = section(source, "function buildChatPayload(", "\n  const currentTools");
  const seen: boolean[] = [];
  const run = new Function("globalThis", "toChatMessages", `${build}\n  return payload;\n}\nreturn buildChatPayload;`)(
    { __lcpFixLocalVisionInput: fix }, (_messages: unknown, supportsImages: boolean) => { seen.push(supportsImages); return []; });
  run({ id: "claude-opus-4-8", input: ["text"] }, { messages: [] }, []);
  run({ id: "plain-text-model", input: ["text"] }, { messages: [] }, []);
  assert.deepEqual(seen, [true, false]);
});

test("each image gate applies exactly once and skips when its anchor is duplicated", (t) => {
  const raw = bundle(t);
  if (!raw) return;
  const result = patchLettaCodeSourceResultForTest(raw);
  assert.equal(result.source.split("globalThis.__lcpFixLocalVisionInput(undefined").length - 1, 2);
  const gate = section(raw, "toChatMessages(messages, ", ")\n");
  const drifted = patchLettaCodeSourceResultForTest(raw + "\n" + gate + ")\n");
  assert.equal(drifted.skippedPatches, result.skippedPatches + 1);
  assert.equal(drifted.source.split("globalThis.__lcpFixLocalVisionInput(undefined").length - 1, 1);
});
