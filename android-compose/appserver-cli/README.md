# meridian-app-server

Standalone host CLI for the official Letta App Server path. It is intentionally
small: `app-server-serve` runs the upstream `letta server` through the shared
`LocalAppServerHost` (the same host the desktop uses for its bundled runtime), and
`app-server-smoke` sends one typed turn through the shared App Server client in
`:sharedLogic`.

## Build

```powershell
cd android-compose
$env:JAVA_HOME="C:\Users\Emmanuel\.cache\jdk26-temurin\jdk-26.0.1+8"
.\gradlew.bat :appserver-cli:distZip
```

The zip is written under `appserver-cli/build/distributions/`. Unzip it and run
`bin/meridian-app-server` or `bin/meridian-app-server.bat` with Java 17 or newer.

## Commands

```powershell
.\bin\meridian-app-server.bat app-server-serve --backend-dir C:\letta\backend --letta-command pnpm --letta-arg dlx --letta-arg @letta-ai/letta-code@0.34.14
```

The server always listens on an ephemeral loopback port (`--listen ws://127.0.0.1:0`)
with `--ws-auth capability-token`: each start writes a fresh 32-byte token to
`<backend-dir>/ws-token` (owner-only) and prints `ready at ws://127.0.0.1:<port>` once
`/readyz` answers. Clients send `Authorization: Bearer <token>`. The command exits
with the server's exit code and never restarts it; the caller (systemd) does.

Each 0.29+ client opens one bidirectional `/ws` connection. Never add the
removed `?channel=control|stream` query. Upstream supports concurrent clients;
Iroh deployments still use the Kotlin wrapper as their authorization, runtime
ownership, and fanout boundary.

Smoke a running local App Server (URL from the `ready at` line, token from `ws-token`):

```powershell
$env:APP_SERVER_TEST_URL="ws://127.0.0.1:<port>"
$env:APP_SERVER_TEST_TOKEN=Get-Content C:\letta\backend\ws-token
$env:APP_SERVER_TEST_AGENT_ID="agt_x"
$env:APP_SERVER_TEST_CONVERSATION_ID="conv_x"
.\bin\meridian-app-server.bat app-server-smoke --message "hello"
```

Expected output includes:

```text
[app-server] connect ws://127.0.0.1:<port>
[lifecycle] Started
[stream] ...
[lifecycle] Completed
```

Set `APP_SERVER_TEST_TOKEN` or pass `--token` for any server started with
`--ws-auth`, which includes every `app-server-serve` server.

## Regenerating the restart/replay evidence

`src/test/resources/appserver/restart-replay-evidence.json` is **observed**, not
hand-authored. `AppServerRestartReplayEvidenceTest` runs everywhere and hard-fails
when the evidence's `source.version` drifts from
`AppServerRestartReplayEvidence.PINNED_LETTA_CODE_VERSION`. The evidence was
last captured at the 0.34.14 contract on 2026-10-17 with Node v24.18.0 (letta-mobile-ckmub.13; it needs a
model provider, so it runs on the Linux host). `AppServerRestartReplayProbeTest` is the
capture tool: it launches a real `letta.js app-server`, runs a turn, kills and
restarts the process against the same backend root, replays the same
`client_message_id`, and reads the on-disk transcript. It skips unless the
environment below is present, so CI stays green without a model provider.

### 1. Prerequisites

Node 24 and the target letta-code build (never the globally-linked one — pin the
version you are capturing):

```bash
NODE=~/.nvm/versions/node/v24.18.0/bin/node
LETTA_JS=~/letta-code-0.34.14/node_modules/@letta-ai/letta-code/letta.js   # the 0.34.14 contract
"$NODE" --version   # must match the `source.node` you are about to record
```

### 2. Provision an isolated provider staging area

**With the local backend, `letta connect` persists provider auth under the
BACKEND dir (`<backend>/providers/auth.json`), not under `HOME`.** The probe runs
against a fresh throwaway backend dir every time, so it starts with no provider
at all unless one is seeded. Stage a provider once in a scratch backend:

```bash
export PROBE_HOME=/tmp/lgns815-probe-home
mkdir -p "$PROBE_HOME"
HOME="$PROBE_HOME" LETTA_LOCAL_BACKEND_EXPERIMENTAL=1 \
  LETTA_LOCAL_BACKEND_DIR="$PROBE_HOME/backend" \
  "$NODE" "$LETTA_JS" --backend local connect lmstudio --base-url <openai-compatible-base-url>
```

Any provider works; `lmstudio` is just the shortest path to a local
OpenAI-compatible endpoint. Confirm the model actually answers before probing:

```bash
HOME="$PROBE_HOME" LETTA_LOCAL_BACKEND_EXPERIMENTAL=1 \
  LETTA_LOCAL_BACKEND_DIR="$PROBE_HOME/backend" \
  "$NODE" "$LETTA_JS" --backend local --model lmstudio/<model> -p "Reply with exactly one word: PONG"
```

### 3. Run the probe

```bash
cd android-compose
export LETTA_CODE_NODE="$NODE"
export LETTA_CODE_JS="$LETTA_JS"
export LETTA_CODE_PROBE_MODEL=lmstudio/<model>
export LETTA_CODE_PROBE_HOME="$PROBE_HOME"
export LETTA_CODE_PROBE_PROVIDER_AUTH="$PROBE_HOME/backend/providers/auth.json"
export OPENROUTER_API_KEY=unused-placeholder   # gate only; unused for non-openrouter models
./gradlew --no-daemon :appserver-cli:cleanTest :appserver-cli:test \
  --tests '*AppServerRestartReplayProbeTest*'
```

| Env | Default | Purpose |
|---|---|---|
| `OPENROUTER_API_KEY` | — | Skip gate (must be non-blank) and the key forwarded to the server process. A placeholder is fine when another provider serves the model. |
| `LETTA_CODE_NODE` | `node` | Node binary; pin it to the version you record in the evidence. |
| `LETTA_CODE_JS` | `~/letta-code-install/.../letta.js` | The letta-code build under test. |
| `LETTA_CODE_PROBE_MODEL` | `openrouter/nvidia/nemotron-nano-9b-v2:free` | Model for the two probe turns. |
| `LETTA_CODE_PROBE_HOME` | real `$HOME` | HOME for the spawned server; point it at a throwaway dir so the run cannot touch the operator's `~/.letta`. |
| `LETTA_CODE_PROBE_PROVIDER_AUTH` | none | `providers/auth.json` copied into the throwaway backend before the first launch. |

The **backend dir is always a fresh `Files.createTempDirectory("lgns815-probe")`**
(see `AppServerRestartReplayProbeTest`) and is passed as `LETTA_LOCAL_BACKEND_DIR`.
Never point a regeneration run at a live store — two writers on one local backend
corrupt it.

Run the probe on an otherwise idle machine. It waits 30s for each server's
`Listening on` banner, and a competing Gradle build is enough to blow that budget
on the phase-2 relaunch — a timeout there is load, not a contract regression.

The probe fails loudly if no assistant message commits. That check exists because
an errored model turn still commits the user message and still emits a terminal
`stop_reason`, which used to let the probe pass green against a misconfigured
provider and would have produced evidence captured from a dead run.

### 4. Update the committed evidence

The probe asserts the headline observations; it does not rewrite the JSON. After a
green run, hand-update `restart-replay-evidence.json` to match what the run
observed:

- `source.version`, `source.node`, `source.model`, `source.provider`
- `durability` / `identity_scopes` if the observed behaviour actually changed
- bump `AppServerRestartReplayEvidence.PINNED_LETTA_CODE_VERSION` to the same version
  (it must match `source.version`; the always-on gate fails otherwise)

Inspect the probe's temp backend dir (`/tmp/lgns815-probe*`) to re-derive the
observations: `conversations/<base64 id>/messages.jsonl` is the committed
transcript, `otid` carries the client message id, and `local-run-N` ids in the
stream frames show the per-process run counter resetting across restart.

Then re-run the always-on gate:

```bash
./gradlew --no-daemon :appserver-cli:test
```
