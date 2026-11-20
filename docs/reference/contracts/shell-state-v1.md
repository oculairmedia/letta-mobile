# Shell state v1: the host's one versioned document of shell and user state

Status: **frozen for v1.** Bead: letta-mobile-fvb1x.

Fixtures: one per method, `android-compose/sharedLogic/src/commonTest/resources/contracts/<method>/shell-state-v1.json`,
each paired with a short spec in `docs/reference/contracts/<method>/shell-state-v1.md`.
Wire names, limits and error codes: `android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/shellstate/wire/`.
Document types: `ShellStateSnapshot`, `ShellStateEntry`, `ShellStateActor` in `.../data/shellstate/ShellStateDocument.kt`.
Client (`SharedShellState`, `channelShellStateLink`): `.../data/shellstate/shared/`.
Host routes, store and push: `android-compose/sharedLogic/src/jvmAndAndroid/kotlin/com/letta/mobile/data/controller/node/iroh/shellstate/`.
Contract tests: `ShellStateFixtureTest` (commonTest) decodes every case through the client types;
`ShellStateRoutesTest` (jvmTest) replays every case through the real routes; `WireContractsTest`
(jvmTest) runs every case through the full host router.

## Decision this implements

Shell and user state (appearance, layout, notification mutes, conversation archives, voice tuning,
canvas edits and the like) is **one document held by the host for its single owner**, not a copy on each
client. A host (an iroh-wrapper-cli server, or the on-device runtime when the phone runs locally) serves
one person and that person's paired devices, so there is no per-user or per-device document: every device
the owner pairs reads and proposes changes to the same document, and a change on one device reaches the
others at once. Clients keep no copy on the device. This contract is the store and the wire only: no
client area moves onto it yet, and nothing is migrated from device storage. The same store is the
storage layer later work (a host turn journal, export and import between hosts) builds on.

## Transport, discovery and versioning

- Both methods are `admin_rpc` methods with the existing envelope: request
  `{"type":"admin_rpc","request_id","method","params"}`, response
  `{"type":"admin_rpc_response","request_id","success","result"|"error"}`.
- A host that serves both methods and the push advertises the auth capability
  **`shell_state_v1`** (through `AdminRpcRouter.featureCapabilities`). Without it a client has no shell
  state: it shows none and refuses edits. It never falls back to a copy of its own.
- The on-device runtime serves the same methods from its in-process router, with the same results. It has
  one connection, so it sends no push.
- The document carries `schema_version` (this contract: **1**). A host refuses to read a stored document
  of a newer schema (`state_unreadable`, file kept); a client does not adopt a document of a newer schema.

## Authorization

| Method | Capability | Notes |
| --- | --- | --- |
| `shell.state.get` | `chat.read` | Read like `pins.get`. |
| `shell.state.propose` | **`conversation.manage`** | Changing shared preferences and layout is a chat-role edit like `pins.set`, so every paired chat device may propose. |

**Agent-scoped peers are refused.** A pairing bounded to some agents (`authorized_agent_ids` set) would
learn, or could change, state that spans every agent on the host, so both methods refuse it with
`forbidden: agent not in this device's scope`, and it receives no `shell_state_updated` push.

## The document

```
{"schema_version": 1, "document_revision": <integer>, "entries": {
  "<key>": {"value": <any JSON>, "revision": <integer>, "updated_by": <actor>, "updated_at_ms": <integer>}, ...}}
```

- `document_revision` is 0 until the first accepted proposal and goes up by exactly one with every
  accepted proposal that changes something. A proposal that changes nothing keeps it and sends no push.
- Every entry a proposal writes takes that proposal's new `document_revision` as its own `revision`, so
  an entry's revision only grows and is never reused, even when the entry is removed and set again.
- A key is 1 to 128 characters of lowercase `a-z`, digits, `.`, `_` and `-`, starting with a letter or
  digit (for example `appearance.theme`).
- `updated_by` records who made the change, as a tagged object: `{"type":"user","client_id":"..."}` for a
  person or `{"type":"agent","agent_id":"..."}` for an agent. **Over `admin_rpc` the actor is always
  `user`**, with `client_id` set to the serving connection's authenticated endpoint (or `host-local` for
  the on-device runtime's in-process router): the host never takes the actor from the request, so a peer
  cannot name itself an agent or another device. The `agent` actor is reserved for host-side agent tools.
- `updated_at_ms` is the host clock when the entry was written.
- The host stores exactly this object (`shell-state.json`), so a host-state export can carry it unchanged.
  The file is replaced by a rename and readable only by the host's user.

### Limits

| Limit | Value | Refusal |
| --- | --- | --- |
| Entries in the document | 512 | `too_large` |
| Changes in one proposal | 64 | `invalid_request` |
| Key length | 128 characters | `invalid_request` |
| One entry's encoded value | 8192 bytes (UTF-8 JSON) | `too_large` |
| The encoded document | 262144 bytes | `too_large` |

## `shell.state.get`

- Params: none (`{}`).
- Result: the document.

## `shell.state.propose`

- Params: `{"changes": [{"key": "...", "value": <any JSON>, "expected_revision": <integer >= 0>}, ...]}`.
  A change without `value` (or with `null`) removes the entry. `expected_revision` is the `revision` of
  that entry in the caller's copy, or 0 when the caller has no such entry.
- Compare-and-set **per entry, all or nothing**: when every change's `expected_revision` equals its
  entry's current revision (0 for an absent entry) the host applies all of them and answers the new
  document; otherwise it changes nothing and answers `revision_conflict`. The caller then reads the
  document again; it never retries blindly. Two writers of different entries never conflict.
- A change that leaves an entry's value as it is writes nothing for that entry.
- Result: the document after the proposal.

## Errors

| `error` | When |
| --- | --- |
| `revision_conflict` | Some change's `expected_revision` is no longer its entry's revision. |
| `invalid_request` | Missing, empty, non-array or overlong `changes`, a non-object change, a key that is not valid or is repeated in the proposal, or a missing, negative or non-integer `expected_revision`. |
| `too_large` | A value or the resulting document would pass a limit above. |
| `state_unreadable` | The host cannot decode its stored document, or it is of a newer schema. The file is kept and nothing is written; the owner must repair or remove it, after which the host serves again without a restart. |
| `forbidden: agent not in this device's scope` | An agent-scoped peer. |
| `forbidden` | The transport's capability gate (no `chat.read` / `conversation.manage`). |
| `Unknown method: shell.state.get` | A host that predates the contract. |

Clients map unknown codes to an "unknown" error; they must not fail on a new code.

## Push: `shell_state_updated`

After every accepted proposal that changed the document, the host sends every connection that is
authenticated, may read agents (`chat.read`) and is not agent-scoped:

`{"v": 1, "type": "shell_state_updated", "id": "shell-state-updated-<uuid>", "ts": "<ISO-8601 instant>", "schema_version": 1, "document_revision": <integer>, "entries": {...}}`

- The frame carries the whole document, so a client replaces its copy without a read.
- A client adopts a push only when its `document_revision` is greater than the one it shows; the writer
  gets its own push too and ignores it.
- A push is an optimisation: a client that was offline missed it and reads the document on reconnect.
- Clients that predate the contract ignore the frame (an unknown host push type is dropped).

## Client behaviour (normative for this repository's clients)

- The last document read is kept in memory only, never on the device. Without the capability there is
  no state and edits are refused.
- A proposal sends each touched entry with the revision the client last saw of it. Success adopts the
  host's answer; `revision_conflict` reads the document again; any other refusal changes nothing shown.
- While the host is unreachable or the document has not been read on this connection, edits are refused
  and nothing is queued; the last document read keeps showing. A connected client whose last read or
  write failed (for any reason, including a refusal) reads again before its next edit, so one failed call
  never leaves it read-only until a reconnect. The document is read again on every reconnect.
