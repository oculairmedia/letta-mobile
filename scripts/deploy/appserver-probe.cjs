#!/usr/bin/env node
// Meridian App Server liveness probe (added 2026-08-23, stall diagnostics).
//
// Times a correlated command round trip on the App Server WS: TCP connect ->
// validated HTTP upgrade -> app_server_info command -> matching JSON response.
// Prints one line:
//
//   OK <total_ms> connect=<ms_from_start> upgrade=<ms_from_start> reply=<ms_from_upgrade>
//   FAIL <total_ms> <reason>
//
// Exit 0 on a matching app_server_info_response within the deadline, 1 otherwise.
//
// Deliberately implements RFC6455 against bare net/crypto rather than requiring
// the `ws` module: the only copy on this host lives under a version-pinned
// /root/letta-code-<version>/node_modules path that moves on every upgrade, and
// a diagnostic must not break when the thing it diagnoses is upgraded.

'use strict';

const net = require('net');
const crypto = require('crypto');
const { TextDecoder } = require('util');

const HOST = process.env.PROBE_HOST || '127.0.0.1';
const PORT = parseInt(process.env.PROBE_PORT || '4500', 10);
const DEADLINE_MS = parseInt(process.env.PROBE_DEADLINE_MS || '10000', 10);
const REQUEST_ID = `stall-probe-${process.pid}-${Date.now()}`;

const startedAt = Date.now();
const elapsed = () => Date.now() - startedAt;
let connectedAt = -1;
let upgradeElapsed = -1;
let upgradeStartedAt = -1;
let settled = false;
let timer;

const key = crypto.randomBytes(16).toString('base64');
const expectedAccept = crypto
  .createHash('sha1')
  .update(`${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`, 'ascii')
  .digest('base64');
const sock = net.connect(PORT, HOST);
sock.setNoDelay(true);

function finish(ok, detail) {
  if (settled) return;
  settled = true;
  clearTimeout(timer);
  const total = elapsed();
  try { sock.destroy(); } catch (_) {}
  if (ok) {
    console.log(
      `OK ${total} connect=${connectedAt} upgrade=${upgradeElapsed} reply=${detail}`,
    );
    process.exit(0);
  } else {
    console.log(`FAIL ${total} ${detail}`);
    process.exit(1);
  }
}

timer = setTimeout(
  () => finish(
    false,
    `timeout_after_${DEADLINE_MS}ms connect=${connectedAt} upgrade=${upgradeElapsed}`,
  ),
  DEADLINE_MS,
);

// Client-to-server frames MUST be masked (RFC6455 section 5.3).
function maskedFrame(opcode, payload) {
  const body = Buffer.isBuffer(payload) ? payload : Buffer.from(payload, 'utf8');
  const mask = crypto.randomBytes(4);
  let header;
  if (body.length < 126) {
    header = Buffer.from([0x80 | opcode, 0x80 | body.length]);
  } else if (body.length < 65536) {
    header = Buffer.alloc(4);
    header[0] = 0x80 | opcode;
    header[1] = 0x80 | 126;
    header.writeUInt16BE(body.length, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x80 | opcode;
    header[1] = 0x80 | 127;
    header.writeBigUInt64BE(BigInt(body.length), 2);
  }
  const masked = Buffer.allocUnsafe(body.length);
  for (let i = 0; i < body.length; i++) masked[i] = body[i] ^ mask[i & 3];
  return Buffer.concat([header, mask, masked]);
}

function parseUpgrade(head) {
  const lines = head.split('\r\n');
  if (!/^HTTP\/1\.[01] 101(?:\s|$)/.test(lines[0])) {
    finish(false, `upgrade_rejected ${lines[0]}`);
    return false;
  }

  const headers = new Map();
  for (const line of lines.slice(1)) {
    const colon = line.indexOf(':');
    if (colon <= 0) continue;
    headers.set(line.slice(0, colon).trim().toLowerCase(), line.slice(colon + 1).trim());
  }
  const upgrade = (headers.get('upgrade') || '').toLowerCase();
  const connection = (headers.get('connection') || '')
    .toLowerCase()
    .split(',')
    .map((value) => value.trim());
  if (upgrade !== 'websocket' || !connection.includes('upgrade')) {
    finish(false, 'upgrade_invalid_headers');
    return false;
  }
  if (headers.get('sec-websocket-accept') !== expectedAccept) {
    finish(false, 'upgrade_invalid_accept');
    return false;
  }
  return true;
}

const utf8 = new TextDecoder('utf-8', { fatal: true });
let buf = Buffer.alloc(0);
let upgraded = false;
let fragmentedText = null;

function handleText(body) {
  let text;
  try {
    text = utf8.decode(body);
  } catch (_) {
    return finish(false, 'response_invalid_utf8');
  }

  let message;
  try {
    message = JSON.parse(text);
  } catch (_) {
    return finish(false, 'response_invalid_json');
  }
  if (!message || typeof message !== 'object' || Array.isArray(message)) {
    return finish(false, 'response_not_object');
  }
  if (message.request_id !== REQUEST_ID) return;
  if (message.type !== 'app_server_info_response') {
    return finish(false, `response_unexpected_type ${String(message.type)}`);
  }
  finish(true, Date.now() - upgradeStartedAt);
}

function consumeFrames() {
  while (!settled && buf.length >= 2) {
    const first = buf[0];
    const second = buf[1];
    const fin = (first & 0x80) !== 0;
    const opcode = first & 0x0f;
    if ((first & 0x70) !== 0) return finish(false, 'frame_rsv_not_zero');
    if ((second & 0x80) !== 0) return finish(false, 'frame_server_masked');

    let offset = 2;
    let length = second & 0x7f;
    if (length === 126) {
      if (buf.length < 4) return;
      length = buf.readUInt16BE(2);
      offset = 4;
    } else if (length === 127) {
      if (buf.length < 10) return;
      const wideLength = buf.readBigUInt64BE(2);
      if (wideLength > BigInt(Number.MAX_SAFE_INTEGER)) {
        return finish(false, 'frame_too_large');
      }
      length = Number(wideLength);
      offset = 10;
    }
    if (opcode >= 0x8 && (!fin || length > 125)) {
      return finish(false, 'frame_invalid_control');
    }
    if (buf.length < offset + length) return;

    const payload = buf.subarray(offset, offset + length);
    buf = buf.subarray(offset + length);

    if (opcode === 0x8) return finish(false, 'closed_before_matching_reply');
    if (opcode === 0x9) {
      sock.write(maskedFrame(0xA, payload));
      continue;
    }
    if (opcode === 0xA) continue;
    if (opcode === 0x2) return finish(false, 'response_binary_not_supported');
    if (opcode === 0x1) {
      if (fragmentedText !== null) return finish(false, 'frame_unexpected_text');
      if (fin) {
        handleText(payload);
      } else {
        fragmentedText = [payload];
      }
      continue;
    }
    if (opcode === 0x0) {
      if (fragmentedText === null) return finish(false, 'frame_unexpected_continuation');
      fragmentedText.push(payload);
      if (fin) {
        const complete = Buffer.concat(fragmentedText);
        fragmentedText = null;
        handleText(complete);
      }
      continue;
    }
    return finish(false, `frame_unsupported_opcode_${opcode}`);
  }
}

sock.on('connect', () => {
  connectedAt = elapsed();
  sock.write(
    `GET /ws HTTP/1.1\r\nHost: ${HOST}:${PORT}\r\nUpgrade: websocket\r\n` +
    `Connection: Upgrade\r\nSec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n\r\n`,
  );
});

sock.on('data', (chunk) => {
  buf = Buffer.concat([buf, chunk]);
  if (!upgraded) {
    const end = buf.indexOf('\r\n\r\n');
    if (end === -1) return;
    const head = buf.subarray(0, end).toString('latin1');
    if (!parseUpgrade(head)) return;
    upgraded = true;
    upgradeStartedAt = Date.now();
    upgradeElapsed = elapsed();
    buf = buf.subarray(end + 4);
    sock.write(maskedFrame(0x1, JSON.stringify({
      type: 'app_server_info',
      request_id: REQUEST_ID,
    })));
  }
  consumeFrames();
});

sock.on('error', (error) => finish(false, `socket_error ${error.code || error.message}`));
sock.on('close', () => finish(false, 'closed_before_matching_reply'));
