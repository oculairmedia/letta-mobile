// Local model for the dedicated mini-bench agent.
const http = require('http');
const fs = require('fs');
const { randomUUID } = require('crypto');
const LOG = process.env.BENCH_LOG || '/opt/letta-test-server/bench-fixture/model.log';
const SETTLE_REPLY = [
  'The garden is quiet after the rain. Small drops remain on the leaves while the path slowly dries in the afternoon light.',
  'A warm cup of tea is waiting on the table. Take a moment to enjoy it before returning to the next task on your list.',
  'Everything requested is now complete. This final paragraph stays visible when the streaming reply becomes durable conversation history.'
].join('\n\n');
const TOKEN_INTERVAL_MS = 60;
function settleMode(msgs) {
  const prompt = msgs.findLast(m => m.role === 'user');
  return JSON.stringify(prompt?.content || '').match(/\[settle-pop\]|\[core-loop:S[134]\]/) !== null;
}
function messageFor(msgs, tools) {
  if (settleMode(msgs)) return { role: 'assistant', content: SETTLE_REPLY };
  const last = msgs[msgs.length - 1] || {};
  if (last.role !== 'tool' && tools.includes('bench_wait')) return { role: 'assistant', content: null, tool_calls: [{ id: 'call_' + randomUUID(), type: 'function', function: { name: 'bench_wait', arguments: '{}' } }] };
  return { role: 'assistant', content: settleMode(msgs) ? SETTLE_REPLY : 'DONE' };
}
module.exports = {messageFor, SETTLE_REPLY, TOKEN_INTERVAL_MS};
if (require.main === module) {
let n = 0;
http.createServer((req, res) => {
  let body = '';
  req.on('data', c => body += c);
  req.on('end', async () => {
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
    fs.appendFileSync(LOG, JSON.stringify({ n, wallMs: Date.now(), monotonicNs: process.hrtime.bigint().toString(), event: callTool ? 'tool_call' : 'reply', assistantContent: response.content }) + '\n');
    const id = 'chatcmpl-' + n;
    if (parsed.stream) {
      res.writeHead(200, { 'content-type': 'text/event-stream' });
      const chunk = (delta, finish) => res.write('data: ' + JSON.stringify({ id, object: 'chat.completion.chunk', created: 0, model: 'bench-model', choices: [{ index: 0, delta, finish_reason: finish ?? null }] }) + '\n\n');
      if (callTool) {
        chunk({ role: 'assistant', tool_calls: [{ index: 0, id: response.tool_calls[0].id, type: 'function', function: { name: 'bench_wait', arguments: '{}' } }] });
        chunk({}, 'tool_calls');
      } else {
        if (settleMode(msgs)) {
          for (const token of 'I will check the result carefully before presenting the final reply.'.match(/\S+\s*/g)) {
            chunk({ role: 'assistant', reasoning_content: token });
            await new Promise(resolve => setTimeout(resolve, TOKEN_INTERVAL_MS));
          }
          await new Promise(resolve => setTimeout(resolve, 500));
        }
        const tokens = response.content.match(/\S+\s*/g) || [];
        if (settleMode(msgs)) {
          for (const token of tokens) {
            chunk({ role: 'assistant', content: token });
            await new Promise(resolve => setTimeout(resolve, TOKEN_INTERVAL_MS));
          }
          await new Promise(resolve => setTimeout(resolve, 2000));
          fs.appendFileSync(LOG, JSON.stringify({event:'settle_reply_complete',wallMs:Date.now(),characters:response.content.length}) + '\n');
        } else chunk({ role: 'assistant', content: response.content });
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
