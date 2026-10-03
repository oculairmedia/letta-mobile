# LCP wire v1: the canvas plugin protocol

The protocol a `process` plugin (any language, a subprocess of the host) and a `service` plugin (a
WebSocket server the host connects to) speak with the Letta host. It mirrors the Kotlin SPI of
`:plugin-api` one to one (`CanvasPlugin` / `PluginHost`), so a plugin behaves the same whatever its
runtime. Design: `docs/design/canvas-plugin-platform-plan.md` section 5. Implementation:
`sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/plugin/wire/` (registry `LcpMethod`,
typed shapes `LcpCalls`).

The golden transcripts in
`android-compose/sharedLogic/src/commonTest/resources/canvas/plugin/v1/wire/` are the normative
examples: one file per method plus a whole recorded session (`session-process.jsonl`) and
`malformed.jsonl`. Each line is `{"dir": "host->plugin" | "plugin->host", "message": {...}}`; the
host's tests hold every line to the typed shapes and replay the session against the real host.

## Framing

- JSON-RPC 2.0, UTF-8. `params` is always an object (absent reads as `{}`); ids are strings or
  numbers; **batches are refused** (`-32600`).
- `process`: one message per `\n`-terminated line on stdin (host to plugin) and stdout (plugin to
  host), nothing else on stdout. A `\r` before the `\n` is tolerated and blank lines are skipped.
  stderr is captured by the host as telemetry (scrubbed).
- `service`: one message per WebSocket text frame.
- A message is at most **4 MiB**. A longer line or frame is dropped unread and answered with a
  `-32005` error with `"id": null`.

## Session

```
uninitialized --plugin.initialize--> initialized --plugin.activate--> active
      any --plugin.deactivate (or the host draining)--> stopping --answer--> closed
```

- Before `plugin.initialize` completes every other call is refused with `-32002`, both ways.
- `initialized` admits `plugin.activate`, `plugin.health`, `plugin.deactivate`,
  `host.settingsChanged` and the plugin's own calls (a plugin may emit while activating);
  `action.invoke` and `element.event` need `active`.
- **Deactivate drains**: the host stops sending new actions and events (they are refused with
  `-32003`), waits up to the deactivate deadline for the calls in flight either way, including the
  plugin's emits from a draining action, then sends `plugin.deactivate` and closes.

### Handshake

The host offers every contract version it speaks; the plugin answers the one it chose:

```json
{"jsonrpc":"2.0","id":1,"method":"plugin.initialize","params":{"contractVersion":1,"contractVersions":[1],"pluginId":"letta.example","settings":{"baseUrl":"https://api.example.test"},"hostInfo":{"name":"letta-host","version":"1.0.0"}}}
{"jsonrpc":"2.0","id":1,"result":{"ok":true,"contractVersion":1,"pluginInfo":{"name":"Example","version":"1.2.0"}}}
```

A plugin that speaks none of the offered versions answers `-32004` with
`"data":{"supported":[...]}`; an answer naming a version the host did not offer is refused by the
host. Either way the host closes the connection.

## Methods

<!-- lcp_wire_tables:start -->
| Method | Direction | Kind | Deadline | Capability | SPI member |
|---|---|---|---|---|---|
| `plugin.initialize` | host->plugin | request | 10s | - | `PluginHost.contractVersion + PluginHost.settings` |
| `plugin.activate` | host->plugin | request | 30s | - | `CanvasPlugin.activate` |
| `plugin.health` | host->plugin | request | 5s | - | `CanvasPlugin.health` |
| `plugin.deactivate` | host->plugin | request | 10s | - | `CanvasPlugin.deactivate` |
| `action.invoke` | host->plugin | request | 1m 40s | - | `CanvasPlugin.invoke` |
| `element.event` | host->plugin | notification | - | - | `CanvasPlugin.onElementEvent` |
| `host.settingsChanged` | host->plugin | notification | - | - | `PluginHost.settings` |
| `host.emit` | plugin->host | request | 30s | by content | `PluginHost.emit` |
| `host.putAsset.begin` | plugin->host | request | 10s | assets:write | `PluginHost.putAsset` |
| `host.putAsset.chunk` | plugin->host | request | 10s | assets:write | `PluginHost.putAsset` |
| `host.putAsset.end` | plugin->host | request | 30s | assets:write | `PluginHost.putAsset` |
| `host.readElements` | plugin->host | request | 10s | canvas:read | `PluginHost.readElements` |
| `host.log` | plugin->host | notification | - | - | `PluginHost.log` |
| `$/cancel` | either | notification | - | - | `coroutine cancellation` |

| Code | Meaning |
|---|---|
| -32700 | the line or frame is not JSON |
| -32600 | not a JSON-RPC 2.0 message LCP accepts (batches included) |
| -32601 | no such method in this direction |
| -32602 | params do not hold to the method's shape |
| -32603 | the handler failed unexpectedly |
| -32800 | the caller sent $/cancel for this request |
| -32001 | the method's deadline passed |
| -32002 | plugin.initialize has not completed |
| -32003 | the session's state refuses the method (stopping, closed, already initialized) |
| -32004 | no contract version both sides speak |
| -32005 | the message exceeds 4 MiB |
| -32006 | the plugin lacks the capability the call needs (data.capability) |
| -32007 | the message held a secret, so it was refused whole |
| -32008 | too many requests in flight; retry later |
| -32009 | the session or transport is closed |
| -32010 | the action failed; data.code is the plugin's own code |
| -32011 | an asset upload broke its limits, order or hash |
<!-- lcp_wire_tables:end -->

Shapes (see the transcripts for complete examples):

- `action.invoke {action, input, context: {origin, canvasId?, elementId?}}` where `origin` is
  `{"kind":"agent","agentId","conversationId?","toolCallId?"}`, `{"kind":"view","elementId","peerId"}`
  or `{"kind":"host","reason"}`. Result `{text, structured?, emit?}`. The plugin's own failure is
  the error `{"code":-32010,"message":...,"data":{"code":"<its code>"}}`.
- `element.event {kind, elementId, event: moved|removed|focused|viewOpened|viewClosed, frame?}`.
- `host.emit {place[], update[], remove[], placeImages[]}` with `place` entries
  `{kind, v, props, fallback: {title, subtitle?, icon?, openUrl?}, ref?, frame?: {x, y, width, height}, snapshot?}`,
  `update` entries `{elementId, props?, fallback?, snapshot?, ref?}` and `placeImages` entries
  `{image, frame?, provenance?}`. A snapshot or image is `{"kind":"asset","ref":"sha256:<hex>"}` or
  small inline bytes `{"kind":"bytes","mediaType","base64"}`. Result
  `{"receipt":{"placed":[ids],"refused":[{"index","reason"}]}}`: a refused entry never fails the call.
- `host.putAsset.begin {mediaType, byteSize}` (at most 8 MiB, at most 4 uploads open) answers
  `{uploadId}`; `host.putAsset.chunk {uploadId, index, base64}` in order from 0, each at most 1 MiB
  of base64; `host.putAsset.end {uploadId, sha256}` (lowercase hex of all the bytes) answers
  `{"ref":"sha256:<hex>"}`. Any broken rule answers `-32011` and drops the upload.
- `host.readElements {query: {canvasId?, elementIds?, kinds?, includeOthers?}}` answers `{elements}`.
- `host.log {level: debug|info|warn|error, message, fields}`.
- `plugin.health` answers `{status: ok|degraded|failed, reason?}`.

## Deadlines and cancellation

Each request has the receiver's deadline in the table; a handler that overruns it is answered
`-32001`. A caller that gives up (its own timeout, or its own cancellation) sends
`{"jsonrpc":"2.0","method":"$/cancel","params":{"id":<id>}}`; the receiver stops the work and
answers the request `-32800`. A late answer to a cancelled request is ignored.

## Backpressure

A peer runs at most 16 of the other side's requests at once and answers more with `-32008`
(retry later); it keeps at most 16 of its own requests waiting and makes further callers wait.
Notifications run in arrival order.

## Capabilities and secrets

- The host serves a plugin's calls only within its consented capabilities: `host.putAsset.*` needs
  `assets:write`, `host.readElements` `canvas:read`, `host.emit` `canvas:place` for placements,
  updates and removals and `assets:write` for images and inline bytes. A refusal is `-32006` with
  `"data":{"capability":"..."}`.
- **Secrets never travel in the protocol.** A `process` plugin gets them in its environment, a
  `service` plugin in its connection headers; the host refuses to send any message that holds one.
- Everything a plugin sends is held to its secrets (verbatim and base64), failing closed: a
  `host.log` keeps its shape with each leaking string replaced; any other call or result holding a
  secret is refused whole with `-32007`; an error's message is scrubbed and its data dropped.
