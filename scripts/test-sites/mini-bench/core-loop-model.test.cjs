const {test} = require('node:test');
const assert = require('node:assert/strict');
const {messageFor, SETTLE_REPLY, TOKEN_INTERVAL_MS} = require('./model.cjs');
for (const scenario of ['S1', 'S3', 'S4']) {
  test(`${scenario} bypasses held tool and returns fixed multi-paragraph reply`, () => {
    const reply = messageFor([{role:'user', content:`[core-loop:${scenario}] run-1`}], ['bench_wait']);
    assert.equal(reply.tool_calls, undefined);
    assert.equal(typeof SETTLE_REPLY, 'string');
    assert.equal(reply.content, SETTLE_REPLY);
    assert.equal(reply.content.split('\n\n').length, 3);
    assert.equal(TOKEN_INTERVAL_MS, 60);
  });
}
test('S3 follow-up is selected by latest user message, even after tool history', () => {
  const reply = messageFor([{role:'user',content:'[core-loop:S2]'}, {role:'tool',content:'ok'},
    {role:'user',content:'[core-loop:S3] follow-up'}], ['bench_wait']);
  assert.equal(reply.tool_calls, undefined);
  assert.equal(typeof reply.content, 'string');
  assert.equal(reply.content, SETTLE_REPLY);
});
test('S2 keeps the existing held-tool path', () => {
  assert.equal(messageFor([{role:'user',content:'[core-loop:S2]'}], ['bench_wait']).tool_calls[0].function.name, 'bench_wait');
  assert.equal(messageFor([{role:'user',content:'[core-loop:S2]'}, {role:'tool',content:'ok'}], ['bench_wait']).content, 'DONE');
});
