# Handoff: Merge Multi-Run Responses Into Single Virtual Run

**Status:** Historical Android investigation/handoff; source-audited 2026-10-18.
The `app/.../chat` paths and line numbers below predate the module/shared UI cuts.
Current product rendering is in `android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/chat/surface/`
and `ui/markdown/StreamingMarkdownDocument.kt`; Android host code is in `feature-chat/`.
The captured device address, working-tree state, bead status and unmerged hypotheses
are session evidence, not current deployment or implementation guarantees.
Do not reintroduce the old SSE/client-mode pipeline or apply these snippets verbatim.

**Date:** 2026-05-07
**Status:** Rolled back — investigation incomplete

---

## Goal

Letta server emits separate `run_id` per think→tool_call→response cycle. A multi-tool assistant turn produces N runs. User sees these as separate collapsed `RunBlock` widgets. **Goal:** merge all contiguous assistant messages into one virtual `RunBlock` regardless of server `runId`, so the user sees a single assistant turn.

---

## What Was Tried

### Change A: `MessageGrouping.kt` line ~150
Changed the greedy-walk condition from:
```kotlin
if (m.role == "assistant" && m.runId == runId) {
```
to:
```kotlin
if (m.role == "assistant") {
```
This merges ALL contiguous assistant messages across server `runId` boundaries into one `ChatRenderItem.RunBlock`.

### Change B: `RunBlock.kt` collapsed preview
Added `selectCollapsedPreview()` to skip trailing reasoning when choosing the collapsed view:
```kotlin
val visibleMessages = if (collapsed) {
    listOf(selectCollapsedPreview(messages))  // skip reasoning, show last substantive message
} else {
    messages
}
```

### Change C: `RunBlock.kt` streaming guard
Added `effectiveCollapsed = collapsed && !isStreaming` to force-expand during streaming.

---

## Results

| Change Combination | Tool Calls During Streaming | After Completion |
|---|---|---|
| A + B | **BROKEN** | Tool calls hidden |
| A only (B reverted) | **WORKED briefly** then broke | — |
| A + C | **BROKEN** | — |
| A + force-expand (`visibleMessages = messages`) | **STILL BROKEN** | — |
| No changes (rolled back) | **WORKS** | Normal RunBlock behavior |

**Key finding:** Force-expanding (always showing all messages, ignoring `collapsed`) did NOT fix tool calls. The merge change itself — not the collapse logic — breaks tool call rendering.

---

## What Changed / What Didn't

### Files modified (all reverted now):
- `MessageGrouping.kt` — merge-walk condition (reverted via `git checkout main --`)
- `RunBlock.kt` — collapsed preview, streaming guard, force-expand test (all reverted)

### Current files SHA256 (match main):
```
MessageGrouping.kt: 4c4ce64bf00e8f54eac7a5c779bb877177c286abc...
RunBlock.kt:        2acc418a355db992e6c1b05a95ebcf16e9369711700a3b19...
```

### Current git state:
```
main @ 275f4fe5 (ahead of origin/main by 2 commits)
  - 275f4fe5 Revert "Revert "fix(mermaid)..."  (mermaid fix present)
  - 5b26db77 Revert "fix(mermaid)..."  (first revert)
  - 35707282 fix(mermaid): prevent accidental copy
  - 3e456a3f chore(chat): remove input history button
  - 8666b1d1 Merge PR #36 (table flicker + animation gating)
```

### Other changes already committed on main:
- Row-level table renderer fix (0a50f468)
- Animation gating for streaming (e1c55dc0)
- Mermaid combinedClickable fix (35707282)
- Input history button removed (3e456a3f)

---

## Instrumentation Gap

The current workflow requires a human to test on a physical device (Pixel 9 Pro at 192.168.50.235:5555). This is the critical feedback loop bottleneck. Tool call visibility during streaming is not automatically testable.

---

## Relevant Code Pipeline

```
AdminChatViewModel (streaming messages)
  → ChatUiState.messages
  → buildChatRenderModel()
    → filterMessagesForMode()  -- Simple mode drops reasoning
    → groupMessages()          -- groups by role (user/assistant)
    → groupMessagesForRender() -- **the merge point**
      → ChatRenderItem.Single  -- 1 message
      → ChatRenderItem.RunBlock -- 2+ messages sharing runId
  → ChatMessageList (LazyColumn)
    → RunBlock composable
      → renderRow → RenderChatMessage → ChatMessageItem
        → resolveRenderer() → ToolCallRenderer → MessageToolCalls
```

### Key file: `MessageGrouping.kt`
- `groupMessagesForRender()` takes `reversed` (newest-first) grouped messages
- Greedy walk accumulates contiguous entries with matching condition
- Current condition: same `role == "assistant"` AND same `runId`
- The desired condition: just `role == "assistant"` (merge all)

### Key file: `ChatMessageList.kt` lines 328-421
- Single items: renders directly or routes through `RunBlock` (if `stableRunKey` set)
- RunBlock items: renders via `RunBlock` composable
- Both paths end up calling `RenderChatMessage`

---

## Hypothesis for Why Merge Breaks Tool Calls

When tool calls are in separate `RunBlock`s (original behavior), they render fine. When merged into one `RunBlock`, they don't. Even force-expanding doesn't help. This suggests the issue is NOT in the collapse state but in how the `RunBlock` rendering handles multi-sub-run messages.

Possible causes:
1. The `RunBlock` composable's `renderRow` callback receives messages correctly but something downstream rejects tool calls when there are too many messages of different types in the block
2. The `resolveRenderer` memoization key (`remember(message.role, message.toolCalls, message.generatedUi)`) could cache the wrong renderer when messages rapidly change during streaming
3. A Compose recomposition issue where the RunBlock body is not re-executing correctly when messages are added from different sub-runs

---

## Next Steps

1. **Add logging** to RunBlock and resolveRenderer to capture actual message render decisions during merge
2. **Debug with adb logcat** during a multi-tool conversation to see which renderer is selected for tool call messages inside the merged RunBlock
3. **Alternative approach:** Instead of merging in `groupMessagesForRender`, consider a "virtual merge" at the UI level — keep RunBlocks separate but visually join them
4. **Minimal reproduction:** Create a unit test that feeds a multi-run message stream through `buildChatRenderModel` and verifies render item types

### Adb logcat commands for debugging:
```bash
# Filter for RunBlock and renderer decisions
adb -s 192.168.50.235:5555 logcat -s "RunBlock-DEBUG:W" "ChatRenderModel-DEBUG:W" "ItemKey-DEBUG:W"

# Clear logcat first
adb -s 192.168.50.235:5555 logcat -c
```
