#!/usr/bin/env node
// Meridian App Server liveness probe (added 2026-08-23, stall diagnostics).
//
// Times a full round trip on the App Server WS: TCP connect -> HTTP upgrade ->
// app_server_info command -> first response byte. Prints one line:
//
//   OK <total_ms> connect=<ms> upgrade=<ms> reply=<ms>
//   FAIL <total_ms> <reason>
//
// Exit 0 on a reply within the deadline, 1 otherwise.
//
// Deliberately implements RFC6455 against bare net/crypto rather than requiring
// the `ws` module: the only copy on this host lives under a version-pinned
// /root/letta-code-<version>/node_modules path that moves on every upgrade, and
// a diagnostic must not break when the thing it diagnoses is upgraded.

const net = require('net');
const crypto = require('crypto');

const HOST = process.env.PROBE_HOST || '127.0.0.1';
const PORT = parseInt(process.env.PROBE_PORT || '4500', 10);
const DEADLINE_MS = parseInt(process.env.PROBE_DEADLINE_MS || '10000', 10);

const t0 = Date.now();
const ms = () => Date.now() - t0;
let tConnect = -1;
let tUpgrade = -1;
let settled = false;

function finish(ok, detail) {
  if (settled) return;
  settled = true;
  clearTimeout(timer);
  try { sock.destroy(); } catch (_) {}
  if (ok) {
    console.log(`OK ${ms()} connect=${tConnect} upgrade=${tUpgrade} reply=${ms()}`);
    process.exit(0);
  } else {
    console.log(`FAIL ${ms()} ${detail}`);
    process.exit(1);
  }
}

const timer = setTimeout(
  () => finish(false, `timeout_after_${DEADLINE_MS}ms connect=${tConnect} upgrade=${tUpgrade}`),
  DEADLINE_MS,
);

// Client->server frames MUST be masked (RFC6455 5.3). Text frame, opcode 0x1.
function maskedTextFrame(payload) {
  const body = Buffer.from(payload, 'utf8');
  const mask = crypto.randomBytes(4);
  let header;
  if (body.length < 126) {
    header = Buffer.from([0x81, 0x80 | body.length]);
  } else if (body.length < 65536) {
    header = Buffer.alloc(4);
    header[0] = 0x81; header[1] = 0x80 | 126;
    header.writeUInt16BE(body.length, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x81; header[1] = 0x80 | 127;
    header.writeBigUInt64BE(BigInt(body.length), 2);
  }
  const masked = Buffer.allocUnsafe(body.length);
  for (let i = 0; i < body.length; i++) masked[i] = body[i] ^ mask[i & 3];
  return Buffer.concat([header, mask, masked]);
}

const key = crypto.randomBytes(16).toString('base64');
const sock = net.connect(PORT, HOST);
sock.setNoDelay(true);

sock.on('connect', () => {
  tConnect = ms();
  sock.write(
    `GET / HTTP/1.1\r\nHost: ${HOST}:${PORT}\r\nUpgrade: websocket\r\n` +
    `Connection: Upgrade\r\nSec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n\r\n`,
  );
});

let buf = Buffer.alloc(0);
let upgraded = false;

sock.on('data', (chunk) => {
  buf = Buffer.concat([buf, chunk]);
  if (!upgraded) {
    const end = buf.indexOf('\r\n\r\n');
    if (end === -1) return;
    const head = buf.subarray(0, end).toString('latin1');
    if (!/^HTTP\/1\.1 101/.test(head)) {
      return finish(false, `upgrade_rejected ${head.split('\r\n')[0]}`);
    }
    upgraded = true;
    tUpgrade = ms();
    buf = buf.subarray(end + 4);
    sock.write(maskedTextFrame(JSON.stringify({
      type: 'app_server_info',
      request_id: `stall-probe-${process.pid}-${Date.now()}`,
    })));
    // Any server frame that arrives after this point is the reply we timed.
    if (buf.length > 0) finish(true);
    return;
  }
  if (buf.length > 0) finish(true);
});

sock.on('error', (e) => finish(false, `socket_error ${e.code || e.message}`));
sock.on('close', () => finish(false, 'closed_before_reply'));
