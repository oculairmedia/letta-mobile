'use strict';

const fs = require('fs');
const modulePromise = import('./letta-mobile-runtime-introspection/runtime-introspection.js');

const EXTERNAL_TOOLS_KEY = Symbol.for('@letta/externalTools');
const EXTERNAL_EXECUTOR_KEY = Symbol.for('@letta/externalToolExecutor');

function registerReadSensorsTool() {
  const registry = globalThis[EXTERNAL_TOOLS_KEY] || new Map();
  registry.set('read_sensors', {
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
  });
  globalThis[EXTERNAL_TOOLS_KEY] = registry;

  const previousExecutor = globalThis[EXTERNAL_EXECUTOR_KEY];
  globalThis[EXTERNAL_EXECUTOR_KEY] = async function(toolCallId, toolName, input) {
    if (toolName !== 'read_sensors') {
      if (typeof previousExecutor === 'function') return previousExecutor(toolCallId, toolName, input);
      return { isError: true, content: [{ type: 'text', text: `External tool not handled: ${toolName}` }] };
    }
    const bridge = process.env.LETTA_ANDROID_NETWORK_BRIDGE_URL;
    if (!bridge) {
      return { isError: true, content: [{ type: 'text', text: 'Android bridge unavailable: LETTA_ANDROID_NETWORK_BRIDGE_URL is not set.' }] };
    }
    try {
      const response = await fetch(`${bridge}/device/sensors/read`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(input || {})
      });
      const text = await response.text();
      if (!response.ok) {
        return { isError: true, content: [{ type: 'text', text: `read_sensors failed (${response.status}): ${text}` }] };
      }
      return { isError: false, content: [{ type: 'text', text }] };
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      return { isError: true, content: [{ type: 'text', text: `read_sensors bridge error: ${message}` }] };
    }
  };
}

function registerHardwareControlTools() {
  const registry = globalThis[EXTERNAL_TOOLS_KEY] || new Map();
  const tools = [
    {
      name: 'hardware_capabilities',
      description: 'Report Android hardware-control capability status for flashlight, vibration, and audio volume without changing device state.',
      parameters: { type: 'object', properties: {}, additionalProperties: false }
    },
    {
      name: 'set_flashlight',
      description: 'Safely enable or disable the Android camera torch when supported. Use dryRun to only probe capability.',
      parameters: {
        type: 'object',
        properties: {
          enabled: { type: 'boolean', description: 'True to enable torch, false to disable.' },
          dryRun: { type: 'boolean', description: 'If true, only probe support and do not change torch state.' }
        },
        required: ['enabled'],
        additionalProperties: false
      }
    },
    {
      name: 'vibrate',
      description: 'Trigger a short Android vibration with safe clamped duration or pattern limits.',
      parameters: {
        type: 'object',
        properties: {
          durationMs: { type: 'integer', minimum: 1, maximum: 1000, description: 'Single vibration duration; clamped to 1000ms.' },
          patternMs: { type: 'array', items: { type: 'integer', minimum: 0, maximum: 500 }, maxItems: 8, description: 'Optional waveform pattern in milliseconds; each segment is clamped.' }
        },
        additionalProperties: false
      }
    },
    {
      name: 'audio_status',
      description: 'Read current Android music volume, max music volume, ringer mode, and fixed-volume policy status.',
      parameters: { type: 'object', properties: {}, additionalProperties: false }
    },
    {
      name: 'adjust_music_volume',
      description: 'Adjust Android STREAM_MUSIC volume if permissionless policy allows, with delta clamped to +/-3 or explicit safe level.',
      parameters: {
        type: 'object',
        properties: {
          delta: { type: 'integer', minimum: -3, maximum: 3, description: 'Relative volume change; clamped to +/-3.' },
          level: { type: 'integer', minimum: 0, description: 'Absolute music volume level clamped to stream bounds.' }
        },
        additionalProperties: false
      }
    }
  ];
  for (const tool of tools) registry.set(tool.name, tool);
  globalThis[EXTERNAL_TOOLS_KEY] = registry;

  const previousExecutor = globalThis[EXTERNAL_EXECUTOR_KEY];
  globalThis[EXTERNAL_EXECUTOR_KEY] = async function(toolCallId, toolName, input) {
    const routes = {
      hardware_capabilities: '/device/hardware/capabilities',
      set_flashlight: '/device/hardware/set_flashlight',
      vibrate: '/device/hardware/vibrate',
      audio_status: '/device/hardware/audio_status',
      adjust_music_volume: '/device/hardware/adjust_music_volume'
    };
    const route = routes[toolName];
    if (!route) {
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
        return { isError: true, content: [{ type: 'text', text: `${toolName} failed (${response.status}): ${text}` }] };
      }
      return { isError: false, content: [{ type: 'text', text }] };
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      return { isError: true, content: [{ type: 'text', text: `${toolName} bridge error: ${message}` }] };
    }
  };
}

registerReadSensorsTool();
registerHardwareControlTools();

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
