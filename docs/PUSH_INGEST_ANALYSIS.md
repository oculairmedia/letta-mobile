# Push Ingest Analysis (letta-mobile-o5zmj)

## Summary

Server-pushed messages from **out-of-band sources** (other agents, cron jobs, shim relays) do NOT update open conversation timelines. Users only see these messages after navigating away and back, forcing a timeline rehydration.

## Root Cause

Mobile only ingests timeline frames for **run subscriptions it initiated**. When an external source pushes a message to a conversation:

1. Shim emits frames with `conversation_id=X` but NO `run_id` that mobile subscribed to
2. Frames reach the WebSocket transport but are **dropped** because mobile isn't subscribed to that run
3. No conversation-scoped routing exists to trigger a timeline refresh
4. Timeline state doesn't update → UI doesn't update
5. Only on navigation (leave-and-return) does `hydrate()` fetch the new messages

## Current Flow (Working - User-Initiated)

```
User sends → AdminChatViewModel.sendMessage()
  ↓
WebSocket subscribe frame (run_id)
  ↓
WsChatBridge.events → WsTimelineEvent.MessageDelta
  ↓
TimelineRepository.ingestStreamEvent(conversationId)
  ↓
TimelineSyncLoop → ChatTimelineObserver
  ↓
UI updates ✓
```

## Missing Flow (Broken - Out-of-Band)

```
External agent/cron sends to conversation X
  ↓
Shim emits frames (conversation_id=X, no mobile run subscription)
  ↓
WsChatBridge receives frames BUT...
  ↓
Mobile not subscribed to that run_id → DROPPED
  ↓
Timeline state unchanged
  ↓
UI unchanged ✗
  ↓
Only on leave-and-return: hydrate() fetches messages
```

## Architecture Gap

**Missing piece:** Conversation-scoped push routing

When a server frame arrives for an **OPEN** conversation (tracked by `ChatTimelineObserver.conversationId` or `CurrentConversationTracker`), mobile needs to:

1. **Detect** the push: frame.conversationId matches open conversation
2. **Trigger reconcile**: Fetch recent messages for that conversation
3. **Or subscribe**: Conversation-level WebSocket channel (requires shim work)

## Implementation Options

### Option A: Mobile-Side Reconcile Trigger (Recommended for MVP)

**What:** Subscribe to `IChannelTransport.events` (raw ServerFrames) in the chat coordinator/ViewModel layer. When a frame arrives for the currently-open conversation, trigger `TimelineRepository.getOrCreate(conversationId).reconcileRecentMessages()`.

**Pros:**
- Works with existing shim (no backend changes)
- Fixes the "invisible push" bug
- ~1-2s latency (acceptable for out-of-band updates)

**Cons:**
- Not true streaming (requires REST fetch after push)
- Extra network request per push
- Won't handle multi-user collaborative editing well

**Pseudocode:**
```kotlin
// In AdminChatViewModel or ChatCoordinator
viewModelScope.launch {
    channelTransport.events.collect { serverFrame ->
        val openConvId = currentConversationTracker.current
        val frameConvId = extractConversationId(serverFrame)
        
        if (openConvId != null && frameConvId == openConvId) {
            // Out-of-band push detected for open conversation
            timelineRepository.getOrCreate(openConvId)
                .reconcileRecentMessages("external_push_detected")
        }
    }
}

private fun extractConversationId(frame: ServerFrame): String? =
    when (frame) {
        is ServerFrame.AssistantMessage -> frame.conversationId
        is ServerFrame.ToolCallMessage -> frame.conversationId
        is ServerFrame.TurnStarted -> frame.conversationId
        // ... handle other frame types
        else -> null
    }
```

**Edge cases to handle:**
- Dedupe: Don't reconcile if we're already in an active run for that conversation
- Rate limit: Debounce rapid pushes (max 1 reconcile per 2s)
- Error handling: Failed reconcile shouldn't crash the chat

### Option B: Shim-Side Conversation Push Channel (Ideal)

**What:** Shim emits a new `conversation_updated` frame (or routes ALL frames by conversation_id, not just run_id). Mobile subscribes to open conversations.

**Pros:**
- True streaming (frames flow directly to timeline)
- No extra REST fetches
- Enables multi-user collaborative chat
- Cleaner architecture

**Cons:**
- Requires shim protocol extension
- Needs mobile + shim coordination
- Larger scope (multi-week effort)

**Shim changes needed:**
```
1. Add conversation_updated frame type (or route all frames by conv_id)
2. Mobile sends: subscribe_conversation { conversation_id }
3. Shim routes: ANY message landing in that conversation → emit to subscribers
4. Mobile receives: frame → ingestStreamEvent() (same as run frames today)
```

## What Can Be Done in Mobile Now

**Implement Option A:**

1. Add `OutOfBandPushDetector` service:
   - Subscribes to `SessionScopedChannelTransport.events`
   - Filters for frames matching `CurrentConversationTracker.current`
   - Calls `TimelineRepository.reconcileRecentMessages()`
   - Deduplicates/rate-limits reconcile calls

2. Wire in `AdminChatViewModel` or `ProjectChatCoordinator`

3. Test coverage:
   - Out-of-band message arrives while conversation open → appears within 2s
   - Rapid push burst → single reconcile (dedupe)
   - Push while user's own run active → skip reconcile (already streaming)

## What Needs Shim Work

For true streaming (Option B):
- Protocol extension: `conversation_updated` or conversation-scoped routing
- WebSocket subscription management for conversations
- Multi-user message routing
- Conflict resolution (who sees what, when)

This is the **same infrastructure** needed for:
- Multi-user collaborative chat
- Shared conversations
- Agent-to-agent messaging visible to users

## Relationship to letta-mobile-ixtzn (P0 Crash)

**Same root cause:** Out-of-band pushed frames hitting the projection path are mishandled.

- **ixtzn:** Projection called on empty timeline → crash (FIXED)
- **o5zmj:** Frames never reach projection → missed update (PARTIAL FIX)

The crash fix (ixtzn) ensures we don't throw when frames arrive on empty state. The push ingest (o5zmj) ensures those frames actually reach the timeline.

## Acceptance Criteria

✓ **Part 1 (ixtzn - COMPLETE):** Empty timeline projection doesn't crash
□ **Part 2 (o5zmj - MOBILE PARTIAL):** Implement Option A reconcile trigger
□ **Part 3 (o5zmj - SHIM):** Implement Option B conversation channel (future)

### Part 2 Tests

1. Agent A sends to conversation while Agent B has it open → appears in 2s
2. Cron fires a message to open conversation → appears in 2s
3. Rapid burst of 5 external messages → single reconcile fetch
4. External message during user's own active run → no duplicate fetch
5. External message to closed conversation → no reconcile (waits for hydrate)

## Timeline

- **Now:** Part 1 (crash fix) ✓
- **MVP (Option A):** 1-2 days (mobile-only, no shim dependency)
- **Ideal (Option B):** Multi-week (requires shim protocol + mobile plumbing)

## Related Beads

- `letta-mobile-ixtzn` — P0 crash on empty projection (FIXED)
- `letta-mobile-o5zmj` — P1 push ingest (THIS DOC)
- `lcp-cq7x` — Shim proactive sendMessage relay (backend side)

## References

- `ChatTimelineObserver.kt:383` — Empty guard added (ixtzn fix)
- `TimelineSyncLoop.kt:242` — `reconcileRecentMessages()`
- `WsChatBridge.kt:59` — Current event stream (run-scoped)
- `IChannelTransport.kt` — Raw ServerFrame stream (conversation_id available)
- `MOBILE_WS_PROTOCOL.md` — Shim protocol spec
