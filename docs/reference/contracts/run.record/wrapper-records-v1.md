# Wrapper run records and leases v1

Producer transport is the existing paired-peer Iroh QUIC `admin_rpc` stream.
Pair on the wrapper host using the same key/store options as the serving wrapper:

```sh
meridian-iroh-wrapper pair --role vibesync --agents agent-A --name producer --iroh-secret-key-file "$KEY" --pairing-store-file "$PAIRS" --dial "$WRAPPER_ADDRESS"
```

Redeem the invite on the existing control channel, authenticate the peer, then open
one bidirectional stream per request. Frames use the existing four-byte big-endian
length prefix and UTF-8 JSON. There is no additional ingress or authentication path.
`run.record` and `lease.upsert` require `workactivity.report`, already in
`VIBESYNC_ROLE`; `lease.list` and `cron.runs` require `chat.read`. `admin.full`
retains its existing override. `workactivity.*` stays unchanged for separate work.
An authorized agent is the agent the record describes, not the producer identity.
Pair for that agent before reporting about it; an out-of-scope agent is forbidden.

Every request is `{ "type":"admin_rpc", "request_id":"producer-1", "method":METHOD,
"params":PARAMS }`. Every success is `{ "type":"admin_rpc_response",
"request_id":"producer-1", "success":true, "result":RESULT }`.
Failures retain `success:false,error:STRING`. Unauthenticated streams return
`error:"unauthorized"` (telemetry `auth.required`); missing capability returns
`error:"forbidden",capability:"workactivity.report"` (telemetry `authz.denied`).
Scope failures use `error:"forbidden: agent not in this device's scope"`.
Validation errors start `validation.invalid_params:`; conflicts start
`validation.conflict:`; capacity rejection starts `storage.capacity:`. These
stable typed prefixes preserve the existing error-string envelope. No request
body is logged on rejection.

## run.record and the extended cron.runs row

Params: required string `agentId,scheduleName,instant,status,reason,source`;
optional strings `scheduleId,detail`. `instant` is ISO-8601 UTC (normalized to
millisecond precision). Status vocabulary: `done,failed,skipped,running,unknown`.
Source vocabulary: `cron,agent,human,jules,unknown`. Any unrecognized status/source
maps to `unknown` at the DTO boundary. These generic values never encode a producer
name. Persistence stores enum names, never ordinals. Unknown fields are ignored.

Append-only natural identity: `(agentId,scheduleId,scheduleName,instant,source)`;
the returned `record.id` is the SHA-256 of its unambiguous JSON tuple. An identical
retry returns the original record, including `createdAt`; changed status/reason/detail
for that identity returns `validation.conflict:`. Retry deduplication lasts for the
90-day retention window. A new event must change the natural key.
Result: `{record:{id,agentId,scheduleId,scheduleName,instant,status,reason,source,
detail,createdAt}}`; absent optional fields are explicit null.

`cron.runs` retains params `task_id` (required), `limit` (default 50, 1..200),
`offset` (default 0, nonnegative), and result `{page:{entries,total,offset,limit,
hasMore,nextOffset}}`. Optional `agentId` narrows to one authorized agent.
`task_id` matches wrapper `scheduleId`; schedule-less rows use the synthetic task
id `wrapper:<agentId>`. Schedule ids are opaque references, not evidence of agent
ownership; row `agentId` owns the record. This API does not create schedules.

Fetch all upstream retained entries in pages of 200 (upstream keeps at most 5000),
merge wrapper rows, filter agent scope BEFORE counting/paging, sort by numeric `ts`
descending (ties: upstream first, wrapper id ascending), then slice once. No upstream
page total is reused. `total` counts the authorized merged retained list;
`hasMore = offset + entries.size < total`; `nextOffset` is that sum or null.
The upstream does not provide a snapshot token, so concurrent upstream mutation
between pages can shift offset pagination; callers should refresh after changes.
An upstream failure/missing or inconsistent page fails closed, never a partial success.
Rows without an upstream `agentId` are hidden from scoped peers.

Wrapper rows retain the upstream row keys `ts` (epoch ms), `jobId`, `action:"finished"`,
`status`, `agentId`, `runId` and add `scheduleName,instant,reason,source,detail`.
Upstream rows preserve their fields except `reason` and `source`, both explicitly
null as required by this contract. (Upstream's scheduler reason is not producer
reason.) Existing consumers ignoring additive fields keep the page shape.
The fixture includes both row origins and complete paging fields. Clients must
feature-detect the advertised registered methods: a missing `run.record` /
`lease.list` on older wrappers means no wrapper records; do not require a matched
display update to connect.

## lease.upsert

Params: required strings `id,owner,label,startedAt,dueAt,state`; optional strings
`runId,detail`. `owner` is the agent id, subject to the same scope as `agentId`.
States: `active,done,expired,recovered,unknown`; unrecognized states map to `unknown`
and remain subject to the active capacity bound, rather than being pruned as closed.
`dueAt >= startedAt`. A later upsert replaces label/dueAt/state/runId/detail;
`owner,startedAt` are immutable (conflict if changed). The id is global: another
agent may never take over an existing lease id. Result `{lease:{id,owner,label,
startedAt,dueAt,state,runId,detail,createdAt,updatedAt}}`; both timestamps are
wrapper clock instants, `createdAt` is first seen, `updatedAt` is last upsert.
This stores the final state and both timestamps, not a transition event log.

## lease.list

Optional filters: string `agentId`, string `state` (unknown values -> `unknown`),
ISO instants `from,to` (inclusive overlap: startedAt <= to and dueAt >= from),
integer `limit` (default 50, 1..200) and nonnegative integer `offset` (default 0).
Reject `from > to`. Scope and filters precede counting/paging. Sort by startedAt
descending then id ascending. Result `{leases:[LEASE],total,offset,limit,hasMore,
nextOffset}`. No out-of-scope rows or totals are exposed.

## Bounds and persistence

Required strings are nonblank. Ids/owner/scheduleId/runId: at most 256 characters;
label/scheduleName: 512; reason: 2048; detail: 8192. Every individual encoded record
is at most 65536 UTF-8 bytes. All supplied instants must be in [2000-01-01,
wrapper-clock + 366 days]; old valid records may be immediately pruned.
Runs older than 90 days by event instant and closed leases older than 30 days by
updatedAt are pruned on startup AND every write using an injectable clock.
Active/unknown leases never auto-prune. Maximum 1000 active/unknown leases;
new/reopened leases over the cap are rejected, existing active leases may update
or close. Maximum 10000 runs and 10000 leases total: reject overflow without
eviction. Pruning and the attempted write commit together atomically.

Use `--run-records-file` / `LETTA_RUN_RECORDS_STORE`; default `run-records.json`
beside `model-exposure.json` (the wrapper state directory). One wrapper owns the
file; synchronized read/modify/fsync-temp/atomic-rename serializes concurrent RPCs.
Corrupt/partial/oversized state is quarantined to a sibling `.corrupt-<uuid>` file,
logged at WARN without body text, and startup continues empty. I/O write failures
return errors and do not mutate the in-memory snapshot. No upstream writer changes.

## Router enumeration

The following methods were enumerated from real router registration sites at base
12edc9831. Runtime tests enumerate `AdminRpcRegistry.buildRouter().registeredMethods`
and require explicit capability coverage of every registered method.

- `agent.context`
- `agent.count`
- `agent.create`
- `agent.list`
- `agent.update`
- `approval.submit`
- `block.get`
- `block.list`
- `block.list_agent`
- `conversation.create`
- `conversation.delete`
- `conversation.get`
- `conversation.list`
- `conversation.list_agent`
- `conversation.update`
- `cron.add`
- `cron.delete`
- `cron.delete_all`
- `cron.get`
- `cron.list`
- `cron.runs`
- `cron.trigger`
- `cron.update`
- `health.check`
- `message.get`
- `message.list`
- `model.exposure.get`
- `model.exposure.set`
- `model.list`
- `model.list.embedding`
- `model.update`
- `pair.invite.create`
- `pair.invite.list`
- `pair.invite.revoke`
- `pair.peer.get`
- `pair.peer.list`
- `pair.peer.rename`
- `pair.peer.revoke`
- `pair.peer.set_agents`
- `pair.peer.set_capabilities`
- `project.archive`
- `project.beadsRemoteStatus`
- `project.create`
- `project.delete`
- `project.get`
- `project.list`
- `project.provisionBeadsRemote`
- `project.triggerSync`
- `project.update`
- `provider.connect`
- `provider.disconnect`
- `provider.list`
- `run.get`
- `run.list`
- `schedule.create`
- `schedule.delete`
- `schedule.get`
- `schedule.list`
- `skill.install`
- `skill.list`
- `skill.list_agent`
- `skill.uninstall`
- `step.list`
- `subagent.list`
- `subagent.todos`
- `tool.get`
- `tool.list`
- `tool_return.get`

Added in this contract: `run.record`, `lease.upsert`, `lease.list`.
