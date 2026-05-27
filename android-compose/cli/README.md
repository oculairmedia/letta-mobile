# letta-mobile-cli

Headless CLI for driving Letta Mobile transport and timeline code paths without
using a device. It is tracked by `letta-mobile-q9t4t`.

The CLI now has two useful modes:

- `connect` / `send` / `record` / `replay` / `dump-timeline` exercise the
  admin-shim mobile WebSocket and the same reducer/writer paths used by the app.
- `rest` exposes generic authenticated JSON access to any Letta REST endpoint,
  which is the foundation for the broader device-free admin/provisioning CLI.
- Typed resource command groups wrap the app's main REST-backed admin surfaces
  so agents, conversations, tools, memory, files, projects, MCP, runs, jobs, and
  related resources can be managed without opening the Android UI.
- `setup apply` / `setup export` provide a declarative JSON/YAML format for
  replaying profile and server setup from a workstation.
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

Most admin-shim commands accept:

| env / flag | what |
| --- | --- |
| `LETTA_BASE_URL` / `--base-url` | Letta/admin-shim base URL, default `https://letta.oculair.ca` |
| `LETTA_TOKEN` / `--token` | Bearer token; falls back to the selected CLI profile |
| `LETTA_PROFILE` / `--profile` | CLI profile name; defaults to the active profile |
| `--device-id` | Device id advertised to the shim, default `letta-mobile-cli` |
| `--client-version` | Client version advertised to the shim, default `letta-mobile-cli` |

Conversation and agent commands also use:

| env / flag | what |
| --- | --- |
| `LETTA_AGENT_ID` / `--agent` | Agent id for send/record |
| `LETTA_CONVERSATION_ID` / `--conversation` | Conversation id for send/dump/replay/record |

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

Profile defaults are used by `send`, `dump-timeline`, `replay`, `record`,
`reconnect`, `stream`, `rest`, and the typed resource command groups when
explicit flags/env vars are omitted.

## Commands

### `connect`

Open the admin-shim mobile WebSocket, print incoming frame summaries, wait for
the welcome state, then optionally hold the connection open.

```powershell
.\gradlew.bat :cli:run -PcliArgs="connect --hold-ms 10000"
```

The output includes the `canonical_live_transport` advertised by the welcome
frame so transport exclusivity can be checked from scripts.

### `send`

Send a user message through admin-shim WS and fold the resulting frames through
`WsChatBridge`, `ChannelTransport`, and the headless timeline store.

```powershell
.\gradlew.bat :cli:run -PcliArgs="send `"hello`" --agent agt_x --conversation conv_x --wait-for-stable --dump-timeline"
```

If `--conversation` is omitted, the CLI creates one for the supplied agent via
REST before sending.

### `dump-timeline`

Fetch conversation history via REST, hydrate the headless timeline store, and
emit stable JSON suitable for diffing.

```powershell
.\gradlew.bat :cli:run -PcliArgs="dump-timeline --conversation conv_x --limit 200"
```

### `record`

Capture raw admin-shim mobile WS frames as replay-compatible JSONL.

```powershell
.\gradlew.bat :cli:run -PcliArgs="record --conversation conv_x --out recordings\conv_x.jsonl"
```

To record shim replay frames for an existing run, add `--run-id` and optional
`--cursor`:

```powershell
.\gradlew.bat :cli:run -PcliArgs="record --run-id run_x --cursor 42 --out recordings\run_x.jsonl"
```

To record a send flow, include the message and required agent/conversation:

```powershell
.\gradlew.bat :cli:run -PcliArgs="record --agent agt_x --conversation conv_x --message `"hello`" --out recordings\send.jsonl"
```

### `replay`

Replay a JSONL recording through `ServerFrameSerializer`, `WsFrameMapper`, and
the headless reducer. Assertions are opt-in so recordings can be inspected even
when they are intentionally bad fixtures.

```powershell
.\gradlew.bat :cli:run -PcliArgs="replay --recording ..\core\src\test\resources\replay\ka770-duplicate-assistant.jsonl --conversation conv-ka770 --assert-no-dups --assert-otid-unique --assert-seq-monotonic"
```

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

Use `--dump-timeline` to print the final folded timeline JSON.

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

### `disconnect` and `reconnect`

`disconnect` verifies that the CLI can connect and close cleanly with `bye`.
`reconnect` exercises a connect/disconnect/connect cycle, with optional run
cursor seeding:

```powershell
.\gradlew.bat :cli:run -PcliArgs="reconnect --conversation conv_x --run-id run_x --cursor 42"
```

### `rest`

Call arbitrary Letta REST endpoints with the same base URL/token flags as the
admin-shim commands. This is the escape hatch for app/server functionality that
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

## Fixture workflow

1. Capture a suspect mobile WS flow with `record`.
2. Reproduce locally with `replay --dump-timeline`.
3. Add the JSONL under `android-compose/core/src/test/resources/replay`.
4. Add a focused replay test under
   `android-compose/core/src/test/java/com/letta/mobile/data/timeline/headless`.

The current regression seed is
`ka770-duplicate-assistant.jsonl`, which intentionally fails
`--assert-no-dups` to prove duplicate assistant bodies are caught.
