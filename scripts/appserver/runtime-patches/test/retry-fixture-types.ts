type ConversationId = string;
type AgentId = string;
type ConversationKey = string;

export type RetryFixtureMessage = {
  type: "message" | "approval";
  role?: "user" | "assistant";
  content?: string;
  otid?: string;
  client_message_id?: string;
  approvals?: unknown[];
};
export type RetryFixtureState = {
  messages: RetryFixtureMessage[];
  imageFailureModesByMessageOtid?: Record<string, string>;
};
export type RetryFixtureRow = {
  id: string;
  role: "user";
  otid?: string;
  content: unknown;
  metadata: { conversation_id: string; agent_id: string };
};
export type RetryFixtureStore = {
  appendTurnInput: (conversationId: ConversationId, body: { agent_id: string; messages: RetryFixtureMessage[] }) => unknown;
  appendUserLocalMessage: (conversationId: ConversationId, agentId: AgentId, message: RetryFixtureMessage) => RetryFixtureRow;
  localMessagesForConversation: (conversationId: ConversationId, agentId: AgentId) => RetryFixtureRow[];
  residentLocalMessagesForConversation: (conversationId: ConversationId, agentId: AgentId) => RetryFixtureRow[];
  pushLocalMessage: (conversationId: ConversationId, agentId: AgentId, message: RetryFixtureRow) => void;
  validateUnreadTranscriptHeadOnce: (key: ConversationKey) => void;
};
export type RetryFixtureRuntime = {
  ensure: (messages: RetryFixtureMessage[]) => RetryFixtureMessage[];
  refresh: (state: RetryFixtureState) => RetryFixtureState;
  update: (state: RetryFixtureState, messages: RetryFixtureMessage[]) => RetryFixtureState;
  providerRetry: (params: { input: RetryFixtureState; turnLease: { signal: { aborted: boolean } } }) => Promise<RetryFixtureState>;
  storeMethods: RetryFixtureStore;
};
