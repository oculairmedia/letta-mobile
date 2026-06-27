# App Server protocol fixtures

These fixtures document the Letta Code App Server v2 frame contract used by the
shared client tests.

## Verified sources

Checked on 2026-06-24 against `@letta-ai/letta-code@0.27.15`:

- `package.json`: host Node requirement is `>=22.19.0`; the local workstation
  used Node `v24.13.1`.
- `dist/types/types/protocol_v2.d.ts`: frame names and core field shapes.
- `dist/types/app-server-client.d.ts` and `dist/app-server-client.js`: channel
  URLs, optional bearer token behavior, control-channel request correlation,
  and one-active-turn-per-runtime behavior.
- `letta app-server --help`: loopback runs omit WS auth; non-loopback runs use
  `--ws-auth capability-token` or `--ws-auth signed-bearer-token` and clients
  send `Authorization: Bearer <token>`.

Verified core frame DTOs: `runtime_start`, `runtime_start_response`, `input`,
`stream_delta`, `sync`, `sync_response`, `abort_message`,
`abort_message_response`, `update_loop_status`, `update_device_status`,
`update_queue`, `update_subagent_state`, `external_tool_call_request`, and
`external_tool_call_response`.

Frames intentionally left permissive or raw in the Kotlin client: stream delta
payload variants, device status, queue items, subagent snapshots, and broader
App Server v2 command groups such as terminal, filesystem, memory, cron,
channel, and admin list/update commands. Unknown frame types preserve the raw
JSON object.

## Live loopback smoke

The successful live smoke used local loopback WebSockets with no WS auth and the
Letta Code local backend:

```powershell
pnpm dlx @letta-ai/letta-code@0.27.15 --backend local app-server --listen ws://127.0.0.1:4515
```

Transcript excerpt:

```text
connected ws://127.0.0.1:4515
> control runtime_start request_id=smoke-runtime-start
< control runtime_start_response ... runtime=.../local-conv-1 success=true
> control input runtime=.../local-conv-1
< control stream_delta ... delta=assistant_message ... "text":"pong" ... "run_id":"local-run-1"
> control sync request_id=smoke-sync ...
< control sync_response request_id=smoke-sync ... success=true
> control abort_message request_id=smoke-abort ... run_id=local-run-1
< control abort_message_response request_id=smoke-abort ... success=true aborted=true
```

The normal JVM integration test remains gated so CI does not require a live App
Server:

```powershell
$env:APP_SERVER_TEST_URL="ws://127.0.0.1:4515"
$env:APP_SERVER_TEST_AGENT_ID="agent-local-..."
$env:APP_SERVER_TEST_CONVERSATION_ID="local-conv-..."
.\gradlew.bat :sharedLogic:jvmTest --tests "*KtorAppServerWebSocketTransportIntegrationTest"
```
