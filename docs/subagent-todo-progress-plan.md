# Single-owner subagent todo progress

Implementation checkpoint for i2f23. Not a deployment claim.

## Verified baseline

- `ControllerSubagentRegistrySource.todos` synthesizes checklist items from recent
  tool activity. These are not agent-authored todos and must not represent progress.
- `SubagentChipRecord.toEntry` does not project todo progress.
- `SelfTodoRepository` already reads foreground TodoWrite snapshots; inspect and
  reuse its parsing contract instead of introducing a second todo parser.
- `TodoWriteToolPresenter` recognizes TodoWrite, todo_write, update_plan and
  write_todos. Presentation support is not proof of state ingestion support.

## Required ownership

One sharedLogic projection owns the actual agent-authored todo snapshot for each
dispatch. The checklist and entry progress derive from that same snapshot. Clients
render it; they do not infer progress from tool calls, elapsed time or worker exit.

No snapshot means unknown progress. Empty plans must not divide by zero. Completed
todos mean complete todo progress, not successful worker exit. Worker lifecycle
remains independent, including failure with unfinished todos and success without
a plan. Replay must preserve parent and child identity boundaries.

## Verification contract

Tests must cover real todo snapshots and updates, unknown/empty plans, completed
plans with running workers, failed workers with incomplete plans, parent/child
isolation, replay, and agreement between list and details responses. Tool activity
without a todo plan must never produce a checklist or determinate percentage.
Reverting the production fix must fail the relevant regressions.

## Ordering contract

Positional revisions rely on the inspected Letta Code 0.34.14 producer: addToolCall
deduplicates call IDs and appends immutable entries; buildSubagentSnapshot forwards
that cumulative array. Remote array replacement is not evidence of truncation.
Controller rehydration is supported while this producer history is retained.
Producer process reconstruction, reordered histories and new plans at equal or
lower positions are not supported ordering contracts. A shorter history cannot
be distinguished reliably from stale replay using arrival time or a local counter.
Supporting truncation requires a producer-authored durable comparable revision.

## External worker limitation

The pinned Letta Code 0.34.14 Codex adapter handles item/started, item/completed
and turn/completed, but not turn/plan/updated. Native child snapshots expose their
cumulative tool_calls and can supply authored plans; external Codex workers cannot
yet supply this input through that adapter. They therefore remain indeterminate.
This change must not be described as complete external-worker progress support.
Forwarding those upstream plan events is a separate prerequisite, not permission
to estimate progress from activity or to mutate the installed runtime in place.
