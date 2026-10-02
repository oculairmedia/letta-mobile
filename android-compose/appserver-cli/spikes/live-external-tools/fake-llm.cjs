// Spike: a fake OpenAI-compatible endpoint that records the tool names each model request carries.
const http = require('http');
const fs = require('fs');
const LOG = process.env.SPIKE_LLM_LOG;
let n = 0;
http.createServer((req, res) => {
  let body = '';
  req.on('data', c => body += c);
  req.on('end', () => {
    if (req.url.endsWith('/models')) {
      res.writeHead(200, { 'content-type': 'application/json' });
      return res.end(JSON.stringify({ object: 'list', data: [{ id: 'spike-model', object: 'model', owned_by: 'spike' }] }));
    }
    let parsed = {}; try { parsed = JSON.parse(body); } catch {}
    const tools = (parsed.tools || []).map(t => t.function ? t.function.name : t.name).filter(x => /^spike_/.test(x));
    const msgs = parsed.messages || [];
    const last = msgs[msgs.length - 1] || {};
    n++;
    fs.appendFileSync(LOG, JSON.stringify({ n, url: req.url, stream: !!parsed.stream, spikeTools: tools, lastRole: last.role }) + '\n');
    const callTool = last.role !== 'tool' && tools.includes('spike_tool_b');
    const id = 'chatcmpl-' + n;
    if (parsed.stream) {
      res.writeHead(200, { 'content-type': 'text/event-stream' });
      const chunk = (delta, finish) => res.write('data: ' + JSON.stringify({ id, object: 'chat.completion.chunk', created: 0, model: 'spike-model', choices: [{ index: 0, delta, finish_reason: finish ?? null }] }) + '\n\n');
      if (callTool) {
        chunk({ role: 'assistant', tool_calls: [{ index: 0, id: 'call_spike_' + n, type: 'function', function: { name: 'spike_tool_b', arguments: '{}' } }] });
        chunk({}, 'tool_calls');
      } else {
        chunk({ role: 'assistant', content: 'DONE' });
        chunk({}, 'stop');
      }
      res.write('data: [DONE]\n\n');
      return res.end();
    }
    res.writeHead(200, { 'content-type': 'application/json' });
    const message = callTool
      ? { role: 'assistant', content: null, tool_calls: [{ id: 'call_spike_' + n, type: 'function', function: { name: 'spike_tool_b', arguments: '{}' } }] }
      : { role: 'assistant', content: 'DONE' };
    res.end(JSON.stringify({ id, object: 'chat.completion', created: 0, model: 'spike-model', choices: [{ index: 0, message, finish_reason: callTool ? 'tool_calls' : 'stop' }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }));
  });
}).listen(4610, '127.0.0.1', () => console.log('fake llm on 4610'));
