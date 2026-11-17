const {test} = require('node:test');
const assert = require('node:assert/strict');
const {spawn} = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const net = require('node:net');
test('tool response contains no assistant text and subsequent response finishes', async () => {
  const port = await new Promise(resolve => { const s = net.createServer().listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => resolve(p)); }); });
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bench-model-'));
  const server = spawn(process.execPath, [path.join(__dirname, 'model.cjs')], {env:{...process.env, BENCH_PORT:String(port), BENCH_LOG:path.join(dir, 'model.log')}});
  try {
    await new Promise((resolve, reject) => { server.stdout.once('data', resolve); server.once('exit', reject); });
    const request = messages => fetch(`http://127.0.0.1:${port}/v1/chat/completions`, {method:'POST', body:JSON.stringify({messages,tools:[{function:{name:'bench_wait'}}]})}).then(r => r.json());
    const first = (await request([{role:'user',content:'go'}])).choices[0].message;
    assert.equal(first.content, null);
    assert.equal(first.tool_calls[0].function.name, 'bench_wait');
    assert.equal((await request([{role:'tool',content:'ok'}])).choices[0].message.content, 'DONE');
    const streamed = await fetch(`http://127.0.0.1:${port}/v1/chat/completions`, {method:'POST', body:JSON.stringify({stream:true,messages:[{role:'user',content:'go'}],tools:[{function:{name:'bench_wait'}}]})}).then(r => r.text());
    const chunks = streamed.split('\n').filter(line => line.startsWith('data: {')).map(line => JSON.parse(line.slice(6)));
    assert.ok(chunks.some(chunk => chunk.choices[0].delta.tool_calls));
    assert.ok(chunks.every(chunk => chunk.choices[0].delta.content === undefined));
    const entries = fs.readFileSync(path.join(dir, 'model.log'), 'utf8').trim().split('\n').map(JSON.parse);
    assert.equal(entries[0].assistantContent, null);
  } finally { server.kill(); fs.rmSync(dir, {recursive:true,force:true}); }
});
