# Pins v1: the host's pinned agents, one list shared by every device

Status: **frozen for v1.** Bead: letta-mobile-885b7.

Fixtures: one per method, `android-compose/sharedLogic/src/commonTest/resources/contracts/<method>/pins-v1.json`,
each paired with a short spec in `docs/reference/contracts/<method>/pins-v1.md`.
Wire names and error codes: `android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/pins/wire/`.
Client (`SharedAgentPins`, `AgentPinsSnapshot`, `channelAgentPinsLink`): `.../data/pins/shared/`.
Host routes, store and push: `android-compose/sharedLogic/src/jvmAndAndroid/kotlin/com/letta/mobile/data/controller/node/iroh/pins/`.
Contract tests: `AgentPinsFixtureTest` (commonTest) decodes every case through the client types;
`AgentPinsRoutesTest` (jvmTest) replays every case through the real routes; `WireContractsTest`
(jvmTest) runs every case through the full host router.

## Decision this implements

Pinned agents are **one global list for the host's single owner**. A host (an iroh-wrapper-cli
server, or the on-device runtime when the phone runs locally) serves one person and that person's
paired devices, so there is no per-user or per-device list: every device the owner pairs reads and
edits the same ordered list, and a change on one device reaches the others at once. There is no
device-local pin store and no migration of pins a device kept before this contract; a host that has
never stored a list answers an empty one.

## Transport, discovery and versioning

- Both methods are `admin_rpc` methods with the existing envelope: request
  `{"type":"admin_rpc","request_id","method","params"}`, response
  `{"type":"admin_rpc_response","request_id","success","result"|"error"}`.
- A host that serves both methods and the push advertises the auth capability
  **`pinned_agents_v1`** (through `AdminRpcRouter.featureCapabilities`). Without it a client has no
  pins: it shows none and refuses pin edits. It never falls back to a list of its own.
- The on-device runtime serves the same methods from its in-process router, with the same results.
  It has one connection, so it sends no push.

## Authorization

| Method | Capability | Notes |
| --- | --- | --- |
| `pins.get` | `chat.read` | Read like `agent.list`. |
| `pins.set` | **`conversation.manage`** | Organising which agents are pinned is a chat-role preference edit, like archiving a conversation, so every paired chat device may pin. |

**Agent-scoped peers are refused.** A pairing bounded to some agents (`authorized_agent_ids` set)
would learn every agent the owner pinned, or could drop pins it cannot see, so both methods refuse it
with `forbidden: agent not in this device's scope`, and it receives no `pins_updated` push.

## The list

`{"revision": <integer>, "agent_ids": [<agent id>, ...]}`

- `agent_ids` is in pin order, without duplicates, at most 200 ids, each 1 to 256 characters after trimming.
- `revision` is 0 until the first accepted write and goes up by exactly one with every accepted write
  that changes the list. A write that changes nothing keeps the revision and sends no push.
- The host stores exactly this object (`pinned-agents.json`), so a host-state export can carry it
  unchanged.

## `pins.get`

- Params: none (`{}`).
- Result: the list.

## `pins.set`

- Params: `{"agent_ids": [...], "expected_revision": <integer >= 0>}`: the whole new list in order, and
  the revision the caller's list was based on.
- Compare-and-set: when `expected_revision` equals the stored revision the host replaces the list
  (duplicates dropped, first one kept) and answers the new list; otherwise it changes nothing and
  answers `revision_conflict`. The caller then reads the list again; it never retries blindly.
- Result: the list after the write.

## Errors

| `error` | When |
| --- | --- |
| `revision_conflict` | `pins.set` with an `expected_revision` that is no longer the stored one. |
| `invalid_request` | Missing or non-array `agent_ids`, a non-string, blank or overlong id, more than 200 ids, or a missing, negative or non-integer `expected_revision`. |
| `state_unreadable` | The host cannot decode its stored list. The file is kept and nothing is written; the owner must repair or remove it. |
| `forbidden: agent not in this device's scope` | An agent-scoped peer. |
| `forbidden` | The transport's capability gate (no `chat.read` / `conversation.manage`). |
| `Unknown method: pins.get` | A host that predates the contract. |

Clients map unknown codes to an "unknown" error; they must not fail on a new code.

## Push: `pins_updated`

After every accepted write that changed the list, the host sends every connection that is
authenticated, may read agents (`chat.read`) and is not agent-scoped:

`{"v": 1, "type": "pins_updated", "id": "pins-updated-<uuid>", "ts": "<ISO-8601 instant>", "revision": <integer>, "agent_ids": [...]}`

- The frame carries the whole list, so a client replaces its list without a read.
- A client adopts a push only when its `revision` is greater than the one it shows; the writer gets
  its own push too and ignores it.
- A push is an optimisation: a client that was offline missed it and reads the list on reconnect.
- Clients that predate the contract ignore the frame (an unknown host push type is dropped).

## Client behaviour (normative for this repository's clients)

- Edits are optimistic: the new list shows at once and is sent with the shown revision. Success
  adopts the host's answer; `revision_conflict` rolls back and reads again; any other refusal rolls back.
- While the host is unreachable or the list has not been read on this connection, edits are refused
  and nothing is queued; the last list read from the host keeps showing. A connected client whose
  last read or write failed reads again before its next edit.
