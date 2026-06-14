'use strict';

const fs = require('fs');
const modulePromise = import('./letta-mobile-runtime-introspection/runtime-introspection.js');

function prependPrefix(content, prefix) {
  if (!prefix) return content;
  if (typeof content === 'string') return prefix + '\n\n' + content;
  if (Array.isArray(content)) return [{ type: 'text', text: prefix + '\n\n' }, ...content];
  return content;
}

function readDeviceSensorGrounding() {
  const file = process.env.LETTA_MOBILE_DEVICE_SENSOR_GROUNDING_PATH;
  if (!file) return null;
  try {
    const raw = fs.readFileSync(file, 'utf8');
    const parsed = JSON.parse(raw);
    const summary = typeof parsed.summary === 'string' ? parsed.summary.trim() : '';
    const count = parsed && parsed.snapshot && Array.isArray(parsed.snapshot.sensors)
      ? parsed.snapshot.sensors.length
      : null;
    if (!summary) return null;
    const suffix = typeof count === 'number' ? ` (sensor catalog: ${count})` : '';
    return `Device context: ${summary}${suffix}`;
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.error('[embedded-runtime-introspection] device sensor grounding read failed (fail-open): ' + message);
    return null;
  }
}

globalThis.__lettaMobileBuildRuntimeIntrospectionContent = async function(agentId, conversationId, content) {
  try {
    const introspect = await modulePromise;
    const effectiveConv = conversationId || 'default';
    const servingModel = introspect.getServingModelHandle(agentId);
    const connectionReminder = introspect.buildConnectionReminder(agentId, effectiveConv);
    const modelDelta = introspect.detectModelChange(agentId, effectiveConv, servingModel);
    const deviceContext = readDeviceSensorGrounding();
    const prefix = [connectionReminder, modelDelta, deviceContext].filter(Boolean).join('\n');
    introspect.seedModelHandle(agentId, effectiveConv, servingModel);
    return prependPrefix(content, prefix);
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.error('[embedded-runtime-introspection] injection failed (fail-open): ' + message);
    return content;
  }
};
