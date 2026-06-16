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

async function postAndroidBridge(path, input, toolName) {
  const bridge = process.env.LETTA_ANDROID_NETWORK_BRIDGE_URL;
  if (!bridge) {
    return { isError: true, content: [{ type: 'text', text: 'Android bridge unavailable: LETTA_ANDROID_NETWORK_BRIDGE_URL is not set.' }] };
  }
  const token = process.env.LETTA_ANDROID_NETWORK_BRIDGE_TOKEN;
  if (!token) {
    return { isError: true, content: [{ type: 'text', text: 'Android bridge unavailable: LETTA_ANDROID_NETWORK_BRIDGE_TOKEN is not set.' }] };
  }
  try {
    const response = await fetch(`${bridge}${path}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` },
      body: JSON.stringify(input || {})
    });
    const text = await response.text();
    if (!response.ok) {
      return { isError: true, content: [{ type: 'text', text: `${toolName} failed (${response.status}): ${text}` }] };
    }
    return { isError: false, content: [{ type: 'text', text }] };
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return { isError: true, content: [{ type: 'text', text: `${toolName} bridge error: ${message}` }] };
  }
}

function registerExternalTool(tool, handler) {
  const registry = getExternalToolRegistry();
  registry.set(tool.name, tool);
  const previousExecutor = globalThis[EXTERNAL_EXECUTOR_KEY];
  globalThis[EXTERNAL_EXECUTOR_KEY] = async function(toolCallId, toolName, input) {
    if (toolName === tool.name) return handler(input || {}, toolName, toolCallId);
    if (typeof previousExecutor === 'function') return previousExecutor(toolCallId, toolName, input);
    return { isError: true, content: [{ type: 'text', text: `External tool not handled: ${toolName}` }] };
  };
}

function registerReadSensorsTool() {
  registerExternalTool({
    name: 'read_sensors',
    description: 'Read no-permission device context from the Android device: battery, thermal state, memory, storage, network, display when available, gated capability status, and the available sensor catalog.',
    parameters: {
      type: 'object',
      properties: {
        mode: { type: 'string', enum: ['summary', 'catalog', 'sensor', 'snapshot'], description: 'summary returns a compact overview; catalog lists sensors; sensor filters catalog by query; snapshot returns bounded telemetry plus sensor catalog.' },
        query: { type: 'string', description: 'Optional sensor filter matched against name, vendor, stringType, or numeric type.' },
        limit: { type: 'integer', minimum: 1, maximum: 256, description: 'Maximum number of sensor descriptors to return.' }
      },
      additionalProperties: false
    }
  }, (input, toolName) => postAndroidBridge('/device/sensors/read', input, toolName));
}

function registerDeviceActionTool() {
  registerExternalTool({
    name: 'device_action',
    description: 'Run a compact Android device-action command. Prefer this over individual device tools to avoid prompt/tool-list bloat. Commands include sensors.summary, sensors.catalog, sensors.snapshot, mobile.capabilities, intent.dry_run, hardware.capabilities, hardware.flashlight (CONTROL the torch: input {enabled: true|false}), hardware.flashlight_on, hardware.flashlight_off, hardware.flashlight_probe (capability check only, does not change the torch), hardware.vibrate, and hardware.audio_status.',
    parameters: {
      type: 'object',
      properties: {
        command: { type: 'string', description: 'Device action command, e.g. sensors.summary, hardware.capabilities, intent.dry_run, or hardware.flashlight to turn the torch on/off.' },
        input: { type: 'object', description: 'Optional command-specific JSON input. For hardware.flashlight, pass {enabled: true} to turn the torch ON or {enabled: false} to turn it OFF (the change is applied immediately, not a dry run).' }
      },
      required: ['command'],
      additionalProperties: false
    }
  }, (input, toolName) => postAndroidBridge('/device/actions/command', input, toolName));
}

function registerEmbeddedExternalTools() {
  registerReadSensorsTool();
  registerDeviceActionTool();
}

registerEmbeddedExternalTools();

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
