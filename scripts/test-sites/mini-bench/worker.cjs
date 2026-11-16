const WebSocket = require('/opt/letta-test-server/node_modules/ws');
const fs = require('fs');
const path = '/opt/letta-test-server/bench-fixture';
const seconds = Number(process.env.BENCH_SECONDS || 40);
if (!Number.isInteger(seconds) || seconds < 7 || seconds > 110) throw Error('BENCH_SECONDS must be 7..110');
const log = (event, extra = {}) => fs.appendFileSync(`${path}/worker.log`, JSON.stringify({event, wallMs: Date.now(), monotonicNs: process.hrtime.bigint().toString(), ...extra}) + '\n');
const scope = JSON.parse(fs.readFileSync(`${path}/scope.json`, 'utf8'));
function connect() {
  const ws = new WebSocket('ws://127.0.0.1:4502');
  ws.on('open', () => ws.send(JSON.stringify({type:'runtime_start',request_id:'fixture',...scope,mode:'unrestricted',external_tools:[{tools:[{name:'bench_wait',description:'Wait for a benchmark',parameters:{type:'object',properties:{}},auto_background:false,timeout_ms:120000}]}]})));
  ws.on('message', data => {
    const m = JSON.parse(data);
    if (m.type === 'runtime_start_response') { log('registered', {success:m.success}); if (!m.success) ws.close(); }
    if (m.type !== 'external_tool_call_request') return;
    if (m.tool_name !== 'bench_wait' || m.runtime?.agent_id !== scope.agent_id || m.runtime?.conversation_id !== scope.conversation_id || !m.input || Object.keys(m.input).length !== 0) {
      log('rejected', {requestId:m.request_id});
      ws.send(JSON.stringify({type:'external_tool_call_response',request_id:m.request_id,result:{content:[{type:'text',text:'invalid fixture request'}],is_error:true}}));
      return;
    }
    log('start', {requestId:m.request_id, seconds});
    setTimeout(() => {
      if (ws.readyState !== WebSocket.OPEN) { log('interrupted', {requestId:m.request_id}); return; }
      ws.send(JSON.stringify({type:'external_tool_call_response',request_id:m.request_id,result:{content:[{type:'text',text:'ok'}],is_error:false}}));
      log('end', {requestId:m.request_id});
    }, seconds * 1000);
  });
  ws.on('error', error => log('connection_error', {message:error.message}));
  ws.on('close', () => { log('disconnected'); setTimeout(connect, 1000); });
}
connect();
