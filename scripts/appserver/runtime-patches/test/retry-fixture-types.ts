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
  appendTurnInput: (conversationId: string, body: { agent_id: string; messages: RetryFixtureMessage[] }) => unknown;
  appendUserLocalMessage: (conversationId: string, agentId: string, message: RetryFixtureMessage) => RetryFixtureRow;
  localMessagesForConversation: (conversationId: string, agentId: string) => RetryFixtureRow[];
  residentLocalMessagesForConversation: (conversationId: string, agentId: string) => RetryFixtureRow[];
  pushLocalMessage: (conversationId: string, agentId: string, message: RetryFixtureRow) => void;
  validateUnreadTranscriptHeadOnce: (key: string) => void;
};
export type RetryFixtureRuntime = {
  ensure: (messages: RetryFixtureMessage[]) => RetryFixtureMessage[];
  refresh: (state: RetryFixtureState) => RetryFixtureState;
  update: (state: RetryFixtureState, messages: RetryFixtureMessage[]) => RetryFixtureState;
  providerRetry: (params: { input: RetryFixtureState; turnLease: { signal: { aborted: boolean } } }) => Promise<RetryFixtureState>;
  storeMethods: RetryFixtureStore;
};
