#!/usr/bin/env node
// Tests for meridian-permission-hook.cjs (letta-mobile-jna0o.7):
//   node --test scripts/deploy/meridian-permission-hook.test.cjs
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const test = require('node:test');

const HOOK = path.join(__dirname, 'meridian-permission-hook.cjs');
const { allowsCommand, install, hookEntry, EXIT_ALLOW, EXIT_NO_OPINION } = require(HOOK);
const vectors = JSON.parse(fs.readFileSync(path.join(__dirname, 'meridian-allowlist-vectors.json'), 'utf8'));

test('allows every allow vector (same vectors as the app allow-list)', () => {
  assert.deepEqual(vectors.allow.filter((c) => !allowsCommand(c)), []);
});

test('refuses every refuse vector', () => {
  assert.deepEqual(vectors.refuse.filter((c) => allowsCommand(c)), []);
});

function runHook(event) {
  return spawnSync(process.execPath, [HOOK], { input: JSON.stringify(event) }).status;
}

test('as a hook: exit 0 allows a meridian call, anything else keeps asking and never denies', () => {
  const meridian = { event_type: 'PermissionRequest', tool_name: 'Bash', tool_input: { command: 'meridian canvas list' } };
  assert.equal(runHook(meridian), EXIT_ALLOW);
  assert.equal(runHook({ ...meridian, tool_input: { command: 'meridian canvas list; id' } }), EXIT_NO_OPINION);
  assert.equal(runHook({ ...meridian, tool_name: 'Write' }), EXIT_NO_OPINION);
  assert.equal(spawnSync(process.execPath, [HOOK], { input: 'garbage' }).status, EXIT_NO_OPINION);
});

test('--install registers the hook once and keeps the rest of settings.json', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mhook-'));
  const file = path.join(dir, 'settings.json');
  const other = { matcher: 'Write', hooks: [{ type: 'command', command: 'echo other' }] };
  fs.writeFileSync(file, JSON.stringify({ permissions: { allow: ['Read'] }, hooks: { PermissionRequest: [other] } }));

  assert.equal(install(file, true), 'DRIFTED');
  assert.equal(install(file, false), 'installed');
  assert.equal(install(file, false), 'in sync');

  const settings = JSON.parse(fs.readFileSync(file, 'utf8'));
  assert.deepEqual(settings.permissions, { allow: ['Read'] });
  assert.deepEqual(settings.hooks.PermissionRequest, [other, hookEntry('/usr/local/lib/meridian/meridian-permission-hook.cjs')]);
});
