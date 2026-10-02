// Spike (letta-mobile-s416w.27): does a repeated runtime_start replace a live runtime's external tools,
// and when does the model see the change?
// SPIKE_NODE_MODULES: the node_modules dir of the letta-code install under test (it ships `ws`).
import { createRequire } from 'module';
const WebSocket = createRequire(process.env.SPIKE_NODE_MODULES + '/')('ws');
import fs from 'fs';
const url = process.argv[2];
const REG = process.env.SPIKE_REGISTRY_LOG;
const LLM = process.env.SPIKE_LLM_LOG;
const ws = new WebSocket(url);
const pending = new Map();
let onToolRequest = null;
ws.on('message', (data) => {
  const m = JSON.parse(data.toString());
  if (m.type === 'runtime_start_response' && pending.has(m.request_id)) { pending.get(m.request_id)(m); pending.delete(m.request_id); }
  if (m.type === 'external_tool_call_request') {
    console.log('  external_tool_call_request:', m.tool_name);
    const answer = () => ws.send(JSON.stringify({ type: 'external_tool_call_response', request_id: m.request_id, result: { content: [{ type: 'text', text: `ok from ${m.tool_name}` }], is_error: false } }));
    if (onToolRequest) onToolRequest(answer); else answer();
  }
  if (m.delta && (m.delta.message_type === 'tool_return_message' || m.delta.message_type === 'error_message')) {
    console.log('  ', m.delta.message_type, JSON.stringify(m.delta.tool_return ?? m.delta.tool_returns ?? m.delta.message ?? '').slice(0, 200));
  }
});
const tool = (name) => ({ name, description: `Spike tool ${name}.`, parameters: { type: 'object', properties: {} } });
const tools = (...names) => [{ tools: names.map(tool) }];
function runtimeStart(body) {
  const request_id = `spike-${Math.random().toString(36).slice(2)}`;
  return new Promise((res) => { pending.set(request_id, res); ws.send(JSON.stringify({ type: 'runtime_start', request_id, ...body })); });
}
const lastLine = (f) => { const l = fs.existsSync(f) ? fs.readFileSync(f, 'utf8').trim().split('\n') : []; return l.length ? JSON.parse(l[l.length - 1]) : null; };
const llmSince = (k) => (fs.existsSync(LLM) ? fs.readFileSync(LLM, 'utf8').trim().split('\n').filter(Boolean).map(JSON.parse) : []).slice(k);
const llmCount = () => llmSince(0).length;
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
function turn(scope, text) {
  const done = new Promise((res) => {
    const h = (data) => { const m = JSON.parse(data.toString()); const d = m.delta; if (d && d.message_type === 'stop_reason' && d.stop_reason !== 'requires_approval') { ws.off('message', h); res(d.stop_reason); } };
    ws.on('message', h);
    setTimeout(() => res('timeout'), 90000);
  });
  ws.send(JSON.stringify({ type: 'input', runtime: scope, payload: { kind: 'create_message', messages: [{ role: 'user', content: text }] } }));
  return done;
}
async function step(label, scope, text) {
  const k = llmCount();
  const stop = await turn(scope, text);
  console.log(`${label}: stop_reason=${stop}; model requests saw:`, JSON.stringify(llmSince(k).map(r => r.spikeTools)));
}
const reattach = (scope, ext) => runtimeStart({ agent_id: scope.agent_id, conversation_id: scope.conversation_id, recover_approvals: false, force_device_status: false, ...(ext ? { external_tools: ext } : {}) });
ws.on('open', async () => {
  const r1 = await runtimeStart({ create_agent: { body: { name: 's416w27-spike', model: 'lmstudio/spike-model' } }, create_conversation: { body: {} }, mode: 'unrestricted', external_tools: tools('spike_tool_a') });
  const scope = r1.runtime;
  console.log('RS1 [a]', r1.success, r1.error ?? ''); await sleep(200); console.log('  registry:', lastLine(REG)?.names);
  await step('TURN1', scope, 'turn one');
  const r2 = await reattach(scope, tools('spike_tool_a', 'spike_tool_b'));
  console.log('RS2 [a,b] same runtime, no turn running', r2.success); await sleep(200); console.log('  registry:', lastLine(REG)?.names);
  await step('TURN2', scope, 'turn two');
  const r3 = await reattach(scope, tools('spike_tool_a'));
  console.log('RS3 [a] same runtime', r3.success); await sleep(200); console.log('  registry:', lastLine(REG)?.names);
  await step('TURN3', scope, 'turn three');
  const r4 = await reattach(scope, tools('spike_tool_a', 'spike_tool_b'));
  console.log('RS4 [a,b]', r4.success);
  // Mid-turn: when the model calls b, remove b with a runtime_start BEFORE answering, then answer.
  onToolRequest = async (answer) => {
    const r5 = await reattach(scope, tools('spike_tool_a'));
    await sleep(200);
    console.log('  RS5 [a] issued mid-turn while b call pending:', r5.success, 'registry:', lastLine(REG)?.names);
    answer();
  };
  await step('TURN4 (b removed mid-turn)', scope, 'turn four');
  onToolRequest = null;
  await step('TURN5', scope, 'turn five');
  const r6 = await reattach(scope, undefined);
  console.log('RS6 same runtime, external_tools omitted', r6.success); await sleep(200); console.log('  registry:', lastLine(REG)?.names);
  ws.close(); process.exit(0);
});

