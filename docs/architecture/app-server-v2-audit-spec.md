# App Server v2 and Meridian State Audit Specification

Status: implemented-system snapshot for audit

Snapshot date: 2026-07-27

Upstream baseline: `@letta-ai/letta-code@0.28.8`, Node `v24.18.0`

Protocol declaration SHA-256:
`68ef0a3683f7e57be02638d5973e7211493658dd0d0038e3e0d5f571da3116f4`

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

## Normative Sources

Audit in this order:

| Priority | Source | Authority |
| --- | --- | --- |
| 1 | Installed `protocol_v2.d.ts` with the pinned hash above | Exact upstream command/message declarations |
| 2 | `installed-protocol-v2-inventory.json` | Reviewed inventory of all 90 upstream commands and 99 upstream messages |
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
        | two local App Server v2 WebSockets
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

App Server v2 uses a control socket and a stream socket.

| Condition | Upstream 0.28.8 behavior | Meridian policy |
| --- | --- | --- |
| Second control socket | New socket closed with code 1008; active control remains | Do not open a competing control socket |
| Duplicate stream socket | New socket closed with code 1008; active stream remains | Do not open a competing stream socket |
| Stream socket disconnect | Control session and runtime remain active; replacement stream may attach | Mark transport degraded and rebuild both sockets |
| Control socket disconnect | Active control session is cleared; runtime shutdown follows listener behavior | Rebuild both sockets and reattach/sync runtimes |
| Official client sees either close | Rejects pending requests; does not reconnect or close sibling automatically | Kotlin reconnect supervisor owns recovery |

The reconnect-both policy is Meridian behavior, not an upstream requirement.

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
`change_device_state`, `remove_queue_item`

Messages:

`runtime_start_response`, `sync_response`, `abort_message_response`,
`update_device_status`, `update_loop_status`, `update_queue`, `stream_delta`,
`control_request`, `update_subagent_state`, `remove_queue_item_response`

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
- Neither extension is part of the upstream 90-command/99-message union.

## Iroh Admin RPC Contract

The current machine matrix contains 89 registered methods:

| Declared owner | Count | Execution class |
| --- | ---: | --- |
| `app_server_v2` | 30 | Native App Server v2 contract, usually native-first with a bounded fallback |
| `admin_rest_service` | 40 | Server-local HTTP adapter, currently port 8291 in production |
| `controller_native` | 9 | Wrapper process memory and pairing store |
| `vibesync_service` | 9 | Server-local VibeSync API, currently port 3099 |
| `capability_gated_unsupported` | 1 | Typed fail-closed response |

Exact method rows, authorization classes, data stores, native discriminants,
fallbacks, and migration slices are maintained in
`iroh-admin-ownership-matrix.json`. CI compares that file to the methods
actually registered by `AdminRpcRegistry.buildRouter`.

### Native-first agent mutation path

While the mobile app uses Iroh, agent administration follows:

```text
mobile agent editor
  -> Iroh admin_rpc("agent.update")
  -> AgentAdminHandlers
  -> agent_update on local App Server v2 WS
  -> Letta local-backend persistence
  -> shim HTTP PATCH only when the native attempt is unavailable/fails
```

Agent create, retrieve, update, delete, and list use the same native-first
policy. Model changes additionally call `controller.stopRuntime(agentId)` so
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
| Manual context limit changed through Iroh admin | App Server | No explicit handler invalidation yet |
| Other agent fields changed | App Server | Depends on App Server runtime behavior; not centrally classified |
| Transport generation changes | None | All cached canonical scopes invalidated and reattached |
| Conversation override changed | App Server | No dedicated wrapper invalidation rule documented |

An audit must classify every mutable agent/conversation field as either
live-read, runtime-captured, or restart-required. The current implementation
only has explicit invalidation for the cases listed above.

## Known Audit Findings

These are known inconsistencies, not accepted architecture:

1. **Projected context limit looked persisted.** The UI displayed 200k while
   `agent_retrieve` returned no context limit. This caused Letta Code's
   effective limit to remain undefined for the affected provider.
2. **Manual context-limit invalidation is incomplete.** `agent.update` evicts
   runtimes for model changes, but not yet for an explicit context-window
   change.
3. **Invalidation policy is field-specific and decentralized.** Model update,
   context repair, transport reconnect, and other configuration changes use
   different code paths.
4. **Ownership-matrix execution descriptions lag implementation.** Several
   matrix rows still describe shim HTTP as `current_backend` even though the
   handlers are now native-first. The owner decision is CI-enforced; execution
   order is not.
5. **Optional direct-disk read tier conflicts with the stated ownership rule.**
   `LocalBackendAdminStore` can serve selected reads, currently `agent.list`,
   when `LETTA_LOCAL_BACKEND_DIR` is configured. The matrix test says direct
   Letta storage access is forbidden but does not detect this implementation.
6. **Native fallback can obscure failures.** A native App Server mutation may
   fail and then succeed through the shim HTTP fallback. Without route telemetry
   the caller cannot prove which authority handled it.
7. **Two client transport paths remain.** Legacy mobile WS and Iroh can expose
   similar screens through different backend paths and state projections.
8. **Admin REST remains broad.** Forty registered methods still depend on the
   server-local admin REST adapter rather than native App Server v2.
9. **Upstream exposure exceeds Kotlin support.** The pinned upstream protocol
   has 90 commands and 99 messages; Kotlin types only the adopted subset.
   Unknown-frame preservation is not equivalent to supported behavior.
10. **Runtime persistence semantics are not schema-classified.** There is no
    single registry stating which agent/conversation fields are read live and
    which are captured when a runtime starts.

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
ANDROID_HOME=/opt/android-sdk \
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
