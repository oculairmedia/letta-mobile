// letta.chart over its real stdio: framing, the session state machine, plot -> putAsset + emit,
// toggle flipping the kind, $/cancel, deactivate, and the golden transcript session-chart.jsonl.
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { FakeHost, SESSION, masked, runSession } from './fake-host.mjs';
import { decodePng } from './png-decode.mjs';

const FIXTURE = fileURLToPath(new URL(
  '../../../android-compose/sharedLogic/src/commonTest/resources/canvas/plugin/v1/wire/session-chart.jsonl', import.meta.url));

const ready = async (host) => {
  await host.request('plugin.initialize', SESSION.initialize);
  await host.request('plugin.activate');
  return host;
};

test('malformed lines are answered with a null id: not JSON -32700, a batch or no jsonrpc -32600', async () => {
  const host = new FakeHost();
  host.sendRaw('not json');
  assert.deepEqual((await host.next()).error.code, -32700);
  host.sendRaw('[{"jsonrpc":"2.0","id":1,"method":"plugin.health"}]');
  const batch = await host.next();
  assert.equal(batch.error.code, -32600);
  assert.equal(batch.id, null);
  host.sendRaw('{"id":2,"method":"plugin.health"}');
  assert.deepEqual(await host.next(), { jsonrpc: '2.0', id: 2, error: { code: -32600, message: 'not a JSON-RPC 2.0 message LCP accepts' } });
  assert.equal(await host.close(), 0);
});

test('before initialize every call is refused -32002; an unknown method -32601', async () => {
  const host = new FakeHost();
  assert.equal((await host.request('plugin.health')).error.code, -32002);
  assert.equal((await host.request('action.invoke', SESSION.plot)).error.code, -32002);
  assert.equal((await host.request('plugin.unknown')).error.code, -32601);
  assert.equal(await host.close(), 0);
});

test('the handshake chooses contract 1, or refuses -32004 with what it supports', async () => {
  const host = new FakeHost();
  const refused = await host.request('plugin.initialize', { ...SESSION.initialize, contractVersion: 2, contractVersions: [2] });
  assert.equal(refused.error.code, -32004);
  assert.deepEqual(refused.error.data, { supported: [1] });
  const ok = await host.request('plugin.initialize', SESSION.initialize);
  assert.deepEqual(ok.result, { ok: true, contractVersion: 1, pluginInfo: { build: '1.0.0' } });
  assert.equal((await host.request('plugin.initialize', SESSION.initialize)).error.code, -32003);
  assert.equal((await host.request('action.invoke', SESSION.plot)).error.code, -32003, 'action.invoke needs active');
  assert.equal(await host.close(), 0);
});

test('plot uploads a PNG and places a chart whose snapshot is that asset', async () => {
  const host = await ready(new FakeHost());
  const answer = await host.request('action.invoke', SESSION.plot);
  assert.deepEqual(answer.result, { text: 'Placed a bar chart "Revenue" (el-1)', structured: { elementId: 'el-1', kind: 'bar' } });
  assert.equal(host.assets.length, 1);
  assert.equal(decodePng(host.assets[0].bytes).header.colorType, 3);
  const [emit] = host.emits;
  assert.deepEqual(emit, {
    place: [{
      kind: 'chart',
      v: 1,
      props: { kind: 'bar', title: 'Revenue', labels: '["Q1","Q2","Q3","Q4"]', values: '[3,5,2,6]' },
      fallback: { title: 'Revenue', subtitle: 'bar chart, 4 points', icon: 'chart' },
      snapshot: { type: 'asset', ref: `sha256:${host.assets[0].sha256}` },
    }],
  });
  assert.equal(await host.close(), 0);
});

test('toggle flips the kind, re-uploads and updates the element; again flips back', async () => {
  const host = await ready(new FakeHost());
  await host.request('action.invoke', SESSION.plot);
  const first = await host.request('action.invoke', SESSION.toggle);
  assert.deepEqual(first.result.structured, { elementId: 'el-1', kind: 'line' });
  const update = host.emits[1].update[0];
  assert.equal(update.elementId, 'el-1');
  assert.equal(update.props.kind, 'line');
  assert.equal(update.snapshot.ref, `sha256:${host.assets[1].sha256}`);
  assert.notEqual(host.assets[0].sha256, host.assets[1].sha256, 'the picture changed');
  const second = await host.request('action.invoke', SESSION.toggle);
  assert.equal(second.result.structured.kind, 'bar');
  assert.equal(host.emits[2].update[0].props.kind, 'bar');
  assert.equal(await host.close(), 0);
});

test('toggle of a chart the plugin does not know uses the props the page sent, or fails unknown_element', async () => {
  const host = await ready(new FakeHost());
  const props = { kind: 'line', title: 'Load', labels: '["a","b"]', values: '[1,2]' };
  const fromPage = await host.request('action.invoke', { ...SESSION.toggle, input: props });
  assert.equal(fromPage.result.structured.kind, 'bar');
  const unknown = await host.request('action.invoke', { ...SESSION.toggle, context: { origin: { type: 'view', elementId: 'el-9' }, elementId: 'el-9' } });
  assert.deepEqual(unknown.error, { code: -32010, message: 'no chart el-9 to toggle', data: { code: 'unknown_element' } });
  assert.equal(await host.close(), 0);
});

test('bad input and unknown actions are the plugin\'s own failures (-32010 with data.code)', async () => {
  const host = await ready(new FakeHost());
  const bad = await host.request('action.invoke', { ...SESSION.plot, input: { title: 'x', labels: ['a'], values: [1, 2] } });
  assert.equal(bad.error.code, -32010);
  assert.equal(bad.error.data.code, 'invalid_input');
  const unknown = await host.request('action.invoke', { ...SESSION.plot, action: 'start' });
  assert.equal(unknown.error.data.code, 'unknown_action');
  assert.equal(await host.close(), 0);
});

test('$/cancel stops a plot mid-upload and answers -32800; the late work sends nothing', async () => {
  const host = await ready(new FakeHost());
  host.serve = function (method, params) {
    if (method === 'host.putAsset.begin') {
      host.notify('$/cancel', { id: host.nextId });
    }
    return FakeHost.prototype.serve.call(this, method, params);
  };
  const answer = await host.request('action.invoke', SESSION.plot);
  assert.deepEqual(answer.error, { code: -32800, message: 'action.invoke was cancelled' });
  assert.deepEqual((await host.request('plugin.health')).result, { status: 'ok' });
  assert.equal(host.emits.length, 0, 'nothing placed after the cancel');
  assert.ok(!host.transcript.some((l) => l.message.method === 'host.putAsset.chunk'), 'no chunk after the cancel');
  assert.equal(await host.close(), 0);
});

test('the whole session matches the golden transcript session-chart.jsonl and deactivate exits 0', async () => {
  const host = new FakeHost();
  const { exitCode } = await runSession(host);
  assert.equal(exitCode, 0);
  const actual = host.transcript.map(masked);
  if (process.env.UPDATE_CHART_FIXTURE) writeFileSync(FIXTURE, host.transcript.map((l) => JSON.stringify(l)).join('\n') + '\n');
  const golden = readFileSync(FIXTURE, 'utf8').split('\n').filter(Boolean).map((l) => masked(JSON.parse(l)));
  assert.deepEqual(actual, golden);
});
