# Canvas mount v1: host-owned canvas defaults and bindings

Status: **PROPOSED for independent security review (letta-mobile-n2stx.5, stage 2).** This stage
adds the host routes (`:sharedLogic`, package `com.letta.mobile.data.controller.node.iroh.mounts`),
the shared wire names (`com.letta.mobile.data.canvas.mount.wire.CanvasMountWire`), the change push,
the host canvas tools' read of the mount, and the shared fixtures below. It ships no client adapter,
UI, agent tool or agent-callable mount path. The wrapper wires it only behind
`--enable-canvas-mounts` (default off, not for use until this review passes).

The user feature it serves: pick a canvas from the side menu and it is mounted for that one
conversation (`canvas.mount.binding.replace`), or set a default for every conversation of an agent
(`canvas.mount.default.set`, removed with `canvas.mount.default.clear`).

A conversation shows one canvas, decided in this order everywhere (resolve, the change push and the
host canvas tools): the conversation's own **binding**, else its agent's **default**, else the
conversation's own canvas. The binding and the default are host-owned records shared by every
client of that host. A conversation that wants its own canvas despite an agent default binds its
own canvas id explicitly. Records hold semantic canvas
ids, never notebook storage ids, and live in the host's `canvas-mounts/` sidecar (see the
`com.letta.mobile.data.canvas.mount` package).

## Capability and registration

- The host advertises `canvas.mount.v1` exactly when all six methods are registered. A client that
  does not see it must not call them.
- All six methods are registered together or not at all, and only when the endpoint's auth policy is
  secure (bearer token, peer allowlist or paired peers only). Under the anonymous test policy
  (`--allow-insecure-anonymous-iroh`) nothing is registered and nothing is advertised.
- Peer capabilities: `canvas.mount.resolve` and `canvas.mount.candidates` need `canvas.mount.read`;
  bind, replace, `canvas.mount.default.set` and `canvas.mount.default.clear` need
  `canvas.mount.write` (the handler re-checks it for the two default methods). On a host running
  with `--enable-canvas-mounts`, every pairing role with chat access (`chat`, `desktop`,
  `vibesync`) holds `canvas.mount.read` and `canvas.mount.write`; without the flag no role does.
  An invite's role is resolved when it is redeemed, by the redeeming host's flag.
- A paired-device record stored without a `capabilities` field means the desktop role as it was
  before canvas mounts (frozen), never a later, wider role.
- Devices paired before this change (D7): when the host first starts with `--enable-canvas-mounts`,
  a one-time upgrade adds the two mount capabilities to every paired device whose capability set is
  exactly the `chat`, `desktop` or `vibesync` role as it was before them (stored explicitly or not:
  a set equal to a role is that role). Any other set is left as it is, nothing else is ever added,
  and a marker beside the pairing store makes it run once.

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
4. Proposal (writes; also each listed canvas of `canvas.mount.candidates`): `canvas_id` is visible
   to the connection (below). `canvas.mount.default.set` additionally needs `canvas.mount.write` on
   the connection, `agent_id` in its agent scope and an existing agent; `canvas.mount.default.clear`
   proposes no canvas and needs exactly those three.
5. Storage.
6. Winner (resolve and bind): the stored canvas is visible to the connection. Replace,
   default.set and default.clear answer the caller's own canvas or none, so this step does not apply.

### Visibility

Decided by the host from its own records only, never from params or document content:

- A binding's own conversation canvas (`canvas-conversation-<conversation_id>`) is visible to a
  caller allowed to address that binding while the host's records show that id unused or held
  through that very conversation (its directory entry belongs to the conversation, or the relay bound
  `conversation:<conversation_id>` to it). Otherwise it is judged like any other canvas below: the id
  string alone proves nothing, since any signed-in app may bind a relay topic to any proposed id.
  Replacing a binding with it is how a client goes back to the conversation's own canvas; resolve
  then answers it as `found` / `binding`.
- Any other canvas must exist on this host. Its owning agents come from, in order: the host canvas
  directory (`host-canvases.json`) entry's conversation (owned by that conversation's agent per the
  App Server), else the entry's writer agents (the creating agent, stamped by the runtime); else a
  conversation canvas the relay has bound (owned by that conversation's agent); else any canvas the
  relay has bound, with no known owner.
- Visible when the connection has no agent scope, or one of the owning agents is in its scope. A
  canvas with no known owner is visible only without agent scope. A canvas the host does not have is
  visible to no one.

A canvas that does not exist and one the caller may not see are denied identically, and take the
same conversation owner lookup. While judging a proposed canvas (bind, replace, default.set) a
failed lookup is that same `canvas.mount.denied`, never `unavailable`, so a probe learns nothing
from it. Any other failed lookup (addressing, the winner check, a candidates listing) denies and is
reported as `unavailable` / `authority`; `unavailable` / `storage` is the mount store's own failure.

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
| `canvas.mount.default.clear` | `agent_id` |
| `canvas.mount.candidates` | `agent_id`, `conversation_id` |

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
| `default_cleared` | default.clear | `durability` | The agent has no default now (whether or not it had one) |
| `listed` | candidates | `canvases`, `truncated` | Candidates in order (below); `truncated` is true when canvases were left out |
| `unavailable` | all | `reason` | Nothing changed. `reason` is `storage`, `authority`, `timeout_before_commit` or `identity` |
| `outcome_unknown` | writes | (none) | The write may or may not have committed: resolve before acting |

```json
{"status": "found", "canvas_id": "canvas-3f9a", "source": "binding", "durability": "durable"}
{"status": "bound", "canvas_id": "canvas-b2c8", "won": false, "durability": "durable"}
{"status": "unavailable", "reason": "timeout_before_commit"}
{"status": "outcome_unknown"}
{"status": "listed", "canvases": [{"canvas_id": "canvas-conversation-conv-41d0", "owner_kind": "conversation"}], "truncated": false}
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
- **Replace** and **default.set** are last-writer-wins. Setting or clearing a default never rewrites
  bindings.
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
- **Candidates** (D6) lists, read-only, the canvases the caller may mount for the addressed binding:
  the binding's own canvas first, then every canvas in the host canvas directory that passes the
  proposal check (step 4) for this caller, so nothing is listed that bind or replace would refuse.
  Each entry has `canvas_id`, `title` when the directory records one, and `owner_kind`
  (`conversation` or `agent`, never an owner id). At most 100 entries. The host reads its directory
  once per call, looks each conversation's owner up at most once, and judges at most its first 500
  directory entries; `truncated` is true when either bound left canvases out. Canvases only the
  relay holds (app-made) are not enumerated. A failed host lookup answers `unavailable` / `authority`, never a
  partial list. It reads no mount record and pushes nothing.
- Agents have no way to call these methods.

## Change push

After a replace, or a bind whose winner is the caller's proposal, the host sends each connection
viewing that conversation:

```json
{"type": "canvas_mount_updated", "agent_id": "agent-7c1e", "conversation_id": "conv-41d0", "canvas_id": "canvas-3f9a", "durability": "durable"}
```

A viewer receives it only when its capabilities admit `canvas.mount.resolve` and resolve's own
checks, run on that viewer's verified identity and scopes, allow addressing the binding and seeing
the canvas. Defaults, reads, failures and `outcome_unknown` push nothing: a binding wins over any
default, so a binding change is always what the conversation shows, while a default change reaches
viewers of unbound conversations when they next resolve. The push is best effort: a
client re-resolves when it opens a conversation and after its own write.

## Agent tools

A host canvas tool call that names no canvas acts on the canvas the runtime-stamped caller's own
conversation is mounted on (its binding, else its agent's default), else the conversation's own
canvas (the same order as resolve), and `canvas_list` marks that one `current`. The tools read the mount as a host-internal
principal that admits only the caller's own binding and default and refuses every mutation, so an
agent can never change a mount.

Grant on mount (D5): the canvas the caller's own conversation is mounted on now may be read and
written by that caller (the runtime-stamped agent of that conversation), even when its ACL names
another agent and even when only the relay holds it (an app-made canvas, opened read-only, never
created by the check). The grant is derived from the live mount at every check and no ACL is
changed, so replacing or removing the mount revokes it on the next call. It covers only that
canvas, only for that agent in that conversation; other conversations and agents gain nothing,
except that a plugin called by that agent in that conversation places as that call and so inherits
the grant, including the call's late and background emits, while the mount stays (current
behaviour, pending an owner decision); a call whose scope is only claimed passes none on. A
mount that cannot be read, or a mounted canvas the host does not have, is a refusal, never another
board.

Only a caller whose agent and conversation scope the host itself established follows a mount: the
App Server's runtime stamp on the tool-call frame, or a Meridian live-call token this host issued for
that one call. A claimed scope (Meridian's env-scoped binding, which takes the ids from the agent's
shell environment, tool arguments, a client) gets no mount at all: no read or write grant, no
mount-based board choice, no mounted canvas in `canvas_list`, exactly as on a host without mounts.
The same holds for a plugin placing for such a call. As defence in depth, a host running
`--enable-canvas-mounts` with `--meridian-caller-binding env-scoped` gives the agent canvas tools no
mount lookup at all and logs that once at startup; the device routes above are unaffected.

## Client rules (for the later adapter)

- Call only when `canvas.mount.v1` is advertised.
- On `canvas_mount_updated` for an open conversation, show the pushed canvas (or re-resolve).
- After `outcome_unknown`, a transport error or a timeout after sending, call resolve and use its
  answer. Never cache a winner across reconnects.
- `denied` and `none` are different states. `unavailable` keeps the current mount.

## Fixtures

`android-compose/sharedLogic/src/jvmTest/resources/canvas-mount/v1/<method>.json`, one file per
method, each case with `params` and exactly one of `result` or `error`.
`CanvasMountContractFixturesTest` checks that params parse exactly as documented and that every
result re-encodes unchanged through the host's own encoder.
