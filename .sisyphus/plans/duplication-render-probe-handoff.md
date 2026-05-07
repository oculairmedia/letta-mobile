# Handoff: Multi-Tool-Call Message Duplication — Render Probe Investigation

## State

The data pipeline is **proven clean** across all layers:
- **StreamingMd**: every partition passes `committedTotalLen + activeTailLen == textLen` validation — no boundary over-count
- **StreamingDisplay**: `heldBack` stays 0 during active streaming — no clamping issues  
- **ChatRenderModel**: pipeline is rock solid — `input=35 reasoningDedup=35 visible=35 grouped=35 keyDedup=35 renderItems=34 assistantCount=26` holds constant across 50+ recompositions. No multiplication anywhere.
- **TimelineObs**: `liveCount` stays at 56 through the stream — assistant message is a single Local, updated in-place, never duplicated

**Yet the user sees the final assistant message echoed multiple times.** The duplication is at the Compose rendering level, not the data pipeline.

## What's Being Built

Two render-count `SideEffect` probes (bead `letta-mobile-1fa2`, blocked by `letta-mobile-lbur`):
1. **ChatMessageComponents.kt** (~line 129): logs `ChatMsgItem-DEBUG` with message ID, role, isReasoning, contentLen, contentHash on every composition
2. **RunBlock.kt** (~line 83): logs `RunBlock-DEBUG` with message count, collapsed state, and truncated IDs on every composition

## Blocker: LazyColumn Crash (`letta-mobile-lbur` P0)

Clean debug build crashes during streaming with:
```
FATAL: measure() may not be called multiple times on the same Measurable
at LazyListMeasuredItemProvider.getAndMeasure(LazyListMeasuredItemProvider.kt:54)
```

Triggered by fling animation + `animateContentSize()` in RunBlock/chat content during streaming. Workaround: **don't scroll during streaming**, let the response finish rendering first.

Alternative: temporarily gate `animateContentSize` with `if (!state.isStreaming)` in RunBlock.kt:112 and ChatMessageComponents.kt:319-325.

## Probe Execution

Once crash is mitigated:
```bash
adb logcat -c
# → send message, DON'T SCROLL, wait for stream to complete
adb logcat -d | grep -E "ChatMsgItem-DEBUG|RunBlock-DEBUG" | tail -100
```

Key analysis:
```bash
# Count how many times the assistant message was rendered
adb logcat -d | grep "ChatMsgItem-DEBUG" | grep "cm-assist" | uniq -c
```

## Expected Outcomes

| Observation | Meaning |
|-------------|---------|
| Same message ID appears once per recomposition | Duplication is INSIDE a single composable (MarkdownText/content renderer repeats text internally) |
| Same message ID appears >1 time consecutively | LazyColumn key collision — two items share the same key |
| RunBlock message count > renderItems count | RunBlock iterating messages incorrectly |

## Key Files (changes only)

| File | Line | Change |
|------|------|--------|
| `ChatMessageComponents.kt` | ~129 | `SideEffect` probe for ChatMsgItem-DEBUG |
| `RunBlock.kt` | ~83 | `SideEffect` probe for RunBlock-DEBUG |

## All Instrumentation Beads

```
letta-mobile-3fnm (existing investigation)
  └─ letta-mobile-apo3 (P1) — parent instrumentation bead
       ├─ letta-mobile-o9kr (P1) — StreamingMarkdownText partition telemetry ✅ DONE
       ├─ letta-mobile-2vza (P1) — Timeline observer dedup telemetry ✅ DONE
       ├─ letta-mobile-thhz (P1) — Render model pipeline comparison probe ✅ DONE
       ├─ letta-mobile-y3j9 (P2) — streamingDisplayText clamping probe ✅ DONE
       └─ letta-mobile-1fa2 (P0) — ChatMessageItem + RunBlock render-count probes ← BLOCKED
            └─ letta-mobile-lbur (P0) — LazyColumn double-measure crash ← TO FIX FIRST

letta-mobile-3fnm (parent) — needs closing once duplication root cause is found
```
