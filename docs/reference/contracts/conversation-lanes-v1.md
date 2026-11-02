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
