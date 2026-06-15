'use strict';

const assert = require('assert');
const {
  isAssistantToolCallMessage,
  isToolResultMessage,
  normalizeSlidingWindowCutoffForToolGroups,
} = require('./patch-embedded-letta-code-tool-groups.cjs');

function assistantToolCall(id = 'call-1') {
  return {
    role: 'assistant',
    content: null,
    tool_calls: [{ id, type: 'function', function: { name: 'Bash', arguments: '{}' } }],
  };
}

function internalToolCall(id = 'call-1') {
  return {
    role: 'assistant',
    content: [{ type: 'toolCall', id, name: 'Bash', arguments: '{}' }],
  };
}

function anthropicToolUse(id = 'call-1') {
  return {
    role: 'assistant',
    content: [{ type: 'tool_use', id, name: 'Bash', input: {} }],
  };
}

function toolResult(id = 'call-1') {
  return { role: 'tool', tool_call_id: id, content: 'ok' };
}

function internalToolResult(id = 'call-1') {
  return { role: 'toolResult', toolCallId: id, content: [{ type: 'text', text: 'ok' }] };
}

function anthropicToolResult(id = 'call-1') {
  return { role: 'user', content: [{ type: 'tool_result', tool_use_id: id, content: 'ok' }] };
}

function user(text) {
  return { role: 'user', content: text };
}

function assistant(text) {
  return { role: 'assistant', content: [{ type: 'text', text }] };
}

(function recognizesOpenAiAndInternalShapes() {
  assert.strictEqual(isAssistantToolCallMessage(assistantToolCall()), true);
  assert.strictEqual(isAssistantToolCallMessage(internalToolCall()), true);
  assert.strictEqual(isAssistantToolCallMessage(anthropicToolUse()), true);
  assert.strictEqual(isToolResultMessage(toolResult()), true);
  assert.strictEqual(isToolResultMessage(internalToolResult()), true);
  assert.strictEqual(isToolResultMessage(anthropicToolResult()), true);
})();

(function preservesCompleteGroupWhenCutFallsOnToolResponse() {
  const messages = [
    user('old'),
    assistant('old answer'),
    assistantToolCall('call-1'),
    toolResult('call-1'),
    user('new'),
    assistant('new answer'),
  ];
  assert.strictEqual(normalizeSlidingWindowCutoffForToolGroups(messages, 3), 2);
  assert.deepStrictEqual(messages.slice(normalizeSlidingWindowCutoffForToolGroups(messages, 3)).map(message => message.role), [
    'assistant',
    'tool',
    'user',
    'assistant',
  ]);
})();

(function dropsOldCompleteGroupWhenCutFallsAfterIt() {
  const messages = [
    user('old'),
    assistantToolCall('call-1'),
    toolResult('call-1'),
    user('new'),
    assistant('new answer'),
  ];
  assert.strictEqual(normalizeSlidingWindowCutoffForToolGroups(messages, 3), 3);
  assert.deepStrictEqual(messages.slice(normalizeSlidingWindowCutoffForToolGroups(messages, 3)).map(message => message.role), [
    'user',
    'assistant',
  ]);
})();

(function preventsRegressionThatWouldOrphanRoleToolMessage() {
  const messages = [
    user('old'),
    assistantToolCall('call-1'),
    toolResult('call-1'),
    toolResult('call-2'),
    user('new'),
  ];
  assert.strictEqual(normalizeSlidingWindowCutoffForToolGroups(messages, 2), 1);
  assert.strictEqual(normalizeSlidingWindowCutoffForToolGroups(messages, 3), 1);
})();

(function preservesAnthropicStyleGroup() {
  const messages = [
    user('old'),
    anthropicToolUse('call-1'),
    anthropicToolResult('call-1'),
    user('new'),
  ];
  assert.strictEqual(normalizeSlidingWindowCutoffForToolGroups(messages, 2), 1);
})();

console.log('patch-embedded-letta-code-tool-groups tests passed');
