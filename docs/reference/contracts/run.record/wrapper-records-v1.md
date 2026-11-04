# Wrapper run records and leases v1

Producer transport is the existing paired-peer Iroh QUIC `admin_rpc` stream.
Pair on the wrapper host using the same key/store options as the serving wrapper:

```sh
meridian-iroh-wrapper pair --role vibesync --agents agent-A --name producer --iroh-secret-key-file "$KEY" --pairing-store-file "$PAIRS" --dial "$WRAPPER_ADDRESS"
```

Use a persistent producer Iroh secret key. Dial the wrapper NodeId/address using ALPN
`/letta/appserver/0`; QUIC verifies the host key. Open the first bidirectional stream
as the control channel and send this length-prefixed UTF-8 JSON auth frame:

```json
{"type":"auth","request_id":"auth-1","token":"invite:<one-time-secret>","peer_name":"producer","capabilities":["frame_part"]}
```

Open the second bidirectional stream as the reserved event stream and immediately
finish its send half with no application bytes. Keep/drain its receive half and keep
the control stream open for the connection lifetime. The host waits for these TWO
reserved streams before processing control auth; opening only a control stream
will stall. Wait for `{"type":"auth_response","request_id":"auth-1","success":true,
"paired":true,"capabilities":[...]}` on the control channel before calling methods.
On subsequent connections with the SAME producer key, send `token:""`; the paired
NodeId authenticates and its persisted role/agent scope applies. Never create a
new key for each process invocation. The invite secret is one-time, not a bearer
credential to keep sending. No additional ingress or authentication path is added.

After auth, open a NEW bidirectional stream per RPC. Send exactly one framed
`admin_rpc`, finish its send half, read one `admin_rpc_response`, then close it.
A plain frame is a four-byte unsigned big-endian length and exactly that many UTF-8
JSON bytes, at most 1 MiB. Advertise `frame_part` only if you support continuation
framing: sentinel `0x7fffffff` (four bytes), flags (one byte, bit 0 = final), part
index (four bytes, zero-based sequential), payload length (four bytes, 1..1 MiB),
then payload bytes. Reassemble at most 32 MiB before parsing JSON. Otherwise omit
that capability and use smaller page limits; responses over 1 MiB fail explicitly.
The successful auth response advertises `run.record`, `lease.upsert`, and
`lease.list` as feature capability strings when these handlers are installed.

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

Executable success requests and complete responses for all four methods are in
[success-examples-v1.json](success-examples-v1.json). The stream test compares these
fixtures against the real handlers with the two documented injected clock values.

## run.record and the extended cron.runs row

Params: required string `agentId,scheduleName,instant,status,reason,source`;
optional nullable strings `scheduleId,detail`. `instant` is ISO-8601 UTC (normalized to
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
id `wrapper:<lowercase SHA-256 hex of agentId UTF-8 bytes>`. This fixed 72-character
key remains readable for every valid owner, independent of its length or path characters. Schedule ids are opaque references, not evidence of agent
ownership; row `agentId` owns the record. This API does not create schedules. A supplied scheduleId must pass the upstream
run-log job-id check: nonblank after ECMAScript trimming, with no slash, backslash,
or NUL. Unknown safe schedule ids are supported; the upstream reader returns an
empty history when its log file does not exist.

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

Params: required strings `id,owner,label,startedAt,dueAt,state`; optional nullable strings
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
is at most 65536 UTF-8 bytes. Write instants must be in [2000-01-01, min(2100-01-01, wrapper-clock + 366 days)]; query
instants must be in [2000-01-01, 2100-01-01]. Old valid records may be immediately pruned.
Runs older than 90 days by event instant and closed leases older than 30 days by
updatedAt are pruned on startup AND every write using an injectable clock.
Active/unknown leases never auto-prune. Maximum 1000 active/unknown leases;
new/reopened leases over the cap are rejected, existing active leases may update
or close. Maximum 10000 runs and 10000 leases total: reject overflow without
eviction. An additional 64 MiB encoded-file cap rejects overflow without eviction.
Pruning and the attempted write commit together atomically.

Use `--run-records-file` / `LETTA_RUN_RECORDS_STORE`; default `run-records.json`
beside `model-exposure.json` (the wrapper state directory). The CLI always binds
this durable default; bare router factories without either host-state path use an
isolated in-memory store and never open the serving host's state. The file carries schema version 1. One wrapper owns the
file; synchronized read/modify/fsync-temp/atomic-rename serializes concurrent RPCs.
Corrupt/partial/oversized state is quarantined to a sibling `.corrupt-<uuid>` file,
logged at WARN without body text, and startup continues empty. I/O write failures
return errors and do not mutate the in-memory snapshot. No upstream writer changes.

## Router enumeration

The following 132 methods were enumerated from the REAL
`AdminRpcRegistry.buildRouter().registeredMethods` with pairing and subagent sources
configured, including the three methods added here. The capability matrix test
checks every registered method against explicit intentional tiers; an unclassified
new method cannot pass via the `admin.full` fallback.

- `agent.context`
- `agent.count`
- `agent.create`
- `agent.delete`
- `agent.get`
- `agent.list`
- `agent.update`
- `approval.submit`
- `archive.list`
- `block.attach`
- `block.create`
- `block.create_agent`
- `block.delete`
- `block.delete_agent`
- `block.detach`
- `block.get`
- `block.list`
- `block.list_agent`
- `block.update`
- `block.update_agent`
- `conversation.archive`
- `conversation.compact`
- `conversation.context_limit`
- `conversation.create`
- `conversation.delete`
- `conversation.fork`
- `conversation.get`
- `conversation.list`
- `conversation.list_agent`
- `conversation.recompile`
- `conversation.restore`
- `conversation.update`
- `cron.add`
- `cron.delete`
- `cron.delete_all`
- `cron.get`
- `cron.list`
- `cron.runs`
- `cron.trigger`
- `cron.update`
- `folder.list`
- `goal.command`
- `goal.get`
- `group.list`
- `health.check`
- `identity.get`
- `identity.list`
- `job.get`
- `job.list`
- `lease.list`
- `lease.upsert`
- `mcp.list`
- `memfs.commit_diff`
- `memfs.enable`
- `memfs.file_at_ref`
- `memfs.history`
- `memfs.list`
- `memfs.read`
- `memfs.write`
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
- `passage.create`
- `passage.delete`
- `passage.list`
- `permission_mode.get`
- `permission_mode.set`
- `plugins.consent`
- `plugins.disable`
- `plugins.disableAll`
- `plugins.enable`
- `plugins.inspect`
- `plugins.install`
- `plugins.list`
- `plugins.page.read`
- `plugins.remove`
- `plugins.view.action`
- `project.archive`
- `project.beadsRemoteStatus`
- `project.create`
- `project.delete`
- `project.get`
- `project.list`
- `project.provisionBeadsRemote`
- `project.update`
- `provider.connect`
- `provider.disconnect`
- `provider.list`
- `reflection.get`
- `reflection.run`
- `reflection.set`
- `run.get`
- `run.list`
- `run.record`
- `schedule.create`
- `schedule.delete`
- `schedule.get`
- `schedule.list`
- `secret.apply`
- `secret.list`
- `skill.install`
- `skill.list`
- `skill.list_agent`
- `skill.uninstall`
- `slash_command.list`
- `slash_command.list_agent`
- `step.list`
- `subagent.list`
- `subagent.todos`
- `tool.attach`
- `tool.create`
- `tool.delete`
- `tool.detach`
- `tool.get`
- `tool.list`
- `tool.update`
- `tool_return.get`
- `workspace.read_file`
- `workspace.search_files`
