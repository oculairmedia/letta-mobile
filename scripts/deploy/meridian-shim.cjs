#!/usr/bin/env node
// `meridian`: the agent-side shim for the host's Meridian command surface
// (letta-mobile-jna0o.5; design docs/design/on-demand-tools-via-meridian-cli.md).
//
// Installed as /usr/local/bin/meridian on the App Server host. The agent runs it from its shell
// tool: `meridian canvas compose <<'JSON' ... JSON`. It knows no commands itself: it forwards
// argv, stdin and the caller scope to the wrapper's `meridian/tools/1` endpoint and prints what
// comes back, so help, schemas and flags always match the host (the shim is version-free).
//
// Endpoint lookup, first hit wins:
//   1. $MERIDIAN_SOCKET (a Unix socket path)
//   2. /run/meridian/tools.sock (systemd RuntimeDirectory of meridian-iroh-wrapper)
//   3. $MERIDIAN_ENDPOINT_FILE or ~/.letta/meridian/tools.endpoint: the loopback TCP fallback,
//      {"port": N, "token": "..."} written 0600 by the wrapper
//
// Exit codes: 0 ok, 2 refused input, 3 denied, 4 host unavailable (stdout {"error":"host_unavailable"}).
// No dependencies; Node 18+.
'use strict';

const fs = require('node:fs');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');

const PROTOCOL = 'meridian/tools/1';
const DEFAULT_SOCKET = '/run/meridian/tools.sock';
const EXIT_HOST_UNAVAILABLE = 4;
const EXIT_REFUSED = 2;
const STDIN_FIRST_BYTE_WAIT_MS = Number(process.env.MERIDIAN_STDIN_WAIT_MS || 2000);
const TIMEOUT_MS = Number(process.env.MERIDIAN_TIMEOUT_MS || 120000);
const MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

function errorJson(code, message) {
  return JSON.stringify({ error: code, message });
}

function finish(exitCode, stdout, stderr) {
  const done = () => process.exit(exitCode);
  if (stderr) process.stderr.write(stderr.endsWith('\n') ? stderr : `${stderr}\n`);
  if (stdout) process.stdout.write(stdout.endsWith('\n') ? stdout : `${stdout}\n`, done);
  else done();
}

function hostUnavailable(reason) {
  finish(
    EXIT_HOST_UNAVAILABLE,
    errorJson('host_unavailable', reason),
    'The Meridian host is not answering; retry in a few seconds.',
  );
}

/** `--input-file PATH` is read here, as the agent's own user, and sent as stdin. */
function takeInputFile(argv) {
  const rest = [];
  let file = null;
  for (let i = 0; i < argv.length; i += 1) {
    const word = argv[i];
    if (word === '--input-file') {
      if (i + 1 >= argv.length) return { error: '--input-file needs a path' };
      file = argv[i + 1];
      i += 1;
    } else if (word.startsWith('--input-file=')) {
      file = word.slice('--input-file='.length);
    } else {
      rest.push(word);
    }
  }
  return { argv: rest, file };
}

/**
 * stdin when it can carry input: a pipe, a heredoc or a file. A terminal or /dev/null (a
 * character device) has none. A pipe that sends nothing within the first-byte wait and stays open
 * counts as empty, so a shell that leaves stdin dangling cannot hang the call.
 */
function readStdin() {
  return new Promise((resolve) => {
    let stat;
    try {
      stat = fs.fstatSync(0);
    } catch {
      resolve(null);
      return;
    }
    if (process.stdin.isTTY || stat.isCharacterDevice()) {
      resolve(null);
      return;
    }
    const chunks = [];
    let started = false;
    const idle = setTimeout(() => {
      if (!started) {
        process.stdin.destroy();
        resolve(null);
      }
    }, STDIN_FIRST_BYTE_WAIT_MS);
    process.stdin.on('data', (chunk) => {
      started = true;
      chunks.push(chunk);
    });
    process.stdin.on('end', () => {
      clearTimeout(idle);
      resolve(Buffer.concat(chunks).toString('utf8'));
    });
    process.stdin.on('error', () => {
      clearTimeout(idle);
      resolve(chunks.length ? Buffer.concat(chunks).toString('utf8') : null);
    });
  });
}

function exists(file) {
  try {
    fs.statSync(file);
    return true;
  } catch {
    return false;
  }
}

/** Where to connect: {path} for a Unix socket, {host, port, token} for TCP, or {missing}. */
function resolveEndpoint() {
  if (process.env.MERIDIAN_SOCKET) return { path: process.env.MERIDIAN_SOCKET };
  if (exists(DEFAULT_SOCKET)) return { path: DEFAULT_SOCKET };
  const file = process.env.MERIDIAN_ENDPOINT_FILE || path.join(os.homedir(), '.letta', 'meridian', 'tools.endpoint');
  let descriptor;
  try {
    descriptor = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (error) {
    return { missing: `no meridian endpoint: ${DEFAULT_SOCKET} is absent and ${file} is ${error.code === 'ENOENT' ? 'absent' : 'unreadable'}` };
  }
  if (!Number.isInteger(descriptor.port) || typeof descriptor.token !== 'string') {
    return { missing: `${file} is not a meridian endpoint descriptor` };
  }
  return { host: '127.0.0.1', port: descriptor.port, token: descriptor.token };
}

function call(endpoint, request) {
  return new Promise((resolve) => {
    const socket = endpoint.path
      ? net.createConnection({ path: endpoint.path })
      : net.createConnection({ host: endpoint.host, port: endpoint.port });
    const chunks = [];
    let size = 0;
    let settled = false;
    const settle = (value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      socket.destroy();
      resolve(value);
    };
    const timer = setTimeout(() => settle({ unavailable: `no answer within ${TIMEOUT_MS} ms` }), TIMEOUT_MS);
    socket.on('connect', () => socket.write(`${JSON.stringify(request)}\n`));
    socket.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_RESPONSE_BYTES) {
        settle({ unavailable: 'the host answer is too large' });
        return;
      }
      chunks.push(chunk);
      const text = Buffer.concat(chunks).toString('utf8');
      const newline = text.indexOf('\n');
      if (newline >= 0) settle({ line: text.slice(0, newline) });
    });
    socket.on('end', () => {
      const text = Buffer.concat(chunks).toString('utf8');
      settle(text ? { line: text } : { unavailable: 'the host closed the connection without an answer' });
    });
    socket.on('error', (error) => settle({ unavailable: `${error.code || 'error'}: ${error.message}` }));
  });
}

async function main() {
  const parsed = takeInputFile(process.argv.slice(2));
  if (parsed.error) {
    finish(EXIT_REFUSED, errorJson('usage', parsed.error), 'See: meridian --help');
    return;
  }
  let stdin = await readStdin();
  if (parsed.file !== null) {
    if (stdin && stdin.trim()) {
      finish(EXIT_REFUSED, errorJson('usage', 'give the input on stdin or with --input-file, not both'), '');
      return;
    }
    try {
      stdin = fs.readFileSync(parsed.file, 'utf8');
    } catch (error) {
      finish(EXIT_REFUSED, errorJson('invalid_input', `cannot read --input-file ${parsed.file}: ${error.code || error.message}`), '');
      return;
    }
  }
  const endpoint = resolveEndpoint();
  if (endpoint.missing) {
    hostUnavailable(endpoint.missing);
    return;
  }
  const request = {
    protocol: PROTOCOL,
    argv: parsed.argv,
    agent_id: process.env.LETTA_AGENT_ID || null,
    conversation_id: process.env.LETTA_CONVERSATION_ID || null,
  };
  if (stdin) request.stdin = stdin;
  if (endpoint.token) request.token = endpoint.token;
  const answer = await call(endpoint, request);
  if (answer.unavailable) {
    hostUnavailable(answer.unavailable);
    return;
  }
  let response;
  try {
    response = JSON.parse(answer.line);
  } catch {
    hostUnavailable('the host answered something that is not meridian/tools/1');
    return;
  }
  finish(Number.isInteger(response.exit_code) ? response.exit_code : EXIT_HOST_UNAVAILABLE, response.stdout || '', response.stderr || '');
}

main().catch((error) => hostUnavailable(`meridian shim failed: ${error && error.message ? error.message : error}`));
