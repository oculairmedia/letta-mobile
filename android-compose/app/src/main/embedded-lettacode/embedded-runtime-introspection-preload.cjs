'use strict';

const fs = require('fs');
const modulePromise = import('./letta-mobile-runtime-introspection/runtime-introspection.js');

const EXTERNAL_TOOLS_KEY = Symbol.for('@letta/externalTools');
const EXTERNAL_EXECUTOR_KEY = Symbol.for('@letta/externalToolExecutor');

function getExternalToolRegistry() {
  const registry = globalThis[EXTERNAL_TOOLS_KEY] || new Map();
  globalThis[EXTERNAL_TOOLS_KEY] = registry;
  return registry;
}

function registerAndroidBridgeTool(tool, route) {
  const registry = getExternalToolRegistry();
  registry.set(tool.name, tool);

  const previousExecutor = globalThis[EXTERNAL_EXECUTOR_KEY];
  globalThis[EXTERNAL_EXECUTOR_KEY] = async function(toolCallId, toolName, input) {
    if (toolName !== tool.name) {
      if (typeof previousExecutor === 'function') return previousExecutor(toolCallId, toolName, input);
      return { isError: true, content: [{ type: 'text', text: `External tool not handled: ${toolName}` }] };
    }
    const bridge = process.env.LETTA_ANDROID_NETWORK_BRIDGE_URL;
    if (!bridge) {
      return { isError: true, content: [{ type: 'text', text: 'Android bridge unavailable: LETTA_ANDROID_NETWORK_BRIDGE_URL is not set.' }] };
    }
    try {
      const response = await fetch(`${bridge}${route}`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(input || {})
      });
      const text = await response.text();
      if (!response.ok) {
        return { isError: true, content: [{ type: 'text', text: `${tool.name} failed (${response.status}): ${text}` }] };
      }
      return { isError: false, content: [{ type: 'text', text }] };
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      return { isError: true, content: [{ type: 'text', text: `${tool.name} bridge error: ${message}` }] };
    }
  };
}

function registerReadSensorsTool() {
  registerAndroidBridgeTool({
    name: 'read_sensors',
    description: 'Read no-permission device context from the Android device: battery, thermal state, memory, storage, network, display when available, and the available sensor catalog.',
    parameters: {
      type: 'object',
      properties: {
        mode: {
          type: 'string',
          enum: ['summary', 'catalog', 'sensor', 'snapshot'],
          description: 'summary returns a compact overview; catalog lists sensors; sensor filters catalog by query; snapshot returns bounded telemetry plus sensor catalog.'
        },
        query: {
          type: 'string',
          description: 'Optional sensor filter matched against name, vendor, stringType, or numeric type.'
        },
        limit: {
          type: 'integer',
          minimum: 1,
          maximum: 256,
          description: 'Maximum number of sensor descriptors to return.'
        }
      },
      additionalProperties: false
    }
  }, '/device/sensors/read');
}

registerReadSensorsTool();

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
