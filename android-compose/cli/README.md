# letta-mobile-cli

Headless CLI for driving Letta Mobile transport and timeline code paths without
using a device. Its legacy REST/replay commands remain diagnostic tools; production
chat uses App Server/Iroh. It is tracked by `letta-mobile-q9t4t`.

The CLI covers:

- `replay` / `dump-timeline` / `record-cursor-state` fold recorded JSONL fixtures
  and REST history through the same reducer/writer paths used by the app. (The
  admin-shim WebSocket commands `connect`, `send`, `capture`, `record`,
  `disconnect`, and `reconnect` were removed with the shim WS pathway; existing
  JSONL recordings still replay, but new ones can no longer be recorded here.)
- `app-server-serve` / `app-server-smoke` exercise the official Letta App
  Server path: launch a host `letta app-server` process, then send a typed turn
  through the shared App Server client.

## Build and run

The module is a plain Kotlin/JVM application (letta-mobile-by4vm.57): it
consumes the `jvm` target of `:sharedLogic` and uses no Android APIs. `:cli:run`
is the `application` plugin's run task; `-PcliArgs` is still accepted for the
existing scripts (`--args` works too). JDK 21+ is required because the Iroh
binding (`computer.iroh:iroh`) is JVM 21 only.

```powershell
cd android-compose
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :cli:run -PcliArgs="<command> [options]"
```

No args prints the short command list.

## Commands

### `dump-timeline`

Fetch conversation history from a `/v1/conversations/{id}/messages` endpoint,
hydrate the headless timeline store, and emit stable JSON suitable for diffing.
`--base-url` / `LETTA_BASE_URL` and `--token` / `LETTA_TOKEN` are required.

```powershell
.\gradlew.bat :cli:run -PcliArgs="dump-timeline --base-url http://127.0.0.1:8283 --conversation conv_x --limit 200"
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
.\gradlew.bat :cli:run -PcliArgs="replay --recording core\android-data\src\test\resources\replay\ka770-duplicate-assistant.jsonl --conversation conv-ka770 --assert-no-dups --assert-otid-unique --assert-seq-monotonic"
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

### `app-server-serve`

Launch the official host Letta App Server process from the mobile CLI. This is
the Phase C seam for replacing the shim as a required remote dependency: the
mobile CLI does not implement a parallel server, it starts `letta app-server`
with the same listen and WebSocket auth flags exposed by Letta Code.

For the installable host distribution, build `:appserver-cli:distZip` and run
`bin/meridian-app-server` from the unzipped artifact. The Gradle-backed `:cli`
command below remains useful when debugging the broader mobile CLI harness.

```powershell
.\gradlew.bat :cli:run -PcliArgs="app-server-serve --letta-command pnpm --letta-arg dlx --letta-arg @letta-ai/letta-code@0.34.14 --listen ws://127.0.0.1:4500"
.\gradlew.bat :cli:run -PcliArgs="app-server-serve --listen ws://0.0.0.0:4500 --ws-auth capability-token --ws-token-file .\token.txt --ws-token-sha256 <sha256>"
.\gradlew.bat :cli:run -PcliArgs="app-server-serve --letta-command pnpm --letta-arg dlx --letta-arg @letta-ai/letta-code@0.34.14 --dry-run"
```

Use `--dry-run` to print the generated process command without launching a
server. Use repeated `--letta-arg` values when the host Letta command is a tool
wrapper such as `pnpm dlx @letta-ai/letta-code@0.34.14`.

Loopback development uses no WebSocket auth. Any non-loopback listen host must
be launched with `--ws-auth`; clients then pass the same token as
`Authorization: Bearer <token>`.

Each 0.29+ client opens one bidirectional `/ws` connection. Never add the
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

## Messaging the agent swarm as a paired device

`pair`, `agents list` and `message send|read|status|pending` let a script on this machine (the
orchestrator) talk to an agent such as `PM-letta-mobile` over Iroh as a **paired device**: no
SSH, no API key, no admin token. The device authenticates by its Iroh key; what it may do is the
grant of the invite it redeemed. Sends run a turn through the shared App Server client and turn
stack (`SwarmMessenger` in sharedLogic), with staged receipts.

### Install once (Windows)

```powershell
cd android-compose
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-26.0.1.8-hotspot"   # any JDK 21+
.\gradlew.bat --no-daemon :cli:installDist
```

Then run `android-compose\cli\meridian.cmd <command>` (a shim for
`cli\build\install\meridian\bin\meridian.bat`; it needs `JAVA_HOME` or `java` on `PATH`).
Re-run `installDist` after pulling changes.

### Pair once

1. Create a **least-privilege** invite for this device: role `chat`, scoped to the agents it
   needs, short-lived. In the Meridian app use the pairing screen (its default grant is chat;
   limit it to PM-letta-mobile), or with an admin connection to the host:

   ```powershell
   meridian.cmd peer invite --ticket <host> --auth-token <admin-token> --name orchestrator-cli --role chat --agents <pm-agent-id> --ttl-ms 900000
   ```

   The invite's `pair_link` is a one-time bearer secret: do not paste it into logs or chats.
   `chat` lets the device read and send chat and create conversations for the scoped agents; it
   cannot administer the host. The CLI never answers a tool approval (see below).
2. Redeem it, reading the invite from stdin so it never lands in shell history:

   ```powershell
   Get-Clipboard | android-compose\cli\meridian.cmd pair --invite - --yes
   ```

   Without `--yes` (and with `--invite <link>`) the CLI shows the host id and asks before
   dialling; compare it with the inviting screen. `--name` sets the device label
   (default `orchestrator-cli`).

The device key, the paired host and the outbox live in `%APPDATA%\Meridian\cli` (or
`~/.letta/meridian-cli`; override with `--profile-dir` / `MERIDIAN_CLI_HOME`), readable by the
current user only (owner-only ACL on Windows, 0600/0700 elsewhere). The key is never printed.
`meridian.cmd unpair` deletes them and prints the device node id: revoke it on the host too
(app paired-devices screen, or `meridian peer revoke --node-id <id> --ticket <host>`).

### Message

```powershell
meridian.cmd agents list
meridian.cmd message send --agent PM-letta-mobile --from orchestrator --text "Status of the hard-cut slices?"
meridian.cmd message send --agent PM-letta-mobile --conversation <conversation-id> --stdin --output json < note.md
meridian.cmd message read --agent PM-letta-mobile --conversation <conversation-id> --since <message-id>
meridian.cmd message status --agent PM-letta-mobile --conversation <conversation-id> --message-id <client-or-server-id>
meridian.cmd message pending
```

- Without `--conversation`, a send creates a **new dedicated conversation** for its message (the
  default for orchestrator traffic, so it never takes over a conversation a person is in); reuse
  the printed conversation id to continue a thread.
- `--from` adds a first line `[from: <label> (self-declared, via meridian cli)]`. It is display
  only and not an identity; the host-stamped sender is a separate host change (letta-mobile-vqyr2).
  `message read` shows the host's `sender_id` when the history carries one.
- Receipts, in order: `connected` (dialled, device accepted, agent and conversation in scope),
  `accepted` (`started`, or `queued` when the agent is busy; not a failure), `persisted` (the
  server message id, read back once), `processed` (the turn's terminal and the stored reply).
  `--wait accepted|persisted` returns early; check later with `message status` / `message read`.
  `--output json` prints one JSON object per event, tool calls and approvals included.
- Every send is written to the outbox before anything is dialled. The client message id
  (`--client-message-id`, printed on `connected`) is the idempotency key: re-running the same send
  with the same id retries a message that never left, and otherwise resumes it from the history;
  it is never sent twice. A dropped link mid-turn reconnects and resumes the same way; the host turn
  is never aborted, not on timeout either.
- Tool approvals are never answered by the CLI: a turn that needs one exits `32` and stays parked
  for the person in the Meridian app.

Exit codes: `0` ok · `1` usage · `10` not paired · `11` device refused (revoked) · `12` host
unreachable · `13` agent not found / out of scope · `14` ambiguous agent name · `15` conversation
not found or not the agent's · `16` host error · `20` message rejected · `21` timed out · `22`
unconfirmed after a dropped link · `23` outbox entry expired · `24` client id reused for another
message · `30` turn failed · `31` turn cancelled · `32` needs approval · `33` reply read from
history, terminal not observed · `40`-`47` pairing (invalid, expired, unreachable, host mismatch,
refused, save failed, already paired, declined).

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
