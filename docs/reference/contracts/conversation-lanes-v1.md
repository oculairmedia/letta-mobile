# Conversation lanes v1

Status: APPROVED by Emmanuel 2026-11-02. Owner of this document: Meridian. Wrapper and UI halves: PM-letta-mobile. Sender and tagging halves: PM-vibesync.

## Problem

Every inbound path to an agent picks a conversation by recency: the wrapper's agent-to-agent router (`IrohAgentMessageRouter.pickMostRecentInteractive`) and vibesync's PM wake sink both deliver to the newest interactive conversation. That is whichever conversation a human last typed in, so wakes, escalations and agent messages land in the middle of a live chat and queue behind the human's turn. Observed 2026-11-02: a wake landed in the PM's live thread 14 s behind an active turn. With per-task mayor conversations on the PM agent, the newest conversation becomes a disposable planning thread nobody reads.

## Concept

An address is **agent + lane**. A lane is a standing conversation with a fixed job, found by a label, never by recency. Three lanes:

| Lane | Job | Who writes into it from outside | Who reads it |
|---|---|---|---|
| `home` | the human and the agent talking | nobody; only the agent itself forwards things here | the human |
| `inbox` | agent-to-agent messages, dispatcher wakes, mayor escalations | any authorised sender | the agent, in its own turn |
| `work` | one fresh conversation per task, a record | nobody; never an inbound target | the human, on demand |

Rules:

1. A message with no lane goes to `inbox`. Machinery never reaches `home` by default.
2. The agent forwards to `home` only what needs the human, from its own turn, as its own message. That is the single controlled way into the human's thread.
3. Dispatch always creates a `work` conversation, titled `<formula> · <step> · <bead>`. Nothing is ever routed into one.
4. Every inbound message carries a correlation ID when it belongs to a task (bead, step, run) so a reply finds its way back.
5. `inbox` is created on first use. A fresh agent is addressable with no setup. `home` is the agent's existing primary interactive conversation; if none exists it is created on first human message. Explicit `conversation_id` targets are honoured and a missing one is dropped, as today (letta-mobile-5m1qy).
6. Delivery to a busy conversation queues until the current turn ends. It never interleaves with a human turn.

## Resolution

Lane membership is a conversation tag: `lane:home`, `lane:inbox`, `lane:work`, plus `bead:<id>` and `run:<id>` on work conversations. One conversation per agent may carry `lane:home` and one `lane:inbox`; the wrapper enforces that invariant on write. `conversation_class` stays as it is: `home` is interactive, `inbox` and `work` are autonomous.

Fallback if tags prove unwritable under the vibesync grant: resolve `inbox` by `conversation_class == autonomous` and title `inbox`, and record that choice in this document.

## Wire changes

Sender (`meridian agent-message send`, `agent_message_send`): `{to, body, lane?, conversation_id?, correlation?}`. `lane` defaults to `inbox`. `conversation_id` and `lane` are mutually exclusive; `conversation_id` wins if both are present.

Router (`IrohAgentMessageRouter.route`): resolve `lane` by tag; create `inbox` if absent; drop a missing explicit id; remove the recency pick entirely. The decision set stays Deliver / Queue / Drop / CreateAndDeliver(inbox only).

Envelope (`IrohAgentMessage`): add `lane: String?` and `correlation: Map<String,String>?` next to the existing `conversationId`.

## UI

The app shows the agent as its `home` lane, as now. Two affordances are new:

- An **inbox indicator** on the agent: a count of unread inbox items, opening a view of the inbox conversation. Items render as agent-to-agent messages with the sender shown, not as the user's own chat.
- **Work conversations** list under the agent, titled, grouped by bead, collapsed by default. Opening one shows the record. They never appear in the picker as targets.

A message the agent forwarded from inbox to home renders in home with a small "from <sender> via inbox" provenance line.

## Acceptance (named false passes)

- Router: a message with no lane to an agent with no inbox creates the inbox and delivers there; a second message delivers to the same inbox (no second creation). False pass: a test that pre-creates the inbox.
- Router: a message with no lane while the home conversation has an active human turn lands in inbox and home's turn is untouched. False pass: asserting only that home received nothing without asserting inbox did.
- Router: `lane:home` from a non-owner sender is rejected, not delivered. False pass: testing only `inbox`.
- Invariant: two conversations tagged `lane:inbox` on one agent is refused on write.
- Sender: `send {to, body}` and `send {to, body, lane:home}` produce different targets; the old shape still works.
- vibesync: a dispatcher wake lands in the PM's inbox during an active home turn, and the PM's next own turn handles it. False pass: running the test while home is idle.
- UI: a projection test feeds an inbox message through the real frames and asserts the inbox count increments and the home timeline does not change. False pass: asserting the count from the data model without rendering.

## Non-goals (v1)

No per-lane permissions beyond owner-only `home`. No lane other than the three. No migration of existing conversations; the first message creates the inbox and the existing primary becomes home on first human message.

## Verified premises and decisions (letta-mobile, 2026-11-02)

- Tags are writable under the vibesync paired-peer grant (`conversation.manage`): `conversation.update {conversation_id, tags}` and `conversation.create {agent_id, summary, tags}` both succeed and the tags read back identically via `conversation.get` (PM-vibesync live proof, 2026-11-02 21:16Z). The `conversation_class` + title `inbox` fallback is therefore NOT used; lanes resolve by tag only.
- Host check: letta-code 0.34.14 `updateLocalConversationRecord` stores `tags` verbatim when it is a string array (replace, not merge), and `conversation.update` is classified `conversation.manage` in `IrohPeerCapabilities`. A tag update replaces the whole tag list, so writers must send the full list.
- Not yet verified (tracked in letta-mobile-ooykc): that `tags` survive the `conversation.list` projection the router's candidate loader reads, and the one-`lane:inbox` / one-`lane:home` write invariant.
- Beads: contract `letta-mobile-0s3m6`, router `letta-mobile-ooykc`, sender `letta-mobile-ywa2y`, UI `letta-mobile-x60fz`.

## Clarifications (letta-mobile, 2026-11-02; resolves PR #2350 review)

### Correlated input receipt

A2A awaits `input_accepted` for a fresh request correlation ID and validates both that ID
and the target runtime. The logical `msgId` and encoded `AgentMessageClientId` stay stable.
An accepted or queued input returns `applicationDelivered: false` with reason
`application_input_accepted`. A correlated rejection returns `application_input_rejected`.
A mismatched receipt returns `application_input_scope_mismatch` and remains ambiguous.
An exception or timeout also remains ambiguous. Sending, acceptance, committed arrival,
and the agent handling the input are distinct milestones.

- Both `lane` and `conversation_id` present: `conversation_id` wins; `lane` is ignored.
- Explicit `conversation_id` targeting `lane:work` is dropped as `target_is_work_lane`;
  targeting `lane:home` remains forbidden to a non-owner sender.

## Inbox activity transport (mr49w implementation contract; not deployed)

The opt-in transport capability is `conversation_activity_v1`, advertised by clients in
`auth.capabilities` and by supporting wrappers in `auth_response.capabilities`. The separate
`conversation_activity` frame has `v`, `id`, `ts`, `agent_id`, `conversation_id`, `at`,
`milestone: "persisted"`, `source_message_id` (committed transcript identity), and
`client_message_id` (opaque `AgentMessageClientId` correlation). The executable fixture is
[conversation-activity.json](../../../android-compose/sharedLogic/src/jvmTest/resources/inbox-arrival/conversation-activity.json).

The wrapper emits only after a targeted read of the resolved `lane:inbox` transcript finds a
**user** message with that exact client identity, using direct transcript `otid` or the legacy
`_otid-map.json` sidecar. A body match, assistant message or input acknowledgement is insufficient.
The installed 0.34.14 writer stores the identity directly as `otid`; the older
[restart probe](../../../android-compose/appserver-cli/src/test/resources/appserver/restart-replay-evidence.json)
also demonstrated committed correlation, and observed duplicate execution after replay across
restart. That capture does not establish deduplication for every later runtime build.

Only authenticated clients with `chat.read`, unrestricted conversation scope and access to the
named agent receive activity after opting in. Grants and revocation are checked at broadcast time.
The existing connection registry sends the frame without changing observed conversations. Activity
never becomes `conversation_updated`: old clients invalidate lists for every reason on that frame.
Shared client transports carry activity separately; timeline mapping ignores it. Consumers must
ignore unknown milestones and deduplicate by agent/conversation/source identity. A push does not
mean unread, rendered or handled. Client unread state and UI activation belong to x60fz.

Observation runs only for accepted A2A inputs and reads a latest-64 window of that one inbox once
per second, using the existing transcript-tail reader (8 MiB tail, 128 raw lines, 64 local messages;
legacy identity sidecars capped at 4 MiB). No run/attachment/timestamp hydration is performed. It does not issue client `message.list`, switch a subscription, or poll the
roster. Pending observations are bounded by the receiver's 4096-entry admission bound. A source
that leaves the window before observation, unavailable backend files, or wrapper shutdown can
prevent the live push; clients must reconcile that inbox on focus/reconnect. Observation jobs and
push delivery are not durable and are not resumed from the journal after restart. No roster-wide
background reconciliation is introduced. The old direct app-server vibesync wake fixture bypasses
A2A and cannot prove this producer path; cutover must use the A2A sender.

### Reservation and restart semantics

The wrapper owns `a2a-receipts.json` beside its configured A2A address book (default
`~/.letta/iroh/`), separate from the App Server's read-only backend. A reservation is atomically
saved **before** application dispatch. Its explicit states are RESERVED, ACCEPTED, AMBIGUOUS and
PERSISTED. Definite correlated input rejection or failure before input dispatch releases the
reservation; a timeout, cancellation, mismatched receipt or dispatch exception retains it.
Duplicates never enter application delivery. Startup treats unfinished RESERVED entries as
ambiguous. A later persisted observation cannot be downgraded by a racing acceptance receipt.

Reservations and every settlement use the canonical authenticated sender plus `msgId`, so a
second sender may reuse a message ID without changing the first sender's reservation. Journal
version 2 encodes reservations as sender -> message ID -> state. Version 1 has senderless keys;
it is rejected without rewriting or dispatch, even when empty, and requires external
reconciliation before migration. Sender-attempt counters cannot identify the owner of an old entry.

The journal retains at most 4096 sender/message identities, 1024 sender counters, 512 characters per
identity and 4 MiB of encoded data. The existing own/sibling guard and 64-attempt per-sender cap
remain; attempts, including definite failures, consume that cap and its count survives restart.
Admission fails closed at any bound, on malformed state, or on write failure. Accepted/ambiguous
identities are never evicted or expired to make room. An OS lock serializes transactions between
wrapper processes; replacement requires atomic rename after file fsync and directory fsync.
Directory sync is skipped on Windows, whose JVM cannot open directory channels, and an
`UnsupportedOperationException` is best-effort on other platforms. Real I/O failures on platforms
with directory channels (including Linux/macOS) fail the write because durability is unconfirmed.
No non-atomic replacement fallback is used for this journal.
Deleting or rotating it loses replay protection and requires external reconciliation. This finite
journal deliberately stops new admission when full; it is not an indefinite delivery service or a
distributed exactly-once guarantee. Before journal replacement, the wrapper atomically writes
and syncs an `a2a-receipts.json.pending`
barrier under the journal's process lock. It removes the barrier only after a fully synced write
or rollback. A failed rollback, including failure before rename, leaves the durable barrier;
startup and other processes fail closed while it exists, requiring external reconciliation.
Barrier deletion is best-effort across crashes: a resurrected barrier only denies admission.
Removing a definite reservation can itself fail during a crash or storage fault, leaving safe
suppression until external reconciliation. The wrapper never uses absence in a bounded history window to justify automatic re-execution.
