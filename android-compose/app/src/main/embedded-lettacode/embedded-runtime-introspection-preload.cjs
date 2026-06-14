'use strict';

const modulePromise = import('./letta-mobile-runtime-introspection/runtime-introspection.js');

function prependPrefix(content, prefix) {
  if (!prefix) return content;
  if (typeof content === 'string') return prefix + '\n\n' + content;
  if (Array.isArray(content)) return [{ type: 'text', text: prefix + '\n\n' }, ...content];
  return content;
}

globalThis.__lettaMobileBuildRuntimeIntrospectionContent = async function(agentId, conversationId, content) {
  try {
    const introspect = await modulePromise;
    const effectiveConv = conversationId || 'default';
    const servingModel = introspect.getServingModelHandle(agentId);
    const connectionReminder = introspect.buildConnectionReminder(agentId, effectiveConv);
    const modelDelta = introspect.detectModelChange(agentId, effectiveConv, servingModel);
    const prefix = [connectionReminder, modelDelta].filter(Boolean).join('\n');
    introspect.seedModelHandle(agentId, effectiveConv, servingModel);
    return prependPrefix(content, prefix);
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.error('[embedded-runtime-introspection] injection failed (fail-open): ' + message);
    return content;
  }
};
