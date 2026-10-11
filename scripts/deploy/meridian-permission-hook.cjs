#!/usr/bin/env node
// letta-code PermissionRequest hook: approve allow-listed `meridian` CLI calls without asking
// (letta-mobile-jna0o.7). Installed as /usr/local/lib/meridian/meridian-permission-hook.cjs and
// registered in the App Server user's ~/.letta/settings.json by install-meridian.sh.
//
// Why a hook and not a `permissions.allow` rule: letta-code's `Bash(meridian:*)` rule is a raw
// string prefix of the whole command line, so it would also allow `meridian canvas list; rm -rf ~`.
// This hook allows exactly one simple `meridian` command (optionally with ONE quoted heredoc) in
// the router's command groups, the same rule as the app's MeridianShellAllowList; both are tested
// against meridian-allowlist-vectors.json.
//
// Hook protocol (letta-code executeHooks): JSON on stdin {tool_name, tool_input, ...};
// exit 0 = allow, exit 2 = deny, anything else = no opinion (the call keeps asking). This hook
// never denies: a command it does not recognise exits 3 and letta-code asks as before.
//
//   meridian-permission-hook.cjs                       run as the hook
//   meridian-permission-hook.cjs --install FILE        register it in a letta-code settings.json
//   meridian-permission-hook.cjs --install FILE --check  report only; exit 1 when not registered
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const SHELL_TOOLS = ['Bash', 'shell', 'shell_command', 'exec_command', 'run_shell_command', 'RunShellCommand'];
const GROUPS = {
  canvas: null,
  plugin: null,
  tool: null,
  guide: null,
  schema: null,
  help: null,
  '--help': null,
  '-h': null,
  agents: ['find'],
  'agent-message': ['send'],
};
const SAFE_CHAR = /^[A-Za-z0-9_@%+=:,./-]$/;
const HEREDOC = /^<<(-?)[ \t]*(?:'([A-Za-z_][A-Za-z0-9_]*)'|"([A-Za-z_][A-Za-z0-9_]*)")[ \t]*$/;
const EXIT_ALLOW = 0;
const EXIT_NO_OPINION = 3;
const HOOK_MARKER = 'meridian-permission-hook';
const INSTALLED_PATH = '/usr/local/lib/meridian/meridian-permission-hook.cjs';

/** The shell words of `text`, or null when it holds anything but plain and safely quoted words. */
function words(text) {
  const out = [];
  let current = '';
  let inWord = false;
  for (let i = 0; i < text.length;) {
    const c = text[i];
    if (c === ' ' || c === '\t') {
      if (inWord) out.push(current);
      current = '';
      inWord = false;
      i += 1;
    } else if (c === "'" || c === '"') {
      const close = text.indexOf(c, i + 1);
      if (close < 0) return null;
      const quoted = text.slice(i + 1, close);
      if (c === '"' && /[$`\\\n]/.test(quoted)) return null;
      current += quoted;
      inWord = true;
      i = close + 1;
    } else if (SAFE_CHAR.test(c)) {
      current += c;
      inWord = true;
      i += 1;
    } else {
      return null;
    }
  }
  if (inWord) out.push(current);
  return out;
}

function heredocClosed(redirect, body) {
  const match = HEREDOC.exec(redirect.trimEnd());
  if (!match) return false;
  const stripTabs = match[1] === '-';
  const tag = match[2] || match[3];
  const end = body.findIndex((line) => (stripTabs ? line.replace(/^\t+/, '') : line) === tag);
  return end >= 0 && body.slice(end + 1).every((line) => line.trim() === '');
}

function pathAllowed(args) {
  if (args.length === 0) return true;
  if (!Object.prototype.hasOwnProperty.call(GROUPS, args[0])) return false;
  const verbs = GROUPS[args[0]];
  return verbs === null || verbs.includes(args[1]);
}

function allowsCommand(command) {
  if (typeof command !== 'string') return false;
  const lines = command.replace(/\r\n/g, '\n').trim().split('\n');
  const head = lines[0];
  const marker = head.indexOf('<<');
  const invocation = marker < 0 ? head : head.slice(0, marker);
  const bodyOk = marker < 0 ? lines.length === 1 : heredocClosed(head.slice(marker), lines.slice(1));
  const argv = words(invocation);
  return bodyOk && argv !== null && argv[0] === 'meridian' && pathAllowed(argv.slice(1));
}

function shellCommand(input) {
  if (!input || typeof input !== 'object') return null;
  const value = input.command !== undefined ? input.command : input.cmd;
  if (typeof value === 'string') return value;
  if (Array.isArray(value) && value.every((w) => typeof w === 'string')) return value.join(' ');
  return null;
}

function decide(event) {
  if (!event || !SHELL_TOOLS.includes(event.tool_name)) return EXIT_NO_OPINION;
  return allowsCommand(shellCommand(event.tool_input)) ? EXIT_ALLOW : EXIT_NO_OPINION;
}

function hookEntry(hookPath) {
  return {
    matcher: SHELL_TOOLS.join('|'),
    hooks: [{ type: 'command', command: `node ${hookPath}`, timeout: 5000 }],
  };
}

/** Registers the hook in a letta-code settings.json, keeping everything else as it is. */
function install(file, checkOnly, hookPath = INSTALLED_PATH) {
  let settings = {};
  if (fs.existsSync(file)) settings = JSON.parse(fs.readFileSync(file, 'utf8') || '{}');
  const hooks = settings.hooks || {};
  const entries = Array.isArray(hooks.PermissionRequest) ? hooks.PermissionRequest : [];
  const wanted = hookEntry(hookPath);
  const ours = entries.filter((e) => JSON.stringify(e).includes(HOOK_MARKER));
  if (ours.length === 1 && JSON.stringify(ours[0]) === JSON.stringify(wanted)) return 'in sync';
  if (checkOnly) return 'DRIFTED';
  hooks.PermissionRequest = entries.filter((e) => !JSON.stringify(e).includes(HOOK_MARKER)).concat([wanted]);
  settings.hooks = hooks;
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const temp = `${file}.meridian-tmp`;
  fs.writeFileSync(temp, `${JSON.stringify(settings, null, 2)}\n`, { mode: 0o600 });
  fs.renameSync(temp, file);
  return 'installed';
}

function readStdin() {
  try {
    return fs.readFileSync(0, 'utf8');
  } catch {
    return '';
  }
}

function main(argv) {
  const at = argv.indexOf('--install');
  if (at >= 0) {
    const file = argv[at + 1];
    if (!file) {
      process.stderr.write('usage: meridian-permission-hook.cjs --install SETTINGS_JSON [--check]\n');
      return 64;
    }
    const status = install(file, argv.includes('--check'));
    process.stdout.write(`${status}\n`);
    return status === 'DRIFTED' ? 1 : 0;
  }
  let event = null;
  try {
    event = JSON.parse(readStdin());
  } catch {
    return EXIT_NO_OPINION;
  }
  return decide(event);
}

if (require.main === module) {
  process.exitCode = main(process.argv.slice(2));
}

module.exports = { allowsCommand, decide, install, hookEntry, EXIT_ALLOW, EXIT_NO_OPINION };
