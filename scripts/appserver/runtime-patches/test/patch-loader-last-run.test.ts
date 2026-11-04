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


const CLR_LISTEN_SITE_TOKEN =
  `      if (!runtime.reminderState.hasSentAgentInfo && cachedAgent) {\n` +
  `        listenAgentMetadata = {\n` +
  `          name: cachedAgent.name ?? null,\n` +
  `          description: cachedAgent.description ?? null,\n` +
  `          lastRunAt: cachedAgent.last_run_completion ?? null\n` +
  `        };\n` +
  `      }`;

test("patch-loader: lcp-clr rewrites the deployed listen-path lastRunAt to a conversation-scoped helper", (t) => {
  const bundlePath = resolveDeployedLettaBundle();
  if (!bundlePath) {
    t.skip(
      "no letta-code bundle with persistCompiledSystemPrompt (>= 0.27) available on this host — lcp-clr cannot be verified against the real bundle",
    );
    return;
  }
  const bundle = readFileSync(bundlePath, "utf8");

  // Unique-match assertion holds on the real bundle (drift alarm on upgrade).
  assert.equal(
    bundle.split(CLR_LISTEN_SITE_TOKEN).length - 1,
    1,
    "lcp-clr anchor must be unique on the deployed bundle",
  );

  const { source: patched, skippedPatches, appliedPatches } = patchLettaCodeSourceResultForTest(bundle);

  // The deployed-bundle test a few blocks above also asserts
  // `skippedPatches === 0` after a sequence of patches runs; that contract
  // must continue to hold with lcp-clr appended — the patch succeeds against
  // a real bundle.
  assert.equal(
    skippedPatches,
    0,
    "no patch (including lcp-clr) may fail-open against the deployed bundle",
  );
  assert.ok(
    (appliedPatches ?? 0) >= 1,
    "lcp-clr must increment appliedPatches on the deployed bundle",
  );

  // The original literal line that read `cachedAgent.last_run_completion`
  // must be gone — replacing the half-conversation-scoped, half-agent-scoped
  // reminder is the entire point of the patch.
  assert.equal(
    patched.split(
      "          lastRunAt: cachedAgent.last_run_completion ?? null",
    ).length - 1,
    0,
    "the agent-scoped lastRunAt literal must be removed",
  );

  // The rewrite calls the helper with the three in-scope identifiers plus
  // the agent-level fallback — they are the only correct seams.
  assert.ok(
    patched.includes(
      "          lastRunAt: await globalThis.__lcpConversationLastRunAt(getBackend(), agentId, conversationId, cachedAgent.last_run_completion ?? null)",
    ),
    "the helper call must thread backend / agentId / conversationId and fall back to the agent-scoped value",
  );

  // The helper is injected after the shebang so Node sees it as a top-level
  // statement; duplicate-injection is harmless because of the `|| function`
  // guard but the function body must be present at least once.
  assert.ok(
    patched.includes(
      'globalThis.__lcpConversationLastRunAt = globalThis.__lcpConversationLastRunAt || async function',
    ),
    "the __lcpConversationLastRunAt helper must be injected",
  );
  assert.ok(
    patched.includes("backend.retrieveConversation(conversationId, agentId)"),
    "the helper must resolve the conversation via the backend",
  );
  assert.ok(
    !patched.includes(`return null;\n      }\n    }\n  } catch {}\n  return agentFallback;`),
    "the helper must surface its no-conversation-match null-return without leaking the literal snippet",
  );
});

test("patch-loader: lcp-clr fail-open on a duplicate-anchor source", () => {
  // Synthesize a minimal source in which the listen-path site appears twice
  // — e.g. a future letta.js that clones the block for the warmup path.
  // lcp-clr must skip rather than patching the wrong site and silently
  // corrupting the warmup path. No other anchor from the upstream patch
  // list appears in this fragment, so applied/skipped counts are scoped
  // solely to lcp-clr.
  const doubleSource = "class A {\n" + CLR_LISTEN_SITE_TOKEN + "\n}\nclass B {\n" + CLR_LISTEN_SITE_TOKEN + "\n}\n";
  const { source, appliedPatches } = patchLettaCodeSourceResultForTest(doubleSource);
  assert.equal(source, doubleSource, "double-matched anchor must not be patched anywhere");
  assert.equal(appliedPatches, 0, "appliedPatches must be 0 when lcp-clr cannot prove uniqueness");

  // Sanity: the same anchor occurring exactly once IS patched.
  const single = "class A {\n" + CLR_LISTEN_SITE_TOKEN + "\n}\n";
  const patchedSingle = patchLettaCodeSourceForTest(single);
  assert.ok(
    patchedSingle.includes(
      "          lastRunAt: await globalThis.__lcpConversationLastRunAt(getBackend(), agentId, conversationId, cachedAgent.last_run_completion ?? null)",
    ),
    "single-occurrence anchor must be rewritten and routed through the helper",
  );
});

// lcp-clr-x: cross-conversation fallback for the agent-info reminder's
// lastRunAt, plus the renderer rewrite that dispatches on the structured
// `{ kind: "this" | "recent" | "none", last_message_at, conversation_id,
// summary }` shape.
//
// The helper is exposed via `import { CONVERSATION_LAST_RUN_HELPER_DEFINITION
// } from "../letta-code-patch-loader.mjs"` so we can eval it
// directly and verify the kind-dispatch semantics with a stubbed backend.
// The renderer is verified against the deployed bundle like the rest of
// the modern-anchor tests.
const CLR_RENDERER_TOKEN =
  `function buildAgentInfo(options) {\n` +
  `  try {\n` +
  `    const { agentInfo, conversationId } = options;\n` +
  `    let lastRunInfo = "No previous messages";\n` +
  `    if (agentInfo.lastRunAt) {\n` +
  `      try {\n` +
  `        const lastRunDate = new Date(agentInfo.lastRunAt);\n` +
  `        const localLastRun = lastRunDate.toLocaleString();\n` +
  `        const relativeTime = getRelativeTime(agentInfo.lastRunAt);\n` +
  `        lastRunInfo = \`\${localLastRun} (\${relativeTime})\`;\n` +
  `      } catch {\n` +
  `        lastRunInfo = "(failed to parse last run time)";\n` +
  `      }\n` +
  `    }\n` +
  `    const showMemoryDir = (() => {\n` +
  `      try {\n` +
  `        return isLocalBackendEnvEnabled() || settingsManager.isMemfsEnabled(agentInfo.id);\n` +
  `      } catch {\n` +
  `        return false;\n` +
  `      }\n` +
  `    })();`;

test("patch-loader: lcp-clr-x rewrites buildAgentInfo to dispatch on the structured lastRunAt shape", (t) => {
  const bundlePath = resolveDeployedLettaBundle();
  if (!bundlePath) {
    t.skip(
      "no letta-code bundle with persistCompiledSystemPrompt (>= 0.27) available on this host — lcp-clr-x cannot be verified against the real bundle",
    );
    return;
  }
  const bundle = readFileSync(bundlePath, "utf8");

  // Unique-match assertion: buildAgentInfo must appear exactly once on the
  // bundle. If it clones for a warmup path in a future release the patch
  // must skip and a human must disambiguate.
  assert.equal(
    bundle.split(CLR_RENDERER_TOKEN).length - 1,
    1,
    "lcp-clr-x renderer anchor must be unique on the deployed bundle",
  );

  const { source: patched, skippedPatches } = patchLettaCodeSourceResultForTest(bundle);
  assert.equal(skippedPatches, 0, "no patch may fail-open against the deployed bundle");

  // The renderer now normalizes the legacy bare-string shape and dispatches
  // through the new formatter.
  assert.ok(
    patched.includes(
      'const lastRunRaw = typeof agentInfo.lastRunAt === "string" && agentInfo.lastRunAt',
    ),
    "the renderer must normalize legacy string-shaped lastRunAt",
  );
  assert.ok(
    patched.includes("const lastRunInfo = globalThis.__lcpFormatLastRun(lastRunRaw);"),
    "the renderer must call the new formatter",
  );
  assert.ok(
    !patched.includes('let lastRunInfo = "No previous messages";'),
    "the original `let lastRunInfo = ...` initializer must be replaced",
  );

  // The formatter helper is injected alongside the lcp-clr helper.
  assert.ok(
    patched.includes("globalThis.__lcpFormatLastRun = globalThis.__lcpFormatLastRun || function"),
    "the __lcpFormatLastRun helper must be injected",
  );
  assert.ok(
    patched.includes('primary = "No previous messages in this conversation"'),
    "the cross-conv branch must emit the honest primary line",
  );
  assert.ok(
    patched.includes("- **Most recent activity**:"),
    "the cross-conv branch must emit the Most recent activity attribution line",
  );
});

// Behaviour tests for the helper. We can't easily mock `backend.listConversations`
// from inside the patched source, so we eval the helper definition against
// a stub backend. This is the only path that exercises the kind-dispatch
// logic with real returns — the bundle-level tests above can only assert
// string presence.
//
// `node --test` doesn't accept `describe` blocks (that's a mocha pattern),
// so the helper tests are top-level. Each test re-evals the helper
// definition against a fresh stub backend so they're isolated.
// `node:test` runs them in declaration order with `--test-concurrency=1`.
//
// @param {Record<string, unknown>} stubBackend
// @returns {(args: { agentId: string; conversationId: string }) => Promise<unknown>}
// The helper assigns to globalThis on first call; we clear the cache key
// beforehand so subsequent evals re-evaluate the function body.
function loadLcpConversationLastRunAt(stubBackend) {
  // @ts-ignore
  delete (globalThis as { __lcpConversationLastRunAt?: unknown }).__lcpConversationLastRunAt;
  const helperSource = CONVERSATION_LAST_RUN_HELPER_DEFINITION;
  // eslint-disable-next-line @typescript-eslint/no-implied-eval
  new Function(helperSource)();
  // @ts-ignore
  const helper = globalThis.__lcpConversationLastRunAt;
  if (typeof helper !== "function") {
    throw new Error("helper did not register on globalThis");
  }
  return (args: { agentId: string; conversationId: string }) =>
    // @ts-ignore
    helper(stubBackend, args.agentId, args.conversationId);
}

test("__lcpConversationLastRunAt: kind: this when the in-flight conversation has a last_message_at", async () => {
  const stamp = "2026-09-18T01:00:00.000Z";
  const backend = {
    retrieveConversation: async () => ({ id: "conv-X", last_message_at: stamp, summary: "active" }),
    listConversations: async () => [],
  };
  const result = await loadLcpConversationLastRunAt(backend)({
    agentId: "agent-1",
    conversationId: "conv-X",
  });
  assert.deepEqual(result, {
    kind: "this",
    last_message_at: stamp,
    conversation_id: "conv-X",
    summary: "active",
  });
});

test("__lcpConversationLastRunAt: kind: recent when this conv is empty and a sibling has a stamp", async () => {
  const backend = {
    retrieveConversation: async () => ({ id: "conv-new", last_message_at: null }),
    listConversations: async () => [
      { id: "conv-old", last_message_at: "2026-09-18T00:30:00.000Z", summary: "earlier" },
    ],
  };
  const result = await loadLcpConversationLastRunAt(backend)({
    agentId: "agent-1",
    conversationId: "conv-new",
  });
  assert.deepEqual(result, {
    kind: "recent",
    last_message_at: "2026-09-18T00:30:00.000Z",
    conversation_id: "conv-old",
    summary: "earlier",
  });
});

function assertNoLastRun(result: unknown) {
  assert.deepEqual(result, {
    kind: "none",
    last_message_at: null,
    conversation_id: null,
    summary: null,
  });
}

test("__lcpConversationLastRunAt: kind: none when listConversations throws", async () => {
  const backend = {
    retrieveConversation: async () => ({ id: "conv-new", last_message_at: null }),
    listConversations: async () => {
      throw new Error("disk gone");
    },
  };
  const result = await loadLcpConversationLastRunAt(backend)({
    agentId: "agent-1",
    conversationId: "conv-new",
  });
  assertNoLastRun(result);
});

test("__lcpConversationLastRunAt: kind: none when this conv is the magic 'default' id", async () => {
  const backend = {
    retrieveConversation: async () => {
      throw new Error("retrieveConversation should NOT be called for `default`");
    },
    listConversations: async () => {
      throw new Error("listConversations should NOT be called for `default`");
    },
  };
  const result = await loadLcpConversationLastRunAt(backend)({
    agentId: "agent-1",
    conversationId: "default",
  });
  assertNoLastRun(result);
});

test("__lcpConversationLastRunAt: recent filters out the in-flight conversation from the sibling set", async () => {
  const backend = {
    retrieveConversation: async () => ({ id: "conv-self", last_message_at: null }),
    listConversations: async () => [
      { id: "conv-self", last_message_at: "2026-09-18T01:00:00.000Z" },
      { id: "conv-other", last_message_at: "2026-09-18T00:30:00.000Z" },
    ],
  };
  const result = await loadLcpConversationLastRunAt(backend)({
    agentId: "agent-1",
    conversationId: "conv-self",
  });
  // Must surface the OTHER conv, not the one we're standing in.
  assert.equal(result.kind, "recent");
  assert.equal(result.conversation_id, "conv-other");
});

// Fail-open assertion for the renderer: when the buildAgentInfo anchor
// appears twice the patch must skip and not patch either site.
test("patch-loader: lcp-clr-x fail-open on a duplicate-renderer source", () => {
  const doubleSource = "class A {\n" + CLR_RENDERER_TOKEN + "\n}\nclass B {\n" + CLR_RENDERER_TOKEN + "\n}\n";
  const { source, skippedPatches } = patchLettaCodeSourceResultForTest(doubleSource);
  // Source must be untouched (every other patch anchor is absent so the only
  // thing the loader could have done is patch a renderer site — which it
  // must not do with a duplicate anchor). However, OTHER patches may have
  // applied (the THINKING/CHOKEPOINT tokens are also absent here), so the
  // assertion is specifically that the renderer anchor was not patched.
  assert.equal(
    source.split(CLR_RENDERER_TOKEN).length - 1,
    2,
    "the renderer anchor must remain in both sites when duplicated",
  );
  assert.ok(skippedPatches >= 1, "duplicate renderer anchor must bump skipped count");

  // Single-occurrence must be patched.
  const single = "class A {\n" + CLR_RENDERER_TOKEN + "\n}\n";
  const patchedSingle = patchLettaCodeSourceForTest(single);
  assert.ok(
    patchedSingle.includes("const lastRunInfo = globalThis.__lcpFormatLastRun(lastRunRaw);"),
    "single-occurrence renderer anchor must be rewritten to call the formatter",
  );
});

// lcp-reflhide / lcp-subagent-errlog (letta-mobile-vmwl1)
