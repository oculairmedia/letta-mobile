# Handoff: Multi-Tool-Call Message Duplication Bug

**Bead**: `letta-mobile-3fnm` (P1, OPEN)
**Date**: 2026-05-04
**Device**: Pixel 9 Pro at 192.168.50.235:5555

## The Bug

When an agent makes multiple tool calls (2+), the final assistant response text appears **duplicated** in the chat UI — one extra copy per tool call made. Duplication starts when the final response begins rendering and repeats at each markdown rendering breakpoint (code block close, section transition). Single-tool-call responses render correctly.

## What It Is NOT

**NOT a data-layer duplication.** Logcat proves the timeline has exactly ONE assistant event during streaming. The `liveCount` (number of UiMessages projected from timeline events) stays flat at 102 while ASSISTANT chunks arrive. No duplicate events are created at any point in the timeline. The bug is purely in the projection-to-UI layer.

### Log Evidence (run at 15:50:35–15:51:56)

```
WS chunks only (SSE returned 400 — no dual-stream):
  TOOL_CALL ×14 chunks across ~6 agent steps → liveCount 93→101
  ASSISTANT  textLen=349 → liveCount 101→102
  ASSISTANT  textLen=303 → liveCount 102 (STABLE)
  ASSISTANT  textLen=331 → liveCount 102 (STABLE)
  ASSISTANT  textLen=252 → liveCount 102 (STABLE)
  ...more ASSISTANT chunks, liveCount never changes
  Stream completed → liveCount=102, prevCount=78
```

liveCount jumped from 78→102 (24 new events) for a run with ~6 reasoning steps and ~10 tool calls. The assistant Local is ONE event, updated in place. Yet the user sees multiple copies of the assistant text.

## The Full Pipeline (with line numbers)

The next agent needs to trace this chain to find where multiplication occurs:

### Stage 1: Timeline → UiMessage List

**File**: `AdminChatViewModel.kt:2725-2826`

```kotlin
// Line 2727: Project all timeline events to UiMessages
val live = timeline.events.mapNotNull { it.toUiMessageOrNull() }

// Lines 2737-2740: Dedup by ID (HashSet)
val seenIds = HashSet<String>(live.size + prefix.size)
val combined = ArrayList<UiMessage>(live.size + prefix.size)
for (m in prefix) if (seenIds.add(m.id)) combined.add(m)
for (m in live) if (seenIds.add(m.id)) combined.add(m)
val ui = combined.toImmutableList()

// Line 2804: Emit to UI state
_uiState.value = ... prev.copy(messages = ui, ...)
```

**toUiMessageOrNull()** (line 2825-2826) delegates to `timelineEventToUiMessage()`.

### Stage 2: TimelineEvent → UiMessage Projection

**File**: `TimelineEventToUiMessage.kt:100-314`

- **Local events** (lines 100-189): Maps `TOOL_CALL` → `assistant` role with `uiToolCalls`. `TOOL_RETURN` Locals return null (folded into TOOL_CALL). `REASONING` → `assistant` with `isReasoning=true`.
- **Confirmed events** (lines 190-312): Same mapping. **Critical ID scoping** at lines 292-296: Client Mode harness events get `"${ev.serverId}:${ev.messageType.name}"` as their UiMessage ID (because Letta reuses the same server ID for reasoning + assistant in one step).

### Stage 3: Render Model Building

**File**: `ChatRenderModelBuilder.kt:39-61`

```kotlin
fun buildChatRenderModel(messages: List<UiMessage>, mode: ChatDisplayMode): ChatRenderModel {
    val visibleMessages = attachLatencyMetadata(
        filterMessagesForMode(
            messages = dedupeReasoningAssistantEchoes(messages),   // Line 45
            mode = mode,
        )
    )
    val groupedMessages = groupMessages(...)
    val reversed = dedupeGroupedMessagesForLazyKeys(groupedMessages).asReversed()  // Line 54
    return ChatRenderModel(
        visibleMessages = visibleMessages,
        groupedMessages = groupedMessages,
        renderItems = groupMessagesForRender(reversed),           // Line 59
    )
}
```

Two dedup layers:
1. **`dedupeReasoningAssistantEchoes()`** (lines 92-107): Skips assistant message if content == immediately preceding reasoning content. **Only matches EXACT adjacent pairs** — if multiple reasoning steps interleave with tool calls, intermediate reasoning might survive.
2. **`dedupeGroupedMessagesForLazyKeys()`** (lines 122-130): HashSet filter on message ID to prevent LazyColumn crash.

### Stage 4: Render Items → LazyColumn

**File**: `ChatMessageList.kt:278-428`

```kotlin
LazyColumn(reverseLayout = true, ...) {
    item(key = "typing") { ... }
    renderItems.forEachIndexed { index, renderItem ->
        item(key = renderItem.key) {  // Line 317
            when (renderItem) {
                is ChatRenderItem.Single -> RenderChatMessage(...)
                is ChatRenderItem.RunBlock -> RunBlock(...)
            }
        }
    }
}
```

**Key generation** (`MessageGrouping.kt`):
- Single: `stableRunKey ?: "msg-${message.id}"` (line 56)
- RunBlock: `"run-$runId"` (line 77)

### Stage 5: ASSISTANT Message Rendering + Streaming

**File**: `ChatMessageComponents.kt` + `MessageContentFactory.kt`

- `isLastAssistant = isStreaming && message.role == "assistant" && message.id == state.messages.lastOrNull()?.id`
- Streaming: `rememberSmoothedStreamingText()` (RememberStreamSmoother.kt:34-60) — frame loop revealing chars at 60fps
- `streamingDisplayText()` (MessageContentFactory.kt:103-148) — **clamps to markdown-stable prefix** before rendering
- `animateContentSize(tween(60ms))` applied during streaming (ChatMessageComponents.kt:319-325)

## Primary Suspects (ranked by likelihood)

### Suspect 1: `groupMessagesForRender()` creating multiple render items for the same assistant message

**Why**: The `runId` grouping in `MessageGrouping.kt` groups messages by `runId`. If the assistant message has a `runId` that also belongs to preceding reasoning/tool-call messages, it gets grouped into a RunBlock. But if it also appears as a standalone Single (due to missing/late `runId`), you'd see it twice.

**Check**: `MessageGrouping.kt:113-171` — the `groupMessagesForRender()` function. Verify that each message appears in exactly one render item.

### Suspect 2: `dedupeReasoningAssistantEchoes()` not catching all reasoning→assistant pairs

**Why**: The dedup at `ChatRenderModelBuilder.kt:92-107` only suppresses assistant messages whose content EXACTLY matches the IMMEDIATELY PRECEDING reasoning message. With multiple reasoning steps, intermediate reasoning messages survive. If the final assistant content happens to match an earlier (non-adjacent) reasoning, it won't be deduped.

**Check**: Log the messages list before and after `dedupeReasoningAssistantEchoes()` — count how many survive with `role="assistant"` and `isReasoning=false`.

### Suspect 3: Multiple reasoning Locals for the same content

**Why**: Each agent step produces a REASONING Local followed by TOOL_CALL Locals. With 6 steps, there are 6 REASONING Locals. If the agent's reasoning content is similar across steps (common for multi-step tool use), and the final ASSISTANT content is similar to ANY of them, the dedup won't catch it.

**Check**: Look at what the 102 events contain — how many are REASONING vs ASSISTANT vs TOOL_CALL? Log `timeline.events.groupBy { it.messageType }` to see the breakdown.

### Suspect 4: Streaming markdown clamp causing visual duplication

**Why**: `streamingDisplayText()` at `MessageContentFactory.kt:103-148` clamps to markdown-stable boundaries. At code block closes, the clamp boundary jumps forward, releasing a held tail. If `animateContentSize()` then re-measures with more content AND the markdown parser re-renders from scratch, the user could see a brief "doubling" flash at the boundary.

**Check**: This would explain "duplicates at markdown breakpoints" but probably not "one extra copy per tool call". Less likely to be the primary cause but could be a contributing visual artifact.

## Secondary Issue: Fuzzy Collapse Only Absorbs ONE Local

**File**: `Timeline.kt:354-410`

`collapseClientModeFuzzyMatch()` uses `maxByOrNull` to pick ONE matching Local for collapse. When WS creates N tool-call Locals (one per tool call per step) and SSE later delivers ONE batched Confirmed, N-1 Locals are orphaned.

**This is a real bug** but was NOT the primary duplication cause in the observed run (SSE was offline). Should still be fixed — scoped to TOOL_CALL messageType only, preserving USER single-match behavior.

## Investigation Strategy for Next Agent

### Step 1: Count the events by type
Add temporary logging at `AdminChatViewModel.kt:2727`:
```kotlin
val typeBreakdown = timeline.events.groupBy { 
    (it as? TimelineEvent.Local)?.messageType 
        ?: (it as? TimelineEvent.Confirmed)?.messageType 
}.mapValues { it.value.size }
Log.w("AdminChatVM-DEBUG", "event breakdown: $typeBreakdown")
```

### Step 2: Count UiMessages before and after each dedup stage
At `ChatScreen.kt:295` (where `buildChatRenderModel` is called), log:
```kotlin
Log.w("ChatScreen-DEBUG", "UiMessage count: ${messages.size}, " +
    "assistant non-reasoning: ${messages.count { it.role == "assistant" && !it.isReasoning }}, " +
    "reasoning: ${messages.count { it.isReasoning }}")
```

### Step 3: Count render items
After `buildChatRenderModel`, log `renderModel.renderItems.size` and for each item log its key and message count.

### Step 4: If UiMessage count > expected (1 assistant), find which events create extras
Add logging inside `timelineEventToUiMessage()` — log every event that produces a non-null UiMessage with `role="assistant"` and `!isReasoning`, including the event's `otid`, `serverId`, `messageType`, and content preview.

### Step 5: Check if multiple events share the same content
If Step 4 shows multiple assistant UiMessages, check whether they have the same or different content. Same content → reasoning echo dedupe missed it. Different content → something else.

## Key Files (with exact paths)

| File | Lines | Role |
|---|---|---|
| `android-compose/app/.../chat/AdminChatViewModel.kt` | 2725-2826 | Timeline observer, UiMessage assembly, state emission |
| `android-compose/app/.../chat/TimelineEventToUiMessage.kt` | 100-314 | Event → UiMessage projection (Local branch: 100-189, Confirmed branch: 190-312) |
| `android-compose/app/.../chat/ChatRenderModelBuilder.kt` | 39-130 | Dedup: reasoning echoes (92-107), LazyColumn keys (122-130) |
| `android-compose/app/.../chat/MessageGrouping.kt` | 113-171 | Run grouping, render item key generation |
| `android-compose/app/.../chat/ChatMessageList.kt` | 278-428 | LazyColumn iteration with `key = renderItem.key` |
| `android-compose/app/.../chat/ChatMessageComponents.kt` | 116-463 | ASSISTANT bubble rendering, streaming animation |
| `android-compose/app/.../chat/MessageContentFactory.kt` | 103-148 | `streamingDisplayText()` markdown clamp |
| `android-compose/app/.../chat/RememberStreamSmoother.kt` | 34-60 | Streaming text smoothing frame loop |
| `android-compose/core/.../timeline/Timeline.kt` | 354-410 | Fuzzy collapse (secondary issue) |
| `android-compose/core/.../timeline/TimelineSyncLoop.kt` | 406-423 | `upsertClientModeLocalAssistantChunk` |

All `app/.../chat/` paths resolve to: `android-compose/app/src/main/java/com/letta/mobile/ui/screens/chat/`
All `core/.../timeline/` paths resolve to: `android-compose/core/src/main/java/com/letta/mobile/data/timeline/`

## Reproduction

1. Connect to Pixel 9 Pro: `adb connect 192.168.50.235:5555`
2. Open a client mode conversation
3. Send a message that causes the agent to call 2+ tools across multiple steps
4. Observe: final assistant text appears duplicated (N copies where N = number of tool calls)
5. Logcat tags: `AdminChatVM-DEBUG` (WS chunks + timeline observer), `SseParser` (SSE frames), `Telemetry/TimelineSync` (merge/collapse)

Logcat is clean — ready for fresh capture with added instrumentation.

## Working Tree

Uncommitted changes are unrelated to this bug:
- `ChatPushService.kt` (push notifications)
- Mermaid renderer native lib + Rust source
- `.sisyphus/` directory

## Acceptance Criteria

1. Multi-tool-call runs: assistant text appears exactly once
2. Single-tool-call runs: unchanged (already correct)
3. Reasoning messages: correctly deduped against matching assistant content
4. Fuzzy collapse: absorbs ALL matching tool-call Locals (not just one)
5. No LazyColumn key crashes from duplicate IDs
6. Existing tests pass
