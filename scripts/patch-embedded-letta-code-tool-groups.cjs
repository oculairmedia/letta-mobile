'use strict';

const fs = require('fs');

function messageContentParts(message) {
  const content = message && message.content;
  if (Array.isArray(content)) return content;
  return [];
}

function hasToolCallArray(message) {
  return Array.isArray(message && message.tool_calls) && message.tool_calls.length > 0 ||
    Array.isArray(message && message.toolCalls) && message.toolCalls.length > 0;
}

function hasToolCallContentPart(message) {
  return messageContentParts(message).some(part =>
    part && (part.type === 'toolCall' || part.type === 'tool_call' || part.type === 'tool_use')
  );
}

function isAssistantToolCallMessage(message) {
  return message && message.role === 'assistant' && (hasToolCallArray(message) || hasToolCallContentPart(message));
}

function hasToolResultContentPart(message) {
  return messageContentParts(message).some(part =>
    part && (part.type === 'toolResult' || part.type === 'tool_result')
  );
}

function isToolResultMessage(message) {
  return !!message && (
    message.role === 'tool' ||
    message.role === 'toolResult' ||
    message.role === 'tool_result' ||
    hasToolResultContentPart(message)
  );
}

function findToolCallGroupStart(messages, index) {
  if (!Array.isArray(messages) || index < 0 || index >= messages.length || !isToolResultMessage(messages[index])) {
    return index;
  }
  let cursor = index;
  while (cursor > 0 && isToolResultMessage(messages[cursor])) {
    cursor -= 1;
  }
  return isAssistantToolCallMessage(messages[cursor]) ? cursor : index;
}

function findToolCallGroupEnd(messages, index) {
  if (!Array.isArray(messages) || index < 0 || index >= messages.length || !isAssistantToolCallMessage(messages[index])) {
    return index + 1;
  }
  let cursor = index + 1;
  while (cursor < messages.length && isToolResultMessage(messages[cursor])) {
    cursor += 1;
  }
  return cursor;
}

function normalizeSlidingWindowCutoffForToolGroups(messages, cutoffIndex) {
  if (cutoffIndex === undefined) return undefined;
  const groupStart = findToolCallGroupStart(messages, cutoffIndex);
  if (groupStart !== cutoffIndex) return groupStart;
  const previousStart = findToolCallGroupStart(messages, cutoffIndex - 1);
  if (previousStart >= 0 && previousStart < cutoffIndex) {
    const previousEnd = findToolCallGroupEnd(messages, previousStart);
    if (cutoffIndex > previousStart && cutoffIndex < previousEnd) return previousStart;
  }
  return cutoffIndex;
}

function applyPatch(source) {
  let patched = source;
  const helperNeedle = `function hasPendingLocalToolCall(message) {\n  return message.role === "assistant" && message.content.some(part => part.type === "toolCall");\n}\n`;
  const helperReplacement = `function messageContentParts(message) {\n  const content = message?.content;\n  return Array.isArray(content) ? content : [];\n}\nfunction hasToolCallArray(message) {\n  return Array.isArray(message?.tool_calls) && message.tool_calls.length > 0 || Array.isArray(message?.toolCalls) && message.toolCalls.length > 0;\n}\nfunction hasToolCallContentPart(message) {\n  return messageContentParts(message).some(part => part && (part.type === "toolCall" || part.type === "tool_call" || part.type === "tool_use"));\n}\nfunction isAssistantToolCallMessage(message) {\n  return message?.role === "assistant" && (hasToolCallArray(message) || hasToolCallContentPart(message));\n}\nfunction hasToolResultContentPart(message) {\n  return messageContentParts(message).some(part => part && (part.type === "toolResult" || part.type === "tool_result"));\n}\nfunction isToolResultMessage(message) {\n  return !!message && (message.role === "tool" || message.role === "toolResult" || message.role === "tool_result" || hasToolResultContentPart(message));\n}\nfunction findToolCallGroupStart(messages, index) {\n  if (!Array.isArray(messages) || index < 0 || index >= messages.length || !isToolResultMessage(messages[index])) return index;\n  let cursor = index;\n  while (cursor > 0 && isToolResultMessage(messages[cursor])) cursor -= 1;\n  return isAssistantToolCallMessage(messages[cursor]) ? cursor : index;\n}\nfunction findToolCallGroupEnd(messages, index) {\n  if (!Array.isArray(messages) || index < 0 || index >= messages.length || !isAssistantToolCallMessage(messages[index])) return index + 1;\n  let cursor = index + 1;\n  while (cursor < messages.length && isToolResultMessage(messages[cursor])) cursor += 1;\n  return cursor;\n}\nfunction normalizeSlidingWindowCutoffForToolGroups(messages, cutoffIndex) {\n  if (cutoffIndex === undefined) return undefined;\n  const groupStart = findToolCallGroupStart(messages, cutoffIndex);\n  if (groupStart !== cutoffIndex) return groupStart;\n  const previousStart = findToolCallGroupStart(messages, cutoffIndex - 1);\n  if (previousStart >= 0 && previousStart < cutoffIndex) {\n    const previousEnd = findToolCallGroupEnd(messages, previousStart);\n    if (cutoffIndex > previousStart && cutoffIndex < previousEnd) return previousStart;\n  }\n  return cutoffIndex;\n}\nfunction hasPendingLocalToolCall(message) {\n  return isAssistantToolCallMessage(message);\n}\n`;
  patched = patched.replace(helperNeedle, helperReplacement);
  if (patched === source) {
    throw new Error('Failed to patch letta.js tool-call helper block.');
  }

  const normalizeNeedle = `  if (cutoffIndex === undefined || evictionPercentage >= 1) {\n`;
  const normalizeReplacement = `  if (cutoffIndex !== undefined) {\n    cutoffIndex = normalizeSlidingWindowCutoffForToolGroups(messages, cutoffIndex);\n  }\n  if (cutoffIndex === undefined || evictionPercentage >= 1) {\n`;
  patched = patched.replace(normalizeNeedle, normalizeReplacement);
  if (!patched.includes(normalizeReplacement)) {
    throw new Error('Failed to patch letta.js sliding-window cutoff normalization.');
  }
  return patched;
}

function patchFile(filePath) {
  const source = fs.readFileSync(filePath, 'utf8');
  const patched = applyPatch(source);
  fs.writeFileSync(filePath, patched);
}

if (require.main === module) {
  const filePath = process.argv[2];
  if (!filePath) {
    console.error('Usage: node patch-embedded-letta-code-tool-groups.cjs <path-to-letta.js>');
    process.exit(2);
  }
  patchFile(filePath);
  console.log('[embedded-lettacode] patched tool-call group trimming in ' + filePath);
}

module.exports = {
  applyPatch,
  findToolCallGroupEnd,
  findToolCallGroupStart,
  isAssistantToolCallMessage,
  isToolResultMessage,
  normalizeSlidingWindowCutoffForToolGroups,
};
