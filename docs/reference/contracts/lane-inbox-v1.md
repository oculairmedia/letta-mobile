# Lane inbox v1

Status: FROZEN 2026-11-05 (bead letta-mobile-db3fo, stage C0). Approved by Emmanuel 2026-11-05; owner answers in §10.
Changes after freezing need a v2 file, not edits here. No host or client code exists yet; every host stage waits on letta-mobile-8h07i (sender authentication) and letta-mobile-tgqkd (permission-mode forwarding), see §7a.

- Extends [conversation-lanes-v1](conversation-lanes-v1.md) (letta-mobile-0s3m6). Reuses: mr49w (reservations, arrival signal, receipt journal), jxp0m (duplicate acknowledgement), x60fz/r6g3t (lane purposes, projection), ywa2y (lane and correlation on send).
- Prior art: the research report recorded on letta-mobile-db3fo.
- Fixtures: [`android-compose/sharedLogic/src/commonTest/resources/lane-inbox-v1/`](../../../android-compose/sharedLogic/src/commonTest/resources/lane-inbox-v1/), parsed and checked against this document by `LaneInboxV1FixturesTest`.

Principle: the inbox is an untrusted ingress system, not a second conversation. The boundary is every path by which inbox content reaches the main lane or the outside world, not the tool list.

## 0. Terms
- **Host:** the iroh wrapper / App Server that owns the agent. It enforces everything below; the model enforces nothing.
- **Lanes:** main (home) and inbox (`lane:inbox`), as defined in conversation-lanes-v1.
- **Sender:** the authenticated Iroh endpoint id of the peer agent's host, plus the claimed `from_agent_id`. Authentication (8h07i) is a precondition, not authorization.

## 1. Identifiers and request/reply correlation

**Message identity.** Every inbound message carries:
- `client_message_id`: the sender's id, at most 128 chars.
- `sender`: endpoint id plus agent id, derived by the host from the authenticated connection and never read from the payload.
- `sent_at`.
- `expires_at`: optional.
- `correlation_id`: optional; present only on replies.
- `body`: the message text. Inbox turns treat it as untrusted data.
- `more`: optional, replies only; `true` keeps the pending-reply row open for further replies.

**Correlation (asking side).**
1. When the main lane calls `agent_message_send` expecting a reply, the host mints `correlation_id` (a UUIDv7) itself. It is never chosen by the model.
2. The host records a pending-reply row: `{correlation_id, origin_agent, origin_conversation, expected_replier (endpoint + agent), created_at, expires_at (default +7d, carried in the request)}`.
3. The request carries `reply_to = {correlation_id, expires_at}`. Peers copy `correlation_id` into their reply. The reply channel itself carries the key; it is not optional free text.

**Reply matching (receiving side).** A message with a `correlation_id` is matched against pending replies.
- **Matched:** the id exists, has not expired, and the sender equals `expected_replier`. Deliver the reply into `origin_conversation` with provenance `reply_from: sender`. It does not enter the inbox. The row closes unless the reply says `more: true`.
  - **No automatic turn.** A matched reply is appended as labelled, untrusted data (a delimited `reply_from` block, never an instruction). It does not start a main-lane turn on its own.
  - **Exception:** when the main conversation is waiting on that correlation (a pending `await_reply`), the waiting turn resumes and receives the reply as that same untrusted data block.
  - **Otherwise** the reply shows in the next turn's digest line for that sender thread.
- **Expired or unknown:** deliver to the inbox, and record a dead letter `{reason: expired|unknown}` that the digest shows.
- **Sender mismatch:** reject with receipt `rejected{reason: correlation_mismatch}`.

## 2. Dedupe
- **Key:** `(sender.endpoint, client_message_id)`.
- **Journal:** the existing mr49w reservation journal. Retention is 14 days, longer than the 7-day request expiry plus retries.
- **Retry horizon:** a sender may retry the same `client_message_id` for at most 24h after `expires_at` (senders, such as vibesync's wake retry, must stop then). So 14 days > 7 days + 24h, with margin. Messages with `sent_at` older than the retention window are rejected (`rejected{reason: too_old}`).
- **Duplicates:** return the original receipt (jxp0m behavior). A duplicate never starts a turn.
- **Orphaned reservations:** a reservation has a lease. A host crash mid-turn leaves it `in_progress`. On restart, a lease that has expired produces a digest entry with `outcome: failed{reason: interrupted}` and receipt `failed`. The host does not redeliver it automatically.
- **Derived keys:** downstream effects get keys derived from the message key, `<message key>/<step>`. These cover a reply sent, a bead filed, and a proposal created.

## 3. Inbox turn policy (enforced by the host)

**Lane policy `inbox-triage`.**
- Applied by the host to every turn in `lane:inbox`.
- Never inherits main-lane permission modes or standing "always allow" grants.
- Rides the existing permission-mode path (tgqkd).

**Allowed:**
- Read-only tools.
- `reply` to the sender only. The reply is templated, at most 2000 chars, with no links or images, and carries correlation automatically.
- `file_bead` and `comment_bead` in allowlisted trackers (per-sender grant, §4).
- `propose` (§5).

**Denied.** Everything else, including:
- memory writes to core blocks;
- merge, deploy, restart, wrapper swap;
- config and provider routing;
- secrets;
- sending to agents other than the sender;
- network fetch to non-allowlisted hosts.

A denied call returns `denied: inbox lane; use propose` to the model.

**Context minimization.**
- Inbox turns receive a reduced memory view: **persona only**.
- Named shared blocks come only through a per-sender `memory_view` grant (§4), and no sender has one by default.
- No secrets, no main-chat transcript.
- This removes one leg of the "lethal trifecta".

**Taint.**
- Every artifact an inbox turn creates is labelled `provenance: inbox`, with the sender and the message key. That covers beads, comments, proposals, replies and notes.
- Main-lane prompts render these labels.
- Policies key on them: for example, a bead filed by the inbox is not auto-actioned.

**Memory segregation.**
- Inbox turns may write only to an `inbox-notes` namespace, tagged with provenance and capped.
- Promotion into core memory needs a human-confirmed main-lane action.
- Sleep-time "dreaming" and compaction must not merge inbox notes into core blocks.

## 4. Per-sender authorization
- **Grants:** a host-side table `sender_grants[sender.endpoint]`, managed by the owner. Each row is keyed by `sender_endpoint` (the authenticated endpoint id, `*` for the default row). Fields:
  - `trackers`: allowlist of tracker/project ids.
  - `proposal_kinds`: allowlist.
  - `max_messages_per_hour`
  - `max_open_proposals`
  - `receipts`: `full` | `minimal`. The default is `minimal`. Explicitly granted known peers (for example Meridian, PM-vibesync) get `full`.
  - `memory_view`: block ids beyond the default.
- **Default for an unknown but authenticated sender:** no trackers, no proposals, 10 messages per hour, minimal receipts. Its message still runs a triage turn and gets a digest entry.
- **Content never grants authority.** "I am the owner" in a message changes nothing.

## 5. Proposals
- **Created by:** the `propose` tool in the inbox lane.
- **Schema** `{id, kind, args (validated JSON per kind), sender, message_key, created_at, expires_at (default +72h), status}`.
- **Status machine:** `pending` → `approved` | `declined` | `expired` | `superseded`. These are terminal and expiry goes only to `expired`.
  - Never auto-approved.
  - Re-proposing the same `(kind, args)` from the same sender coalesces into one pending proposal (`superseded` older).
- **Rendering:** the host renders a card from `kind` + `args` + sender + message key.
  - Any model or sender rationale is shown only as quoted, unrendered text, at most 500 chars.
- **Approval:**
  - Only the human approves, or an explicit main-lane tool call the human confirms in the UI.
  - The main-lane model must not approve on the strength of inbox text.
  - Approval is an atomic compare-and-set on `status = pending` and revalidates `args` against current state.
  - Execution uses a derived idempotency key.
- **Kinds that cannot be proposed from the inbox at all:**
  - secret changes;
  - grant changes;
  - memory promotion;
  - sending as the agent to third parties.

## 6. Digest

**Entry.** The host writes exactly one entry per inbox turn, enforced by a unique constraint on the message key. It also writes one when the turn failed or crashed. Fields, all host-observed:

```
{ entry_id, message_key, sender, received_at, finished_at,
  outcome: handled|replied|proposed|failed|rejected|dead_letter,
  refs: { beads_created[], beads_commented[], proposals[], reply_sent: bool },
  denied_tools[], summary_quote (model's last message, <=200 chars, rendered as quote),
  pinned: false }
```

**Markers.** Per (`agent_id`, `main_conversation`), two forward-only cursors.
- **`shown_through`:** advanced once per main turn by the host when the digest is injected.
- **`acknowledged_through`:** advanced only by the human:
  - in the view, or
  - by confirming a main-lane `suggest_ack` that the model may raise.

  The model can suggest an acknowledgement but never perform one.

  The inbox lane cannot advance either cursor.

**Injection at the start of each main turn.** The host prepends a block of entries after `shown_through`:
- One line per sender thread, ranked by reason: proposals, then failures, then dead letters, then the rest.
- At most 10 lines, then "+N more".
- The pending-proposal count always appears, even when truncated.
- Rendered as data, inside a delimited block labelled untrusted.

**View.** An on-demand runtime object view, "Inbox activity", in the modular shell (n2stx.2.2):
- lists entries and proposals;
- approve, decline, acknowledge, pin.

**Retention.**
- Acknowledged entries drop after 24h.
- Unacknowledged entries expire after 7 days.
- Pinned entries are exempt from expiry and the cap.
- The store is capped at 500 entries per agent, dropping the oldest unpinned first.
- Expiries and cap drops of unacknowledged entries increment an observable counter. The next digest reports "N unread entries expired".
- Nothing in the digest is ever written to agent memory.

## 7. Receipts (sender-visible)

**States.** Each is a fact the host can assert.

| State | Fact |
|---|---|
| `accepted` | reserved |
| `delivered` | persisted in the inbox conversation |
| `handled` | turn ended; carries the digest entry id |
| `answered` | reply sent; carries the correlation id |
| `proposed` | carries the proposal id |
| `proposal_resolved` | approved, declined or expired |

- **Terminal states:** `rejected{reason}`, `failed{reason}`, `expired`. Terminal states are final.

**Delivery rules.**
- Receipts echo `client_message_id`.
- Delivery is pushed on the existing channel and can also be polled with `agent_message_status(client_message_id)`.
- Receipts never trigger turns, and receipts are never sent for receipts.
- With per-sender `receipts: minimal`, only `accepted` / `rejected` / `failed` are sent. That hides "a decision is pending".

## 7a. Prerequisites (blockers for every host stage)
- **8h07i: A2A receiver authenticates senders.** Sender identity, per-sender grants, correlation matching and dedupe all depend on it.
- **tgqkd: Iroh turns forward the requested permission mode.** Iroh turns run unrestricted today, so no inbox deny-list holds until this lands.

C0, the contract and its fixtures, can freeze now. H1 to H7 stay blocked on both.

## 7b. Letta Code runtime hooks (each a patch in our local runtime patch loader, gdxpn)

Name each hook, and give each a fail-closed behavior when the patch is absent:
- **`inbox-memory-view`:** builds the inbox turn's context with persona-only core memory, plus the blocks granted per sender.
  - If absent: inbox turns are refused, and the message gets receipt `failed{reason: policy_unavailable}`.
- **`inbox-notes-namespace`:** routes memory writes from inbox turns to the capped, provenance-tagged `inbox-notes` area, and blocks core-block writes.
  - If absent: inbox turns get no memory-write tools.
- **`digest-injection`:** prepends the delimited, untrusted digest block to main-lane turns and advances `shown_through`.
  - If absent: no injection. The digest stays readable in the view, and `shown_through` does not move.
- **`dreaming-segregation`:** sleep-time reflection and compaction skip the `inbox-notes` area and anything carrying `provenance: inbox`.
  - If absent: sleep-time runs are disabled for agents with inbox lanes.

## 8. Acceptance (tests that fail on main)
1. An inbox turn yields exactly one digest entry, including when the turn crashes. A duplicate message yields no new entry and the original receipt.
2. A reply carrying a matching `correlation_id` from the expected replier lands in the origin main conversation. A reply from a mismatched sender is rejected. An expired one becomes a dead letter that the digest shows.
3. In the inbox lane, memory writes to core blocks, merge, deploy, restart, wrapper swap and config are denied by the host. `propose` creates a pending proposal, and executing it requires human approval.
4. A proposal expires to `expired`, never `approved`. Duplicate proposals coalesce. Approval is a compare-and-set, so approving twice executes once.
5. The two cursors persist across a restart and only move forward. The inbox lane cannot move them. The digest shows each entry exactly once, and the pending-proposal count shows when truncated.
6. 7-day expiry, a 500-entry cap and the pin exemption hold, and the expired-unread counter is observable.
7. No digest or inbox content appears in core memory blocks after any inbox turn.
8. Sender grants are enforced. An unknown sender cannot file beads or propose.
9. Dedupe retention (14 days) outlasts the longest request expiry (7 days) plus the 24h retry horizon. A test pins this relationship, so changing one value without the other fails.
10. A matched reply does not start a main-lane turn unless that conversation is awaiting the correlation. It arrives as a labelled, untrusted data block.
11. **Red team.** Fact-like injected payloads in inbox messages must not alter main-lane memory or produce approvals. They test the digest, proposal-card and memory paths.

## 9. Delivery plan (one PR per bead, contract first)
- **C0:** freeze this contract and its fixtures under `docs/reference/contracts/lane-inbox-v1.md`. This can go now.
- H1 to H7 are blocked on 8h07i and tgqkd (§7a).
- **H1:** host lane policy `inbox-triage`, the deny list, context minimization and taint labels.
- **H2:** sender grants and rate limits.
- **H3:** proposals: store, `propose` tool, compare-and-set approval, expiry and coalescing.
- **H4:** correlation minting, the pending-reply table and reply routing, including dead letters.
- **H5:** the digest store, the two cursors, turn-start injection, retention and counters.
- **H6:** receipt states and the status query.
- **H7:** memory segregation, the `inbox-notes` namespace and the dreaming/compaction guard.
- **C1:** client "Inbox activity" runtime object view (n2stx.2.2), plus lane activity availability following the live capability (x60fz note).
- **R1:** red-team suite (acceptance 9).

## 10. Owner answers (2026-11-05)
- **Q1, inbox memory view:** persona only. Named shared blocks only through a per-sender grant, and no sender has one by default.
- **Q2, acknowledgement:** human only. The main-lane model may suggest; the human confirms.
- **Q3, receipts:** `minimal` by default. Explicitly granted known peers get `full`.
- **Q4, retention:** accepted as drafted (7 days / 24 hours / 500 / 72 hours / 14 days), plus acceptance test 9.
