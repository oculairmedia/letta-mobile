#!/usr/bin/env node
// letta.chart: the reference `process` plugin for LCP wire v1 (docs/reference/canvas-plugin-wire-v1.md).
//
// Zero dependencies. The host runs `node server.mjs` in the package directory and speaks JSON-RPC
// 2.0 with it over stdio, one message per `\n`-terminated line (NDJSON); nothing else may go to
// stdout, so diagnostics go to stderr or through `host.log`.
//
//   host -> plugin  plugin.initialize / activate / health / deactivate (requests)
//                   action.invoke (request), element.event, host.settingsChanged, $/cancel (notifications)
//   plugin -> host  host.putAsset.begin / chunk / end, host.emit (requests), host.log (notification)
//
// `plot` renders a PNG, uploads it, and places a `chart` element whose snapshot is that asset.
// `toggle` (from the element's page) flips bar <-> line, re-renders, re-uploads and updates it.
import { createHash } from 'node:crypto';
import { createInterface } from 'node:readline';
import { pathToFileURL } from 'node:url';
import { chartPng } from './png.mjs';

export const CONTRACT_VERSIONS = [1];
const BUILD = '1.0.0';
const CHUNK_BYTES = 1024 * 1024; // host.putAsset.chunk carries at most 1 MiB of raw bytes
const MAX_POINTS = 24;
const MAX_PROP_CHARS = 1024; // the props schema's maxLength for labels/values

/** The LCP error codes this plugin answers with (the table in the wire reference). */
export const Code = Object.freeze({
  PARSE: -32700, INVALID_REQUEST: -32600, METHOD_NOT_FOUND: -32601, INVALID_PARAMS: -32602, INTERNAL: -32603,
  CANCELLED: -32800, NOT_INITIALIZED: -32002, SESSION_STATE: -32003, CONTRACT_MISMATCH: -32004, ACTION_FAILED: -32010,
});

class RpcError extends Error {
  constructor(code, message, data) {
    super(message);
    Object.assign(this, { code, data });
  }
}

/** The plugin's own failure: `{"code":-32010,"message":...,"data":{"code":<its code>}}`. */
const actionFailed = (code, message) => new RpcError(Code.ACTION_FAILED, message, { code });
const CANCELLED = Symbol('cancelled');

const isArrayOf = (value, check) => Array.isArray(value) && value.every((v) => check(v));
const sameLength = ({ labels, values }) => labels.length === values.length;
const pointsInRange = ({ labels }) => labels.length >= 1 && labels.length <= MAX_POINTS;

/** What `plot` input must hold, in order: each rule and the message when it does not. */
const INPUT_RULES = [
  [({ kind }) => kind === 'bar' || kind === 'line', 'kind is bar or line'],
  [({ title }) => typeof title === 'string' && title.length <= 120, 'title is a string of at most 120 characters'],
  [({ labels }) => isArrayOf(labels, (l) => typeof l === 'string'), 'labels is an array of strings'],
  [({ values }) => isArrayOf(values, Number.isFinite), 'values is an array of finite numbers'],
  [(input) => sameLength(input) && pointsInRange(input), `labels and values have the same length, 1 to ${MAX_POINTS}`],
];

/** Validates `plot` input (arrays) into flat element props (arrays as JSON text). */
export function propsFromInput(raw) {
  const input = { kind: 'bar', ...raw };
  const broken = INPUT_RULES.find(([holds]) => !holds(input));
  if (broken) throw actionFailed('invalid_input', broken[1]);
  const props = { kind: input.kind, title: input.title, labels: JSON.stringify(input.labels), values: JSON.stringify(input.values) };
  const tooLong = Math.max(props.labels.length, props.values.length) > MAX_PROP_CHARS;
  if (tooLong) throw actionFailed('invalid_input', 'the chart data is too long');
  return props;
}

/** The arrays inside flat props. */
export const chartOf = (props) => ({ ...props, labels: JSON.parse(props.labels), values: JSON.parse(props.values) });

/** The props a page sent with `toggle`, when complete and well-formed; else null. */
function propsFromView(input) {
  try {
    return propsFromInput(chartOf({ kind: input.kind, title: input.title, labels: input.labels, values: input.values }));
  } catch {
    return null;
  }
}

/** The element an action from a view concerns. */
const NO_ELEMENT = '(none)';
const elementIdOf = (context) => context.elementId ?? context.origin?.elementId ?? NO_ELEMENT;

const fallbackOf = (props) => ({ title: props.title, subtitle: `${props.kind} chart, ${JSON.parse(props.values).length} points`, icon: 'chart' });

/**
 * One LCP session over a line transport: [send] writes one message (an object) as one line,
 * [exit] ends the process once everything sent is flushed.
 */
export function createSession({ send, exit }) {
  let state = 'uninitialized'; // -> initialized -> active -> stopping
  let nextId = 0;
  const pending = new Map(); // our requests to the host: id -> {resolve, reject}
  const inflight = new Map(); // the host's requests we are running: JSON id -> {method, cancelled}
  const charts = new Map(); // elementId -> props, for toggle

  const reply = (id, result) => send({ jsonrpc: '2.0', id, result });
  const replyError = (id, code, message, data) => send({ jsonrpc: '2.0', id, error: data ? { code, message, data } : { code, message } });

  /** A request to the host: `{"jsonrpc":"2.0","id":"p-N","method":...,"params":...}`, answered by id. */
  function call(method, params, work) {
    const id = `p-${++nextId}`;
    const answer = new Promise((resolve, reject) => pending.set(id, { resolve, reject }));
    send({ jsonrpc: '2.0', id, method, params });
    return answer.then((result) => {
      if (work?.cancelled) throw CANCELLED; // the host gave up on the action: stop here
      return result;
    });
  }

  const log = (level, message) => send({ jsonrpc: '2.0', method: 'host.log', params: { level, message } });

  /** host.putAsset.begin {mediaType, byteSize} -> chunk {uploadId, index, base64}... -> end {uploadId, sha256} -> {ref}. */
  async function upload(bytes, work) {
    const { uploadId } = await call('host.putAsset.begin', { mediaType: 'image/png', byteSize: bytes.length }, work);
    for (let index = 0; index * CHUNK_BYTES < bytes.length || index === 0; index++) {
      const base64 = bytes.subarray(index * CHUNK_BYTES, (index + 1) * CHUNK_BYTES).toString('base64');
      await call('host.putAsset.chunk', { uploadId, index, base64 }, work);
    }
    const sha256 = createHash('sha256').update(bytes).digest('hex');
    return (await call('host.putAsset.end', { uploadId, sha256 }, work)).ref;
  }

  const refusal = (receipt) => receipt?.refused?.[0]?.reason ?? 'the host placed nothing';

  async function plot(input, work) {
    const props = propsFromInput(input);
    const ref = await upload(chartPng(chartOf(props)), work);
    // host.emit {place:[{kind, v, props, fallback, snapshot}]} -> {receipt:{placed:[id], refused:[...]}}
    const { receipt } = await call('host.emit', {
      place: [{ kind: 'chart', v: 1, props, fallback: fallbackOf(props), snapshot: { type: 'asset', ref } }],
    }, work);
    const elementId = receipt?.placed?.[0];
    if (!elementId) throw actionFailed('refused', refusal(receipt));
    charts.set(elementId, props);
    return { text: `Placed a ${props.kind} chart "${props.title}" (${elementId})`, structured: { elementId, kind: props.kind } };
  }

  /** The chart [elementId] shows: what the plugin placed, else the props the page sent with toggle. */
  function chartToToggle(elementId, input) {
    const known = elementId !== NO_ELEMENT;
    const current = known ? charts.get(elementId) ?? propsFromView(input ?? {}) : null;
    if (!current) throw actionFailed('unknown_element', `no chart ${elementId} to toggle`);
    return current;
  }

  async function toggle(input, context, work) {
    const elementId = elementIdOf(context);
    const current = chartToToggle(elementId, input);
    const props = { ...current, kind: current.kind === 'bar' ? 'line' : 'bar' };
    const ref = await upload(chartPng(chartOf(props)), work);
    // host.emit {update:[{elementId, props, fallback, snapshot}]}: only the given fields change.
    const { receipt } = await call('host.emit', {
      update: [{ elementId, props, fallback: fallbackOf(props), snapshot: { type: 'asset', ref } }],
    }, work);
    if (receipt?.refused?.length) throw actionFailed('refused', refusal(receipt));
    charts.set(elementId, props);
    return { text: `Switched ${elementId} to a ${props.kind} chart`, structured: { elementId, kind: props.kind } };
  }

  /** The host's requests: method -> handler(params, work). */
  const requests = {
    'plugin.initialize': (params) => {
      if (state !== 'uninitialized') throw new RpcError(Code.SESSION_STATE, 'already initialized');
      const offered = Array.isArray(params.contractVersions) ? params.contractVersions : [params.contractVersion];
      const chosen = Math.max(...offered.filter((v) => CONTRACT_VERSIONS.includes(v)));
      if (!Number.isFinite(chosen)) {
        throw new RpcError(Code.CONTRACT_MISMATCH, `the plugin speaks contract [${CONTRACT_VERSIONS}], the host offered [${offered}]`, { supported: CONTRACT_VERSIONS });
      }
      state = 'initialized';
      return { ok: true, contractVersion: chosen, pluginInfo: { build: BUILD } };
    },
    'plugin.activate': () => {
      state = 'active';
      log('info', 'chart plugin activated');
      return {};
    },
    'plugin.health': () => ({ status: 'ok' }),
    'plugin.deactivate': () => {
      state = 'stopping';
      return {};
    },
    'action.invoke': (params, work) => {
      if (state !== 'active') throw new RpcError(Code.SESSION_STATE, `action.invoke needs an active session (${state})`);
      if (typeof params.action !== 'string' || typeof params.context !== 'object') throw new RpcError(Code.INVALID_PARAMS, 'action.invoke needs action and context');
      if (params.action === 'plot') return plot(params.input, work);
      if (params.action === 'toggle') return toggle(params.input, params.context, work);
      throw actionFailed('unknown_action', `letta.chart has no action ${params.action}`);
    },
  };

  /** The host's notifications. */
  const notifications = {
    'element.event': ({ elementId, event }) => event === 'removed' && charts.delete(elementId),
    'host.settingsChanged': () => {}, // letta.chart has no settings
    // $/cancel {id}: stop the work and answer the request -32800 now; a late result is never sent.
    '$/cancel': ({ id }) => {
      const work = inflight.get(JSON.stringify(id));
      if (!work) return;
      work.cancelled = true;
      inflight.delete(JSON.stringify(id));
      replyError(id, Code.CANCELLED, `${work.method} was cancelled`);
    },
  };

  /** Why [method] cannot run now, as [code, message]; null when it can. */
  function refusalOf(method) {
    if (!(method in requests)) return [Code.METHOD_NOT_FOUND, `no method ${method} in this direction`];
    const initializing = method === 'plugin.initialize';
    if (state === 'uninitialized' && !initializing) return [Code.NOT_INITIALIZED, 'plugin.initialize has not completed'];
    return null;
  }

  /** A handler's failure as its answer; nothing once the host cancelled the request. */
  function answerFailure(id, method, work, e) {
    if (work.cancelled || e === CANCELLED) return;
    if (e instanceof RpcError) replyError(id, e.code, e.message, e.data);
    else replyError(id, Code.INTERNAL, `${method} failed: ${e?.message ?? e}`);
  }

  async function runRequest({ id, method, params }) {
    const refusal = refusalOf(method);
    if (refusal) return replyError(id, ...refusal);
    const key = JSON.stringify(id);
    const work = { method, cancelled: false };
    inflight.set(key, work);
    try {
      const result = await requests[method](params, work);
      if (!work.cancelled) reply(id, result);
    } catch (e) {
      answerFailure(id, method, work, e);
    } finally {
      inflight.delete(key);
    }
    if (method === 'plugin.deactivate') exit();
  }

  function runNotification({ method, params }) {
    if (state === 'uninitialized' && method !== '$/cancel') return;
    notifications[method]?.(params);
  }

  /** An answer to one of our requests. */
  function settle(message) {
    const waiter = pending.get(message.id);
    if (!waiter) return;
    pending.delete(message.id);
    if ('error' in message) waiter.reject(new RpcError(message.error.code, message.error.message, message.error.data));
    else waiter.resolve(message.result);
  }

  /** A line as a JSON-RPC message, or the error it is answered with (always with a null id here). */
  function parse(line) {
    try {
      return { message: JSON.parse(line) };
    } catch {
      return { error: [Code.PARSE, 'the line is not JSON'] };
    }
  }

  const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
  const isValidId = (id) => typeof id === 'string' || typeof id === 'number';
  const isJsonRpc = (message) => isObject(message) && message.jsonrpc === '2.0';

  /** A call (it has a method): a request when it has an id, else a notification. */
  function dispatch(message) {
    const params = message.params ?? {};
    const isRequest = 'id' in message;
    if (!isObject(params)) return isRequest ? replyError(message.id, Code.INVALID_PARAMS, 'params is an object') : undefined;
    if (isRequest) return runRequest({ id: message.id, method: message.method, params });
    return runNotification({ method: message.method, params });
  }

  /** One line from the host. */
  function receive(line) {
    if (!line.trim()) return;
    const { message, error } = parse(line);
    if (error) return replyError(null, ...error);
    if (!isJsonRpc(message)) {
      return replyError(isValidId(message?.id) ? message.id : null, Code.INVALID_REQUEST, 'not a JSON-RPC 2.0 message LCP accepts');
    }
    if (typeof message.method === 'string') return dispatch(message);
    return settle(message);
  }

  return { receive, get state() { return state; } };
}

/** stdio: NDJSON in on stdin, out on stdout; the end of stdin ends the plugin. */
function main() {
  const send = (message) => process.stdout.write(`${JSON.stringify(message)}\n`);
  const exit = () => process.stdout.write('', () => process.exit(0));
  const session = createSession({ send, exit });
  const lines = createInterface({ input: process.stdin, crlfDelay: Infinity });
  lines.on('line', (line) => session.receive(line.replace(/\r$/, '')));
  lines.on('close', exit);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main();
