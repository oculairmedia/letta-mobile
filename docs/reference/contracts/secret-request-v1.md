# Secret request v1: secure secret-input flow

Status: **PROPOSED for review; contract, shared logic, host service and route stages.** Stage 1
supplies the shared models (`:sharedLogic`, package `com.letta.mobile.data.secrets.request`), a
durable journal (package `com.letta.mobile.data.secrets.journal`), the host service (package `com.letta.mobile.data.secrets.host`, JVM only) and the
shared fixtures below. Stage 2 registers the admin RPC routes and the agent tool (package
`com.letta.mobile.data.controller.node.iroh.secrets`, JVM only), the route wire (package
`com.letta.mobile.data.secrets.wire`) and the client over the routes (package
`com.letta.mobile.data.secrets.client`); see Routes. No stage ships a real keystore provider.
Sections marked *route* state what the route stage enforces.

Agents need secrets (API keys, tokens) and until now the only way to hand one over was to paste it
into a conversation, where it stays in the transcript, the prompt and every log. This flow keeps
the value out of all of them: an agent asks for a secret by name, the human types it into a
dedicated secure surface, the host writes it to the keystore
([keystore-v1.md](keystore-v1.md)) and the agent learns only a receipt.

## Flow

1. The agent calls `secret.request` with `{purpose, vaultItemName, fieldName}`. The host validates
   it, fixes the target (keystore, item, field), generates an unguessable `requestId` and answers
   `{requestId, status: "pending", expiresAtMs}`.
2. The human's client lists pending requests (`secret.request.list`) and shows each as an
   actionable item, not a chat bubble and not the composer. Its action opens a dedicated secure
   input surface.
3. The surface states exactly what will be written (keystore, item, field, requesting agent,
   expiry) and shows the purpose labelled "Agent-authored". The value is typed into a masked field
   and sent with `secret.request.submit` straight to the host, never through a conversation.
4. The host claims the request, writes the value with create-then-verify, wipes it and records
   the outcome. The agent reads its receipt with `secret.request.status`:
   `{status, itemId, fieldName, reason?}`.

## Invariants

Each is a test target of `:sharedLogic:jvmTest` (`com.letta.mobile.data.secrets.*`).

1. **Q1 No value in a sink.** The value never enters a conversation, prompt, tool result, receipt,
   journal, log line, telemetry event, crash report, exception message, `toString`, process
   argument list, environment variable, notebook or canvas. In process it lives only in
   `SecretValue` (keystore V1 to V5) and is wiped when `submit` returns, on every path. On the wire
   it appears only in the `value` field of `secret.request.submit` params.
2. **Q2 No value metadata for the agent.** A receipt carries no value and no length. The read-back
   comparison is host-internal.
3. **Q3 Single use.** A request is answered at most once. A replay, a concurrent second submission
   and a submission after a restart all answer `not_pending` and write nothing.
4. **Q4 Fixed target.** The keystore, item and field are fixed when the request is created.
   Submission cannot change them, and a request can create or update only that one slot. There is
   no agent-callable keystore read.
5. **Q5 Human only.** Only the human principal (below) may list, read, submit or cancel. An agent
   may create requests and read the receipts of its own requests, nothing else.
6. **Q6 Nothing partial.** A failed write leaves the keystore as it was (see Writing), and is never
   retried without a new request and a new human action.

## Names and limits

| Name | Grammar | Notes |
| --- | --- | --- |
| `requestId` | `sr_` followed by 43 base64url characters (32 random bytes) | Host-generated from a secure random source; never reused |
| `vaultItemName` / `item` | Keystore `SecretRef.path`: 1 to 16 segments joined by `/`, each `[A-Za-z0-9_.-]+` and not `.` or `..`, at most 256 characters | A name, never a value |
| `fieldName` / `field` | Keystore `SecretRef.field`: `[A-Za-z0-9_.-]{1,64}`, not `.` or `..` | Required |
| `keystore` | Keystore provider id: `[a-z0-9]+([.-][a-z0-9]+)*`, at most 64 characters | Chosen by the host, never by the agent |
| `purpose` | Plain text, 1 to 280 characters after trimming | See Purpose |
| value | 1 to 65 536 bytes (keystore `MAX_VALUE_BYTES`) | UTF-8 for text secrets |

- **N1** Names are compared as exact strings: no case folding, normalization or decoding. A
  malformed name is refused, never repaired.
- **N2** Every requested item must equal the agent's scope root or lie under it by whole segments.
  The default root is per agent, `agents/<agentId>` (the verified requesting agent's id), so an
  agent can never name another agent's items; an agent id that is not one item segment gets no
  root and every item is refused. A wider, shared root is configured explicitly by the host owner
  (`SecretItemScope.Shared`); there is no unrestricted scope, and `SecretRequestFeature.forHost`
  takes the scope as a required argument. A request outside the scope is `item_outside_scope`.
- **N3** One agent may have at most 8 pending requests (`too_many_pending`) and may create at most
  8 requests in any 15 minutes, a sliding window counted from the journal (`too_many_requests`;
  declined, expired and failed requests count too). The journal keeps at most 16 settled requests
  per agent, newest first. A journal holds at most 512 records.
- **N4** A request expires 15 minutes after creation. The agent cannot choose the lifetime.

### Purpose

The purpose is agent-authored. A client shows it as plain text labelled "Agent-authored", never
renders markup in it, never turns text in it into a link and never presents it as host or system
text. The host trims surrounding whitespace and refuses (`purpose_not_plain_text`) any purpose
that holds `<`, `>`, `[`, `]` or a backtick, `://`, `www.` or `mailto:`, starts with `#`, or holds a
control, format (including bidirectional overrides and zero-width characters), line or paragraph
separator, private-use, surrogate or unassigned character, or a word (a run of letters) that mixes
Latin letters with Greek or Cyrillic ones (the look-alike trick of spelling a familiar name with a
Cyrillic "a"); a sentence may still use several scripts, one per word. A bare domain such as
`api.openai.com` is accepted on purpose: naming the service a key is for is an ordinary purpose,
and no small rule tells a domain from `ASP.NET` or a file name. The defence against a link is the
client's: it never turns purpose text into a link, and the target it shows is the host's.

## Methods

Admin RPC methods follow the router's dotted, lower-case naming (`pair.peer.list`,
`model.exposure.set`, `approval.submit`). Params and results are JSON objects; optional fields are
omitted when absent, never `null`; enum values are the lower-case codes in this document.

| Method | Caller | Params | Result |
| --- | --- | --- | --- |
| `secret.request` | Agent | `{purpose, vaultItemName, fieldName}` | `{requestId, status: "pending", expiresAtMs}` |
| `secret.request.status` | Agent (own requests) | `{requestId}` | `{receipt?}`: absent while the request is open |
| `secret.request.list` | Human | `{}` | `{requests: [request...]}`, newest first |
| `secret.request.get` | Human | `{requestId}` | request |
| `secret.request.submit` | Human | `{requestId, value}` | `{receipt}` |
| `secret.request.cancel` | Human | `{requestId}` | `{receipt}` |

A *request* (what a client sees) is:

```json
{
  "requestId": "sr_...",
  "details": {
    "requestingAgent": "agent-...",
    "target": {"keystore": "local.vault", "item": "agents/notes/openai", "field": "api_key"},
    "purpose": {"text": "...", "author": "agent"},
    "replacesExisting": true
  },
  "timing": {"createdAtMs": 0, "expiresAtMs": 0},
  "status": {"state": "pending", "reason": "...", "settledAtMs": 0}
}
```

`status.reason` is present only for `failed`; `status.settledAtMs` only for terminal states.
`details.replacesExisting` is present (and `true`) only when the target slot already held a value
when the host created the request: the host reads the slot itself at creation, never takes it
from the agent, and a client shows it as "this replaces an existing value". The agent's answers
never carry it. A
*receipt* is `{status, itemId, fieldName, reason?}`: `status` is `saved`, `cancelled`, `expired`
or `failed`; `itemId` and `fieldName` are the target's item and field; `reason` is present only
for `failed`.

- **M1** `value` in `secret.request.submit` is standard padded base64 of the secret bytes, as in
  keystore W2. It is the only field in this contract that carries a value.
- **M2** `secret.request.status` is a poll. The host service can also await a receipt
  (`awaitReceipt`); how the agent is told that its request settled is decided with the agent
  integration (open decision 5).
- **M3** *route* A refusal keeps the admin RPC envelope: `{"success": false, "error": <text>}`, where
  `<text>` is the code, or for `invalid_request` the code, `:` and the problem
  (`"invalid_request:purpose_blank"`). The admin RPC envelope has no other field a handler can set.

### Errors

| Code | When | Calls |
| --- | --- | --- |
| `invalid_request` | The agent's request breaks a rule; `problem` says which | `secret.request` |
| `forbidden` | A human call from anything but the human principal; an agent call from anything but the agent tool (no admin RPC connection is an agent) | All |
| `not_pending` | Unknown, pruned, malformed, expired, cancelled, answered or being written: one code for all, nothing written | `submit`, `cancel` |
| `invalid_value` | The value is empty, over 65 536 bytes, or not canonical padded base64; the request stays pending | `submit` |
| `unknown_request` | No such request for this caller; another agent's request answers the same | `status`, `get` |
| `journal_unavailable` | The journal cannot be read or written; nothing was written to the keystore by this call | All |
| `invalid_params` | *route* The params are not exactly the method's fields, or a field is not a JSON string; nothing about the params is echoed | All |
| `busy` | Another submission's write did not finish within the lock wait (default 2 s); nothing was decoded, claimed or written and the request stays pending | `submit` |

Problems for `invalid_request`: `purpose_blank`, `purpose_too_long`, `purpose_not_plain_text`,
`item_malformed`, `field_malformed`, `item_outside_scope`, `too_many_pending`, `too_many_requests`.

Failure reasons in a `failed` receipt, each a fixed code with no provider text:

| Reason | Meaning |
| --- | --- |
| `vault_locked` | The keystore is locked |
| `vault_unavailable` | The keystore is unreachable or unhealthy |
| `vault_denied` | The keystore refused |
| `vault_unsupported` | The keystore lacks a capability the write needs (read, write, list, delete) |
| `vault_error` | The keystore failed unexpectedly, or threw |
| `target_not_writable` | Writing could leave a partial store (see W2), or the slot appeared concurrently |
| `value_rejected` | The keystore refused the value |
| `verify_failed` | The read-back did not match; the write was undone |
| `rollback_failed` | The read-back did not match and undoing the write failed too; an operator must check the item |
| `interrupted` | The host stopped (or could not record the outcome) while the write was in progress; the outcome is unknown |
| `target_changed` | The slot held no value when the request was made (`replacesExisting` absent) but holds one now; nothing was replaced |

What a receipt reveals about the keystore: `target_not_writable` tells the agent that the item
exists while the field does not (or that the keystore cannot list), and `target_changed` that the
slot gained a value after the request was made. No other answer depends on what the keystore
holds: creating a request answers the same whether or not the slot exists, and `saved` does not
say whether a value was replaced. Under the per-agent scope both reveal only items in the agent's
own namespace.

## Lifecycle

```
pending --submit (claim)--> submitting --verified--> saved
   |                             '---------failure--> failed
   |--cancel--> cancelled
   '--expiry (now >= expiresAtMs)--> expired
```

- **L1** `saved`, `cancelled`, `expired` and `failed` are terminal: they never change again.
- **L2** `submitting` is transient. It is visible to the human's client, never in a receipt.
- **L3** A submission first claims the request (pending to submitting) in the journal, under the
  service lock, before anything is written to the keystore. Only one claim can succeed.
- **L4** A pending request whose expiry has passed accepts only expiry. Every service call expires
  due requests first; a host may also call `expire` on a schedule; a waiting receipt read expires
  its own request when its expiry passes.
- **L5** On a host restart, a `submitting` record that no live submission owns is settled as
  `failed` (`interrupted`). One host process owns a journal.
- **L6** A replay of any id that is not pending, before or after a restart, answers `not_pending`
  (submit, cancel) and never reaches the keystore.

## Writing

The host writes the value to `{keystore, item, field}` through the keystore SPI so that either the
verified value is stored or nothing new is left behind:

- **W1** An existing slot is replaced (`put` mode `replace`) only when the request carries
  `replacesExisting`; its previous value is read first and held in host memory only, then wiped. A
  mismatch restores it. A slot that came into existence after the request was made is never
  replaced: the submission settles `failed` (`target_changed`) and nothing is written.
- **W2** A missing slot is created (`put` mode `create`) only when the keystore can `list` and the
  listing proves the whole item is absent, so undoing it is deleting the item. A missing slot in an
  existing item, or in an item whose absence cannot be proved, is refused with
  `target_not_writable`: keystore v1 has no field delete, so a failed verification there could not
  be undone.
- **W3** After the write the slot is read back and compared in constant time, and the read-back is
  wiped. A mismatch undoes the write and settles `verify_failed`, or `rollback_failed` when the
  undo fails.
- **W4** Every keystore error maps to a fixed reason; diagnostics and exception text are dropped.
  There is no automatic retry. Writes are serialized across requests.
- **W5** *Time bounds.* A submission for a request that is not pending is refused at once. One
  that cannot get the write lock within the lock wait (default 2 s) is refused `busy` before its
  value is decoded or its request claimed. A keystore write, and the creation-time check of the
  slot, may take at most the keystore deadline (default 15 s): a write that runs over settles
  `failed` (`vault_unavailable`), its value is wiped and the lock is freed (the value may or may
  not have been stored, as for `interrupted`); a check that runs over counts as "no existing
  value". The deadline can stop only a provider that suspends cancellably; one that blocks a
  thread holds it until it returns.

## Principals

- **P1** *Human principal.* `SecretRequestPrincipal {endpoint, origin, access}` may answer only when
  `origin` is `human_paired_device`, `access` is `admin_full` and `endpoint` is a well-formed
  endpoint id. *route* It may be built only by the serving transport, from the identity it verified
  for the connection: the authenticated endpoint id of a paired device's Iroh control connection
  (the admin RPC request context's authenticated endpoint), the session's verified `admin.full`
  capability, and the fact that the call is a human-initiated admin RPC on that connection. It is
  never built from params, tool arguments, frame fields or anything an agent can write.
- **P2** The service refuses every other principal, including every agent origin
  (`agent_a2a`, `agent_app_server_input`, `agent_tool_call`) and `host_internal`, with `forbidden`.
- **P3** *route* The submit path is a dedicated admin RPC route that bypasses the conversation and
  App Server turn pipeline. It is not an agent tool call, not a chat message, and not the existing
  `secret.apply` relay (which carries values through the App Server and its logs). The route must
  not log its params or result; it may log method, outcome code and duration.
- **P4** *Requesting agent.* `RequestingAgent {agentId}` is the agent whose turn made the
  `secret.request` call, as the host verified it, never as an argument names it. It may read the
  receipts of its own requests only; any other id answers `unknown_request`.

## Who sees what

| Party | May see | Never sees |
| --- | --- | --- |
| Requesting agent | Its own `requestId`, `expiresAtMs`, receipt (`status`, `itemId`, `fieldName`, `reason`) | The value, its length, other agents' requests, who answered |
| Other agents | Nothing about the request (`unknown_request`) | Anything |
| Human (paired device, `admin.full`) | Every request of an agent in the device's agent scope: target, requesting agent, purpose, timing, state, reason | The value after submission (never returned); requests of agents outside its scope |
| Journal, logs, telemetry | Ids, targets, purpose (journal only), states, timestamps, fixed codes, bounded id labels | The value, provider diagnostics, exception text |
| Keystore provider | The value, at the fixed target | The purpose, the agent |

Telemetry records only fixed codes and an 11-character label of a well-formed request id; never an
item, field, purpose, path or value.

## Routes

*route* Stage 2 (letta-mobile-n2stx.2.4), in `com.letta.mobile.data.controller.node.iroh.secrets`.

- **R1 Registration.** `SecretRequestFeature.forHost` builds the routes and the tool only when the
  auth policy it is given is secure (bearer token, peer allowlist or paired peers only) and a
  keystore provider is configured; otherwise it answers null and the host registers, advertises and
  offers nothing. The routes refuse to register under the anonymous test policy even when handed
  one. Two limits: the policy is whatever the caller passes, since the factory cannot see the
  endpoint, so this check is only as good as the future wiring; the per-call backstop is R3 (an
  anonymous endpoint's sessions are never paired devices, so even routes built for a secure policy
  refuse them, test-pinned). And today the feature is inert because nothing in production calls
  `forHost` at all (no keystore provider exists yet), not because `forHost` answers null on real
  hosts. The classes exist only in the JVM host (decision 8). All six methods register together, and
  only then is host capability `secret.request.v1` advertised.
- **R2 Gate.** At the connection's capability gate the human methods need peer capability
  `secret.request.answer` and the agent methods `secret.request.agent` (`admin.full` passes both, as
  for every method). No role grants either.
- **R3 Human principal.** Built from the request context the serving Iroh connection made: it must
  be authenticated, carry a 64-hex verified endpoint id, be a paired device by its stored record
  (`pairedDevice`; a bootstrap session such as a host-local process holding the admin token is not),
  and hold `admin.full` (`grantedCapabilities`). The device's agent scope applies: `list` shows only
  requests of agents in scope; `get` of a request outside it answers `unknown_request` and
  `submit`/`cancel` answer `not_pending`, byte for byte as for an unknown id. Byte-identical holds
  for every denial the handler makes; a call the connection's capability gate denies (R2) never
  reaches the handler and gets the gate's envelope, which also carries a `capability` field.
- **R4 Agent principal.** No admin RPC connection is ever an agent: `secret.request` and
  `secret.request.status` answer `forbidden` to every connection. Agents call the host tool
  `secret_request` (actions `create` and `status`), which is bound to the agent id the host runtime
  stamped on the tool call, never to an argument. The caller's identity must be verified
  (`CallerIdentity.VERIFIED`: the App Server's runtime stamp, or a Meridian live-call token); a
  claimed identity (Meridian env-scoped binding, a bare agent id, a scope relayed from another
  host) or a call without an agent answers `forbidden` before any argument is read (owner decision
  2026-11-11). Its result is the route's result and its error the route's code.
- **R5 Order.** Principal, then strict params (exactly the method's fields, all JSON strings), then
  visibility of the named request, then the service. For `submit` the service checks that the
  request is pending and takes the write lock (W5) before the value is touched; only then is it
  decoded (length and padding checked against 65 536 bytes before any byte is allocated, then
  decoded straight into the keystore secret type). A malformed id answers like an unknown one.
- **R6 No value out.** No result, refusal, telemetry event or `toString` carries a value. The routes
  log method, side, transport and outcome code only; the stream server answers a frame it cannot
  parse with fixed text.

## Journal

- **J1** The journal stores `SecretRequestRecord`s (the request object above): ids, targets, the
  agent's purpose, states and timestamps. Never a value or a value's length.
- **J2** *Durability.* An update is atomic and durable before the service acts on it or returns:
  the claim is durable before the keystore is written, and the outcome is durable before the
  receipt is returned. The file journal writes a synced temporary file, renames it over the
  journal and syncs the directory where the platform supports it.
- **J3** *Barrier.* Before replacing the journal a `.pending` barrier is written and removed after
  the replacement, or after a fully synced rollback. A barrier left behind makes every operation
  fail closed (`journal_unavailable`) until an operator reconciles it.
- **J4** *Fail closed.* A journal that is not a regular file, is empty, exceeds 2 MiB, is not strict
  UTF-8 JSON, has duplicate keys, has an unknown version, or holds records that break the rules
  (malformed ids or names, duplicate ids, inconsistent status, more than 512 records) is never
  reset or rewritten; every operation answers `journal_unavailable`.
- **J5** *Bounds.* Terminal records are pruned 24 hours after they settle, and beyond the newest 16
  per agent; pending and submitting records are never pruned. A new request that would push the
  journal over 512 records or 2 MiB first makes room by pruning settled records created before
  the 15-minute creation window, then, if that is not enough, every settled record; only then is
  it refused (`journal_unavailable`). A pruned id answers like an unknown one.
- **J6** Only a missing journal file reads as empty. Deleting it forgets every request, so its
  directory must be writable only by the host.

## Fixtures

The normative examples are in
[`android-compose/sharedLogic/src/jvmTest/resources/secret-request/v1/`](../../../android-compose/sharedLogic/src/jvmTest/resources/secret-request/v1/),
one file per method: `{method, caller, cases: [{name, params, result | error}]}`. Their values are
sentinels. `SecretRequestContractFixturesTest` decodes every case into the typed models, re-encodes
it to an equal JSON value, checks validation against the documented problems and checks the name
grammars against the keystore SPI. They are model fixtures; the admin RPC route fixtures are the
per-method wire contracts under `docs/reference/contracts/secret.request*/secret-request-v1.md`.

## Tests and what they cannot see

`SecretRequestServiceTest`, `SecretRequestRestartTest`, `FileSecretRequestJournalTest`, the common
lifecycle, validation and journal tests, and `SecretRequestSentinelTest`, which sends sentinel
values through every service path against a file journal, a capturing telemetry recorder and a
capturing log, and finds no sentinel (raw, base64, base64url or hex) in any journal byte, telemetry
event, log line, result, error or `toString`. They cannot see a real host's system journal, the App
Server's logs, the admin RPC transport, a real provider's own storage or the client's screen.

## Non-goals

No real keystore provider or vault contact in any stage so far (stage 4). Out of v1 entirely: agent-callable keystore reads; rotation;
deleting a single field; multiple fields per request; requests for another agent; delegation of
answering to an agent; reading a value back to any client; recovering the outcome of an
interrupted write.

## Open decisions

Each was settled conservatively for v1 and is listed for the owner:

1. **New field in an existing item is refused** (W2) rather than allowed with a failure path that
   could leave a partial store. Allowing it needs a keystore field delete (a keystore minor).
2. **`submitting` is a visible lifecycle state** for the human's client (needed for crash safety);
   receipts still carry only the four terminal statuses.
3. **Per-agent item scope by default and no silent replace** (N2, W1): author's choice after the
   2026-11-13 security review, awaiting owner confirmation. Before it, any well-formed item was
   accepted and an existing slot was replaced, so an agent could overwrite any secret it could
   name.
4. **`interrupted` means unknown outcome**: the value may or may not be stored. The host does not
   probe the keystore after a restart because it no longer holds the value to compare.
5. **Receipt delivery to the agent** is a poll (`secret.request.status`) in this contract; pushing a
   notification into the agent's conversation is decided with the agent integration. The receipt
   omits `requestId` (the agent already holds it).
6. **The purpose is journaled** so a pending request can still be shown after a restart; it is
   agent-authored text, never a value.
7. **The human principal requires a paired device session** with `admin.full`. A host-local process
   holding an admin token is not a paired device and must not be mapped to `human_paired_device` by
   the route stage.
8. **The host service is JVM only**, because `:plugin-api` is a JVM-only dependency of
   `:sharedLogic`. An Android host would need that dependency on Android too.
9. **Limits**: purpose 280 characters, 8 pending requests per agent, 512 journal records, 15 minute
   lifetime, 24 hour retention of settled records.
