# letta-mobile-cli

Headless CLI for driving Letta Mobile transport and timeline code paths without
using a device. It is tracked by `letta-mobile-q9t4t`.

The CLI now has two useful modes:

- `connect` / `send` / `record` / `replay` / `dump-timeline` exercise the
  admin-shim mobile WebSocket and the same reducer/writer paths used by the app.
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
| `LETTA_TOKEN` / `--token` | Bearer token |
| `--device-id` | Device id advertised to the shim, default `letta-mobile-cli` |
| `--client-version` | Client version advertised to the shim, default `letta-mobile-cli` |

Conversation and agent commands also use:

| env / flag | what |
| --- | --- |
| `LETTA_AGENT_ID` / `--agent` | Agent id for send/record |
| `LETTA_CONVERSATION_ID` / `--conversation` | Conversation id for send/dump/replay/record |

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

Use `--dump-timeline` to print the final folded timeline JSON.

### `disconnect` and `reconnect`

`disconnect` verifies that the CLI can connect and close cleanly with `bye`.
`reconnect` exercises a connect/disconnect/connect cycle, with optional run
cursor seeding:

```powershell
.\gradlew.bat :cli:run -PcliArgs="reconnect --conversation conv_x --run-id run_x --cursor 42"
```

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
