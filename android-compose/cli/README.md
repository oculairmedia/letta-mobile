# letta-mobile-cli

Headless CLI for driving Letta Mobile transport and timeline code paths without
using a device. It is tracked by `letta-mobile-q9t4t`.

The CLI now has two useful modes:

- `replay` / `dump-timeline` / `record-cursor-state` fold recorded JSONL fixtures
  and REST history through the same reducer/writer paths used by the app. (The
  admin-shim WebSocket commands `connect`, `send`, `capture`, `record`,
  `disconnect`, and `reconnect` were removed with the shim WS pathway; existing
  JSONL recordings still replay, but new ones can no longer be recorded here.)
- `rest` exposes generic authenticated JSON access to any Letta REST endpoint,
  which is the foundation for the broader device-free admin/provisioning CLI.
- Typed resource command groups wrap the app's main REST-backed admin surfaces
  so agents, conversations, tools, memory, files, projects, MCP, runs, jobs, and
  related resources can be managed without opening the Android UI.
- `setup apply` / `setup export` provide a declarative JSON/YAML format for
  replaying profile and server setup from a workstation.
- `app-server-serve` / `app-server-smoke` exercise the official Letta App
  Server path: launch a host `letta app-server` process, then send a typed turn
  through the shared App Server client.
- `stream` keeps the older direct REST/SSE tracer for low-level comparison when
  debugging server wire frames or merge behavior.

## Build and run

The module is still an Android library because the production timeline code
lives in `:core`, which is also Android-backed. The entrypoint is no longer a
JUnit test: `:cli:run` is a `JavaExec` task that uses the Android unit-test
runtime classpath only to provide Android stubs for the JVM process.

```powershell
cd android-compose
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME="$env:USERPROFILE\AppData\Local\Android\Sdk"
.\gradlew.bat :cli:run -PcliArgs="<command> [options]"
```

No args prints the short command list.

## Shared options

Most REST-backed commands accept:

| env / flag | what |
| --- | --- |
| `LETTA_BASE_URL` / `--base-url` | Letta base URL, default `https://letta.oculair.ca` |
| `LETTA_TOKEN` / `--token` | Bearer token; falls back to the selected CLI profile |
| `LETTA_PROFILE` / `--profile` | CLI profile name; defaults to the active profile |

Conversation and agent commands also use:

| env / flag | what |
| --- | --- |
| `LETTA_AGENT_ID` / `--agent` | Default agent id for agent-scoped commands |
| `LETTA_CONVERSATION_ID` / `--conversation` | Conversation id for dump-timeline/replay |

## Profiles

Profiles live in `%USERPROFILE%\.letta-mobile-cli\profiles.json` by default
or under `LETTA_MOBILE_CLI_HOME` when set. They provide device-free backend
configuration for CLI runs.

```powershell
.\gradlew.bat :cli:run -PcliArgs="profile set dev --base-url https://letta.oculair.ca --token $env:LETTA_TOKEN --agent agt_x --conversation conv_x --active"
.\gradlew.bat :cli:run -PcliArgs="profile list"
.\gradlew.bat :cli:run -PcliArgs="profile show dev --show-token"
.\gradlew.bat :cli:run -PcliArgs="profile use dev"
.\gradlew.bat :cli:run -PcliArgs="profile export --out cli-profiles.json"
.\gradlew.bat :cli:run -PcliArgs="profile import --file cli-profiles.json"
```

Profile defaults are used by `dump-timeline`, `replay`, `stream`, `rest`, and
the typed resource command groups when explicit flags/env vars are omitted.

## Commands

### `dump-timeline`

Fetch conversation history via REST, hydrate the headless timeline store, and
emit stable JSON suitable for diffing.

```powershell
.\gradlew.bat :cli:run -PcliArgs="dump-timeline --conversation conv_x --limit 200"
```

### `record-cursor-state`

Snapshot the highest observed cursor state from an existing JSONL recording:

```powershell
.\gradlew.bat :cli:run -PcliArgs="record-cursor-state --recording recordings\resume.jsonl"
```

### `replay`

Replay a JSONL recording through `ServerFrameSerializer`, `WsFrameMapper`, and
the headless reducer. Assertions are opt-in so recordings can be inspected even
when they are intentionally bad fixtures.

```powershell
.\gradlew.bat :cli:run -PcliArgs="replay --recording ..\core\src\test\resources\replay\ka770-duplicate-assistant.jsonl --conversation conv-ka770 --assert-no-dups --assert-otid-unique --assert-seq-monotonic"
```

Mixed REST/WS hydration fixtures can include REST snapshot lines alongside
inbound WS frames:

```jsonl
{"direction":"rest_hydrate","messages":[{"id":"user-1","message_type":"user_message","content":"Hi"}]}
{"direction":"inbound","frame":{"v":1,"type":"assistant_message","id":"cm-1","conversation_id":"conv_x","run_id":"run_x","content":"Hello","seq_id":1}}
```

Use `--hydration-order=rest-first|ws-first|interleaved` to apply REST snapshots
before WS frames, after WS frames, or by recording timestamp order.

Supported assertions:

- `--assert-no-dups`: no duplicate UI ids and no duplicate semantic assistant,
  reasoning, tool, or error messages in the same run.
- `--assert-otid-unique`: every local optimistic id is globally unique.
- `--assert-seq-monotonic`: reducer output and recorded run sequence numbers are
  monotonic.
- `--assert-no-empty-bodies`: no blank UiMessage body in a run that also has a
  non-empty UiMessage.
- `--assert-no-prefix-orphans`: no UiMessage whose full content is a strict
  prefix of another UiMessage in the same run.
- `--assert-ui-message-count-per-run=N`: each run must produce exactly `N`
  distinct UiMessages.
- `--assert-final-status-matches=completed|cancelled|failed`: the final
  observed run status must match the expected terminal status.
- `--assert-no-orphan-tool-returns`: every observed tool return must have a
  matching tool call in the same run.
- `--assert-run-completes`: every observed run must reach `completed`,
  `cancelled`, or `failed`.
- `--assert-no-abandoned-tool-calls`: terminal runs must not leave any
  timeline tool-call event without an attached tool return.
- `--assert-approval-tool-return-on-approval-run`: tool returns for approval
  requests must stay on the approval run that emitted the request.
- `--assert-otid-stable-across-retry`: the same server message id/type must not
  be observed with multiple OTIDs across retry/replay boundaries.
- `--resume-from-cursor=N`: treat frames with `seq <= N` as already applied and
  skip them during replay, matching a resumed client tail.
- `--assert-no-gap-on-resume`: with `--resume-from-cursor`, post-resume seqs
  must start at `N+1` and remain contiguous.
- `--assert-no-dup-on-resume`: with `--resume-from-cursor`, the recording must
  not include any replayed seq `<= N`.
- `--assert-cursor-expired-graceful`: assert a `cursor_expired` error is
  observed and the recording continues afterward, proving the socket stayed up.
- `--assert-isStreaming-clears-by-terminal-frame`: after a terminal run frame,
  the replayed chat streaming state must be idle.
- `--assert-no-locks-held-after-terminal`: after a terminal run frame, the
  headless timeline write lock must be released.
- `--assert-typing-indicator-state`: streaming and typing-indicator state must
  move together at every traced transition.
- `--assert-no-orphaned-run-tracker`: started runs must not remain active after
  replay reaches the end of the recording.
- `--assert-terminal-frame-received`: every started run must receive a terminal
  `turn_done` or `subscribe_done` frame.
- `--assert-all`: enable the state-machine assertion bundle above.
- `--trace-state-transitions`: print streaming/typing/run-tracker transitions
  while replaying a fixture.

Use `--dump-timeline` to print the final folded timeline JSON.

Use `--bisect-frame` with one or more assertions to greedily remove unnecessary
recording lines while preserving the failure. Add `--bisect-out` to write the
minimized fixture:

```powershell
.\gradlew.bat :cli:run -PcliArgs="replay --recording recordings\conv_x.jsonl --conversation conv_x --assert-no-dups --bisect-frame --bisect-out recordings\conv_x.min.jsonl"
```

For incremental inspection, use one of the frame dump selectors:

```powershell
.\gradlew.bat :cli:run -PcliArgs="replay --recording recordings\conv_x.jsonl --conversation conv_x --dump-after-each-frame"
.\gradlew.bat :cli:run -PcliArgs="replay --recording recordings\conv_x.jsonl --conversation conv_x --dump-after-frame 12"
.\gradlew.bat :cli:run -PcliArgs="replay --recording recordings\conv_x.jsonl --conversation conv_x --dump-frames 0,12,13"
```

When a frame dump selector is active, stdout is a stable JSON array of
per-frame snapshots and replay status lines move to stderr:

```json
[
  {
    "frame_index": 0,
    "frame_type": "assistant_message",
    "frame_id": "cm-stream-a",
    "ingested": true,
    "timeline": { "conversationId": "conv_x", "eventCount": 1 }
  }
]
```

Use `--interactive` to step through a recording and inject synthetic frames:

```powershell
.\gradlew.bat :cli:run -PcliArgs="replay --recording recordings\conv_x.jsonl --conversation conv_x --interactive"
```

Interactive commands:

- `step [N]`: ingest the next recorded frame(s).
- `dump`: print full Timeline JSON.
- `diff`: show the first Timeline JSON change from the previous frame.
- `inject <json>`: ingest a synthetic server frame through the same reducer
  path.
- `assert <name>`: run one assertion against the current state.
- `save-fixture <path>`: write consumed and injected frames as replayable JSONL.
- `reset`, `exit`.

### `rest`

Call arbitrary Letta REST endpoints with the shared base URL/token/profile
flags. This is the escape hatch for app/server functionality that
does not yet have a typed CLI wrapper.

```powershell
.\gradlew.bat :cli:run -PcliArgs="rest get /v1/agents --query limit=20"
.\gradlew.bat :cli:run -PcliArgs="rest post /v1/agents --body-file agent-create.json"
.\gradlew.bat :cli:run -PcliArgs="rest put /v1/tools --body-file tool.json"
.\gradlew.bat :cli:run -PcliArgs="rest patch /v1/agents/agt_x --body '{`"name`":`"CLI agent`"}'"
.\gradlew.bat :cli:run -PcliArgs="rest delete /v1/tools/tool_x"
```

Supported options:

- `--query name=value`: repeatable query parameters.
- `--header name=value`: repeatable request headers, useful for mutation
  contracts such as `If-Match` and `Idempotency-Key`.
- `--body <json>` / `--body-file <path>`: request body for POST/PUT/PATCH/DELETE.
- `--compact`: compact JSON response output.
- `--raw`: print response without JSON formatting.
- `--allow-error`: print non-2xx response body without failing the process.

### Typed resources

Typed resource groups use the same profile/base-url/token options as `rest` and
share its JSON output flags. They are thin wrappers over the production app API
routes, so `--query`, `--header`, `--body`, and `--body-file` pass through to the
server contract. Positional `agent_id`, `conversation_id`, and `project_id`
values fall back to the selected profile defaults when omitted.

Core resource groups:

- `agents`: list/get/create/update/delete/export/import, context, core-memory
  blocks, archive/tool/identity/block attachments, and agent message actions.
- `conversations`: list/get/create/update/delete, fork/cancel/recompile, and
  conversation messages.
- `tools`, `blocks`, `archives`, `passages`, `folders`, `groups`,
  `identities`, `schedules`, `mcp`, `models`, and `providers`: app admin
  surfaces exposed as first-class CLI groups.
- `runs`, `jobs`, `steps`, `messages`, and `message-batches`: operational
  inspection and mutation routes.
- `projects`, `project-agents`, and `project-work`: Vibesync project registry,
  beads remote, sync trigger, ready-work, issue, and analytics routes.
- `debug` and `vibesync-admin`: health/stats and admin refresh routes.

The maintained route-by-route audit lives in
[`docs/app-parity-matrix.md`](docs/app-parity-matrix.md).

Examples:

```powershell
.\gradlew.bat :cli:run -PcliArgs="agents list --query limit=20"
.\gradlew.bat :cli:run -PcliArgs="agents update agt_x --body-file agent-update.json"
.\gradlew.bat :cli:run -PcliArgs="agents attach-tool agt_x tool_x"
.\gradlew.bat :cli:run -PcliArgs="agents import --file agent-export.json --override-name `"CLI import`""
.\gradlew.bat :cli:run -PcliArgs="folders upload folder_x --file .\notes.md --duplicate-handling replace"
.\gradlew.bat :cli:run -PcliArgs="projects sync-trigger project_x"
.\gradlew.bat :cli:run -PcliArgs="project-work status issue_x --header If-Match=abc --header Idempotency-Key=run-1 --body '{`"status`":`"closed`"}'"
```

### `setup`

Apply or export a whole CLI/app setup without touching a device. Input files may
be JSON or YAML; export currently writes JSON.

```powershell
.\gradlew.bat :cli:run -PcliArgs="setup apply --file setup.yaml --dry-run"
.\gradlew.bat :cli:run -PcliArgs="setup apply --file setup.yaml"
.\gradlew.bat :cli:run -PcliArgs="setup export --out current-setup.json"
.\gradlew.bat :cli:run -PcliArgs="setup export --profiles-only --redact-token"
```

Top-level setup shape:

```json
{
  "activeProfile": "dev",
  "profiles": [
    {
      "name": "dev",
      "baseUrl": "https://letta.oculair.ca",
      "defaultAgentId": "agt_x",
      "defaultProjectId": "project_x",
      "prefs": { "enableProjects": true }
    }
  ],
  "resources": {
    "agents": [
      { "ref": "primary", "id": "agt_x", "body": { "name": "Primary" } }
    ],
    "tools": [],
    "blocks": [],
    "archives": [],
    "folders": [],
    "groups": [],
    "identities": [],
    "providers": [],
    "mcpServers": [],
    "projects": [],
    "schedules": [
      { "agentRef": "primary", "body": { "message": "standup", "cron": "0 9 * * *" } }
    ]
  },
  "links": {
    "agentTools": [
      { "agentRef": "primary", "toolId": "tool_x" }
    ]
  }
}
```

Resource entries are upserted when they have an `id`; missing ids are created.
Use `ref` to connect resources created in the same file through `links` or
agent-scoped schedules. `--dry-run` prints the mutation plan without changing
profiles or server state. Server mutations require a token; profile-only setup
does not.

### `stream`

Direct Letta REST/SSE tracer retained from `letta-mobile-6p4o`.

```powershell
.\gradlew.bat :cli:run -PcliArgs="stream -m `"your prompt here`""
```

For each SSE frame, it prints the message type, server id, merge branch, old
text, new text, and output text. The tracer calls the same production
`mergeStreamText` helper used by the live reducer, so CLI diagnostics and app
behavior stay aligned.

### `app-server-serve`

Launch the official host Letta App Server process from the mobile CLI. This is
the Phase C seam for replacing the shim as a required remote dependency: the
mobile CLI does not implement a parallel server, it starts `letta app-server`
with the same listen and WebSocket auth flags exposed by Letta Code.

For the installable host distribution, build `:appserver-cli:distZip` and run
`bin/meridian-app-server` from the unzipped artifact. The Gradle-backed `:cli`
command below remains useful when debugging the broader mobile CLI harness.

```powershell
.\gradlew.bat :cli:run -PcliArgs="app-server-serve --letta-command pnpm --letta-arg dlx --letta-arg @letta-ai/letta-code@0.29.9 --listen ws://127.0.0.1:4500"
.\gradlew.bat :cli:run -PcliArgs="app-server-serve --listen ws://0.0.0.0:4500 --ws-auth capability-token --ws-token-file .\token.txt --ws-token-sha256 <sha256>"
.\gradlew.bat :cli:run -PcliArgs="app-server-serve --letta-command pnpm --letta-arg dlx --letta-arg @letta-ai/letta-code@0.29.9 --dry-run"
```

Use `--dry-run` to print the generated process command without launching a
server. Use repeated `--letta-arg` values when the host Letta command is a tool
wrapper such as `pnpm dlx @letta-ai/letta-code@0.29.9`.

Loopback development uses no WebSocket auth. Any non-loopback listen host must
be launched with `--ws-auth`; clients then pass the same token as
`Authorization: Bearer <token>`.

Each 0.29.9 client opens one bidirectional `/ws` connection. Never add the
removed `?channel=control|stream` query. Upstream supports concurrent clients;
Iroh deployments still use the Kotlin wrapper as their authorization, runtime
ownership, and fanout boundary.

### `app-server-smoke`

Connect to a running App Server and send one turn through the shared
`AppServerTurnEngine`.

```powershell
$env:APP_SERVER_TEST_URL="ws://127.0.0.1:4500"
$env:APP_SERVER_TEST_AGENT_ID="agt_x"
$env:APP_SERVER_TEST_CONVERSATION_ID="conv_x"
.\gradlew.bat :cli:run -PcliArgs="app-server-smoke --message `"hello`""
```

Expected output includes:

```text
[app-server] connect ws://127.0.0.1:4500
[lifecycle] Started
[stream] ...
[lifecycle] Completed
```

Set `APP_SERVER_TEST_TOKEN` or pass `--token` when connecting to a non-loopback
server that requires bearer auth.

## Fixture workflow

1. Start from an existing JSONL recording (the shim-WS `capture`/`record`
   commands are gone; see above).
2. Reproduce locally with `replay --dump-timeline`.
3. Minimize noisy fixtures with `replay --bisect-frame --bisect-out`.
4. Add the JSONL under `android-compose/core/src/test/resources/replay`.
5. Add a focused replay test under
   `android-compose/core/src/test/java/com/letta/mobile/data/timeline/headless`.

The current regression seed is
`ka770-duplicate-assistant.jsonl`, which intentionally fails
`--assert-no-dups` to prove duplicate assistant bodies are caught.
