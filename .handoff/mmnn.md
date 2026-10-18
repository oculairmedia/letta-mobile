# Handoff — letta-mobile-mmnn (Claude Code session, 2026-05-07)

**Status:** Historical Android investigation/handoff; source-audited 2026-10-18.
The `app/.../chat` paths and line numbers below predate the module/shared UI cuts.
Current product rendering is in `android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/chat/surface/`
and `ui/markdown/StreamingMarkdownDocument.kt`; Android host code is in `feature-chat/`.
The captured device address, working-tree state, bead status and unmerged hypotheses
are session evidence, not current deployment or implementation guarantees.
Do not reintroduce the old SSE/client-mode pipeline or apply these snippets verbatim.

## Current state

**Commit:** 72f5ec9e — "stable height floor via derivedStateOf — only updates at paragraph boundaries"  
**Build:** installed on Pixel 9 Pro 192.168.50.235:5555  
**Status:** text/paragraph flicker mostly fixed; TABLE row flicker remains

---

## What the last 3 fix attempts tackled

### Attempt 1 — cfc6daa0 (suppress animateContentSize during streaming)
- **Problem:** animateContentSize(260ms, FastOutSlowInEasing) can't catch 50ms-increment jumps — FastOutSlowInEasing has a slow start
- **Probe result:** outerHeight logs at EVERY 50ms tick (~22 jumps in ~5s)
- **Fix:** suppress animateContentSize while isStreaming=true; re-apply post-stream
- **Result:** flicker persisted — height was jumping but animation couldn't catch up

### Attempt 2 — eb0f61b9 (remove heightIn, use fixed height() + free growth)
- **Problem (theoretical):** heightIn(min=sH) forced aggressive text wrapping within a locked intermediate container
- **Fix:** Modifier.height(firstMeasuredHeightPx) to clip at first-measured; no heightIn so Column grows freely
- **Result:** BROKE — Column clipped at ~43px, nothing rendered until user scrolled

### Attempt 3 — 72f5ec9e (derivedStateOf stable floor)
- **Problem:** Attempt 2 locked the Column at the wrong (tiny) height; Attempt 1's heightIn changed every tick
- **Fix:** derivedStateOf tracks committedBlocksForRender.size; stableFloorHeightPx only updates when committed block list structurally changes (paragraph boundary), NOT on tail-tick churn; heightIn(min=stableFloorHeightPx) is referentially stable between boundaries
- **Result:** user confirmed text/paragraph flicker is mostly gone

---

## Remaining problem: table row flicker

User confirmed 2026-05-07:
> "the table that is being worked on flicker as the rows are rendered one by one"

This is specifically about **in-progress tables** (not committed/closed tables).

### Where tables are rendered during streaming

In `StreamingMarkdownText.kt`, there are TWO rendering paths for tables:

**Path 1 — Committed table blocks** (via key(block.key)):
```kotlin
committedBlocksForRender.forEach { block ->
    key(block.key) {
        if (stabilizeTables && block.text.looksLikeMarkdownTable()) {
            StableStyledMarkdownBlock(text = block.text, ...)
        } else {
            MarkdownText(text = block.text, ...)
        }
    }
}
```

**Path 2 — Active (in-progress) table** (via activeTableText):
```kotlin
activeTableText?.let { tableText ->
    StableStyledMarkdownBlock(text = tableText, textColor = textColor)
}
```

Path 2 is the flicker source. `activeTableText` is computed as:
```kotlin
val lastCommittedTable = renderPartition.committedBlocks.lastOrNull()
    ?.takeIf { stabilizeTables && it.text.looksLikeMarkdownTable() }
val activeTailBelongsToTable = lastCommittedTable != null && activeTailFirstLine.contains('|')
val activeTableText = lastCommittedTable
    ?.takeIf { activeTailBelongsToTable }
    ?.let { table -> table.text + renderPartition.activeTail + cursorText.orEmpty() }
```

So the in-progress table is ONE StableStyledMarkdownBlock whose `text` grows every 50ms tick
as new rows arrive. StableStyledMarkdownBlock tries to double-buffer, but if the text changes
every tick, the buffer swap fires every tick.

### Table row commit detection

`findLastSafeBoundary()` has this logic for progressive row commits:
```kotlin
// Row-by-row commit: each completed data row after header+separator gets committed
if (runAllHavePipe && runSeparatorMatches && runLineCount >= 3) {
    lastSafe = i + 1
}
```

However, logs from the previous session showed `committed=N` growing in ~2s increments
(committed=1 at 0.2s, committed=2 at 1.5s, committed=3 at 2.7s, committed=4 at 3.9s...)
— much slower than per-row cadence, suggesting the row-commit detection may not be
working as expected, or committedBlocksForRender drops the last block when the active
tail belongs to a table.

Check this code:
```kotlin
val committedBlocksForRender = if (activeTailBelongsToTable) {
    renderPartition.committedBlocks.dropLast(1)  // drops last committed block!
} else {
    renderPartition.committedBlocks
}
```

When a table is in-progress, the LAST committed block (the table header) is dropped
from rendering and re-rendered via `activeTableText` instead. This means committed
block count grows but committedBlocksForRender size stays the same until a new
non-table block is committed. The `derivedStateOf` key (`committedBlocksForRender.size`)
does NOT change during table streaming — the height floor stays stable — but the
`StableStyledMarkdownBlock` still re-renders every tick as `activeTableText` changes.

---

## Files to investigate next

### 1. StreamingMarkdownText.kt — activeTableText path (THE FLICKER SOURCE)
Lines ~235-260. The `StableStyledMarkdownBlock(text=activeTableText)` re-emits the
entire table markdown every 50ms tick as new rows arrive.

**Possible fix options:**
- Keep committed table rows in committedBlocks (don't dropLast when activeTailBelongsToTable)
- Render the committed rows via stable keyed MarkdownText; only render the IN-PROGRESS
  row (the partial last row) as plain Text
- Increase PAINT_INTERVAL_MS for the table path specifically
- Disable StableStyledMarkdownBlock double-buffer during active table streaming (just render
  plain MarkdownText directly since text changes every tick anyway)

### 2. findLastSafeBoundary() — table row commit detection
Around lines 440-530. Check if `runLineCount >= 3` branch fires at every data-row \n
or if there's a logic gap.

### 3. StableStyledMarkdownBlock double-buffer
Lines ~322-370. The 16ms delay before showing the new table may not be helping —
it's essentially flickering at 16ms cadence (worse than 50ms).

### 4. committedBlocksForRender.dropLast(1) when activeTailBelongsToTable
Around line ~208. When streaming a table, this drops the last committed block
(the table itself) and re-renders it via activeTableText. Consider keeping it
in committedBlocksForRender and only taking the in-progress row out.

---

## Probe plan for next session

Add a probe inside the `activeTableText?.let { }` block to log how often
StableStyledMarkdownBlock's text changes:

```kotlin
var prevActiveTableLen by remember { mutableStateOf(-1) }
SideEffect {
    if (tableText.length != prevActiveTableLen) {
        prevActiveTableLen = tableText.length
        android.util.Log.d("StreamingMarkdown", "activeTable.len=${tableText.length}")
    }
}
```

Also probe `committedBlocksForRender.size` and `renderPartition.committedBlocks.size`
to see if they diverge during table streaming (the dropLast(1) hiding committed count changes).

---

## Key invariants to preserve
- Text/paragraph streaming must stay smooth (currently working)
- Reason why inner/outer separation for RunBlock works: animateContentSize is on the
  INNER Column inside a stable outer container. For assistant bubbles, we can't do this
  easily because the outer container IS the bubble. The stable floor approach is the
  right substitute.
- No regression to committed table rendering (tables that are fully closed should
  render normally)
