// Local model for the dedicated mini-bench agent.
const http = require('http');
const fs = require('fs');
const { randomUUID } = require('crypto');
const LOG = process.env.BENCH_LOG || '/opt/letta-test-server/bench-fixture/model.log';
function messageFor(msgs, tools) {
  const last = msgs[msgs.length - 1] || {};
  if (last.role !== 'tool' && tools.includes('bench_wait')) return { role: 'assistant', content: null, tool_calls: [{ id: 'call_' + randomUUID(), type: 'function', function: { name: 'bench_wait', arguments: '{}' } }] };
  return { role: 'assistant', content: 'DONE' };
}
module.exports = {messageFor};
if (require.main === module) {
let n = 0;
http.createServer((req, res) => {
  let body = '';
  req.on('data', c => body += c);
  req.on('end', () => {
    if (req.url === '/api/tags') { res.writeHead(200, {'content-type':'application/json'}); return res.end(JSON.stringify({models:[{name:'bench-model'}]})); }
    if (req.url === '/api/ps') { res.writeHead(200, {'content-type':'application/json'}); return res.end(JSON.stringify({models:[{name:'bench-model:latest',model:'bench-model:latest',context_length:128000}]})); }
    if (req.url.endsWith('/models')) {
      res.writeHead(200, { 'content-type': 'application/json' });
      return res.end(JSON.stringify({ object: 'list', data: [{ id: 'bench-model', object: 'model', owned_by: 'spike' }] }));
    }
    let parsed = {}; try { parsed = JSON.parse(body); } catch {}
    const tools = (parsed.tools || []).map(t => t.function ? t.function.name : t.name).filter(x => /^bench_/.test(x));
    const msgs = parsed.messages || [];
    n++;
    const response = messageFor(msgs, tools);
    const callTool = !!response.tool_calls;
    fs.appendFileSync(LOG, JSON.stringify({ n, wallMs: Date.now(), monotonicNs: process.hrtime.bigint().toString(), event: callTool ? 'tool_call' : 'reply', assistantContent: callTool ? null : 'DONE' }) + '\n');
    const id = 'chatcmpl-' + n;
    if (parsed.stream) {
      res.writeHead(200, { 'content-type': 'text/event-stream' });
      const chunk = (delta, finish) => res.write('data: ' + JSON.stringify({ id, object: 'chat.completion.chunk', created: 0, model: 'bench-model', choices: [{ index: 0, delta, finish_reason: finish ?? null }] }) + '\n\n');
      if (callTool) {
        chunk({ role: 'assistant', tool_calls: [{ index: 0, id: response.tool_calls[0].id, type: 'function', function: { name: 'bench_wait', arguments: '{}' } }] });
        chunk({}, 'tool_calls');
      } else {
        chunk({ role: 'assistant', content: 'DONE' });
        chunk({}, 'stop');
      }
      res.write('data: [DONE]\n\n');
      return res.end();
    }
    res.writeHead(200, { 'content-type': 'application/json' });
    const message = response;
    res.end(JSON.stringify({ id, object: 'chat.completion', created: 0, model: 'bench-model', choices: [{ index: 0, message, finish_reason: callTool ? 'tool_calls' : 'stop' }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }));
  });
}).listen(Number(process.env.BENCH_PORT || 11434), '127.0.0.1', () => console.log('fake llm ready'));

}
