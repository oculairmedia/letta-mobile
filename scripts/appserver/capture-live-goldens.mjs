#!/usr/bin/env node
// Captures App Server conformance goldens from a LIVE server with the official TS helper
// (@letta-ai/letta-code/app-server-client) and appends them to the Kotlin golden fixture.
//
//   npm install --prefix /tmp/lc @letta-ai/letta-code@<pinned> ws
//   NODE_PATH=/tmp/lc/node_modules node scripts/appserver/capture-live-goldens.mjs \
//     --capture standard|strict_approval|model_error --agent probe --out <fixture.jsonl> [--model lmstudio/MiniMax-M3]
//
// - Uses a throwaway agent (default `probe`) and creates a fresh conversation; it runs real LLM
//   turns, so pick a fast model (MiniMax-M3). `--model` is applied with agent_update first and is
//   not recorded. `model_error` expects the agent's model to be invalid at the provider.
// - `strict_approval` switches the device to strict with change_device_state so the Bash call
//   raises a control_request, denies it, then restores standard mode.
// - Sanitises before writing: the home directory becomes /home/meridian, credential-looking keys
//   are redacted, and device_status.current_available_skills is trimmed to two entries (marked).
import { appendFileSync } from "node:fs";
import { homedir } from "node:os";
import { request as httpRequest } from "node:http";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { AppServerClient } = require("@letta-ai/letta-code/app-server-client");
const { WebSocket } = require("ws");

const arg = (name, fallback) => {
  const index = process.argv.indexOf(`--${name}`);
  return index >= 0 ? process.argv[index + 1] : fallback;
};
const CAPTURE = arg("capture", "standard");
const AGENT = arg("agent", "probe");
const OUT = arg("out");
const MODEL = arg("model");
const BASE = arg("url", "http://127.0.0.1:4500");
if (!OUT) throw new Error("--out <fixture.jsonl> is required");

const rows = [];
let scenario = "connect";
const settle = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function http(path) {
  const res = await fetch(BASE + path);
  const text = await res.text();
  let body;
  try { body = JSON.parse(text); } catch { body = text.slice(0, 200); }
  rows.push({ scenario, direction: "http", request: { method: "GET", path }, status: res.status, body });
}

async function legacyChannelProbe() {
  const url = new URL(`${BASE}/ws?channel=stream`);
  const result = await new Promise((resolve, reject) => {
    const req = httpRequest({
      host: url.hostname, port: url.port, path: url.pathname + url.search,
      headers: { Connection: "Upgrade", Upgrade: "websocket", "Sec-WebSocket-Version": "13", "Sec-WebSocket-Key": "dGhlIHNhbXBsZSBub25jZQ==" },
    });
    req.on("response", (res) => { let body = ""; res.on("data", (d) => (body += d)); res.on("end", () => resolve({ status: res.statusCode, body })); });
    req.on("upgrade", (res, socket) => { socket.destroy(); resolve({ status: res.statusCode, body: "upgraded" }); });
    req.on("error", reject);
    req.end();
  });
  rows.push({ scenario, direction: "http", request: { method: "GET", path: "/ws?channel=stream", upgrade: "websocket" }, status: result.status, body: result.body.slice(0, 200) });
}

scenario = "http_discovery";
await http("/readyz");
await http("/healthz");
await http("/app-server-info");
scenario = "legacy_channel_rejected";
await legacyChannelProbe();

const client = new AppServerClient({ url: BASE.replace(/^http/, "ws"), WebSocket, requestTimeoutMs: 60_000 });
const waiters = [];
let recording = true;
client.onSend((command) => { if (recording) rows.push({ scenario, direction: "client_to_server", frame: command }); });
client.onMessage((message) => {
  if (recording) rows.push({ scenario, direction: "server_to_client", frame: message });
  for (const waiter of [...waiters]) if (waiter.match(message)) { waiters.splice(waiters.indexOf(waiter), 1); waiter.resolve(message); }
});
const waitFor = (match, ms = 180_000) => new Promise((resolve, reject) => {
  waiters.push({ match, resolve });
  setTimeout(() => reject(new Error(`timeout in ${scenario}`)), ms);
});
await client.connect();

scenario = "app_server_info";
await client.info();

if (MODEL) {
  recording = false; // harness setup on the throwaway agent, not protocol under test
  await client.request({ type: "agent_update", request_id: client.nextRequestId("agent-update"), agent_id: AGENT, body: { model: MODEL } });
  recording = true;
}

scenario = "runtime_start";
const { runtime } = await client.runtimeStart({
  agent_id: AGENT, cwd: "/tmp", mode: "standard", client_info: { name: "letta-mobile-golden", version: "capture" },
  recover_approvals: true, force_device_status: true,
});
await settle(1500);

const turn = async (name, content, id) => {
  scenario = name;
  client.send({ type: "input", request_id: client.nextRequestId("input"), runtime, payload: { kind: "create_message", messages: [{ role: "user", content, client_message_id: id }] } });
  const first = await waitFor((m) => m.type === "control_request" || m.type === "turn_finished");
  if (first.type === "control_request") {
    client.send({ type: "input", runtime, payload: { kind: "approval_response", request_id: first.request_id, decision: { behavior: "deny", message: "Denied by golden capture" } } });
    await waitFor((m) => m.type === "turn_finished");
  }
  await settle(1000);
  return first.type;
};

if (CAPTURE === "strict_approval") {
  scenario = "change_device_state_strict";
  client.send({ type: "change_device_state", runtime, payload: { mode: "strict" } });
  await waitFor((m) => m.type === "update_device_status" && m.device_status?.current_permission_mode === "strict", 30_000);
  const outcome = await turn("turn_control_request_denied", "Use your Bash tool to run this exact shell command: echo golden-strict", "golden-msg-3");
  if (outcome !== "control_request") throw new Error("strict mode did not raise a control_request");
  client.send({ type: "change_device_state", runtime, payload: { mode: "standard" } });
  await settle(500);
} else {
  await turn("turn_end_turn", "Reply with exactly the word: golden", "golden-msg-1");
  await turn("turn_requires_approval_denied", "Use your Bash tool to run this exact shell command: echo golden-approval", "golden-msg-2");
  scenario = "sync";
  await client.sync({ runtime, recover_approvals: true, force_device_status: false });
  await settle(500);
  scenario = "abort_idle";
  await client.request({ type: "abort_message", request_id: client.nextRequestId("abort"), runtime });
  await settle(500);
}
client.close();

const CREDENTIAL = /^(authorization|password|privatekey)$|(token|secret|apikey)$/;
function sanitise(value) {
  if (Array.isArray(value)) return value.map(sanitise);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.entries(value).map(([key, v]) =>
      [key, CREDENTIAL.test(key.toLowerCase().replace(/[^a-z0-9]/g, "")) ? "<redacted>" : sanitise(v)]));
  }
  return typeof value === "string" ? value.replaceAll(`${homedir()}/`, "/home/meridian/") : value;
}

const lines = rows.map((row) => {
  const out = { capture: CAPTURE, ...sanitise(row) };
  const skills = out.frame?.device_status?.current_available_skills;
  if (Array.isArray(skills) && skills.length > 2) {
    out.frame.device_status.current_available_skills = skills.slice(0, 2);
    out.trimmed = ["frame.device_status.current_available_skills"];
  }
  return JSON.stringify(out);
});
appendFileSync(OUT, lines.join("\n") + "\n");
console.log(`appended ${lines.length} ${CAPTURE} rows to ${OUT}; runtime=${JSON.stringify(runtime)}`);
