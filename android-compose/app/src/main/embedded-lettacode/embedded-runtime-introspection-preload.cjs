'use strict';

const fs = require('fs');
const modulePromise = import('./letta-mobile-runtime-introspection/runtime-introspection.js');

const EXTERNAL_TOOLS_KEY = Symbol.for('@letta/externalTools');
const EXTERNAL_EXECUTOR_KEY = Symbol.for('@letta/externalToolExecutor');

function registerEmbeddedExternalTools() {
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
  registerMobileIntentTools(registry);
  globalThis[EXTERNAL_TOOLS_KEY] = registry;

  const previousExecutor = globalThis[EXTERNAL_EXECUTOR_KEY];
  globalThis[EXTERNAL_EXECUTOR_KEY] = async function(toolCallId, toolName, input) {
    if (toolName === 'read_sensors') {
      return postAndroidBridge('/device/sensors/read', input || {}, toolName);
    }
    if (MOBILE_INTENT_TOOL_NAMES.has(toolName)) {
      return postAndroidBridge('/device/mobile-actions/intent', { ...(input || {}), tool: toolName }, toolName);
    }
    if (typeof previousExecutor === 'function') return previousExecutor(toolCallId, toolName, input);
    return { isError: true, content: [{ type: 'text', text: `External tool not handled: ${toolName}` }] };
  };
}

const MOBILE_INTENT_TOOL_NAMES = new Set([
  'open_wifi_settings',
  'show_location_on_map',
  'compose_email',
  'insert_contact',
  'insert_calendar_event'
]);

function registerMobileIntentTools(registry) {
  registry.set('open_wifi_settings', {
    name: 'open_wifi_settings',
    description: 'Open Android Wi-Fi settings. User action is required; this tool only launches settings UI.',
    parameters: { type: 'object', properties: {}, additionalProperties: false }
  });
  registry.set('show_location_on_map', {
    name: 'show_location_on_map',
    description: 'Show a location query in a maps app. User action is required in the launched app.',
    parameters: {
      type: 'object',
      properties: { location: { type: 'string', description: 'Address, place name, or latitude/longitude query to show on a map.' } },
      required: ['location'],
      additionalProperties: false
    }
  });
  registry.set('compose_email', {
    name: 'compose_email',
    description: 'Open an email compose UI with recipient, subject, and body prefilled. Does not send email.',
    parameters: {
      type: 'object',
      properties: {
        to: { type: 'string', description: 'Recipient email address.' },
        subject: { type: 'string', description: 'Draft email subject.' },
        body: { type: 'string', description: 'Draft email body.' }
      },
      required: ['to'],
      additionalProperties: false
    }
  });
  registry.set('insert_contact', {
    name: 'insert_contact',
    description: 'Open Android contact insert UI with fields prefilled. Does not write a contact silently.',
    parameters: {
      type: 'object',
      properties: {
        firstName: { type: 'string' },
        lastName: { type: 'string' },
        phoneNumber: { type: 'string' },
        email: { type: 'string' }
      },
      additionalProperties: false
    }
  });
  registry.set('insert_calendar_event', {
    name: 'insert_calendar_event',
    description: 'Open Android calendar event insert UI with date/time and title prefilled. Does not write an event silently.',
    parameters: {
      type: 'object',
      properties: {
        datetime: { type: 'string', description: 'Event start time, preferably ISO-8601.' },
        title: { type: 'string', description: 'Calendar event title.' }
      },
      required: ['datetime', 'title'],
      additionalProperties: false
    }
  });
}

async function postAndroidBridge(path, input, toolName) {
  const bridge = process.env.LETTA_ANDROID_NETWORK_BRIDGE_URL;
  if (!bridge) {
    return { isError: true, content: [{ type: 'text', text: 'Android bridge unavailable: LETTA_ANDROID_NETWORK_BRIDGE_URL is not set.' }] };
  }
  try {
    const response = await fetch(`${bridge}${path}`, {
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
