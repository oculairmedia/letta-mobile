// Fake OpenAI-compatible LLM for the embedded-runtime-node18 CI job (letta-mobile-by4vm.110.3).
// Each turn: when the last message is the user's and a shell-like tool is offered, answer with ONE
// tool call (exercises tool execution + child_process on Node 18); once the tool result is back (or
// with no tool offered) answer with the text NODE18-OK. Requests are logged as JSON lines to
// $FAKE_LLM_LOG, including any tool result text, so the caller can assert the tool really ran.
// usage: FAKE_LLM_PORT=4711 FAKE_LLM_LOG=<log path> node fake-llm.cjs
'use strict';
const http = require('http');
const fs = require('fs');

const port = Number(process.env.FAKE_LLM_PORT || 4711);
const LOG = process.env.FAKE_LLM_LOG;
if (!LOG) throw new Error('FAKE_LLM_LOG (the request log path) is required');
const MODEL = 'node18-model';
const SHELL_TOOLS = ['Bash', 'bash', 'shell', 'Shell', 'ShellCommand', 'run_shell_command', 'exec_command'];
const TOOL_ARGS = JSON.stringify({ command: 'echo NODE18-TOOL-$((6*7))', description: 'node18 smoke' });

let requests = 0;

function textOf(content) {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) return content.map((part) => (typeof part === 'string' ? part : part.text || '')).join('');
  return '';
}

function plan(parsed) {
  const toolNames = (parsed.tools || []).map((t) => (t.function ? t.function.name : t.name));
  const msgs = parsed.messages || [];
  const last = msgs[msgs.length - 1] || {};
  const shellTool = SHELL_TOOLS.find((t) => toolNames.includes(t)) || null;
  const toolResult = last.role === 'tool' ? textOf(last.content).slice(0, 200) : null;
  return { toolNames, lastRole: last.role, shellTool, toolResult, callTool: Boolean(shellTool) && last.role === 'user' };
}

function streamReply(res, id, step) {
  const chunk = (delta, finish) => res.write(`data: ${JSON.stringify({
    id, object: 'chat.completion.chunk', created: 0, model: MODEL,
    choices: [{ index: 0, delta, finish_reason: finish ?? null }],
  })}\n\n`);
  res.writeHead(200, { 'content-type': 'text/event-stream' });
  if (step.callTool) {
    chunk({ role: 'assistant', tool_calls: [{ index: 0, id: `call_${id}`, type: 'function', function: { name: step.shellTool, arguments: TOOL_ARGS } }] });
    chunk({}, 'tool_calls');
  } else {
    chunk({ role: 'assistant', content: 'NODE18-' });
    chunk({ content: 'OK' });
    chunk({}, 'stop');
  }
  res.write(`data: ${JSON.stringify({ id, object: 'chat.completion.chunk', created: 0, model: MODEL, choices: [], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } })}\n\n`);
  res.write('data: [DONE]\n\n');
  res.end();
}

function jsonReply(res, id, step) {
  const message = step.callTool
    ? { role: 'assistant', content: null, tool_calls: [{ id: `call_${id}`, type: 'function', function: { name: step.shellTool, arguments: TOOL_ARGS } }] }
    : { role: 'assistant', content: 'NODE18-OK' };
  res.writeHead(200, { 'content-type': 'application/json' });
  res.end(JSON.stringify({
    id, object: 'chat.completion', created: 0, model: MODEL,
    choices: [{ index: 0, message, finish_reason: step.callTool ? 'tool_calls' : 'stop' }],
    usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
  }));
}

http.createServer((req, res) => {
  let body = '';
  req.on('data', (c) => { body += c; });
  req.on('end', () => {
    if (req.url.endsWith('/models')) {
      res.writeHead(200, { 'content-type': 'application/json' });
      return res.end(JSON.stringify({ object: 'list', data: [{ id: MODEL, object: 'model', owned_by: 'ci' }] }));
    }
    let parsed = {};
    try { parsed = JSON.parse(body); } catch { /* logged below as an empty request */ }
    const step = plan(parsed);
    const id = `chatcmpl-${++requests}`;
    fs.appendFileSync(LOG, `${JSON.stringify({ n: requests, url: req.url, stream: Boolean(parsed.stream), ...step })}\n`);
    return parsed.stream ? streamReply(res, id, step) : jsonReply(res, id, step);
  });
}).listen(port, '127.0.0.1', () => console.error(`fake llm on 127.0.0.1:${port}`));
