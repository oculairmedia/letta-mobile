# Canvas mount v1: host-owned canvas defaults and bindings

Status: **PROPOSED for independent security review (letta-mobile-n2stx.5, stage 2).** This stage
adds the host routes (`:sharedLogic`, package `com.letta.mobile.data.controller.node.iroh.mounts`),
the shared wire names (`com.letta.mobile.data.canvas.mount.wire.CanvasMountWire`) and the shared fixtures
below. It ships no client adapter, UI, agent tool or agent-callable path, and no production host
wiring: a host registers the routes only by passing `CanvasMountRoutes` as
`AdminRpcHostServices.canvasMountRoutes`.

A conversation shows one canvas. Which one is a host-owned record shared by every client of that
host: a conversation's own **binding**, else its agent's **default**. Records hold semantic canvas
ids, never notebook storage ids, and live in the host's `canvas-mounts/` sidecar (see the
`com.letta.mobile.data.canvas.mount` package).

## Capability and registration

- The host advertises `canvas.mount.v1` exactly when all four methods are registered. A client that
  does not see it must not call them.
- All four methods are registered together or not at all, and only when the endpoint's auth policy is
  secure (bearer token, peer allowlist or paired peers only). Under the anonymous test policy
  (`--allow-insecure-anonymous-iroh`) nothing is registered and nothing is advertised.
- Peer capabilities: `canvas.mount.resolve` needs `canvas.mount.read`; the three writes need
  `canvas.mount.write`. `admin.full` includes both. No pairing role grants them by default.

## Who is asking

The principal is the endpoint id the serving connection verified (64 lowercase hex characters),
taken only when the connection is authenticated. Nothing in params can name, replace or widen it.
The connection also contributes its agent scope and conversation scope. `agent_id` and
`conversation_id` are targets, never the actor. A connection without a verified identity gets
`unavailable` / `identity`.

## Authorization

Every call is checked in this order; the first failure ends it.

1. The router's agent scope guard: a paired peer naming an agent outside its scope gets the router's
   own `forbidden: agent not in this device's scope` before the handler runs.
2. Strict params (below); failure is `invalid_input`, and nothing else is consulted.
3. Scope: the agent is in the connection's agent scope; for a binding, the conversation is in the
   connection's conversation scope and the host's own conversation record says it belongs to
   `agent_id`.
4. Proposal (writes): the principal holds a canvas grant that allows mounting `canvas_id`
   (`mayRead` and `mayEdit`). `canvas.mount.default.set` additionally needs unrestricted conversation
   scope (a `conversation.manage` or `admin.full` peer) and an existing agent.
5. Storage.
6. Winner (resolve and bind): the principal holds a canvas grant that allows seeing the stored canvas
   (`mayRead`). Replace and default.set answer the caller's own canvas, so this step does not apply.

Canvas grants are host-administered, keyed by principal and semantic canvas id, and kept in their
own grant file (not the notebook grant file): notebook grants name a notebook storage id that
compaction replaces, so a canvas grant must name the canvas. Any failed lookup denies; a failed
lookup is reported as `unavailable` / `authority`, never as a policy answer.

## Params

Params are an object with exactly the listed fields; any other field is refused
(`invalid_input:params:unknown_field`), so a field such as `principal`, `host_id`, `actor` or
`agentId` is never read. Every id is a JSON string, not blank, at most 512 UTF-8 bytes, without
control characters or invisible formatting characters (bidirectional overrides, zero-width
characters).

| Method | Params |
| --- | --- |
| `canvas.mount.resolve` | `agent_id`, `conversation_id` |
| `canvas.mount.binding.bind` | `agent_id`, `conversation_id`, `canvas_id` |
| `canvas.mount.binding.replace` | `agent_id`, `conversation_id`, `canvas_id` |
| `canvas.mount.default.set` | `agent_id`, `canvas_id` |

## Results

Outcomes are `success: true` results carrying `status`. A client treats an unknown status as
`unavailable`.

| Status | Methods | Fields | Meaning |
| --- | --- | --- | --- |
| `found` | resolve | `canvas_id`, `source` (`binding` or `default`), `durability` | The conversation's binding, else its agent's default |
| `none` | resolve | (none) | Authorized; neither record exists |
| `bound` | bind | `canvas_id`, `won`, `durability` | The durable winner; `won` is true when it is the caller's own `canvas_id` |
| `replaced` | replace | `canvas_id`, `durability` | The binding now holds the caller's canvas |
| `default_set` | default.set | `canvas_id`, `durability` | The default now holds the caller's canvas |
| `unavailable` | all | `reason` | Nothing changed. `reason` is `storage`, `authority`, `timeout_before_commit` or `identity` |
| `outcome_unknown` | writes | (none) | The write may or may not have committed: resolve before acting |

```json
{"status": "found", "canvas_id": "canvas-3f9a", "source": "binding", "durability": "durable"}
{"status": "bound", "canvas_id": "canvas-b2c8", "won": false, "durability": "durable"}
{"status": "unavailable", "reason": "timeout_before_commit"}
{"status": "outcome_unknown"}
```

Refusals are `success: false` admin RPC responses whose `error` is fixed text:

- `canvas.mount.denied`: every denial, byte-identical whichever check failed and whether or not a
  record exists. It never carries the stored canvas.
- `canvas.mount.invalid_input:<field>:<code>`: `<field>` is `params`, `agent_id`,
  `conversation_id` or `canvas_id`; `<code>` is `missing`, `not_string`, `blank`, `too_long`,
  `malformed` or `unknown_field`. Neither part is ever copied from the request.

Any other failure answers the router's fixed `Internal error`.

## Semantics

- **Bind** writes only when the binding is absent and is idempotent: every caller gets the same
  durable winner. When a binding exists nothing is written; a winner the caller may not see is
  `denied`.
- **Replace** and **default.set** are last-writer-wins. Setting a default never rewrites bindings.
- **Deadline.** A call has about 10 seconds. Past it, a call that cannot have reached its commit
  point (still authorizing, or unwound by cancellation before the atomic replacement) is
  `unavailable` / `timeout_before_commit`; a write that may have committed is `outcome_unknown`.
- **outcome_unknown** follows any failure at or after the commit point (the atomic replacement, its
  directory barrier, a failed winner check after a bind wrote). The client must call
  `canvas.mount.resolve` and use its answer.
- **Durability.** `durable`: contents, atomic replacement and directory entries are confirmed.
  `entry_unconfirmed` (Windows hosts): contents are forced and atomically replaced, but the
  directory entry is not confirmed, so after a power cut a first bind can vanish or a replace can
  revert, and two clients may have been told different winners. A read reports the record as it is
  on disk and does not re-confirm it: after an ambiguous write it can return a value a power cut
  could still lose.
- No listing method exists. Agents have no way to call these methods.

## Client rules (for the later adapter)

- Call only when `canvas.mount.v1` is advertised.
- After `outcome_unknown`, a transport error or a timeout after sending, call resolve and use its
  answer. Never cache a winner across reconnects.
- `denied` and `none` are different states. `unavailable` keeps the current mount.

## Fixtures

`android-compose/sharedLogic/src/jvmTest/resources/canvas-mount/v1/<method>.json`, one file per
method, each case with `params` and exactly one of `result` or `error`.
`CanvasMountContractFixturesTest` checks that params parse exactly as documented and that every
result re-encodes unchanged through the host's own encoder.
