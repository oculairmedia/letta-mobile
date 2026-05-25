# Message Sync Architecture Migration Plan

**Status:** Proposed — awaiting POC validation
**Date:** 2026-04-16
**Author:** Huly - letta-mobile agent + Emmanuel

## Context

The current message synchronization in letta-mobile chat screens has proven brittle across multiple refactors. Bugs fixed in isolation create new bugs elsewhere, indicating fundamental architectural issues rather than isolated defects.

This document describes:
1. Current architecture and its specific failure modes
2. Target architecture modeled after Matrix's battle-tested approach
3. A POC plan to validate the approach in a minimal CLI before touching mobile code
4. A migration plan with rollback strategy

For the current gateway-backed external-transport event lifecycle, see
[`external-transport-timeline-rules.md`](external-transport-timeline-rules.md).
That document is the active contract for USER/ASSISTANT/REASONING/TOOL ingestion
and reconcile behavior, centered on `WsChatSendCoordinator`,
`TimelineExternalTransportWriter`, and `TimelineSyncLoop`'s `TimelineGatewayEvent`
queue. It supersedes the prior `client-mode-timeline-rules.md`.

---

## Part 1: Current Architecture Problems

### State ownership is fragmented across 4+ sources

| State | Owner | Purpose |
|-------|-------|---------|
| `_uiState.value.messages` | `AdminChatViewModel` | What's shown on screen |
| `_serverMessages` | `MessageRepository` | Last API fetch result |
| `_pendingMessages` | `MessageRepository` | Optimistic local sends |
| `_streamingMessages` | `MessageRepository` | In-flight SSE responses |
| Polling timer | ChatScreen/ViewModel | Background refresh every 3s |

These sources update independently. They race. They get out of sync.

### Concrete bugs this architecture causes

- **Pending indicator stuck**: Pending cleared in repo but not in ViewModel's local copy
- **Message ordering scrambled**: Same-date messages sort unstably
- **Messages disappear on refresh**: Stale server fetch overwrites streaming state
- **Response only visible after next send**: Streaming state updated after `Complete` emit
- **Thinking appears after response**: API desc order returns reasoning/assistant in unstable order

### Root causes (not symptoms)

1. **No stable message identity until server persists** — we match pending→confirmed via `content.hashCode()`. This breaks when:
   - Server adds metadata prefixes (Matrix wrapper, timestamps)
   - User sends identical text twice
   - Whitespace/encoding differs between client and server

2. **Multiple write paths to message state** — Stream, polling, fetch, and optimistic sends all write to different stores, then a merge function tries to combine them at read time. The merge logic is fragile because it's working with incomplete information.

3. **No single timeline with append-only semantics** — Messages can be re-inserted at different positions on every merge, causing visible "jumping" and ordering inconsistencies.

4. **Timestamp-based sorting on non-unique timestamps** — Multiple messages in the same agent run share the same timestamp. `sortedBy { date }` is non-deterministic in this case.

5. **Implicit sync assumptions** — Code assumes the server will have persisted our message immediately after stream completion. It often hasn't.

---

## Part 2: Target Architecture (Matrix-inspired)

### Core principles

1. **Client-generated transaction IDs (otid)** — Every outgoing message gets a client-side UUID. The server echoes it back. We match on otid, not content.

2. **Single unified timeline** — One ordered list of events per conversation. Events are append-only once confirmed. No merging of separate stores.

3. **Two event states: `local` and `confirmed`** — Local events are shown optimistically. When a matching confirmed event arrives, it replaces the local event in-place (preserving position).

4. **Single sync loop** — One source of incoming events. No separate stream + poll + fetch with different code paths. All incoming events flow through the same handler.

5. **Server is the ordering authority** — We never re-order events based on client-side heuristics. The order events arrive from the server IS the order we display them.

6. **No content-based matching** — Ever. Always use IDs (otid for local, event_id for confirmed).

### Data model

```kotlin
sealed class TimelineEvent {
    abstract val position: Long      // monotonic, server-assigned or local
    abstract val otid: String        // client-generated, always present
    
    data class Local(
        override val position: Long,
        override val otid: String,
        val content: String,
        val sentAt: Instant,
        val deliveryState: DeliveryState,  // SENDING | SENT | FAILED
    ) : TimelineEvent()
    
    data class Confirmed(
        override val position: Long,
        override val otid: String,
        val serverId: String,
        val messageType: MessageType,    // USER, ASSISTANT, REASONING, ...
        val content: String,
        val date: Instant,
        val runId: String?,
        val stepId: String?,
    ) : TimelineEvent()
}

data class Timeline(
    val conversationId: String,
    val events: List<TimelineEvent>,  // ordered by position
    val backfillCursor: String?,       // for paginating older messages
    val liveCursor: String?,           // last known server position
)
```

### Event flow

**Sending a message:**
```
1. User types "hi", presses send
2. Generate otid = UUID
3. Append Local(otid=X, content="hi", state=SENDING) to timeline
4. UI shows message with timer icon
5. POST /messages with otid=X
6. On 200: update Local state to SENT (still shown optimistically)
7. Server begins streaming response
8. Each stream event = Confirmed with its own otid, appended to timeline
9. Eventually server sync returns our USER message with otid=X
10. Replace Local(otid=X) with Confirmed(otid=X, serverId=...) in place
11. Timer icon disappears, message stays in exact same position
```

**Receiving messages:**
```
1. Single sync loop polls or subscribes to server
2. Server returns events in order with positions
3. For each event:
   - If Local with matching otid exists: replace in place
   - Else: append to timeline if position > liveCursor, or insert at position if backfilling
4. Update liveCursor
```

**No race conditions because:**
- Only one write path (the sync handler)
- Identity via otid is deterministic
- Position is server-assigned, deterministic
- Local events have their own stable position (last + 0.5, or similar)

---

## Part 3: POC Plan — CLI Proof of Concept

Before touching the mobile app, validate the architecture with a minimal CLI. This proves:

1. The Letta API actually echoes otid back
2. Our state model handles all race conditions correctly
3. The single timeline approach holds up under adversarial conditions

### POC scope

**Location:** `/opt/stacks/letta-mobile/poc/chat-cli/` (new directory)
**Language:** Kotlin (matches mobile code, reuses types)
**Runtime:** JVM CLI (ktor client, no Android deps)

### POC features

```
chat> connect <agent-id> <conversation-id>
chat> send <message>
chat> history
chat> status
chat> stress <count>      # send N messages rapidly to test race conditions
chat> disconnect <seconds> # simulate network drop
chat> quit
```

### POC validation scenarios

Each scenario must pass before we touch mobile:

1. **Basic send/receive** — Send "hi", see response, timeline consistent
2. **Rapid consecutive sends** — Send 5 messages in 100ms, all appear, all get responses, ordering correct
3. **Send during active stream** — Start message A, send B before A's response complete
4. **Network drop during stream** — Kill connection mid-response, reconnect, verify recovery
5. **Send identical content** — Send "hi" twice, both appear separately (not merged by content)
6. **Long-running response** — 30s stream, verify UI would show response progressing
7. **Pagination** — Scroll up, fetch older messages, verify no duplicates, no reordering
8. **otid echo verification** — Confirm every outgoing otid appears in at least one server response

### POC success criteria

- All 8 scenarios pass deterministically
- Zero cases of messages disappearing, duplicating, or reordering
- Timeline state is logged and auditable at each step
- Code is < 1000 lines (forces simplicity)

---

## Part 4: Migration Plan

### Phase 0: POC (this document)
- Build CLI, run scenarios, iterate until all pass
- **Exit criteria:** All 8 POC scenarios pass

### Phase 1: Extract core sync into reusable module
- Move validated POC logic into `core/src/main/java/com/letta/mobile/sync/`
- Write unit tests for each scenario using fake API
- **Exit criteria:** 100% scenario coverage in tests, no Android dependencies in module

### Phase 2: Add new sync alongside old (feature flag)
- New `TimelineRepository` coexists with old `MessageRepository`
- Feature flag `USE_TIMELINE_SYNC` routes chat to either implementation
- **Exit criteria:** Can toggle at runtime, both paths work

### Phase 3: Migrate chat UI to new timeline
- Update `AdminChatViewModel` to consume `Timeline` directly
- Remove local pending state from ViewModel
- **Exit criteria:** With flag on, all current functionality works; with flag off, unchanged

### Phase 4: Dogfood
- Enable flag for internal testing
- Run for 1 week, collect bug reports
- **Exit criteria:** No regressions, all known bugs resolved

### Phase 5: Remove old code ✅ COMPLETE (2026-04-17)
- Deleted `MessageRepository._pendingMessages`, `_streamingMessages`, `_serverMessages`
- Deleted `contentHash()` matching logic (from `AppMessage`, `UiMessage`, `MessageRepository`)
- Deleted legacy streaming `MessageRepository.sendMessage()` (Timeline owns all sends)
- Deleted `checkForNewMessages`, `getCachedMessages`, `getDisplayMessages`, `clearConfirmedPendingMessages`
- Deleted `AdminChatViewModel.refreshFromCache`, `startMessagePolling`, `stopMessagePolling`, `reloadMessagesFromServer`
- Deleted unused `IMessageRepository` interface
- There was never a runtime `USE_TIMELINE_SYNC` flag — Phase 3 moved the chat VM to Timeline directly
- **Exit criteria:** Old code removed, tests pass ✅

### Rollback strategy

At any phase:
- Phase 0-1: No production impact, just POC/module work
- Phase 2-4: Feature flag controls routing, instant rollback by toggling
- Phase 5: Requires hot-fix if issues found, but flag history in VCS allows quick restore

---

## Part 5: Open Questions (for POC to answer)

1. **Does Letta's `/v1/agents/{id}/messages` endpoint accept and echo `otid`?**
   - Documentation is unclear
   - POC Scenario 8 explicitly tests this

2. **Does the streaming endpoint tag events with otid?**
   - If not, we need a workaround (e.g., use run_id for assistant message matching)

3. **How does Letta assign `position` or stable ordering within a run?**
   - The `otid` suffix increments (`...1880`, `...1881`) — is this guaranteed?
   - Or should we request a sequence number from the server?

4. **What's the actual behavior of `/conversations/{id}/messages?after=X`?**
   - Does it reliably return events in order?
   - Does it include events at position X, or strictly after?

5. **How does the server handle duplicate otid?**
   - Idempotency? Error? Silent success?
   - Affects retry strategy

---

## References

- Matrix client-server spec, section 13.2.1.2 (transaction IDs): https://spec.matrix.org/v1.8/client-server-api/#client-behaviour
- Matrix SDK JS: `MatrixClient#_makeEventFromEventDict` for echo handling
- Letta Python SDK: grep for `otid` usage in message send paths
- Current mobile code: `MessageRepository.kt`, `AdminChatViewModel.kt`

---

## Decision log

- **2026-04-16**: Decided to build POC CLI before touching mobile code due to repeated regression cycles. Target: validate otid-based architecture in isolation.
