import assert from "node:assert/strict";
import { appendFileSync, existsSync, readFileSync } from "node:fs";
import { dirname } from "node:path";
import type { RetryFixtureRow, RetryFixtureRuntime } from "./retry-fixture-types.ts";

type FixturePath = { path: string };

export function retryFixtureStore(runtime: RetryFixtureRuntime, { path }: FixturePath) {
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

export function readRetryRows(path: string): RetryFixtureRow[] {
  if (!existsSync(path)) return [];
  return readFileSync(path, "utf8").trim().split("\n").map((line) => {
    const entry: unknown = JSON.parse(line);
    assert.ok(entry && typeof entry === "object" && "message" in entry);
    const message = entry.message;
    assert.ok(message && typeof message === "object" && "role" in message && message.role === "user" && "metadata" in message);
    return message as RetryFixtureRow;
  });
}
