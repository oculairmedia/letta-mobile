# App Server v2 and Meridian State Audit Specification

Status: implemented-system snapshot for audit

Snapshot date: 2026-07-29

Upstream baseline: `@letta-ai/letta-code@0.29.9`, Node `v24.18.0`

Protocol declaration SHA-256:
`41874aad00d23d3f63a5aaab86986ac707e1437a0d02625d7ee161d261f0a285`

## Purpose

This document is the entry point for auditing the complete App Server v2
integration used by Meridian. It distinguishes four contracts that must not be
treated as one:

1. The upstream Letta App Server v2 WebSocket protocol.
2. The subset represented by Meridian's typed Kotlin protocol.
3. Meridian's Iroh `admin_rpc` extension and its routing fallbacks.
4. State ownership, persistence, projections, defaults, and runtime caches.

The machine-readable inventories remain authoritative for exact discriminants
and registered methods. This document explains how those inventories compose
into the running system and records known consistency risks.

Implementation and rollout handoff:
[LettaShim Retirement Implementation and Deployment Runbook](lettashim-retirement-deployment-runbook.md)

## Required End State: No LettaShim

LettaShim is a migration dependency, not a permanent architecture component.
The target production path is:

```text
Mobile/Desktop
  -> Iroh QUIC
  -> Kotlin wrapper
      -> stock Letta App Server v2 WS for Letta-owned operations
      -> explicitly owned bounded service for operations absent from v2
      -> VibeSync directly for project operations
      -> wrapper-owned state for pairing and transport health
```

The target has no route to port 8291, no legacy mobile WS dependency, no
native-to-shim fallback, and no direct reads or writes of Letta backend files.
An operation that has no approved post-shim owner must return a typed
`capability_unavailable` result. It must not silently regain behavior through a
generic proxy.

Shim retirement is complete only when all of the following are true:

1. `LETTA_IROH_ADMIN_BASE_URL` and the default `http://127.0.0.1:8291` are
   removed from production wrapper wiring.
2. No production handler constructs an `AdminProxyClient` for LettaShim.
3. `NativeAdmin` does not fall back to LettaShim after timeout, protocol error,
   or unsuccessful response.
4. Every `shim_until_cutover` matrix row has moved to `none`, a named bounded
   service, or `deny_fail_closed`.
5. Subagent state comes from controller/runtime state, not
   `HttpSubagentRegistrySource`.
6. The legacy mobile WS send, approval, cron, and timeline paths are either
   removed or isolated as an unsupported legacy connector outside the Iroh
   product path.
7. A full mobile parity suite passes with nothing listening on port 8291.
8. No replacement component reads Letta storage directly to recreate shim
   behavior.

## Normative Sources

Audit in this order:

| Priority | Source | Authority |
| --- | --- | --- |
| 1 | Installed `protocol_v2.d.ts` with the pinned hash above | Exact upstream command/message declarations |
| 2 | `installed-protocol-v2-inventory.json` | Reviewed inventory of all 91 upstream commands and 100 upstream messages |
| 3 | `app-server-v2-contract-matrix.json` | Captured socket, channel, ownership, reconnect, and idempotency policy |
| 4 | `AppServerProtocol.kt` | Kotlin's typed wire representation |
| 5 | `iroh-admin-ownership-matrix.json` | Declared owner for every registered Iroh `admin_rpc` method |
| 6 | `AdminRpcRegistry.kt` and `*AdminHandlers.kt` | Actual Iroh admin routing and fallback order |
| 7 | `AppServerTurnEngine.kt` and `AppServerContextWindowPreflight.kt` | Runtime, turn, approval, and context-recovery policy |
| 8 | Mobile repositories and ViewModels | Client projection and UI defaults, never backend authority |

Relative repository paths:

- `android-compose/sharedLogic/src/jvmTest/resources/appserver/installed-protocol-v2-inventory.json`
- `android-compose/sharedLogic/src/jvmTest/resources/appserver/app-server-v2-contract-matrix.json`
- `android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/transport/appserver/AppServerProtocol.kt`
- `android-compose/sharedLogic/src/jvmTest/resources/appserver/iroh-admin-ownership-matrix.json`
- `android-compose/sharedLogic/src/jvmAndAndroid/kotlin/com/letta/mobile/data/controller/node/iroh/AdminRpcRegistry.kt`
- `android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/runtime/AppServerTurnEngine.kt`
- `android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/runtime/AppServerContextWindowPreflight.kt`

The TypeScript declaration is authoritative for field-level upstream schema.
Do not maintain a second hand-written copy of every field here. The verifier
hashes that declaration and compares both discriminant unions to the checked-in
inventory.

## Production Topology

The current server deployment is:

```text
Android/Desktop client
        |
        | Iroh QUIC, UDP 4501
        v
Meridian Kotlin Iroh wrapper
        |
        | one bidirectional App Server v2 WebSocket
        | ws://127.0.0.1:4500
        v
Letta Code App Server
        |
        | Letta local-backend persistence
        v
Durable agent, conversation, message, memory, cron, and provider state
```

Additional server-local routes used by `admin_rpc`:

```text
Kotlin wrapper -> admin REST adapter / legacy LettaShim on 127.0.0.1:8291
Kotlin wrapper -> VibeSync service on 127.0.0.1:3099
Kotlin wrapper -> controller-owned memory for health, subagents, and pairing
Kotlin wrapper -> optional LocalBackendAdminStore for selected reads
```

The client never dials ports 4500, 8291, or 3099 while using an `iroh://`
backend. The wrapper is the transport and authorization boundary.

When the client uses the legacy WS backend instead of Iroh, its mobile WS
traffic is a separate LettaShim path. That path must not be used as evidence of
Iroh/App Server v2 behavior.

## Upstream Socket Contract

App Server v2 in Letta Code 0.29.9 uses one bidirectional WebSocket per client
at `/ws`. Control responses and runtime events share that socket. The legacy
`?channel=control|stream` URLs are rejected during upgrade with HTTP 426.

| Condition | Upstream 0.29.9 behavior | Meridian policy |
| --- | --- | --- |
| One client connects to `/ws` | Commands, responses, and events share one socket | Decode once and route frames internally by message type |
| Legacy split-channel URL | Upgrade rejected with HTTP 426 `Upgrade Required` | Treat the actual 426 handshake response as terminal; never construct a channel query |
| Concurrent client connects | Each client receives an independent bidirectional session | Keep request registries and runtime ownership scoped by connection generation |
| Session disconnect | Pending requests are rejected; reconnect is a client concern | Stop new sends, rebuild one socket, then reattach and sync affected runtimes |
| Stream consumer is slow | Upstream continues multiplexing control and event frames | Never block socket reads on stream delivery; preserve the loss-sensitive socket handoff and let `RuntimeEventFanout` own bounded per-subscriber buffering |

The reconnect and internal delivery policies are Meridian behavior, not
upstream protocol requirements.

### Correlation and event ordering

- Request/response operations correlate by `request_id`.
- Turn creation uses `client_message_id` as its reconciliation/deduplication key.
- Runtime events use `idempotency_key` for deduplication.
- `event_seq` orders replay inside one runtime.
- Runtime identity is the canonical `(agent_id, conversation_id,
  acting_user_id)` scope returned by `runtime_start`.
- A transport generation change invalidates cached runtime scopes. They must be
  reattached and synchronized before new sends resume.

### Retry classes

| Class | Examples | Disconnect policy |
| --- | --- | --- |
| Session setup/read | auth, runtime start, sync, list/retrieve operations | Retry with a new request ID after reconnect |
| Turn input | `input/create_message` | Do not blindly retry; reconcile by `client_message_id` and sync |
| Approval response | `input/approval_response` | Do not blindly retry; sync pending approvals first |
| Abort | `abort_message` | Sync, then retry against the same run target when still required |
| External tool result | `external_tool_call_response` | Never re-execute the tool; replay only its cached result for the same request |
| Admin mutation | create/update/delete/compact/enable/disable/cron mutation | Treat outcome as ambiguous; reconcile before reissuing |
| Event | stream/status/queue/subagent update | Dedupe by runtime plus `idempotency_key` |

## Complete Upstream Capability Inventory

This section lists every discriminant in the pinned upstream unions. Exposure
means the operation exists upstream; it does not mean Meridian currently has a
typed API or UI for it.

### 1. Runtime turns

Classification: exposed

Commands:

`runtime_start`, `input`, `sync`, `abort_message`,
`change_device_state`, `remove_queue_item`, `app_server_info`

Messages:

`runtime_start_response`, `sync_response`, `abort_message_response`,
`update_device_status`, `update_loop_status`, `update_queue`, `stream_delta`,
`control_request`, `update_subagent_state`, `remove_queue_item_response`,
`app_server_info_response`

### 2. Agent CRUD

Classification: exposed

Commands:

`agent_list`, `agent_retrieve`, `agent_create`, `agent_update`,
`agent_delete`, `create_agent`

Messages:

`agent_list_response`, `agent_retrieve_response`, `agent_create_response`,
`agent_update_response`, `agent_delete_response`, `create_agent_response`

### 3. Conversation CRUD and hydration

Classification: partial; upstream has no `conversation_delete`

Commands:

`conversation_list`, `conversation_retrieve`, `conversation_create`,
`conversation_update`, `conversation_recompile`, `conversation_fork`,
`conversation_messages_list`, `conversation_compact`

Messages:

`conversation_list_response`, `conversation_retrieve_response`,
`conversation_create_response`, `conversation_update_response`,
`conversation_recompile_response`, `conversation_fork_response`,
`conversation_messages_list_response`, `conversation_compact_response`

### 4. Models and providers

Classification: exposed

Commands:

`list_models`, `update_model`, `update_toolset`, `list_connect_providers`,
`connect_provider`, `disconnect_provider`, `chatgpt_usage_read`

Messages:

`list_models_response`, `update_model_response`, `update_toolset_response`,
`list_connect_providers_response`, `connect_provider_response`,
`disconnect_provider_response`, `chatgpt_usage_read_response`

### 5. Memory filesystem

Classification: exposed

Commands:

`list_memory`, `memory_history`, `memory_file_at_ref`, `memory_commit_diff`,
`read_memory_file`, `write_memory_file`, `delete_memory_file`, `enable_memfs`

Messages:

`list_memory_response`, `memory_history_response`,
`memory_file_at_ref_response`, `memory_commit_diff_response`,
`read_memory_file_response`, `write_memory_file_response`,
`delete_memory_file_response`, `enable_memfs_response`, `memory_updated`

### 6. Skills

Classification: partial; upstream has no `skill_list` command

Commands:

`skill_enable`, `skill_disable`

Messages:

`skill_enable_response`, `skill_disable_response`, `skills_updated`

Skill hydration is projected from
`update_device_status.device_status.current_available_skills`.

### 7. Crons

Classification: exposed

Commands:

`cron_list`, `cron_add`, `cron_get`, `cron_runs`, `cron_trigger`,
`cron_update`, `cron_delete`, `cron_delete_all`

Messages:

`cron_list_response`, `cron_add_response`, `cron_get_response`,
`cron_runs_response`, `cron_trigger_response`, `cron_update_response`,
`cron_delete_response`, `cron_delete_all_response`, `crons_updated`

### 8. Channels

Classification: exposed

Commands:

`channels_list`, `channel_accounts_list`, `channel_account_create`,
`channel_account_update`, `channel_account_bind`, `channel_account_unbind`,
`channel_account_delete`, `channel_account_start`, `channel_account_stop`,
`channel_get_config`, `channel_set_config`, `channel_start`, `channel_stop`,
`channel_pairings_list`, `channel_pairing_bind`, `channel_routes_list`,
`channel_targets_list`, `channel_target_bind`, `channel_route_remove`,
`channel_route_update`

Messages:

`channels_list_response`, `channel_accounts_list_response`,
`channel_account_create_response`, `channel_account_update_response`,
`channel_account_bind_response`, `channel_account_unbind_response`,
`channel_account_delete_response`, `channel_account_start_response`,
`channel_account_stop_response`, `channel_get_config_response`,
`channel_set_config_response`, `channel_start_response`,
`channel_stop_response`, `channel_pairings_list_response`,
`channel_pairing_bind_response`, `channel_routes_list_response`,
`channel_targets_list_response`, `channel_target_bind_response`,
`channel_route_remove_response`, `channel_route_update_response`,
`channels_updated`, `channel_accounts_updated`, `channel_pairings_updated`,
`channel_routes_updated`, `channel_targets_updated`

### 9. Secrets

Classification: exposed-sensitive

Commands:

`secret_list`, `secret_apply`

Messages:

`secret_list_response`, `secret_apply_response`

The upstream `secret_list_response` contract includes plaintext values. These
operations must remain unavailable through generic `admin_rpc` until an
explicit authorization and redaction design is approved.

### 10. Filesystem, terminal, and Git

Classification: exposed

Commands:

`terminal_spawn`, `terminal_input`, `terminal_resize`, `terminal_kill`,
`search_files`, `grep_in_files`, `list_in_directory`, `get_tree`,
`read_file`, `write_file`, `watch_file`, `unwatch_file`, `edit_file`,
`file_ops`, `get_cwd_map`, `search_branches`, `checkout_branch`

Messages:

`terminal_output`, `terminal_spawned`, `terminal_exited`,
`search_files_response`, `grep_in_files_response`,
`list_in_directory_response`, `get_tree_response`, `read_file_response`,
`write_file_response`, `file_ops`, `edit_file_response`, `file_changed`,
`get_cwd_map_response`, `search_branches_response`,
`checkout_branch_response`

### 11. External tools

Classification: exposed

Command:

`external_tool_call_response`

Message:

`external_tool_call_request`

### 12. Experiments, reflection, and commands

Classification: exposed

Commands:

`get_experiments`, `set_experiment`, `get_reflection_settings`,
`set_reflection_settings`, `execute_command`

Messages:

`get_experiments_response`, `set_experiment_response`,
`get_reflection_settings_response`, `set_reflection_settings_response`

## Kotlin Typed Protocol Surface

Meridian intentionally types a subset of the complete upstream inventory.
Unknown upstream frames are retained as unknown frames for diagnostics; their
presence does not imply functional support.

Typed top-level client commands:

`auth`, `runtime_start`, `input`, `sync`, `abort_message`,
`external_tool_call_response`, `admin_rpc`, `agent_list`, `agent_retrieve`,
`agent_create`, `agent_update`, `agent_delete`, `conversation_list`,
`conversation_retrieve`, `conversation_create`, `conversation_update`,
`conversation_messages_list`, `conversation_compact`, `list_models`,
`skill_enable`, `skill_disable`, `cron_list`, `cron_add`, `cron_get`,
`cron_runs`, `cron_trigger`, `cron_update`, `cron_delete`, `cron_delete_all`,
`get_reflection_settings`, `set_reflection_settings`

Typed `input` payloads:

`create_message`, `approval_response` with `allow` or `deny`

Typed inbound frames:

`auth_response`, `runtime_start_response`, `sync_response`,
`abort_message_response`, `stream_delta`, `update_loop_status`,
`update_device_status`, `update_queue`, `update_subagent_state`,
`external_tool_call_request`, `control_request`, `admin_rpc_response`,
`list_models_response`, `skill_enable_response`, `skill_disable_response`,
`cron_list_response`, `cron_add_response`, `cron_get_response`,
`cron_runs_response`, `cron_trigger_response`, `cron_update_response`,
`cron_delete_response`, `cron_delete_all_response`,
`get_reflection_settings_response`, `set_reflection_settings_response`,
`agent_list_response`, `agent_retrieve_response`, `agent_create_response`,
`agent_update_response`, `agent_delete_response`,
`conversation_list_response`, `conversation_retrieve_response`,
`conversation_create_response`, `conversation_update_response`,
`conversation_messages_list_response`, `conversation_compact_response`

Meridian transport extensions:

- `auth` and `auth_response` provide wrapper authentication.
- `admin_rpc` and `admin_rpc_response` expose the Iroh admin router.
- Neither extension is part of the upstream 91-command/100-message union.

## Iroh Admin RPC Contract

The current machine matrix contains 89 registered methods:

| Declared owner | Count | Execution class |
| --- | ---: | --- |
| `app_server_v2` | 29 | Native App Server v2 contract (fail-closed; no shim fallback) |
| `admin_rest_service` | 36 | Explicitly injected bounded admin REST adapter (`LETTA_IROH_ADMIN_REST_BASE_URL`) |
| `controller_native` | 9 | Wrapper process memory and pairing store |
| `vibesync_service` | 9 | Explicitly injected VibeSync product API (`LETTA_IROH_VIBESYNC_BASE_URL`) |
| `capability_gated_unsupported` | 6 | Typed fail-closed response |

Exact method rows, authorization classes, data stores, native discriminants,
fallbacks, production first routes, post-shim owners, and migration slices are
maintained in `iroh-admin-ownership-matrix.json`. CI compares that file to the
methods actually registered by `AdminRpcRegistry.buildRouter`.

### Native-first agent mutation path

While the mobile app uses Iroh, agent administration follows:

```text
mobile agent editor
  -> Iroh admin_rpc("agent.update")
  -> AgentAdminHandlers
  -> agent_update on local App Server v2
  -> Letta local-backend persistence
```

Agent create, retrieve, update, delete, and list are fail-closed native.
Model / context-window / tools / skills / memory changes call
`controller.stopRuntime(agentId)` (which also invalidates the turn engine) so
the next turn starts a fresh runtime.

### Other routing classes

- Conversation CRUD and message hydration are native-first where the pinned
  protocol has a matching command.
- `conversation.delete` fails closed because upstream has no such operation;
  archive is the supported lifecycle operation.
- Cron and reflection handlers are native and have no shim fallback.
- Admin REST domains include runs, archives, folders, passages, identities,
  embedding models, providers, schedules/jobs, tools, blocks, MCP, goals, and
  slash commands.
- Project operations are owned by VibeSync, not Letta App Server.
- Health, subagent state, and peer pairing are wrapper-owned.
- Secrets are not exposed by generic Iroh admin routing.

## LettaShim Retirement Ledger

The ownership-matrix fallback totals are:

- 0 as `shim_until_cutover`
- 83 as `none`
- 6 as `deny_fail_closed`

Phase 4 removed the last migration-time shim fallbacks: health is
controller-native only, and subagent list/todos hydrate from
`ControllerSubagentRegistrySource` (`update_subagent_state`) instead of
LettaShim HTTP discovery. Bounded admin REST still requires an explicit
`LETTA_IROH_ADMIN_REST_BASE_URL`; goal and slash command methods remain
product-removed (`deny_fail_closed`).

### Current dependency classes

| Class | Current behavior | Required correction |
| --- | --- | --- |
| 40 `admin_rest_service` methods | Production injects the same port-8291 base used by LettaShim | Provide a separately deployed, bounded, versioned service for each approved domain, adopt an upstream v2 command, or fail closed |
| Native-first agent/conversation/message routes | Try App Server v2, then optional direct disk, then LettaShim | Prove native parity, remove disk and HTTP fallback, and return typed native failures |
| Model catalog | `model.list` defaults to shim REST shape; native shape is opt-in | Define one canonical mobile model projection from `list_models`; make native the only Letta-owned source |
| Skills | Lists and agent-scoped install/uninstall use shim semantics; native v2 exposes filesystem enable/disable | Choose and document one semantic model; adapt the UI/API or provide a bounded non-shim owner |
| Approval submission | Live approval uses the controller; failure/no controller falls back to shim pending-approval REST | Recover approvals through v2 `sync` and fail closed when no matching live/recovered request exists |
| Conversation delete | Matrix says fail closed, but production uses shim delete because `shimRetired` defaults false | Remove delete or map product behavior to archive; enable fail-closed behavior unconditionally |
| Subagents | Matrix calls the methods controller-native, but production discovers `HttpSubagentRegistrySource` from port 8291 | Build the registry from runtime events/controller state and remove HTTP discovery |
| Health | Controller health is native in production; shim is used only when no controller is wired | Require a controller in production and remove the shim branch |
| Projects | Matrix still says `shim_until_cutover`, but production calls VibeSync directly on port 3099 | Update the matrix to `none` and retain typed capability-unavailable behavior when VibeSync is absent |
| Native circuit breaker | One timeout/error opens a global 60-second breaker, sending unrelated operations to fallback | Remove shim fallback; make capability state per command/domain and expose typed unavailable/degraded results |
| Optional local backend tier | Selected agent/conversation/message reads may bypass both v2 and shim by reading files | Remove it from production; it is not an acceptable shim replacement |
| Legacy client WS | Non-Iroh clients can still use `WsChatBridge`, shim detection, cron WS, approval, and timeline subscription code | Establish an explicit support boundary, migrate required behavior to Iroh/v2, then delete the shim connector |

### Port-8291 removal work by domain

The following `admin_rest_service` surfaces need an approved owner before the
port can be removed:

- Agent context: `agent.context`.
- Runs and steps: `run.list`, `run.get`, `step.list`.
- Archives and memory administration: `archive.list`, `folder.list`,
  `passage.list/create/delete`, `group.list`.
- Identities: `identity.list`, `identity.get`.
- Model/provider administration: `model.list.embedding`, `provider.list`.
- Schedules and jobs: `schedule.list/get/create/delete`, `job.list/get`.
- Tools: `tool.list/get/create/update/delete/attach/detach`.
- Blocks: `block.list/get/create/update/delete/attach/detach/update_agent`.
- MCP: `mcp.list`.
- Goals: `goal.get`, `goal.command`.
- Slash commands: `slash_command.list`, `slash_command.list_agent`.

For each domain, the decision must be one of:

1. Adopt an existing upstream App Server v2 command and add a typed Kotlin
   projection.
2. Propose and pin a new upstream v2 contract.
3. Route to a separately owned bounded service with its own health,
   authorization, version, schema, and tests.
4. Remove the feature and return `capability_unavailable`.

Growing a generic REST mirror inside the Kotlin wrapper or reading the Letta
backend files directly are not valid choices.

### Native-first fallback removal work

Before deleting each native-to-shim fallback:

1. Verify request and response semantic parity, not just matching names.
2. Add projection tests for pagination, filtering, ordering, nullability, and
   error shape.
3. Add mutation tests for persistence and runtime invalidation.
4. Add ambiguous-disconnect reconciliation tests.
5. Run the route with port 8291 closed.
6. Change fallback to a typed failure.
7. Remove the dead proxy branch and update the ownership matrix.

Native App Server success must be the normal route. Native timeout must be a
visible v2 availability failure, not a trigger that changes the state authority
for the request.

### Shim-free release gates

| Gate | Required evidence |
| --- | --- |
| Static dependency gate | Production source has no LettaShim base URL, `/shim/` path, or shim `AdminProxyClient` construction |
| Ownership gate | Zero `shim_until_cutover` rows in the machine matrix |
| Network gate | Wrapper and complete mobile parity probes pass while port 8291 is closed/rejected |
| State-authority gate | Every mutation is retrieved from the same authority that accepted it |
| Runtime gate | Every runtime-captured field has a tested invalidation rule |
| Approval gate | Ask User and tool approvals survive reconnect without shim pending-approval REST |
| Subagent gate | Subagent list/todos hydrate from controller/runtime state only |
| Projection gate | Native model, skill, message, conversation, and agent shapes satisfy mobile contracts |
| Deployment gate | Systemd/container definitions do not order, require, configure, or health-check LettaShim |
| Observability gate | Soak telemetry records zero shim fallback attempts; native route and typed failures are distinguishable |
| Storage gate | No direct Letta backend reader/writer is enabled in production |
| Legacy removal gate | Mobile WS shim detector/bridge and shim-only repositories are removed or moved outside supported builds |

The final cutover should deliberately stop LettaShim first, execute these gates,
and only then remove its deployment. A successful test while the shim is still
reachable does not prove independence.

## State Ownership and Persistence

File-backed storage does not mean live configuration files are a reactive API.
All durable Letta mutations must go through an App Server or another explicitly
owned public contract.

| State | Authority | Durable store | Runtime/cache copies | Valid mutation path |
| --- | --- | --- | --- | --- |
| Agent configuration | Letta App Server/local backend | Letta local-backend files | App Server runtime plus wrapper runtime registry | Native v2 `agent_create/update/delete`; bounded fallback during migration |
| Conversation configuration | Letta App Server/local backend | Letta local-backend files | App Server conversation runtime plus mobile projection | Native v2 conversation commands |
| Messages and active context membership | Letta App Server/local backend | Message records plus `in_context_message_ids` | Runtime event stream, wrapper reducer, mobile timeline cache | `input`, native hydration, `conversation_compact` |
| Core memory/MemFS | Letta App Server/local backend | Letta memory storage | Device-status and client projections | Native MemFS commands where adopted; otherwise owned adapter |
| Runtime scope and loop state | Letta App Server process | Not independently durable | App Server and wrapper runtime registries | `runtime_start`, `sync`, `abort_message`; rebuild after generation loss |
| Pending approval/tool gate | Letta runtime | Runtime-dependent; recover through sync | Wrapper turn engine and mobile UI | `control_request` plus `input/approval_response` |
| Mobile settings/UI defaults | Mobile app | Mobile settings store | ViewModel/UI state | Mobile settings APIs only; never evidence of backend persistence |
| Admin REST projections | Owning adapter/Letta backend | Depends on owning service | HTTP response and mobile repositories | Bounded server-local adapter |
| Project state | VibeSync | VibeSync registry | Iroh/mobile projections | VibeSync service |
| Peer authorization | Kotlin wrapper | Pairing JSON and Iroh key files | Wrapper pairing service | Pairing `admin_rpc` methods |

### Required invariants

1. UI defaults and fallback display values must be labelled as projected, not
   persisted.
2. A successful mutation response must identify the authority that committed
   it.
3. Direct edits to Letta backend files are unsupported and must not be relied
   on for live reload.
4. A persisted agent change does not by itself prove an existing runtime
   reloaded it.
5. Any field captured at `runtime_start` needs an explicit cache-invalidation
   rule.
6. Fallback routing must be observable; a native failure followed by shim
   success must not be indistinguishable from native success in audit logs.
7. Conversation-specific values take precedence over agent defaults where the
   upstream contract defines both.

## Context Window and Compaction Policy

The wrapper default is `200000`. Its purpose is to make Letta Code's configured
sliding-window mode computable for providers that do not supply a discoverable
context limit.

Effective context limit:

```text
conversation.context_window_limit
  else agent.context_window_limit
  else agent.model_settings.context_window_limit
  else persist wrapper default 200000 on the agent
```

Before a normal user-message turn, the wrapper:

1. Retrieves the authoritative agent and conversation through App Server v2.
2. Persists `200000` only when the agent has no explicit limit.
3. Uses a conversation-specific limit when present.
4. Reads only the newest 20 messages.
5. Intersects overflow evidence with `in_context_message_ids` when available.
6. Recognizes provider `length` termination with either over-limit usage or an
   empty assistant result.
7. Calls native `conversation_compact` with:

```json
{
  "compaction_settings": {
    "mode": "sliding_window",
    "sliding_window_percentage": 0.3
  }
}
```

8. Drops the wrapper's cached runtime when it changed configuration or
   compacted, forcing a fresh `runtime_start`.

Approval responses and Ask User answers bypass this preflight because they
must settle an existing runtime gate rather than create a new user-message
turn.

The preflight is fail-closed. If authoritative agent, conversation, message,
update, or compaction operations fail, the user turn is not started.

## Cache Invalidation Matrix

| Change | Persisted owner | Current wrapper invalidation |
| --- | --- | --- |
| Agent model changed through Iroh admin | App Server | Explicit `stopRuntime(agentId)` |
| Missing context limit repaired by preflight | App Server | Current turn runtime discarded |
| Conversation compacted by preflight | App Server | Current turn runtime discarded |
| Manual context limit changed through Iroh admin | App Server | `RuntimeInvalidationPolicy` → `stopRuntime(agentId)` |
| Other agent fields changed | App Server | Classified in `RuntimeInvalidationPolicy.AGENT_RESTART_FIELDS` (model, tools, skills, memory, system, embeddings, …) |
| Transport generation changes | None | All cached canonical scopes invalidated and reattached |
| Conversation override changed | App Server | `RuntimeInvalidationPolicy.CONVERSATION_RESTART_FIELDS` → `stopRuntime(agentId)` when agent id known |
| Skill enable/disable | App Server | Optional `agent_id` → `stopRuntime(agentId)` |

An audit must classify every mutable agent/conversation field as either
live-read, runtime-captured, or restart-required. The current implementation
only has explicit invalidation for the cases listed above.

## Known Audit Findings

Residual inconsistencies after Phases 1–4 (not accepted end state):

1. **Legacy mobile WS still coexists with Iroh.** Similar screens can still be
   reached through the retired ChannelTransport path. Deletion is tracked as
   `letta-mobile-lgns8.10.4.1`.
2. **Bounded admin REST remains broad.** Thirty-six registered methods still
   depend on an explicitly injected `admin_rest_service` adapter rather than
   native App Server v2. Those adapters must not default to a LettaShim base.
3. **Upstream exposure exceeds Kotlin support.** The pinned upstream protocol
   has 90 commands and 99 messages; Kotlin types only the adopted subset.
   Unknown-frame preservation is not equivalent to supported behavior.
4. **`skill.list_agent` is intentionally unavailable.** Process-global
   availability must not be presented as per-agent install state. Reintroduce
   only with a real assignment projection.
5. **Skills catalog hydration is event-driven.** `skill.list` can return an
   empty success before the first `update_device_status` / `skills_updated`
   snapshot arrives; clients should treat empty-as-pending or trigger sync.
6. **Model list wire shape still needs projection.** Native `list_models`
   entries are not yet fully mapped into the mobile `LlmModel` catalog schema.
7. **Message get has a searchable window ceiling.** `message.get` /
   `tool_return.get` walk up to 20 × 500 newest-first pages; older IDs fail
   closed with `not_found` until a direct retrieve exists upstream.
8. **Route telemetry coverage is incomplete.** Non-`NativeAdmin` owners
   (bounded REST, VibeSync, some controller-native paths) do not yet emit
   `IrohAdminRoute` selection events on every dispatch.
9. **Wrapper packaging (Phase 5) is not yet the production artifact.** Staging
   still needs the installable `iroh-wrapper-cli` distribution and host-level
   proof that the *wrapper* process never dials the retired shim port.

## Audit Procedure

### 1. Verify the installed upstream contract

```bash
~/.nvm/versions/node/v24.18.0/bin/node \
  scripts/appserver/verify-contract-baseline.mjs \
  --package-root ~/letta-code-install/node_modules/@letta-ai/letta-code

node scripts/appserver/verify-v2-audit-doc.mjs
```

Any version, declaration hash, command union, or message union change requires
a reviewed baseline and audit-document update before implementation changes.

### 2. Run contract and ownership tests

From `android-compose`:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}" \
./gradlew \
  :sharedLogic:jvmTest \
  --tests 'com.letta.mobile.data.transport.appserver.AppServerContractBaselineTest' \
  --tests 'com.letta.mobile.data.controller.node.iroh.IrohAdminOwnershipMatrixTest'
```

### 3. Audit every Iroh admin method

For each row in `iroh-admin-ownership-matrix.json`, verify:

- actual first route and every fallback;
- authorization before routing;
- source of truth and durable store;
- mutation idempotency and ambiguous-disconnect behavior;
- runtime/cache invalidation;
- response projection and field loss;
- route telemetry;
- whether the shim can be removed or must remain an explicit owner.

The audit target is always shim removal. "Must remain in LettaShim" is not a
valid final decision; use an upstream contract, named bounded service, product
removal, or typed denial.

### 4. Audit every mutable state field

Create a row for every agent and conversation configuration field:

| Field | Persisted schema path | UI path | Mutation command | Read timing | Cache owner | Invalidation trigger | Tested |
| --- | --- | --- | --- | --- | --- | --- | --- |

No field is complete until all columns are known.

### 5. Test state convergence

For each mutation:

1. Capture authoritative `retrieve` output.
2. Apply the mutation through Iroh.
3. Capture the native/fallback route used.
4. Retrieve again through App Server v2.
5. Inspect a newly started runtime.
6. Inspect an already-running runtime.
7. Restart only the wrapper and repeat.
8. Restart only App Server and repeat.
9. Confirm the mobile projection matches authoritative state.

### 6. Test reconnect ambiguity

Drop the transport after send but before response for every mutation class.
Verify that reads replay, turns reconcile, approvals remain recoverable,
external tools do not re-execute, and mutations are not blindly duplicated.

### 7. Record audit output

Audit findings should update:

- this document for architecture-level conclusions;
- the machine ownership matrix for route/owner decisions;
- the upstream baseline fixtures for protocol changes;
- `bd` issues for implementation work;
- tests that fail when a resolved invariant regresses.

### 8. Prove shim independence

Run the wrapper and all parity probes with port 8291 unavailable. Capture:

- registered capabilities;
- every `admin_rpc` method result;
- native command and response types;
- typed unavailable results;
- runtime invalidations;
- reconnect/approval recovery;
- any attempted connection to port 8291.

Any attempted connection to port 8291 is a failed shim-retirement audit, even
when the operation later succeeds through another route.

## Change Control

Changes requiring review of this specification:

- upgrading `@letta-ai/letta-code`;
- adding or removing an upstream typed command/frame;
- registering an Iroh `admin_rpc` method;
- changing native-first or fallback order;
- adding direct backend storage access;
- adding a displayed default for persisted state;
- changing runtime cache keys or invalidation;
- changing context-window precedence or compaction;
- exposing secrets, terminal, filesystem, Git, or channels over Iroh;
- changing reconnect or mutation retry policy.

The audit is complete only when the protocol inventory, actual handlers,
state-field matrix, runtime invalidation behavior, and mobile projections agree.
