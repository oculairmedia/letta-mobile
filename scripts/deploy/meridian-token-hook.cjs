#!/usr/bin/env node
// letta-code PreToolUse hook: give a shell call that runs `meridian` its per-call capability token
// (letta-mobile-by4vm.44). Installed as /usr/local/lib/meridian/meridian-token-hook.cjs, next to
// meridian-shim.cjs and meridian-permission-hook.cjs, and registered in the App Server user's
// ~/.letta/settings.json by install-meridian.sh.
//
// Why: the wrapper used to attribute a `meridian` call by the LETTA_AGENT_ID / LETTA_CONVERSATION_ID
// the shell sent, which the agent can override. Now letta-code runs this hook right before it
// executes the approved shell call; the hook asks the wrapper for the token of that tool_call_id
// (issued once, only for a `meridian` shell call the wrapper saw start) and prepends
// `export LETTA_TOOL_TOKEN=...` to the command. The shim sends the token, and the wrapper binds the
// request to that one call's App Server scope. The token dies with the call.
//
// Hook protocol (letta-code executeHooks): JSON on stdin {event_type, tool_name, tool_input,
// tool_call_id, ...}; exit 0 with {"hookSpecificOutput":{"updatedInput":{...}}} rewrites the input.
// This hook never blocks: anything it cannot do exits 3 (no opinion) and the command runs as
// written, so a `meridian` call in it is then refused by the host, as before the hook.
//
//   meridian-token-hook.cjs                          run as the hook
//   meridian-token-hook.cjs --install FILE [--check] register it in a letta-code settings.json
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { PROTOCOL, resolveEndpoint, call } = require('./meridian-shim.cjs');
const { SHELL_TOOLS, install, EXIT_ALLOW, EXIT_NO_OPINION } = require('./meridian-permission-hook.cjs');

const HOOK_MARKER = 'meridian-token-hook';
const INSTALLED_PATH = path.join('/usr/local/lib/meridian', 'meridian-token-hook.cjs');
// Same rule as MeridianShellCallSignal.runsMeridian on the host: `meridian` as the program word.
const MERIDIAN_WORD = /(?:^|[\s/;&|(`$])meridian(?:$|[\s;&|)])/;
const TOKEN = /^[0-9a-f]{64}$/;
// Under the hook's 5 s letta-code timeout; the host waits up to 2 s for the call's start frame.
const ISSUE_TIMEOUT_MS = 4000;

/** The string command of a shell call and the key it sits under, or null (an argv array is left alone). */
function commandField(input) {
  if (!input || typeof input !== 'object') return null;
  const key = input.command !== undefined ? 'command' : 'cmd';
  return typeof input[key] === 'string' ? { key, command: input[key] } : null;
}

/** Asks the host for the call's token; the token, or null when the host has none for it. */
async function issueToken(toolCallId, endpoint = resolveEndpoint()) {
  if (endpoint.missing) return null;
  const request = { protocol: PROTOCOL, issue_tool_token: toolCallId };
  if (endpoint.token) request.token = endpoint.token;
  const answer = await call(endpoint, request, ISSUE_TIMEOUT_MS);
  try {
    const response = JSON.parse(answer.line);
    const token = response.exit_code === 0 ? JSON.parse(response.stdout).tool_token : null;
    return typeof token === 'string' && TOKEN.test(token) ? token : null;
  } catch {
    return null;
  }
}

/** {exitCode, stdout} for one PreToolUse event. */
async function decide(event, issue = issueToken) {
  const none = { exitCode: EXIT_NO_OPINION, stdout: '' };
  if (!event || !SHELL_TOOLS.includes(event.tool_name) || typeof event.tool_call_id !== 'string') return none;
  const field = commandField(event.tool_input);
  if (!field || !MERIDIAN_WORD.test(field.command)) return none;
  const token = await issue(event.tool_call_id);
  if (!token) return none;
  const updatedInput = { [field.key]: `export LETTA_TOOL_TOKEN=${token}\n${field.command}` };
  return { exitCode: EXIT_ALLOW, stdout: JSON.stringify({ hookSpecificOutput: { hookEventName: 'PreToolUse', updatedInput } }) };
}

async function main(argv) {
  const at = argv.indexOf('--install');
  if (at >= 0) {
    const file = argv[at + 1];
    if (!file) {
      process.stderr.write('usage: meridian-token-hook.cjs --install SETTINGS_JSON [--check]\n');
      return 64;
    }
    const status = install(file, argv.includes('--check'), { hookPath: INSTALLED_PATH, event: 'PreToolUse', marker: HOOK_MARKER });
    process.stdout.write(`${status}\n`);
    return status === 'DRIFTED' ? 1 : 0;
  }
  let event = null;
  try {
    event = JSON.parse(fs.readFileSync(0, 'utf8'));
  } catch {
    return EXIT_NO_OPINION;
  }
  const { exitCode, stdout } = await decide(event);
  if (stdout) process.stdout.write(`${stdout}\n`);
  return exitCode;
}

if (require.main === module) {
  main(process.argv.slice(2)).then(
    (code) => { process.exitCode = code; },
    () => { process.exitCode = EXIT_NO_OPINION; },
  );
}

module.exports = { decide, issueToken, commandField };
