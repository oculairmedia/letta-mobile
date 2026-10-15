#!/usr/bin/env node
// Tests for meridian-token-hook.cjs (letta-mobile-by4vm.44):
//   node --test scripts/deploy/meridian-token-hook.test.cjs
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const { decide, issueToken } = require('./meridian-token-hook.cjs');
const { install } = require('./meridian-permission-hook.cjs');

const TOKEN = 'a'.repeat(64);
const bash = (command, id = 'call-1') => ({ event_type: 'PreToolUse', tool_name: 'Bash', tool_call_id: id, tool_input: { command } });

test('a meridian shell call gets its token exported ahead of the command', async () => {
  const asked = [];
  const result = await decide(bash("meridian canvas compose <<'JSON'\n{}\nJSON"), async (id) => { asked.push(id); return TOKEN; });

  assert.equal(result.exitCode, 0);
  assert.deepEqual(asked, ['call-1']);
  assert.deepEqual(JSON.parse(result.stdout).hookSpecificOutput.updatedInput, {
    command: `export LETTA_TOOL_TOKEN=${TOKEN}\nmeridian canvas compose <<'JSON'\n{}\nJSON`,
  });
});

test('other commands, other tools and argv arrays are left alone without asking the host', async () => {
  const issue = async () => assert.fail('the host must not be asked');
  const events = [
    bash('ls -la'),
    bash('cat meridian.txt'),
    { ...bash('meridian canvas list'), tool_name: 'Read' },
    { ...bash('meridian canvas list'), tool_input: { cmd: ['meridian', 'canvas', 'list'] } },
    { ...bash('meridian canvas list'), tool_call_id: undefined },
  ];
  for (const event of events) {
    assert.deepEqual(await decide(event, issue), { exitCode: 3, stdout: '' });
  }
});

test('no token from the host means no rewrite, never a block', async () => {
  assert.deepEqual(await decide(bash('meridian canvas list'), async () => null), { exitCode: 3, stdout: '' });
});

test('asks the endpoint for the call id and accepts only a well-formed token', async () => {
  const sock = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'mtok-')), 'tools.sock');
  const requests = [];
  let reply = { exit_code: 0, stdout: JSON.stringify({ tool_token: TOKEN }) };
  const server = net.createServer((socket) => {
    socket.once('data', (chunk) => {
      requests.push(JSON.parse(chunk.toString('utf8')));
      socket.end(`${JSON.stringify(reply)}\n`);
    });
  });
  await new Promise((resolve) => server.listen(sock, resolve));
  try {
    assert.equal(await issueToken('call-9', { path: sock }), TOKEN);
    assert.deepEqual(requests[0], { protocol: 'meridian/tools/1', issue_tool_token: 'call-9' });
    reply = { exit_code: 3, stdout: '{"error":"denied"}' };
    assert.equal(await issueToken('call-9', { path: sock }), null);
    reply = { exit_code: 0, stdout: JSON.stringify({ tool_token: 'x; rm -rf ~' }) };
    assert.equal(await issueToken('call-9', { path: sock }), null);
    assert.equal(await issueToken('call-9', { missing: 'no endpoint' }), null);
  } finally {
    server.close();
  }
});

test('registers under PreToolUse beside the PermissionRequest hook', () => {
  const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'mtok-settings-')), 'settings.json');
  const ours = { hookPath: '/usr/local/lib/meridian/meridian-token-hook.cjs', event: 'PreToolUse', marker: 'meridian-token-hook' };
  install(file, false);

  assert.equal(install(file, true, ours), 'DRIFTED');
  assert.equal(install(file, false, ours), 'installed');
  assert.equal(install(file, false, ours), 'in sync');
  const hooks = JSON.parse(fs.readFileSync(file, 'utf8')).hooks;
  assert.equal(hooks.PreToolUse.length, 1);
  assert.equal(hooks.PermissionRequest.length, 1);
  assert.match(hooks.PreToolUse[0].hooks[0].command, /meridian-token-hook\.cjs$/);
});
