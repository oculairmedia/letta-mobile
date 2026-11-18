import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolveDeployedLettaBundle } from "./bundle-fixtures.ts";
import { patchLettaCodeSourceResultForTest } from "../letta-code-patch-loader.mjs";

const anchors = [
  '  backgroundTasks.set(taskId, bgTask);\n  registerSubagentAbortHook(subagentId, buildBackgroundTaskAbortHook(taskId, abortController));',
  '    bgTask.status = result3.success ? "completed" : "failed";\n    if (result3.error) {',
  '    bgTask.status = "failed";\n    bgTask.error = errorMessage5;',
  '      task.status = "failed";\n      scheduleBackgroundTaskCleanup(task_id);',
];

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
  task.status = 'running';
  run({ start: 'bgTask.status = "failed";', end: 'bgTask.error = errorMessage5;',
    args: ['bgTask', 'notifyBackgroundProcessStateChanged'], values: [task, notify] });
  task.status = 'running';
  run({ start: 'task.status = "failed";', end: 'scheduleBackgroundTaskCleanup(task_id);',
    args: ['task', 'notifyBackgroundProcessStateChanged'], values: [task, notify] });
  assert.deepEqual(events, [scope, scope, scope, scope, scope]);
});

test("agent task patch matches four unique real bundle anchors and skips atomically on drift", (t) => {
  const source = bundle(t);
  if (!source) return;
  for (const anchor of anchors) assert.equal(source.split(anchor).length - 1, 1);
  const result = patchLettaCodeSourceResultForTest(source);
  assert.equal((result.source.match(/\/\/ lcp-agent-task-status/g) ?? []).length, 4);
  for (const anchor of anchors) {
    const duplicate = source + '\n' + anchor;
    const drifted = patchLettaCodeSourceResultForTest(duplicate);
    assert.equal((drifted.source.match(/\/\/ lcp-agent-task-status/g) ?? []).length, 0);
    assert.equal(drifted.skippedPatches, result.skippedPatches + 1);
  }
  const reapplied = patchLettaCodeSourceResultForTest(result.source).source;
  assert.equal(reapplied.split("// lcp-agent-task-status").length - 1, 4);
});
