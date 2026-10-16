# App Server protocol client

Kotlin client for the Letta App Server (Protocol V2, alpha hard-cut): one bidirectional
WebSocket at `/ws`. Wire types are pinned to `@letta-ai/letta-code@0.34.14`
(`APP_SERVER_PROTOCOL_VERSION = 1`), the one contract (letta-mobile-ckmub.5). Its command and
message unions are those of 0.32.10 plus `launch_subagent` / `launch_subagent_response` (0.33.6)
plus the `turn_finished_ack` command (0.34.8) plus `browser_device_mcp_oauth` /
`browser_device_mcp_oauth_cancel` / `browser_device_mcp_oauth_response` (0.34.14, not used by the
client), with optional 0.34 fields:
`input.terminal_consumer_id`, `turn_finished.terminal_consumer_ids`, `client_preferences` on
`create_message` / `teleport_continue`, workflow `progress`, the `memory_palace` experiment.

## letta-code version pins

There is **one** wire contract: **0.34.14**. Every other version below is a runtime or a
capture that is knowingly not the contract, and says why. Each pin has one source of truth;
everything else cites it (letta-mobile-bzvro.10, letta-mobile-ckmub.5).

| Pin | Version | Source of truth | What it means |
|---|---|---|---|
| THE pin: wire contract, desktop bundled runtime, Android embedded runtime | 0.34.14 | `letta-code-runtime/letta-code-runtime.properties` (`lettaCodeVersion`, letta-mobile-by4vm.110 D1) | One version everywhere. The Android embedded runtime (`com.letta.mobile.embedded-runtime` build-logic plugin) and the desktop bundled runtime (`desktop/build.gradle.kts` reads the same file) are built from it, and it is THE wire contract: the protocol `.d.ts` corpus the Kotlin wire types are verified against (`scripts/appserver/verify-contract-baseline.mjs`), 104 commands, 113 messages. Mirrors that must name it: the contract matrix `sharedLogic/src/jvmTest/resources/appserver/app-server-v2-contract-matrix.json` (`baseline.version`); `desktop/runtime/package.json`, `package-lock.json`, `runtime-manifest.json`; the `appserver-contract-baseline` CI job (it reads `baseline.version` from the matrix, never a literal); `LettaCodeContract.VERSION` (user-facing text); and the pins quoted in CLAUDE.md, AGENTS.md and the CLI READMEs. `LettaCodeVersionPinsTest` (appserver-cli) fails if any of them disagree. |
| Deployed Linux host | 0.33.6 | the host (not in this repo) | Not the contract: the host has not been redeployed to 0.34.x yet (redeploy item, letta-mobile-ckmub.14). The client keeps working against it: decoding is additive-tolerant (0.34-only fields are optional, unknown frames decode to `Unknown`), and the 0.34-only `turn_finished_ack` / `terminal_consumer_id` / `client_preferences` are never sent by production code, so nothing requires a 0.34 server. Any future use must be gated on an advertised capability. |
| Live golden capture | 0.32.3 | `golden/letta-code-0.32.3-live.jsonl` (same matrix) | A historical capture, not the contract. To be re-captured after the host redeploy (letta-mobile-ckmub.14); it needs a live server (`scripts/appserver/capture-live-goldens.mjs`). Its unions equal 0.32.10, so it covers neither `launch_subagent` nor `turn_finished_ack`. |
| Restart-replay evidence | 0.29.12 | `appserver-cli/src/test/resources/appserver/restart-replay-evidence.json` (`AppServerRestartReplayEvidence.PINNED_LETTA_CODE_VERSION`) | Not the contract until re-captured: it is produced by a live probe against a real local server plus a model provider, which a pin bump cannot fake. Durability and identity observations (restart, reconnect, `client_message_id`) were measured on 0.29.12 and are not re-measured on the contract yet; the re-capture at the contract is letta-mobile-ckmub.13. |

### What "verified against a 0.33/0.34 server" means

The desktop bundled runtime and any remote 0.34.x App Server speak the contract wire. The Android
embedded runtime runs the same letta-code release, but until it moves to the App Server path
(letta-mobile-by4vm.110.6) Android drives it through the headless stream-json protocol, which has
no fork, dream, approvals or App Server admin commands. So any acceptance criterion of the form
"verified against a 0.33/0.34 server" is still **desktop / remote-only** for now; on the embedded
runtime those features must degrade (absent, not failing). The Iroh wrapper host has to run the
contract letta-code for the 0.34 features to reach Android over Iroh.

Moving the pin is a deliberate change: set `lettaCodeVersion` + `lettaCodeIntegrity` in
`letta-code-runtime/letta-code-runtime.properties` and bump its `assetRevision`, then update the
matrix fixtures (`scripts/appserver/verify-contract-baseline.mjs` must pass under the Node version
in the matrix), the desktop runtime package files (`npm install --package-lock-only` in
`desktop/runtime`), `LettaCodeContract.VERSION` and the quoted pins together; `LettaCodeVersionPinsTest` lists anything missed. The replay evidence and the golden
capture move only when re-captured live.

Source of truth, in order: the package's `dist/types/types/protocol_v2.d.ts` (+ siblings), then
[protocol lifecycle](https://docs.letta.com/platform/app-server/protocol-lifecycle/index.md).
The docs changelog lags releases; prefer the `.d.ts`.

## Lifecycle

```
GET /readyz | /healthz | /app-server-info      AppServerDiscovery (no socket needed)
connect /ws (+ Authorization: Bearer when --ws-auth)
→ app_server_info            ← app_server_info_response   (split_channels must be false)
→ runtime_start [request_id] ← runtime_start_response     (keep the returned runtime exactly)
                             ← state replay: update_device_status / loop / queue / subagents
→ input create_message [request_id?]
                             ← input_accepted             (only when request_id was sent)
                             ← stream_delta*  …  control_request (can_use_tool)
→ input approval_response    (payload.request_id = control_request.request_id)
                             ← stream_delta … stop_reason (terminal)  ← turn_finished
```

Reconnect is a new socket plus `runtime_start` again (re-register external tools).

## Turn boundaries

One reading of `stop_reason` for every path: `AppServerStopReason`. `requires_approval` pauses
the run and never settles a turn; official `StopReasonType` reasons (and the provider finish
reasons `stop_sequence`, `max_tokens`, `length`) end it; an unrecognised reason such as a provider
`tool_use` does not. Live captures show `turn_finished` always arrives after the terminal
`stop_reason` delta, and a trailing `loop_error` can follow a failed turn's `turn_finished`.

## Correlation

- `request_id` is connection-local. `input.request_id` correlates `input_accepted` only.
- An approval answer carries the `control_request`'s id inside `payload`, not the outer input's.
- `event_seq` is monotonic per connection; `idempotency_key` dedupes replays.
- Unknown `type`s decode to `AppServerInboundFrame.Unknown` with the raw envelope; additive
  fields stay on `AppServerReceivedFrame.raw`.
- Never send the legacy names: `request_state` → `sync`, `change_cwd` / `change_mode` →
  `change_device_state`, `cancel_run` → `abort_message`, `recover_pending_approvals` →
  `sync { recover_approvals: true }`. Never use `?channel=control|stream` (HTTP 426).

## Not this protocol

`transport/MobileWsFrames` (admin-shim `/shim/v1/mobile`), `transport/WsChatBridge`, and the
timeline SSE / WS subscriptions are separate contracts. Do not fold them into this package.

## Keeping it honest

- Baseline: `scripts/appserver/verify-contract-baseline.mjs --package-root <install>` checks the
  inventory, hashes and CLI probes in `jvmTest/resources/appserver/` against the pinned package;
  `--unions-only` compares just the unions (the `appserver-contract` workflow warns weekly when a
  newer release changes them).
- Goldens: `jvmTest/resources/appserver/golden/*.jsonl`, captured from a live server with
  `scripts/appserver/capture-live-goldens.mjs` and checked by `AppServerLiveGoldenConformanceTest`.
