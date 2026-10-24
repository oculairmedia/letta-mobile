#!/usr/bin/env node
// Tests for meridian-subagent-archive.cjs (letta-mobile-sz671):
//   node --test scripts/deploy/meridian-subagent-archive.test.cjs
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const { archive, restore, appServerRunning, parseArgs } = require('./meridian-subagent-archive.cjs');

const NOW = Date.parse('2026-10-24T12:00:00Z');
const DAY = 24 * 60 * 60 * 1000;
const b64 = (text) => Buffer.from(text, 'utf8').toString('base64url');

function write(file, text, ageDays) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, text);
  const at = new Date(NOW - ageDays * DAY);
  fs.utimesSync(file, at, at);
}

/** A store laid out like lc-local-backend: agents/<b64 id>.json, conversations/<b64 key>/..., memfs/<id>. */
function makeStore() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'subarchive-'));
  const store = path.join(root, 'lc-local-backend');
  const agent = (id, record, ageDays) => write(path.join(store, 'agents', `${b64(id)}.json`), JSON.stringify({ id, ...record }), ageDays);
  const conversation = (key, agentId, ageDays, messagesAgeDays = ageDays) => {
    const dir = path.join(store, 'conversations', b64(key));
    if (agentId) write(path.join(dir, 'conversation.json'), JSON.stringify({ agent_id: agentId }), ageDays);
    write(path.join(dir, 'messages.jsonl'), '{}\n', messagesAgeDays);
  };
  const subagent = { hidden: true, tags: ['origin:letta-code', 'role:subagent', 'type:reflection'] };
  return { root, store, archiveDir: `${store}-archive`, agent, conversation, subagent };
}

const exists = (...parts) => fs.existsSync(path.join(...parts));

test('an idle hidden subagent moves with its conversations and memfs; everything else stays', () => {
  const s = makeStore();
  s.agent('agent-old', s.subagent, 30);
  s.conversation('default:agent-old', null, 30);
  s.conversation('conversation:local-conv-1', 'agent-old', 30);
  write(path.join(s.store, 'memfs', 'agent-old', 'memory', 'a.md'), 'x', 30);
  s.agent('agent-busy', s.subagent, 30);
  s.conversation('default:agent-busy', 'agent-busy', 30, 1);
  s.agent('agent-main', { tags: ['origin:letta-code'] }, 90);
  s.conversation('default:agent-main', 'agent-main', 90);
  s.agent('agent-shown', { hidden: false, tags: ['role:subagent'] }, 90);
  write(path.join(s.store, 'agents', 'broken.json'), '{not json', 90);

  const result = archive({ store: s.store, archiveDir: s.archiveDir, apply: true, now: NOW });

  assert.deepEqual(result, { archived: 1, failed: 0, kept: 1, stale: 1 });
  for (const rel of [path.join('agents', `${b64('agent-old')}.json`), path.join('conversations', b64('default:agent-old')),
    path.join('conversations', b64('conversation:local-conv-1')), path.join('memfs', 'agent-old')]) {
    assert.ok(!exists(s.store, rel), `${rel} left the store`);
    assert.ok(exists(s.archiveDir, rel), `${rel} reached the archive`);
  }
  assert.ok(exists(s.store, 'agents', `${b64('agent-busy')}.json`), 'a conversation touched yesterday keeps its agent');
  assert.ok(exists(s.store, 'agents', `${b64('agent-main')}.json`), 'a visible agent is never archived');
  assert.ok(exists(s.store, 'agents', `${b64('agent-shown')}.json`), 'hidden must be true, not just the tag');
  assert.ok(exists(s.store, 'agents', 'broken.json'));
  const manifest = fs.readFileSync(path.join(s.archiveDir, 'manifest.jsonl'), 'utf8').trim().split('\n').map((line) => JSON.parse(line));
  assert.deepEqual(manifest.map((entry) => entry.agent_id), ['agent-old']);
  assert.equal(manifest[0].paths.length, 4);
});

test('a dry run reports the same agents and moves nothing', () => {
  const s = makeStore();
  s.agent('agent-old', s.subagent, 30);
  s.conversation('default:agent-old', 'agent-old', 30);

  assert.deepEqual(archive({ store: s.store, archiveDir: s.archiveDir, now: NOW }), { archived: 0, failed: 0, kept: 0, stale: 1 });
  assert.ok(exists(s.store, 'agents', `${b64('agent-old')}.json`));
  assert.ok(!exists(s.archiveDir));
});

test('restore puts every archived path back where it was', () => {
  const s = makeStore();
  s.agent('agent-old', s.subagent, 30);
  s.conversation('default:agent-old', 'agent-old', 30);
  archive({ store: s.store, archiveDir: s.archiveDir, apply: true, now: NOW });

  assert.equal(restore({ store: s.store, archiveDir: s.archiveDir, agentId: 'agent-old' }), 2);
  assert.ok(exists(s.store, 'agents', `${b64('agent-old')}.json`));
  assert.ok(exists(s.store, 'conversations', b64('default:agent-old'), 'messages.jsonl'));
  assert.throws(() => restore({ store: s.store, archiveDir: s.archiveDir, agentId: 'agent-never' }), /not in/);
});

test('an agent that cannot move completely stays whole in the store', () => {
  const s = makeStore();
  s.agent('agent-old', s.subagent, 30);
  s.conversation('default:agent-old', 'agent-old', 30);
  // A leftover copy in the archive blocks the conversation's rename after the record already moved.
  fs.mkdirSync(path.join(s.archiveDir, 'conversations', b64('default:agent-old')), { recursive: true });
  const logged = [];

  const result = archive({ store: s.store, archiveDir: s.archiveDir, apply: true, now: NOW, log: (line) => logged.push(line) });

  assert.equal(result.failed, 1);
  assert.equal(result.archived, 0);
  assert.ok(exists(s.store, 'agents', `${b64('agent-old')}.json`), 'the record moved back');
  assert.ok(exists(s.store, 'conversations', b64('default:agent-old'), 'messages.jsonl'));
  assert.match(logged[0], /kept agent-old: .*already exists/);
  assert.ok(!exists(s.archiveDir, 'manifest.jsonl'));
});

test('a running letta server is detected from /proc', () => {
  const proc = fs.mkdtempSync(path.join(os.tmpdir(), 'subarchive-proc-'));
  const cmdline = (pid, argv) => write(path.join(proc, String(pid), 'cmdline'), `${argv.join('\0')}\0`, 0);
  cmdline(10, ['node', '/usr/local/lib/meridian/meridian-subagent-archive.cjs', '--apply']);
  cmdline(11, ['node', '/root/.bun/bin/letta', 'conversations', 'list']);
  assert.equal(appServerRunning(proc), false);

  cmdline(12, ['node', '/root/.bun/bin/letta', 'server', '--listen', 'ws://127.0.0.1:4500']);
  assert.equal(appServerRunning(proc), true);
});

test('the store comes from LETTA_LOCAL_BACKEND_DIR and the archive sits beside it', () => {
  const options = parseArgs(['--days', '14'], { LETTA_LOCAL_BACKEND_DIR: '/srv/lc' });
  assert.equal(options.store, '/srv/lc');
  assert.equal(options.archiveDir, '/srv/lc-archive');
  assert.equal(options.days, 14);
  assert.throws(() => parseArgs(['--days', '0'], {}), /at least 1/);
});
