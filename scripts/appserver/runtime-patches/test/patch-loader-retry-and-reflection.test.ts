import { retrySourceBetween, retryAnchors } from "./retry-source-fixtures.ts";
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


const REFLHIDE_TITLE = "summary: buildReflectionIntegrationConversationTitle(";
const ERRLOG_WARN = "function debugWarn(prefix, message, ...args) {\n";
const ERRLOG_EXIT = "function describeSubagentExit(exitCode, exitSignal, stderr) {\n";

test("patch-loader: lcp-reflhide hides both reflection integration title sites on the deployed bundle", (t) => {
  const bundlePath = resolveDeployedLettaBundle();
  if (!bundlePath) {
    t.skip("no letta-code bundle available on this host");
    return;
  }
  const bundle = readFileSync(bundlePath, "utf8");
  if (bundle.split(REFLHIDE_TITLE).length - 1 !== 2) {
    t.skip("this bundle predates reflection integration titling");
    return;
  }
  const { source: patched, skippedPatches } = patchLettaCodeSourceResultForTest(bundle);
  assert.equal(skippedPatches, 0, "no patch may fail-open against the deployed bundle");
  assert.equal(patched.split(`hidden: true, ${REFLHIDE_TITLE}`).length - 1, 2, "both title sites must mark the conversation hidden");
});

test("patch-loader: lcp-reflhide skips, untouched, when the title site count drifts", () => {
  const drifted = `const a = { ${REFLHIDE_TITLE}x) };`;
  const { source } = patchLettaCodeSourceResultForTest(drifted);
  assert.ok(!source.includes("hidden: true"), "one title site is not the shape the patch knows; leave it stock");
});

test("patch-loader: lcp-subagent-errlog writes subagent warnings and failed exits to stderr", (t) => {
  const bundlePath = resolveDeployedLettaBundle();
  if (!bundlePath) {
    t.skip("no letta-code bundle available on this host");
    return;
  }
  const bundle = readFileSync(bundlePath, "utf8");
  if (!bundle.includes(ERRLOG_EXIT)) {
    t.skip("this bundle has no describeSubagentExit");
    return;
  }
  const { source: patched, skippedPatches } = patchLettaCodeSourceResultForTest(bundle);
  assert.equal(skippedPatches, 0, "no patch may fail-open against the deployed bundle");
  assert.ok(patched.includes(`${ERRLOG_WARN}  if (prefix === "subagent") process.stderr.write(`), "subagent warnings reach stderr");
  assert.ok(patched.includes(`${ERRLOG_EXIT}  process.stderr.write(\`[lcp-subagent] exit code=`), "failed exits reach stderr");
});

test("patch-loader: lcp-subagent-errlog applies both sites or neither", () => {
  const onlyWarn = `${ERRLOG_WARN}  writeDebugLine(prefix, message);\n}\n`;
  const { source } = patchLettaCodeSourceResultForTest(onlyWarn);
  assert.ok(!source.includes("[lcp-subagent]"), "a bundle missing the exit site gets neither half");
});

// lcp-retry-otid: use the shipped functions, without importing/executing the
// CLI or contacting a provider. Only the store's environment is a fixture.
test("patch-loader: lcp-retry-otid applies both anchors on the real deployed bundle", (t) => {
  const bundlePath = resolveDeployedLettaBundle();
  if (!bundlePath) return t.skip("no deployed letta-code bundle available");
  const bundle = readFileSync(bundlePath, "utf8");
  const { refresh, persist } = retryAnchors(bundle);
  assert.equal(bundle.split(refresh).length - 1, 1);
  assert.equal(bundle.split(persist).length - 1, 1);
  const patched = patchLettaCodeSourceResultForTest(bundle);
  assert.equal(patched.skippedPatches, 0);
  assert.ok(patched.source.includes("retryFromPersistedLocalState && message2.role === \"user\" && message2.otid"));
  assert.ok(patched.source.includes("stored.role === \"user\" && stored.otid === otid"));

  // Losing one anchor must lose exactly ONE counted patch, with neither
  // half installed. Other patches still apply to this complete bundle.
  const drifted = patchLettaCodeSourceResultForTest(bundle.replace(refresh, ""));
  assert.equal(patched.appliedPatches, drifted.appliedPatches + 1);
  assert.equal(drifted.skippedPatches, patched.skippedPatches + 1);
  assert.ok(!drifted.source.includes("stored.role === \"user\" && stored.otid === otid"));
});

test("patch-loader: lcp-retry-otid skips atomically on missing or duplicate anchors", (t) => {
  const bundlePath = resolveDeployedLettaBundle();
  if (!bundlePath) return t.skip("no deployed letta-code bundle available");
  const { refresh, persist } = retryAnchors(readFileSync(bundlePath, "utf8"));
  const single = `${refresh}\nclass Store {\n${persist}}`;
  const patched = patchLettaCodeSourceResultForTest(single);
  assert.equal(patched.appliedPatches, 1);
  for (const input of [refresh, persist, single + refresh, single + persist]) {
    const result = patchLettaCodeSourceResultForTest(input);
    assert.equal(result.source, input, "neither half may land on anchor drift");
    assert.equal(result.appliedPatches, 0);
    assert.equal(result.skippedPatches, patched.skippedPatches + 1);
  }
});

test("patch-loader: lcp-retry-otid reports missing anchors loudly at load time", () => {
  const target = fileURLToPath(new URL("./retry-otid-fixture.mjs", import.meta.url));
  const loader = new URL("../letta-code-patch-loader.mjs", import.meta.url).href;
  const result = spawnSync(process.execPath, ["--input-type=module", "--eval", `
    const { load } = await import(${JSON.stringify(loader)});
    await load(${JSON.stringify(pathToFileURL(target).href)}, {}, async () => ({
      format: "module", source: "export const drifted = true;"
    }));
  `], { encoding: "utf8", env: { ...process.env, LETTA_CLI_PATH_REAL: target }, timeout: 10_000 });
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stderr, /WARN: lcp-retry-otid anchors matched refresh=0 persist=0/);
  assert.match(result.stderr, /local retries may duplicate user prompts/);
  assert.match(result.stderr, /lcp-patches applied=0 skipped=[1-9]\d*/);
});

type RetryFixtureMessage = {
  type: "message" | "approval";
  role?: "user" | "assistant";
  content?: string;
  otid?: string;
  client_message_id?: string;
  approvals?: unknown[];
};
type RetryFixtureState = {
  messages: RetryFixtureMessage[];
  imageFailureModesByMessageOtid?: Record<string, string>;
};
type RetryFixtureRow = {
  id: string;
  role: "user";
  otid?: string;
  content: unknown;
  metadata: { conversation_id: string; agent_id: string };
};
type RetryFixtureStore = {
  appendTurnInput: (conversationId: string, body: { agent_id: string; messages: RetryFixtureMessage[] }) => unknown;
  appendUserLocalMessage: (conversationId: string, agentId: string, message: RetryFixtureMessage) => RetryFixtureRow;
  localMessagesForConversation: (conversationId: string, agentId: string) => RetryFixtureRow[];
  residentLocalMessagesForConversation: (conversationId: string, agentId: string) => RetryFixtureRow[];
  pushLocalMessage: (conversationId: string, agentId: string, message: RetryFixtureRow) => void;
  validateUnreadTranscriptHeadOnce: (key: string) => void;
};
type RetryFixtureRuntime = {
  ensure: (messages: RetryFixtureMessage[]) => RetryFixtureMessage[];
  refresh: (state: RetryFixtureState) => RetryFixtureState;
  update: (state: RetryFixtureState, messages: RetryFixtureMessage[]) => RetryFixtureState;
  providerRetry: (params: { input: RetryFixtureState; turnLease: { signal: { aborted: boolean } } }) => Promise<RetryFixtureState>;
  storeMethods: RetryFixtureStore;
};

function retryFixtureRuntime(
  source: string,
  local: boolean,
  validateHead: (path: string, headBytes: number) => void = () => {},
): RetryFixtureRuntime {
  const factory = new Function("getBackend", "randomUUID9", "crypto", "validateUnreadTranscriptHead", `
    ${retrySourceBetween(source, { start: "function optionalString3(", end: "function currentIsoTimestamp2(" })}
    ${retrySourceBetween(source, { start: "function textContent(", end: "function localImageContentFromLegacyImage(" })}
    ${retrySourceBetween(source, { start: "function timestampFromIso(", end: "\nfunction " })}
    ${retrySourceBetween(source, { start: "function refreshInputOtidsForNewRequest(", end: "function rebuildInputWithFreshDenials(" })}
    ${retrySourceBetween(source, { start: "function ensureTurnInputMessageOtids(", end: "var init_turn_input_state =" })}
    ${retrySourceBetween(source, { start: "async function prepareProviderRetryInput(", end: "async function shouldRetryPostStopTurn(" })}
    ${retrySourceBetween(source, { start: "function residentCoversIds(", end: "\nfunction " })}
    ${retrySourceBetween(source, { start: "function overlayResidentLocalMessageTail(", end: "\nfunction " })}
    const getRetryDelayMs2 = () => 0;
    const emitRecoverableRetryNotice = () => {};
    const getRetryStatusMessage = () => "retry";
    const LLM_API_ERROR_MAX_RETRIES = 3;
    return {
      ensure: ensureTurnInputMessageOtids,
      refresh: refreshTurnInputOtidsForNewRequest,
      update: updateTurnInputMessagesPreservingOtids,
      providerRetry: prepareProviderRetryInput,
      storeMethods: {
        ${retrySourceBetween(source, { start: "  appendTurnInput(", end: "  appendStreamChunk(" })},
        ${retryAnchors(source).persist},
        ${retrySourceBetween(source, { start: "  localMessagesForConversation(", end: "  residentLocalMessagesForConversation(" })},
        ${retrySourceBetween(source, { start: "  residentLocalMessagesForConversation(", end: "  readConversationMessagesFromDisk(" })},
        ${retrySourceBetween(source, { start: "  pushLocalMessage(", end: "  validateUnreadTranscriptHeadOnce(" })},
        ${retrySourceBetween(source, { start: "  validateUnreadTranscriptHeadOnce(", end: "  persistPendingAssistantMessage(" })}
      }
    };
  `);
  let seq = 0;
  const uuid = () => `retry-uuid-${++seq}`;
  const result: unknown = factory(
    () => ({ capabilities: { localModelCatalog: local, remoteMemfs: !local } }), uuid, { randomUUID: uuid }, validateHead,
  );
  assert.ok(result && typeof result === "object" && "refresh" in result && typeof result.refresh === "function");
  return result as RetryFixtureRuntime;
}

type FixturePath = { path: string };

function retryFixtureStore(runtime: RetryFixtureRuntime, { path }: FixturePath) {
  let seq = 0;
  const store = {
    ...runtime.storeMethods,
    // Keep the same resident tail across attempts. The shipped resident,
    // push and unread-head methods above run against these maps.
    localMessagesByConversationKey: new Map<string, RetryFixtureRow[]>(),
    loadedConversationKeys: new Set<string>(),
    tailResidentConversationKeys: new Set<string>(),
    conversations: new Map<string, { id: string; in_context_message_ids: string[] }>(),
    unreadTranscriptHeadBytesByKey: new Map<string, number>(),
    loadRepairedConversationKeys: new Set<string>(),
    transcriptMetadataByConversationKey: new Map<string, { messagesPath: string; conversationDir: string }>(),
    stats: { fullTranscriptReads: 0 },
    storageDir: dirname(path),
    conversationKey: (conversationId: string, agentId: string) => `${agentId}:${conversationId}`,
    readConversationMessagesFromDisk(key: string) {
      this.stats.fullTranscriptReads += 1;
      // Reproduce the full-read side effects that used to bypass validation
      // and disturb repair state on every new OTID.
      this.unreadTranscriptHeadBytesByKey.delete(key);
      this.loadRepairedConversationKeys.delete(key);
      const rows = readRetryRows(path).filter((row) =>
        `${row.metadata.agent_id}:${row.metadata.conversation_id}` === key);
      return { messages: rows, transcript: { messageById: new Map(rows.map((row) => [row.id, row])) } };
    },
    findConversation: () => ({ agent_free: true }),
    ensureConversation(id: string, agentId: string) {
      const key = this.conversationKey(id, agentId);
      let conversation = this.conversations.get(key);
      if (!conversation) {
        conversation = { id, in_context_message_ids: [] };
        this.conversations.set(key, conversation);
        this.localMessagesByConversationKey.set(key, []);
        this.tailResidentConversationKeys.add(key);
      }
      return conversation;
    },
    currentLocalMessageDate: () => "2026-10-28T00:00:00.000Z",
    nextLocalMessageId: () => `ui-msg-${++seq}`,
    localContentFromInputContent: (content: unknown) => content,
    trimResidentConversationTail: () => {},
    touchConversationForLocalMessage: () => {},
    loadConversationMessageTail() {
      throw new Error("fixture conversation should already have a resident tail");
    },
    persistConversationState(_conversationId: string, _agentId: string, options: { transcript: string; message: RetryFixtureRow }) {
      assert.equal(options.transcript, "append");
      appendFileSync(path, JSON.stringify({ type: "message", message: options.message }) + "\n");
    },
    applyApprovalResults: () => {},
  };
  return store;
}

function readRetryRows(path: string): RetryFixtureRow[] {
  if (!existsSync(path)) return [];
  return readFileSync(path, "utf8").trim().split("\n").map((line) => {
    const entry: unknown = JSON.parse(line);
    assert.ok(entry && typeof entry === "object" && "message" in entry);
    const message = entry.message;
    assert.ok(message && typeof message === "object" && "role" in message && message.role === "user" && "metadata" in message);
    return message as RetryFixtureRow;
  });
}

test("patch-loader: lcp-retry-otid first send with a new otid avoids full reads and validates the unread head", (t) => {
  const bundlePath = resolveDeployedLettaBundle();
  if (!bundlePath) return t.skip("no deployed letta-code bundle available");
  const dir = mkdtempSync(fileURLToPath(new URL("./.retry-otid-", import.meta.url)));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const path = join(dir, "messages.jsonl");
  const key = "agent-1:conv-1";
  let validations = 0;
  const runtime = retryFixtureRuntime(patchLettaCodeSourceForTest(readFileSync(bundlePath, "utf8")), true, (validatedPath, bytes) => {
    validations += 1;
    assert.equal(validatedPath, path);
    assert.equal(bytes, 512);
    assert.equal(store.unreadTranscriptHeadBytesByKey.get(key), 512, "validation must see the unread-head marker");
    assert.equal(store.localMessagesByConversationKey.get(key)?.length, 0, "validate before adding the row");
    assert.equal(existsSync(path), false, "validate before persisting the row");
  });
  const store = retryFixtureStore(runtime, { path });
  const conversation = store.ensureConversation("conv-1", "agent-1");
  // The resident tail does not cover these active IDs. Calling the broader
  // localMessagesForConversation would read the full transcript here too.
  conversation.in_context_message_ids = ["head-only-id"];
  store.unreadTranscriptHeadBytesByKey.set(key, 512);
  store.transcriptMetadataByConversationKey.set(key, { messagesPath: path, conversationDir: dir });
  store.loadRepairedConversationKeys.add(key);
  const send = (otid: string) => store.appendTurnInput("conv-1", {
    agent_id: "agent-1", messages: [{ type: "message", role: "user", content: "hello", otid }],
  });
  send("first-ever-otid");
  assert.equal(store.stats.fullTranscriptReads, 0, "a new OTID must not trigger a full-transcript read");
  assert.equal(validations, 1, "append must validate the unread head");
  assert.equal(store.unreadTranscriptHeadBytesByKey.has(key), false, "validation consumes the marker");
  assert.equal(store.loadRepairedConversationKeys.has(key), true, "dedupe must preserve repair state");
  send("another-new-otid");
  assert.equal(store.stats.fullTranscriptReads, 0);
  assert.equal(validations, 1, "the head is validated only once");
  assert.deepEqual(readRetryRows(path).map((row) => row.otid), ["first-ever-otid", "another-new-otid"]);
});

test("patch-loader: lcp-retry-otid persists one original user row across provider, empty and plan-swap retries", async (t) => {
  const bundlePath = resolveDeployedLettaBundle();
  if (!bundlePath) return t.skip("no deployed letta-code bundle available");
  const runtime = retryFixtureRuntime(patchLettaCodeSourceForTest(readFileSync(bundlePath, "utf8")), true);
  const dir = mkdtempSync(fileURLToPath(new URL("./.retry-otid-", import.meta.url)));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const path = join(dir, "messages.jsonl");
  const store = retryFixtureStore(runtime, { path });
  let state: RetryFixtureState = { messages: runtime.ensure([
    { type: "message", role: "user", content: "hello", client_message_id: "cm-android-original" },
  ]) };
  const send = () => store.appendTurnInput("conv-1", { agent_id: "agent-1", messages: state.messages });
  send();
  // A pre-stream exception may occur AFTER appendTurnInput: retrying the
  // identical request must also be idempotent, without refreshing anything.
  send();
  state = await runtime.providerRetry({ input: state, turnLease: { signal: { aborted: false } } });
  send();
  state = runtime.refresh(state); // empty-response retry call site
  send();
  state = runtime.refresh(state); // ChatGPT plan-swap call site
  send();
  const rows = readRetryRows(path);
  assert.equal(rows.length, 1, "all retry attempts must share ONE persisted user row");
  assert.equal(rows[0]?.otid, "cm-android-original");
  assert.deepEqual(rows[0]?.content, [{ type: "text", text: "hello" }]);
  assert.equal(state.messages[0]?.otid, "cm-android-original");
  assert.equal(store.stats.fullTranscriptReads, 0, "retry dedupe only searches resident rows");
});

test("patch-loader: lcp-retry-otid preserves image policy and persists new retry inputs separately", (t) => {
  const bundlePath = resolveDeployedLettaBundle();
  if (!bundlePath) return t.skip("no deployed letta-code bundle available");
  const runtime = retryFixtureRuntime(patchLettaCodeSourceForTest(readFileSync(bundlePath, "utf8")), true);
  const dir = mkdtempSync(fileURLToPath(new URL("./.retry-otid-", import.meta.url)));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const path = join(dir, "messages.jsonl");
  const store = retryFixtureStore(runtime, { path });
  const original: RetryFixtureState = {
    messages: [{ type: "message", role: "user", content: "same text", otid: "explicit-original", client_message_id: "client-id" }],
    imageFailureModesByMessageOtid: { "explicit-original": "error" },
  };
  const send = (state: RetryFixtureState, conversationId = "conv-1", agentId = "agent-1") =>
    store.appendTurnInput(conversationId, { agent_id: agentId, messages: state.messages });
  send(original);
  let retry = runtime.refresh(original);
  assert.deepEqual(retry.imageFailureModesByMessageOtid, original.imageFailureModesByMessageOtid);
  retry = runtime.update(retry, [...retry.messages, { type: "message", role: "user", content: "empty response reminder" }]);
  retry = runtime.refresh(retry);
  send(retry);
  send(runtime.refresh(retry));
  assert.deepEqual(readRetryRows(path).map((row) => row.otid), ["explicit-original", retry.messages[1]?.otid]);
  assert.equal(retry.messages[0]?.otid, "explicit-original", "explicit OTID takes precedence over client_message_id");
  // Equal text with a new identity, or the same OTID in another scope,
  // represents another user input. Unidentified legacy inputs also append.
  send({ messages: [{ ...original.messages[0], type: "message", otid: "new-send" }] });
  send(original, "conv-2");
  send(original, "default", "agent-2");
  send(original, "default", "agent-3");
  send({ messages: [{ type: "message", role: "user", content: "legacy" }] });
  send({ messages: [{ type: "message", role: "user", content: "legacy" }] });
  assert.equal(readRetryRows(path).length, 8);
});

test("patch-loader: lcp-retry-otid leaves cloud refresh and approval identities fresh", (t) => {
  const bundlePath = resolveDeployedLettaBundle();
  if (!bundlePath) return t.skip("no deployed letta-code bundle available");
  const source = patchLettaCodeSourceForTest(readFileSync(bundlePath, "utf8"));
  const state: RetryFixtureState = {
    messages: [
      { type: "message", role: "user", content: "image", otid: "user-otid" },
      { type: "approval", approvals: [], otid: "approval-otid" },
    ],
    imageFailureModesByMessageOtid: { "user-otid": "error" },
  };
  const cloud = retryFixtureRuntime(source, false).refresh(state);
  assert.notEqual(cloud.messages[0]?.otid, "user-otid");
  assert.notEqual(cloud.messages[1]?.otid, "approval-otid");
  assert.deepEqual(cloud.imageFailureModesByMessageOtid, { [cloud.messages[0]?.otid ?? "missing"]: "error" });
  const local = retryFixtureRuntime(source, true).refresh(state);
  assert.equal(local.messages[0]?.otid, "user-otid");
  assert.notEqual(local.messages[1]?.otid, "approval-otid");
  assert.deepEqual(state.messages.map((message) => message.otid), ["user-otid", "approval-otid"], "refresh must not mutate its input");
});

// lcp-agent-lazyload (letta-mobile-gxooq): agents created after App Server start (subagents,
// written by their own process) must be readable. The live proof runs a real `letta server`
// against an isolated backend dir (see the PR); these assert the patch lands on the deployed
// bundle and leaves the "no file" case refusing.
test("patch-loader: deployed letta.js lazy-loads agents created after start (lcp-agent-lazyload)", (t) => {
  const bundlePath = resolveDeployedLettaBundle();
  if (!bundlePath) {
    t.skip("no deployed letta-code bundle (>= 0.27) on this host");
    return;
  }
  const bundle = readFileSync(bundlePath, "utf8");
  const refreshAnchor =
    "  refreshAgentRecordFromStorage(agentId) {\n" +
    "    const existing = this.agents.get(agentId);\n" +
    "    if (!this.storageDir || !existing)\n" +
    "      return existing;";
  assert.equal(bundle.split(refreshAnchor).length - 1, 1, "refresh anchor must be unique");
  const { source: patched, skippedPatches } = patchLettaCodeSourceResultForTest(bundle);
  assert.equal(skippedPatches, 0, "no patch may fail-open against the deployed bundle");
  // Unknown agents are read from agents/<encoded id>.json with the same normalization as loadFromStorage.
  assert.match(patched, /if \(this\.storageDir && !existing && typeof agentId === "string" && agentId\) \{/);
  assert.match(patched, /normalizeAgentRecord\(raw, this\.defaultAgentModel\);\n\s+if \(!agent \|\| agent\.id !== agentId\)\n\s+return existing;\n\s+this\.agents\.set\(agentId, agent\);/);
  // Both read paths consult the lazy loader before refusing; neither still tests the map directly.
  assert.match(patched, /!conversation\?\.agent_free && !this\.refreshAgentRecordFromStorage\(agentId\)\)/);
  assert.match(patched, /listAgentMessages\(agentId, body\) \{\n\s+if \(this\.strictAgentAccess && !this\.refreshAgentRecordFromStorage\(agentId\)\)/);
  assert.doesNotMatch(patched, /!conversation\?\.agent_free && !this\.agents\.has\(agentId\)\)/);
  // A missing file still means "not found": nothing is created on lookup.
  assert.match(patched, /const mtimeMs = this\.agentRecordFileMtimeMs\(agentId\);\n\s+if \(mtimeMs === undefined\)\n\s+return existing;/);
});

test("patch-loader: lcp-agent-lazyload skips atomically when any anchor drifts", () => {
  const anchors =
    "  refreshAgentRecordFromStorage(agentId) {\n" +
    "    const existing = this.agents.get(agentId);\n" +
    "    if (!this.storageDir || !existing)\n" +
    "      return existing;\n" +
    "    if (this.strictAgentAccess && !conversation?.agent_free && !this.agents.has(agentId)) {\n" +
    "      throw new LocalBackendNotFoundError(\"Agent\", agentId);\n" +
    "    }\n";
  // listAgentMessages anchor deliberately missing: nothing of the patch may land.
  const { source } = patchLettaCodeSourceResultForTest(anchors);
  assert.equal(source.includes("refreshAgentRecordFromStorage(agentId)) {"), false);
  assert.equal(source.includes("typeof agentId === \"string\""), false);
});
