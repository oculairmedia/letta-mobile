#!/usr/bin/env node
// Move stale subagent agents out of the App Server's local backend store (letta-mobile-sz671).
// Installed as /usr/local/lib/meridian/meridian-subagent-archive.cjs and run by install-meridian.sh's
// meridian-appserver drop-in as ExecStartPre, i.e. on every App Server start, before it loads the store.
//
// Why: letta-code creates a full agent for every subagent and reflection run and never deletes it (its
// completed-subagent cleanup only drops the in-memory UI entry). The live store grew to ~2,600 hidden
// subagents owning ~2,700 conversation dirs, and the App Server re-reads every conversation record on
// conversation_list while the wrapper stats them all. Owner decision: never delete; after an age, move
// them where nothing scans, still restorable.
//
// What moves: an agent record that is `hidden: true` AND tagged `role:subagent`, idle for --days (the
// newest mtime of its record and of every file in every conversation it owns), together with those
// conversation dirs, memfs/<id> and search/<id>. Each path is renamed to the same relative path under
// the archive (default: a sibling `<store>-archive`, so renames stay on one filesystem), and a line per
// agent is appended to the archive's manifest.jsonl. An agent that cannot move completely is put back.
//
// Only while no App Server runs: it loads every agent at startup and keeps the in-memory copy when a
// file disappears, so archiving under a live server would leave the agents listed and let a later write
// recreate a partial record. --apply refuses when a `letta ... server` process is found.
//
//   meridian-subagent-archive.cjs [--days N]        dry run: report what would move
//   meridian-subagent-archive.cjs --apply [--days N]
//   meridian-subagent-archive.cjs --restore AGENT_ID
//   (--store DIR / --archive DIR override $LETTA_LOCAL_BACKEND_DIR and its sibling)
'use strict';

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const DEFAULT_DAYS = 7;
const DAY_MS = 24 * 60 * 60 * 1000;
const MANIFEST = 'manifest.jsonl';
const SUBAGENT_TAG = 'role:subagent';

function decodeSegment(segment) {
  try {
    return Buffer.from(segment, 'base64url').toString('utf8');
  } catch {
    return null;
  }
}

function readJson(file) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    return null;
  }
}

// Files only: a directory's mtime also moves when this janitor or a restore renames entries in or out,
// and any write that is real activity leaves a fresh file behind anyway.
function newestMtime(target) {
  let stat;
  try {
    stat = fs.statSync(target);
  } catch {
    return 0;
  }
  if (!stat.isDirectory()) return stat.mtimeMs;
  let newest = 0;
  for (const entry of fs.readdirSync(target)) newest = Math.max(newest, newestMtime(path.join(target, entry)));
  return newest;
}

/** Owning agent of each conversation dir: its conversation.json, else a `default:<agentId>` dir name. */
function conversationOwners(store) {
  const owners = new Map();
  const dir = path.join(store, 'conversations');
  if (!fs.existsSync(dir)) return owners;
  for (const entry of fs.readdirSync(dir)) {
    const record = readJson(path.join(dir, entry, 'conversation.json'));
    const decoded = decodeSegment(entry) ?? '';
    const owner = record?.agent_id ?? (decoded.startsWith('default:') ? decoded.slice('default:'.length) : null);
    if (!owner) continue;
    if (!owners.has(owner)) owners.set(owner, []);
    owners.get(owner).push(path.join('conversations', entry));
  }
  return owners;
}

function isStoredSubagent(record) {
  return record?.hidden === true && Array.isArray(record.tags) && record.tags.includes(SUBAGENT_TAG);
}

/** The store-relative paths one agent owns: its record, side dirs that exist, its conversations. */
function ownedPaths(store, recordFile, agentId, owners) {
  const sideDirs = ['agents', 'memfs', 'search']
    .map((dir) => path.join(dir, agentId))
    .filter((rel) => fs.existsSync(path.join(store, rel)));
  return [path.join('agents', recordFile), ...sideDirs, ...(owners.get(agentId) ?? [])];
}

/** Every archivable subagent with the store-relative paths it owns and its last activity. */
function findSubagents(store) {
  const agentsDir = path.join(store, 'agents');
  if (!fs.existsSync(agentsDir)) return [];
  const owners = conversationOwners(store);
  return fs.readdirSync(agentsDir)
    .filter((file) => file.endsWith('.json'))
    .map((file) => ({ file, record: readJson(path.join(agentsDir, file)) }))
    .filter(({ record }) => isStoredSubagent(record) && typeof record.id === 'string')
    .map(({ file, record }) => {
      const paths = ownedPaths(store, file, record.id, owners);
      return { id: record.id, paths, lastActivity: Math.max(...paths.map((rel) => newestMtime(path.join(store, rel)))) };
    });
}

/** Renames every path or none: a failure part-way puts the moved ones back and rethrows. */
function moveAll(paths, fromRoot, toRoot) {
  const moved = [];
  try {
    for (const rel of paths) {
      const to = path.join(toRoot, rel);
      if (fs.existsSync(to)) throw new Error(`${to} already exists`);
      fs.mkdirSync(path.dirname(to), { recursive: true });
      fs.renameSync(path.join(fromRoot, rel), to);
      moved.push(rel);
    }
  } catch (error) {
    for (const rel of moved.reverse()) fs.renameSync(path.join(toRoot, rel), path.join(fromRoot, rel));
    throw error;
  }
}

function archive({ store, archiveDir, days = DEFAULT_DAYS, apply = false, now = Date.now(), log = console.log }) {
  const cutoff = now - days * DAY_MS;
  const subagents = findSubagents(store);
  const stale = subagents.filter((agent) => agent.lastActivity < cutoff);
  const result = { archived: 0, failed: 0, kept: subagents.length - stale.length, stale: stale.length };
  if (!apply) return result;
  for (const agent of stale) {
    try {
      moveAll(agent.paths, store, archiveDir);
      fs.appendFileSync(path.join(archiveDir, MANIFEST), `${JSON.stringify({
        agent_id: agent.id,
        archived_at: new Date(now).toISOString(),
        last_activity: new Date(agent.lastActivity).toISOString(),
        paths: agent.paths,
      })}\n`);
      result.archived += 1;
    } catch (error) {
      result.failed += 1;
      log(`subagent-archive: kept ${agent.id}: ${error.message}`);
    }
  }
  return result;
}

/** Moves an archived agent back; the most recent manifest entry for it says what to move. */
function restore({ store, archiveDir, agentId }) {
  const manifest = path.join(archiveDir, MANIFEST);
  const entries = fs.existsSync(manifest)
    ? fs.readFileSync(manifest, 'utf8').split('\n').filter(Boolean).map((line) => JSON.parse(line))
    : [];
  const entry = entries.reverse().find((candidate) => candidate.agent_id === agentId);
  if (!entry) throw new Error(`${agentId} is not in ${manifest}`);
  moveAll(entry.paths.filter((rel) => fs.existsSync(path.join(archiveDir, rel))), archiveDir, store);
  return entry.paths.length;
}

/** True when a `letta ... server` process is running (the App Server holds the store in memory). */
function appServerRunning(procRoot = '/proc') {
  for (const pid of fs.readdirSync(procRoot)) {
    if (!/^\d+$/.test(pid) || Number(pid) === process.pid) continue;
    let argv;
    try {
      argv = fs.readFileSync(path.join(procRoot, pid, 'cmdline'), 'utf8').split('\0');
    } catch {
      continue;
    }
    if (argv.some((arg) => /(^|\/)letta(\.js)?$/.test(arg)) && argv.includes('server')) return true;
  }
  return false;
}

// Flags that take a value, by the option they set.
const VALUE_FLAGS = { '--days': 'days', '--store': 'store', '--archive': 'archiveDir', '--restore': 'restore' };

function parseArgs(argv, env) {
  const options = { apply: false, days: DEFAULT_DAYS, restore: null, store: undefined, archiveDir: undefined };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--apply') options.apply = true;
    else if (VALUE_FLAGS[arg]) options[VALUE_FLAGS[arg]] = argv[++i];
    else throw new Error(`unknown argument ${arg}`);
  }
  options.days = Number(options.days);
  if (!Number.isFinite(options.days) || options.days < 1) throw new Error('--days must be at least 1');
  options.store = path.resolve(options.store ?? env.LETTA_LOCAL_BACKEND_DIR ?? path.join(os.homedir(), '.letta', 'lc-local-backend'));
  options.archiveDir = path.resolve(options.archiveDir ?? `${options.store}-archive`);
  return options;
}

function main(argv = process.argv.slice(2), env = process.env) {
  const options = parseArgs(argv, env);
  if ((options.apply || options.restore) && appServerRunning()) {
    console.error('subagent-archive: an App Server is running; stop it first (it holds the store in memory)');
    return 2;
  }
  if (options.restore) {
    const count = restore({ ...options, agentId: options.restore });
    console.log(`subagent-archive: restored ${options.restore} (${count} paths) into ${options.store}`);
    return 0;
  }
  const result = archive(options);
  const verb = options.apply ? 'archived' : 'would archive';
  console.log(`subagent-archive: ${verb} ${options.apply ? result.archived : result.stale} idle >${options.days}d, `
    + `kept ${result.kept} recent${result.failed ? `, ${result.failed} failed` : ''} (${options.store} -> ${options.archiveDir})`);
  return result.failed ? 1 : 0;
}

if (require.main === module) {
  try {
    process.exitCode = main();
  } catch (error) {
    console.error(`subagent-archive: ${error.message}`);
    process.exitCode = 1;
  }
}

module.exports = { archive, restore, findSubagents, appServerRunning, parseArgs };
