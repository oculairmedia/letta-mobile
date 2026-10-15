// A test host for letta.chart: spawns `node server.mjs`, answers the plugin's host.* requests the
// way the Kotlin tests' RecordingHost does (upload-N, el-N, real sha256 refs) and records every
// line as a wire transcript entry {"dir", "message"}.
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';

export const PLUGIN_DIR = fileURLToPath(new URL('..', import.meta.url));

/** A request from the plugin to the host (answered here, never handed to a test). */
const isHostRequest = (message) => 'id' in message && String(message.method).startsWith('host.');

export class FakeHost {
  constructor() {
    this.child = spawn(process.execPath, ['server.mjs'], { cwd: PLUGIN_DIR, stdio: ['pipe', 'pipe', 'inherit'] });
    this.transcript = [];
    this.assets = [];
    this.emits = [];
    this.waiters = [];
    this.inbox = [];
    this.uploads = new Map();
    this.nextId = 0;
    this.placed = 0;
    this.exited = new Promise((resolve) => this.child.on('exit', (code) => resolve(code)));
    createInterface({ input: this.child.stdout }).on('line', (line) => this.onLine(line));
  }

  sendRaw(text) {
    this.child.stdin.write(`${text}\n`);
  }

  send(message) {
    this.transcript.push({ dir: 'host->plugin', message });
    this.sendRaw(JSON.stringify(message));
  }

  /** A host request; resolves with the plugin's answer message. */
  request(method, params = {}) {
    const id = ++this.nextId;
    this.send({ jsonrpc: '2.0', id, method, params });
    return this.next((m) => m.id === id && !('method' in m));
  }

  notify(method, params = {}) {
    this.send({ jsonrpc: '2.0', method, params });
  }

  /** The next plugin message matching [predicate] (default: any not auto-answered). */
  next(predicate = () => true) {
    const index = this.inbox.findIndex(predicate);
    if (index >= 0) return Promise.resolve(this.inbox.splice(index, 1)[0]);
    return new Promise((resolve) => this.waiters.push({ predicate, resolve }));
  }

  onLine(line) {
    const message = JSON.parse(line);
    this.transcript.push({ dir: 'plugin->host', message });
    if (isHostRequest(message)) {
      this.answer(message);
      return;
    }
    const index = this.waiters.findIndex((w) => w.predicate(message));
    if (index >= 0) this.waiters.splice(index, 1)[0].resolve(message);
    else this.inbox.push(message);
  }

  answer({ id, method, params }) {
    const result = this.serve(method, params);
    this.send({ jsonrpc: '2.0', id, result });
  }

  serve(method, params) {
    switch (method) {
      case 'host.putAsset.begin': {
        const uploadId = `upload-${this.uploads.size + 1}`;
        this.uploads.set(uploadId, { ...params, chunks: [] });
        return { uploadId };
      }
      case 'host.putAsset.chunk':
        this.uploads.get(params.uploadId).chunks.push(Buffer.from(params.base64, 'base64'));
        return {};
      case 'host.putAsset.end': {
        const bytes = Buffer.concat(this.uploads.get(params.uploadId).chunks);
        const sha256 = createHash('sha256').update(bytes).digest('hex');
        if (sha256 !== params.sha256) throw new Error(`sha256 mismatch ${sha256} != ${params.sha256}`);
        this.assets.push({ bytes, sha256 });
        return { ref: `sha256:${sha256}` };
      }
      case 'host.emit': {
        this.emits.push(params);
        const placed = (params.place ?? []).map(() => `el-${++this.placed}`);
        return { receipt: placed.length ? { placed } : {} };
      }
      default:
        throw new Error(`unexpected ${method}`);
    }
  }

  close() {
    this.child.stdin.end();
    return this.exited;
  }
}

/** The values that depend on the PNG bytes (zlib's output may differ between builds), masked. */
export function masked(value) {
  if (Array.isArray(value)) return value.map(masked);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, MASKED_KEYS.has(k) ? '<masked>' : masked(v)]));
  }
  return value;
}
const MASKED_KEYS = new Set(['base64', 'sha256', 'ref', 'byteSize']);

/** The chart session every test (and the golden transcript session-chart.jsonl) runs. */
export const SESSION = {
  initialize: { contractVersion: 1, contractVersions: [1], pluginId: 'letta.chart', settings: {}, hostInfo: { name: 'letta-host', version: 'test' } },
  plot: {
    action: 'plot',
    input: { kind: 'bar', title: 'Revenue', labels: ['Q1', 'Q2', 'Q3', 'Q4'], values: [3, 5, 2, 6] },
    context: { origin: { type: 'agent', agentId: 'agent-1', conversationId: 'conv-1', toolCallId: 'call-1' }, callId: 'c-1' },
  },
  toggle: {
    action: 'toggle',
    input: {},
    context: { origin: { type: 'view', elementId: 'el-1', peerId: 'peer-1' }, canvasId: 'canvas-1', elementId: 'el-1' },
  },
};

/** Runs the whole chart session: initialize, activate, plot, toggle, deactivate. */
export async function runSession(host) {
  const answers = [];
  answers.push(await host.request('plugin.initialize', SESSION.initialize));
  answers.push(await host.request('plugin.activate'));
  answers.push(await host.request('action.invoke', SESSION.plot));
  answers.push(await host.request('action.invoke', SESSION.toggle));
  answers.push(await host.request('plugin.deactivate'));
  return { answers, exitCode: await host.exited };
}
