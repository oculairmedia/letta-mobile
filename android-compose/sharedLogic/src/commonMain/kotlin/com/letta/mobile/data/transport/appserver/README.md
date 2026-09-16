# App Server protocol client

Kotlin client for the Letta App Server (Protocol V2, alpha hard-cut): one bidirectional
WebSocket at `/ws`. Wire types are pinned to `@letta-ai/letta-code@0.32.10`
(`APP_SERVER_PROTOCOL_VERSION = 1`); the deployed server runs 0.32.3, whose command and message
unions are identical.

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
