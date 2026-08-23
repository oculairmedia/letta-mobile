#!/usr/bin/env node
'use strict';

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const net = require('node:net');
const path = require('node:path');
const { spawn } = require('node:child_process');
const test = require('node:test');

const PROBE = path.join(__dirname, 'appserver-probe.cjs');
const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

function serverFrame(opcode, payload, fin = true) {
  const body = Buffer.isBuffer(payload) ? payload : Buffer.from(payload, 'utf8');
  let header;
  if (body.length < 126) {
    header = Buffer.from([(fin ? 0x80 : 0) | opcode, body.length]);
  } else {
    assert.ok(body.length < 65536, 'test fixture only supports 16-bit lengths');
    header = Buffer.alloc(4);
    header[0] = (fin ? 0x80 : 0) | opcode;
    header[1] = 126;
    header.writeUInt16BE(body.length, 2);
  }
  return Buffer.concat([header, body]);
}

function readClientFrame(buffer) {
  if (buffer.length < 6) return null;
  const first = buffer[0];
  const second = buffer[1];
  const opcode = first & 0x0f;
  assert.ok(opcode === 0x1 || opcode === 0xA, `unexpected client opcode ${opcode}`);
  assert.notEqual(second & 0x80, 0, 'client frame must be masked');
  let offset = 2;
  let length = second & 0x7f;
  if (length === 126) {
    if (buffer.length < 8) return null;
    length = buffer.readUInt16BE(2);
    offset = 4;
  }
  assert.notEqual(length, 127, 'test fixture does not support 64-bit lengths');
  if (buffer.length < offset + 4 + length) return null;
  const mask = buffer.subarray(offset, offset + 4);
  const payload = Buffer.alloc(length);
  for (let i = 0; i < length; i++) {
    payload[i] = buffer[offset + 4 + i] ^ mask[i & 3];
  }
  return {
    opcode,
    message: opcode === 0x1 ? JSON.parse(payload.toString('utf8')) : null,
    rest: buffer.subarray(offset + 4 + length),
  };
}

async function createFixture({ invalidAccept = false, afterUpgrade, onRequest }) {
  const sockets = new Set();
  const server = net.createServer((socket) => {
    sockets.add(socket);
    socket.on('close', () => sockets.delete(socket));
    let input = Buffer.alloc(0);
    let upgraded = false;

    socket.on('data', (chunk) => {
      input = Buffer.concat([input, chunk]);
      if (!upgraded) {
        const end = input.indexOf('\r\n\r\n');
        if (end === -1) return;
        const requestHead = input.subarray(0, end).toString('latin1');
        assert.match(requestHead, /^GET \/ws HTTP\/1\.1\r\n/);
        const keyMatch = requestHead.match(/\r\nSec-WebSocket-Key:\s*([^\r\n]+)/i);
        assert.ok(keyMatch, 'upgrade request must include Sec-WebSocket-Key');
        const accept = invalidAccept
          ? 'invalid-accept'
          : crypto.createHash('sha1').update(`${keyMatch[1].trim()}${GUID}`).digest('base64');
        socket.write(
          'HTTP/1.1 101 Switching Protocols\r\n' +
          'Upgrade: websocket\r\n' +
          'Connection: keep-alive, Upgrade\r\n' +
          `Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
        );
        upgraded = true;
        input = input.subarray(end + 4);
        if (afterUpgrade) afterUpgrade(socket);
      }

      let frame;
      while ((frame = readClientFrame(input)) !== null) {
        input = frame.rest;
        if (frame.opcode === 0x1 && onRequest) onRequest(socket, frame.message);
      }
    });
  });

  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  return {
    port: server.address().port,
    close: async () => {
      for (const socket of sockets) socket.destroy();
      await new Promise((resolve) => server.close(resolve));
    },
  };
}

function runProbe(port, deadline = 1000) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [PROBE], {
      env: {
        ...process.env,
        PROBE_HOST: '127.0.0.1',
        PROBE_PORT: String(port),
        PROBE_DEADLINE_MS: String(deadline),
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (chunk) => { stdout += chunk; });
    child.stderr.on('data', (chunk) => { stderr += chunk; });
    child.once('error', reject);
    child.once('close', (code) => resolve({ code, stdout: stdout.trim(), stderr }));
  });
}

test('waits for the correlated, fragmented JSON response and times from upgrade', async (t) => {
  const fixture = await createFixture({
    afterUpgrade(socket) {
      socket.write(serverFrame(0x1, JSON.stringify({
        type: 'app_server_info_response',
        request_id: 'another-client',
      })));
      socket.write(serverFrame(0x9, 'ping'));
    },
    onRequest(socket, request) {
      assert.equal(request.type, 'app_server_info');
      assert.match(request.request_id, /^stall-probe-/);
      const response = Buffer.from(JSON.stringify({
        type: 'app_server_info_response',
        request_id: request.request_id,
        version: 'test',
        details: 'x'.repeat(200),
      }));
      const split = Math.floor(response.length / 2);
      const first = serverFrame(0x1, response.subarray(0, split), false);
      const second = serverFrame(0x0, response.subarray(split), true);
      socket.write(first.subarray(0, 1));
      setTimeout(() => socket.write(first.subarray(1)), 5);
      setTimeout(() => socket.write(second), 55);
    },
  });
  t.after(() => fixture.close());

  const result = await runProbe(fixture.port);
  assert.equal(result.code, 0, result.stderr || result.stdout);
  const match = result.stdout.match(
    /^OK (\d+) connect=(\d+) upgrade=(\d+) reply=(\d+)$/,
  );
  assert.ok(match, `unexpected output: ${result.stdout}`);
  const [, total, connect, upgrade, reply] = match.map(Number);
  assert.ok(connect <= upgrade, `connect=${connect}, upgrade=${upgrade}`);
  assert.ok(reply >= 40, `probe accepted an uncorrelated frame: reply=${reply}`);
  assert.ok(reply < total, `reply must be relative to upgrade: reply=${reply}, total=${total}`);
});

test('rejects an upgrade with the wrong Sec-WebSocket-Accept', async (t) => {
  const fixture = await createFixture({ invalidAccept: true });
  t.after(() => fixture.close());

  const result = await runProbe(fixture.port);
  assert.equal(result.code, 1, result.stderr || result.stdout);
  assert.match(result.stdout, /^FAIL \d+ upgrade_invalid_accept$/);
});

test('rejects malformed JSON instead of treating any server bytes as a reply', async (t) => {
  const fixture = await createFixture({
    onRequest(socket) {
      socket.write(serverFrame(0x1, '{not-json'));
    },
  });
  t.after(() => fixture.close());

  const result = await runProbe(fixture.port);
  assert.equal(result.code, 1, result.stderr || result.stdout);
  assert.match(result.stdout, /^FAIL \d+ response_invalid_json$/);
});
