const fs = require('fs');
const WebSocket = require('/opt/letta-test-server/node_modules/ws');
const dir = '/opt/letta-test-server/bench-fixture';
const files = fs.readdirSync('/opt/letta-test-server/state/agents');
const agents = files.map(f => { try { return JSON.parse(fs.readFileSync(`/opt/letta-test-server/state/agents/${f}`, 'utf8')); } catch { return null; } }).filter(a => a?.name === 'mini-bench');
if (agents.length > 1 || (agents.length === 1 && agents[0].model !== 'ollama/bench-model')) throw Error('ambiguous or incompatible mini-bench');
const ws = new WebSocket('ws://127.0.0.1:4502');
ws.on('open', () => ws.send(JSON.stringify({type:'runtime_start',request_id:'setup',...(agents.length ? {agent_id:agents[0].id} : {create_agent:{body:{name:'mini-bench',model:'ollama/bench-model'}}}),create_conversation:{body:{}},mode:'unrestricted'})));
ws.on('message', data => {
  const m = JSON.parse(data);
  if (m.type !== 'runtime_start_response' || m.request_id !== 'setup') return;
  if (!m.success || !m.runtime) { console.error(m.error); process.exit(1); }
  fs.writeFileSync(`${dir}/scope.json`, JSON.stringify(m.runtime), {mode:0o600});
  console.log(JSON.stringify(m.runtime));
  ws.close();
});
setTimeout(() => { console.error('runtime_start timed out'); process.exit(1); }, 30000).unref();
