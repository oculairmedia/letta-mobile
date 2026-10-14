// letta-mobile-st78v: expose letta.js's in-process agent/conversation switch capability to the
// embedded headless stream-json control path. Android keeps the single node::Start process alive
// and sends this over stdin instead of stop()+start() re-entering node::Start.
// verifyEmbeddedLettaCodeSwitchSessionControl asserts the marker below landed.
import { readFileSync, writeFileSync } from 'fs';
const file = process.argv[2];
let src = readFileSync(file, 'utf8');
const marker = 'letta-mobile-st78v-switch-session-control';
if (src.includes(marker)) {
  console.warn('[embedded-lettacode] switch_session control already patched');
  process.exit(0);
}
for (const required of [
  'function setCurrentAgentId(agentId)',
  'function setConversationId(conversationId)',
  'if (message.type === "control_request")',
  'subtype === "interrupt"'
]) {
  if (!src.includes(required)) {
    throw new Error('patch-switch-session-control: required token not found — letta.js shape changed: ' + required);
  }
}
const token = '      } else if (subtype === "register_external_tools") {';
if (!src.includes(token)) {
  throw new Error('patch-switch-session-control: register_external_tools branch token not found — letta.js shape changed');
}
const replacement = `      } else if (subtype === "switch_session") {
  // letta-mobile-st78v-switch-session-control
  const switchAgentId = message.request?.agent_id ?? message.agent_id;
  const switchConversationId = message.request?.conversation_id ?? message.conversation_id;
  if (typeof switchAgentId !== "string" || switchAgentId.length === 0 || typeof switchConversationId !== "string" || switchConversationId.length === 0) {
    const switchErrorResponse = {
      type: "control_response",
      response: {
        subtype: "error",
        request_id: requestId ?? "",
        error: "switch_session requires agent_id and conversation_id"
      },
      session_id: sessionId,
      uuid: randomUUID19()
    };
    writeWireMessage(switchErrorResponse);
    continue;
  }
  if (currentAbortController !== null) {
    currentAbortController.abort();
    currentAbortController = null;
  }
  agent2 = await backend4.retrieveAgent(switchAgentId, {
    include: ["agent.secrets", "agent.tools", "agent.tags"]
  });
  conversationId = switchConversationId;
  telemetry.setCurrentAgentId(agent2.id);
  setCurrentAgentId(agent2.id);
  setConversationId(conversationId);
  setAgentContext(agent2.id, skillsDirectory, resolvedSkillSources);
  availableTools = agent2.tools?.map(t2 => t2.name).filter(n => !!n) || [];
  try {
    const switchedToolContext = await prepareHeadlessToolExecutionContext({
      agentId: agent2.id,
      conversationId,
      cachedAgent: agent2
    });
    availableTools = switchedToolContext.availableTools;
    cachedAgent = switchedToolContext.cachedAgent;
    preparedEffectiveModel = switchedToolContext.preparedEffectiveModel;
  } catch (switchContextError) {
    if (isDebugEnabled()) {
      console.warn("[embedded-lettacode] switch_session tool-context refresh failed", switchContextError);
    }
  }
  if (shouldPersistSessionState()) {
    settingsManager.persistSession(agent2.id, conversationId);
  }
  msgQueueRuntime.clear("switch_session");
  const switchResponse = {
    type: "control_response",
    response: {
      subtype: "success",
      request_id: requestId ?? "",
      response: {
        agent_id: agent2.id,
        conversation_id: conversationId,
        tools: availableTools
      }
    },
    session_id: sessionId,
    uuid: randomUUID19()
  };
  writeWireMessage(switchResponse);
} else if (subtype === "register_external_tools") {`;
src = src.replace(token, replacement);
if (!src.includes(marker)) {
  throw new Error('patch-switch-session-control: replacement did not land');
}
writeFileSync(file, src);
console.log('[embedded-lettacode] patched headless stream-json switch_session control_request');
