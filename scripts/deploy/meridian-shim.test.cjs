#!/usr/bin/env node
// Tests for meridian-shim.cjs (letta-mobile-jna0o.5): `node --test scripts/deploy/meridian-shim.test.cjs`.
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const test = require('node:test');

const SHIM = path.join(__dirname, 'meridian-shim.cjs');

function socketPath(name) {
  return process.platform === 'win32'
    ? `\\\\.\\pipe\\meridian-shim-test-${process.pid}-${name}`
    : path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'mshim-')), 'tools.sock');
}

/** A fake wrapper endpoint: records each request line and answers with `reply(request)`. */
function fakeEndpoint(listenArg, reply) {
  const requests = [];
  const server = net.createServer((socket) => {
    let buffer = '';
    socket.on('data', (chunk) => {
      buffer += chunk.toString('utf8');
      const newline = buffer.indexOf('\n');
      if (newline < 0) return;
      const request = JSON.parse(buffer.slice(0, newline));
      requests.push(request);
      socket.end(`${JSON.stringify(reply(request))}\n`);
    });
  });
  return new Promise((resolve) => {
    server.listen(listenArg, () => resolve({ server, requests }));
  });
}

function runShim(args, { env = {}, input, keepStdinOpen = false } = {}) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [SHIM, ...args], {
      env: { PATH: process.env.PATH, MERIDIAN_ENDPOINT_FILE: path.join(os.tmpdir(), 'no-such-endpoint'), ...env },
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (d) => { stdout += d; });
    child.stderr.on('data', (d) => { stderr += d; });
    child.on('close', (code) => resolve({ code, stdout, stderr }));
    if (input !== undefined) child.stdin.end(input);
    else if (!keepStdinOpen) child.stdin.end();
  });
}

test('forwards argv, stdin and the caller env, and prints the host answer', async () => {
  const sock = socketPath('forward');
  const { server, requests } = await fakeEndpoint(sock, () => ({ exit_code: 2, stdout: '{"error":"invalid_input"}', stderr: 'See: meridian canvas compose --help\n' }));
  try {
    const result = await runShim(['canvas', 'compose', '--dry-run'], {
      env: { MERIDIAN_SOCKET: sock, LETTA_AGENT_ID: 'agent-1', LETTA_CONVERSATION_ID: 'conv-1' },
      input: '{"items":[]}',
    });
    assert.equal(result.code, 2);
    assert.equal(result.stdout, '{"error":"invalid_input"}\n');
    assert.match(result.stderr, /canvas compose --help/);
    assert.deepEqual(requests[0], {
      protocol: 'meridian/tools/1',
      argv: ['canvas', 'compose', '--dry-run'],
      agent_id: 'agent-1',
      conversation_id: 'conv-1',
      stdin: '{"items":[]}',
    });
  } finally {
    server.close();
  }
});

test('reads --input-file itself and sends it as stdin', async () => {
  const sock = socketPath('file');
  const input = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'mshim-in-')), 'ops.json');
  fs.writeFileSync(input, '{"ops":[]}');
  const { server, requests } = await fakeEndpoint(sock, () => ({ exit_code: 0, stdout: '{"ok":true}', stderr: '' }));
  try {
    const result = await runShim(['canvas', 'apply-ops', '--input-file', input], { env: { MERIDIAN_SOCKET: sock } });
    assert.equal(result.code, 0);
    assert.deepEqual(requests[0].argv, ['canvas', 'apply-ops']);
    assert.equal(requests[0].stdin, '{"ops":[]}');
  } finally {
    server.close();
  }
});

test('an unreachable host is exit 4 with a structured error and a retry hint', async () => {
  const result = await runShim(['canvas', 'list'], { env: { MERIDIAN_SOCKET: socketPath('absent') } });
  assert.equal(result.code, 4);
  assert.equal(JSON.parse(result.stdout).error, 'host_unavailable');
  assert.match(result.stderr, /retry/);
});

test('uses the loopback TCP descriptor and its token when there is no socket', async () => {
  const { server, requests } = await fakeEndpoint({ host: '127.0.0.1', port: 0 }, () => ({ exit_code: 0, stdout: 'help text\n', stderr: '' }));
  const descriptor = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'mshim-ep-')), 'tools.endpoint');
  fs.writeFileSync(descriptor, JSON.stringify({ protocol: 'meridian/tools/1', transport: 'tcp', host: '127.0.0.1', port: server.address().port, token: 't0k' }));
  try {
    const result = await runShim(['--help'], { env: { MERIDIAN_ENDPOINT_FILE: descriptor } });
    assert.equal(result.code, 0);
    assert.equal(result.stdout, 'help text\n');
    assert.equal(requests[0].token, 't0k');
    assert.equal(requests[0].stdin, undefined);
  } finally {
    server.close();
  }
});

test('a stdin pipe left open and silent does not hang the call', async () => {
  const sock = socketPath('dangling');
  const { server, requests } = await fakeEndpoint(sock, () => ({ exit_code: 0, stdout: '{}', stderr: '' }));
  try {
    const result = await runShim(['canvas', 'scene'], { env: { MERIDIAN_SOCKET: sock, MERIDIAN_STDIN_WAIT_MS: '200' }, keepStdinOpen: true });
    assert.equal(result.code, 0);
    assert.equal(requests[0].stdin, undefined);
  } finally {
    server.close();
  }
});
