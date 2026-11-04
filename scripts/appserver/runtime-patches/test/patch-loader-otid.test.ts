import { resolveLettaBundle, resolveDeployedLettaBundle } from "./bundle-fixtures.ts";
import { test } from "node:test";
import assert from "node:assert/strict";
import { appendFileSync, existsSync, mkdtempSync, readFileSync, readdirSync, rmSync } from "node:fs";
import { dirname, join } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath, pathToFileURL } from "node:url";

import {
  patchLettaCodeSourceForTest,
  patchLettaCodeSourceResultForTest,
  CONVERSATION_LAST_RUN_HELPER_DEFINITION,
} from "../letta-code-patch-loader.mjs";


const OTID_STAMP_ANCHOR = `            yield createLocalMessageChunk(event.message);`;
const OTID_PERSIST_ANCHOR = `    usage: message.usage ?? emptyLocalUsage(),`;
const OTID_PROJECT_ANCHOR =
  "    messages.push({\n" +
  "      id: isFirst ? message.id : `${message.id}:assistant:${pendingTextStartIndex}`,\n" +
  "      date,\n" +
  "      agent_id: agentId,\n" +
  "      conversation_id: conversationId,\n" +
  '      message_type: "assistant_message",\n' +
  '      role: "assistant",\n' +
  "      content: pendingTextContent\n" +
  "    });";

test("patch-loader: lcp-otid stamps the segment otid and persists it", () => {
  const bundle =
    "const before = 1;\n" +
    OTID_STAMP_ANCHOR + "\n" +
    OTID_PERSIST_ANCHOR + "\n" +
    OTID_PROJECT_ANCHOR + "\n" +
    "const after = 2;\n";

  const patched = patchLettaCodeSourceForTest(bundle);

  // Stamped where the otids were minted, persisted where the record is built.
  assert.ok(patched.includes('globalThis.__lcpStampAssistantOtids(event.message, typeof assistantOtids === "undefined" ? undefined : assistantOtids)'));
  assert.ok(patched.includes("...message.otid ? { otid: message.otid } : {},"));
  assert.ok(patched.includes("globalThis.__lcpStampAssistantOtids = globalThis.__lcpStampAssistantOtids ||"));
});

test("patch-loader: lcp-otid stamps the first segment and records them all", () => {
  const globals = globalThis as Record<string, unknown>;
  const previous = globals["__lcpStampAssistantOtids"];
  delete globals["__lcpStampAssistantOtids"];
  try {
    const patched = patchLettaCodeSourceForTest(
      OTID_STAMP_ANCHOR + "\n" + OTID_PERSIST_ANCHOR + "\n" + OTID_PROJECT_ANCHOR + "\n",
    );
    // The helper is injected ahead of the bundle; take exactly it, so the test
    // evaluates the shipped source rather than a copy that could drift from it.
    const start = patched.indexOf("globalThis.__lcpStampAssistantOtids =");
    assert.ok(start >= 0, "helper must be injected");
    const end = patched.indexOf("\n};\n", start);
    assert.ok(end > start, "helper must be terminated");
    new Function(patched.slice(start, end + "\n};".length))();
    const stamp = globals["__lcpStampAssistantOtids"] as
      (message: unknown, otids: unknown) => Record<string, unknown>;

    const otids = new Map<number, string>([
      [0, "provider-assistant-0-aaa"],
      [2, "provider-assistant-2-bbb"],
    ]);
    const message: Record<string, unknown> = { role: "assistant", content: [] };
    const stamped = stamp(message, otids);

    // The first segment names the row, because that is the id the stream opens
    // with; the rest are kept so a multi-segment reply can still be paired.
    assert.equal(stamped["otid"], "provider-assistant-0-aaa");
    // Keyed by the segment index they were minted under, so the read path can
    // look one up exactly when a stored reply projects into several frames.
    assert.deepEqual(
      (stamped["metadata"] as Record<string, unknown>)["segment_otids"],
      { "0": "provider-assistant-0-aaa", "2": "provider-assistant-2-bbb" },
    );
    // Stamping is in place: the object the caller persists is the one we edited.
    assert.equal(stamped, message);

    // An otid the turn already carries wins; a stamp must never rename a row.
    const named: Record<string, unknown> = { otid: "already-named" };
    assert.equal(stamp(named, otids)["otid"], "already-named");

    // Nothing to stamp, nothing changed.
    const empty: Record<string, unknown> = { role: "assistant" };
    assert.equal(stamp(empty, new Map())["otid"], undefined);
  } finally {
    if (previous === undefined) delete globals["__lcpStampAssistantOtids"];
    else globals["__lcpStampAssistantOtids"] = previous;
  }
});

test("patch-loader: lcp-otid projects the stored otid back onto history frames", () => {
  const globals = globalThis as Record<string, unknown>;
  const previous = globals["__lcpSegmentOtid"];
  delete globals["__lcpSegmentOtid"];
  try {
    const patched = patchLettaCodeSourceForTest(
      OTID_STAMP_ANCHOR + "\n" + OTID_PERSIST_ANCHOR + "\n" + OTID_PROJECT_ANCHOR + "\n",
    );
    assert.ok(patched.includes("globalThis.__lcpSegmentOtid(message, pendingTextStartIndex, isFirst)"));

    const start = patched.indexOf("globalThis.__lcpSegmentOtid =");
    assert.ok(start >= 0, "projection helper must be injected");
    const end = patched.indexOf("\n};\n", start);
    new Function(patched.slice(start, end + "\n};".length))();
    const segmentOtid = globals["__lcpSegmentOtid"] as
      (message: unknown, index: number, isFirst: boolean) => Record<string, unknown>;

    const stored = {
      otid: "provider-assistant-0-aaa",
      metadata: {
        segment_otids: { "0": "provider-assistant-0-aaa", "2": "provider-assistant-2-bbb" },
      },
    };
    // Each projected frame carries the otid of the segment it was flushed from.
    assert.deepEqual(segmentOtid(stored, 0, true), { otid: "provider-assistant-0-aaa" });
    assert.deepEqual(segmentOtid(stored, 2, false), { otid: "provider-assistant-2-bbb" });
    // A segment with no recorded otid names nothing rather than borrowing one.
    assert.deepEqual(segmentOtid(stored, 5, false), {});
    // Records written before this patch fall back to the row otid on the first
    // frame only, and never invent one for a later segment.
    const legacy = { otid: "provider-assistant-0-ccc" };
    assert.deepEqual(segmentOtid(legacy, 0, true), { otid: "provider-assistant-0-ccc" });
    assert.deepEqual(segmentOtid(legacy, 3, false), {});
    assert.deepEqual(segmentOtid({}, 0, true), {});
  } finally {
    if (previous === undefined) delete globals["__lcpSegmentOtid"];
    else globals["__lcpSegmentOtid"] = previous;
  }
});

// lcp-clr: conversation-scoped lastRunAt for the agent-info reminder. The
// listen-path site that builds `listenAgentMetadata` is the only place that
// reads `cachedAgent.last_run_completion`, so the anchor must match the
// deployed bundle exactly once, the rewrite must install the helper via
// `globalThis.__lcpConversationLastRunAt(...)` against the in-scope
// `getBackend()`, `agentId`, and `conversationId`, and the original literal
// line must be gone — otherwise the local backend keeps reporting the
// agent's `default` conversation timestamp in non-default conversations
// (the "55 days ago" on a brand-new conversation bug).
//
// Anchors and replacements are shape-only — the minifier renumbers bindings
// on most releases and this test must keep passing across them.
