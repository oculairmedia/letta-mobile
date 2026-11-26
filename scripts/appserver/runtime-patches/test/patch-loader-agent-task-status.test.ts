import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolveDeployedLettaBundle } from "./bundle-fixtures.ts";
import { patchLettaCodeSourceResultForTest } from "../letta-code-patch-loader.mjs";

const common = [
  '  backgroundTasks.set(taskId, bgTask);\n  registerSubagentAbortHook(subagentId, buildBackgroundTaskAbortHook(taskId, abortController));',
  '    bgTask.status = result3.success ? "completed" : "failed";\n    if (result3.error) {',
];
const stop = '      task.status = "failed";\n      scheduleBackgroundTaskCleanup(task_id);';
// 0.34.39 and 0.34.70 shapes; 0.34.70 adds two failure transitions on the task stop path.
const releases = [
  { catchBinding: "errorMessage5", anchors: [...common, '    bgTask.status = "failed";\n    bgTask.error = errorMessage5;', stop] },
  {
    catchBinding: "errorMessage6",
    anchors: [
      ...common,
      '    bgTask.status = "failed";\n    bgTask.error = errorMessage6;',
      '          task.status = "failed";\n          scheduleBackgroundTaskCleanup(task_id);\n          return {',
      '          task.status = "failed";\n          scheduleBackgroundTaskCleanup(task_id);\n          task.cancelPendingWakes',
      stop,
    ],
  },
];

function release(source: string) {
  const matching = releases.filter(({ anchors }) => anchors.every((anchor) => source.split(anchor).length - 1 === 1));
  assert.equal(matching.length, 1, "the bundle must match exactly one known release shape");
  return matching[0]!;
}

function bundle(t: { skip: (reason: string) => void }): string | null {
  const path = resolveDeployedLettaBundle();
  if (!path) { t.skip("deployed bundle unavailable"); return null; }
  return readFileSync(path, "utf8");
}

test("agent task status transitions notify listeners with the task scope", (t) => {
  const source = bundle(t);
  if (!source) return;
  const patched = patchLettaCodeSourceResultForTest(source).source;
  const events: unknown[] = [];
  const scope = { agentId: "parent", conversationId: "conversation" };
  const task = { status: "running", runtimeScope: scope };
  const listeners = new Set([(value: unknown) => events.push(value)]);
  const notifier = source.match(/function notifyBackgroundProcessStateChanged\(scope\) \{[\s\S]*?\n\}/);
  assert.ok(notifier);
  const notify = new Function("backgroundProcessStateListeners", `${notifier[0]}; return notifyBackgroundProcessStateChanged;`)(listeners);
  const run = ({ start, end, args, values }: { start: string; end: string; args: string[]; values: unknown[] }) => {
    const from = patched.indexOf(start);
    assert.notEqual(from, -1);
    const to = patched.indexOf(end, from);
    assert.notEqual(to, -1);
    new Function(...args, patched.slice(from, to))(...values);
  };
  run({ start: 'backgroundTasks.set(taskId, bgTask);', end: 'registerSubagentAbortHook',
    args: ['backgroundTasks', 'taskId', 'bgTask', 'notifyBackgroundProcessStateChanged'],
    values: [new Map(), 'id', task, notify] });
  for (const success of [true, false]) {
    task.status = 'running';
    run({ start: 'bgTask.status = result3.success ?', end: 'if (result3.error)',
      args: ['bgTask', 'result3', 'notifyBackgroundProcessStateChanged'], values: [task, { success }, notify] });
    assert.equal(task.status, success ? 'completed' : 'failed');
  }
  const { catchBinding, anchors } = release(source);
  task.status = 'running';
  run({ start: 'bgTask.status = "failed";', end: `bgTask.error = ${catchBinding};`,
    args: ['bgTask', 'notifyBackgroundProcessStateChanged'], values: [task, notify] });
  // Every task-stop failure transition, each located by its whole patched text (first line, the
  // inserted notification with its marker, then the rest of the anchor): 0.34.70's stop sites share
  // their first line, so a first-line lookup would run one site twice and miss another.
  const stopSites = anchors.filter((anchor) => anchor.trimStart().startsWith("task.status"));
  for (const anchor of stopSites) {
    task.status = 'running';
    const newline = anchor.indexOf("\n");
    const indent = anchor.match(/^ */)![0];
    const patchedSite = `${anchor.slice(0, newline)}\n${indent}notifyBackgroundProcessStateChanged(task.runtimeScope); // lcp-agent-task-status${anchor.slice(newline)}`;
    assert.equal(patched.split(patchedSite).length - 1, 1, `stop site must notify exactly once: ${anchor.slice(newline + 1, newline + 80)}`);
    const at = patched.indexOf(patchedSite);
    const body = patched.slice(at, patched.indexOf("scheduleBackgroundTaskCleanup(task_id);", at));
    new Function('task', 'notifyBackgroundProcessStateChanged', body)(task, notify);
    assert.equal(task.status, 'failed');
  }
  assert.deepEqual(events, Array(3 + 1 + stopSites.length).fill(scope));
});

test("agent task patch matches every unique real bundle anchor and skips atomically on drift", (t) => {
  const source = bundle(t);
  if (!source) return;
  const { anchors } = release(source);
  const result = patchLettaCodeSourceResultForTest(source);
  assert.equal((result.source.match(/\/\/ lcp-agent-task-status/g) ?? []).length, anchors.length);
  for (const anchor of anchors) {
    const duplicate = source + '\n' + anchor;
    const drifted = patchLettaCodeSourceResultForTest(duplicate);
    assert.equal((drifted.source.match(/\/\/ lcp-agent-task-status/g) ?? []).length, 0);
    assert.equal(drifted.skippedPatches, result.skippedPatches + 1);
  }
  const reapplied = patchLettaCodeSourceResultForTest(result.source).source;
  assert.equal(reapplied.split("// lcp-agent-task-status").length - 1, anchors.length);
});

test("agent task patch skips, untouched, a source that matches both release shapes", () => {
  // Every anchor of both variants exactly once: an ambiguous bundle must not be patched as either.
  const both = [...new Set(releases.flatMap(({ anchors }) => anchors))].join("\n// separator\n");
  for (const { anchors } of releases) {
    for (const anchor of anchors) assert.equal(both.split(anchor).length - 1, 1, "fixture holds each anchor once");
  }
  const result = patchLettaCodeSourceResultForTest(both);
  assert.equal(result.source.includes("// lcp-agent-task-status"), false, "nothing of the patch may land");
  const reference = patchLettaCodeSourceResultForTest("// unrelated\n");
  assert.equal(result.skippedPatches, reference.skippedPatches, "the ambiguous bundle counts as a skip like any drift");
});
