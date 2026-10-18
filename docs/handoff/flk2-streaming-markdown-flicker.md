# flk.2 — Streaming Markdown Flicker Regression Notes

**Date:** 2026-04-29
**Branch:** current working branch
**Reporter:** Emmanuel
**Symptom:** Assistant chat bubbles visibly flicker while text streams, with the worst flash occurring when fenced code blocks or other block-level markdown are still being built.

## Diagnosis (confirmed)

The flicker was **not** primarily a bad chunking / text-merging problem.

The real issue was architectural:

1. `StreamingMarkdownText` was reparsing the **entire accumulated bubble** through mikepenz markdown on every paint tick.
2. The stream smoother plus paint coalescer reduced update frequency, but did **not** change the fact that mikepenz rebuilt a heavy subtree each time.
3. Block-level markdown (especially fenced code blocks, lists, headings, and tables) causes large composable-tree shape changes as the text grows.
4. Instrumentation in `MarkdownTextRaw` confirmed frequent recomposition during streaming; slowing the cadence reduced the severity but did not remove the flash.

The conclusion is important: **if the active streaming region goes through full markdown parsing every tick, the subtree churn is enough to remain visible even after cadence tuning.**

## Chosen fix

The renderer now uses a **split-render architecture**:

- **Committed prefix**
  - Partition the stream at the last safe markdown boundary using `findLastSafeBoundary()`.
  - Split the committed prefix into independently keyed blocks with `splitMarkdownBlocks()`.
  - Render those committed blocks through `MarkdownText` only once per boundary advance.

- **Active tail**
  - Everything after the last safe boundary stays in the active tail.
  - The active tail renders as plain `Text`, not `MarkdownText`.
  - This keeps the high-cadence streaming region out of mikepenz completely.

- **Cursor behavior**
  - `streamingDisplayText()` still owns cursor injection / markdown stability clamping.
  - `cursorText` is passed separately so the UI can still show a cursor when the stream lands exactly on a committed boundary and the active tail is empty.

## Why this works

The key difference is that the subtree with the biggest visual cost — markdown-rendered block content — becomes **append-only and stable**.

That means:

- previously committed blocks do not rewrite
- previously committed block keys do not churn
- only the plain-text tail updates at paint cadence
- block-level markdown only reparses when a real structural boundary completes

This pushes expensive rendering work down to **paragraph / closed-block cadence** instead of **every paint sample**.

## Non-obvious design rules

These are the invariants future changes must preserve:

1. **Committed blocks are append-only**
   - Once a block is committed, its `text` and `key` must remain byte-identical for all later prefixes where it still exists.
2. **Committed blocks must never contain open block fences**
   - No committed block may end with an unclosed triple-backtick fence or unclosed `$$` display-math fence.
3. **Boundary detection is allowed to be conservative**
   - It is acceptable to delay promotion into the committed prefix.
   - It is **not** acceptable to commit unstable block structure early.
4. **The active tail must not go through mikepenz at high cadence**
   - Reintroducing `MarkdownText(activeTail)` during live streaming is the fastest way to bring the flicker back.
5. **Instrumentation belongs in tests / temporary diagnosis, not production rendering**
   - The recomposition counter in `MarkdownTextRaw` was useful diagnostically, but it must stay out of the shipping path.

## Regression tests that now protect the fix

### `SplitMarkdownBlocksTest`

Protects the structural helpers directly:

- committed-prefix partitioning for open code fences
- single-paragraph tail behavior
- committed block key stability as later blocks arrive

### `StreamingDisplayTextTest`

Protects the streaming text contract directly:

- empty input does **not** show a pre-stream cursor flash
- open code fences suppress cursor injection
- normal prose still gets cursor decoration

### `StreamingFlickerTest`

This is the main regression harness.

It simulates streaming **one character at a time** and asserts:

- committed blocks never disappear
- committed block `text` never rewrites
- committed block `key` never churns
- committed blocks never contain unclosed block fences
- active-tail growth stays bounded once boundary candidates exist
- the tail behaves append-only at the byte level across consecutive ticks

If this suite fails, the flicker likely has a deterministic non-device repro.

## Files that matter

- `android-compose/designsystem/src/main/java/com/letta/mobile/ui/components/StreamingMarkdownText.kt`
- `android-compose/app/src/main/java/com/letta/mobile/ui/screens/chat/MessageContentFactory.kt`
- `android-compose/designsystem/src/main/java/com/letta/mobile/ui/components/MarkdownText.kt`
- `android-compose/designsystem/src/test/java/com/letta/mobile/ui/components/SplitMarkdownBlocksTest.kt`
- `android-compose/designsystem/src/test/java/com/letta/mobile/ui/components/StreamingFlickerTest.kt`
- `android-compose/app/src/test/java/com/letta/mobile/ui/screens/chat/StreamingDisplayTextTest.kt`

## Required verification before changing this area

Run all of these from `android-compose/`:

```bash
./gradlew :designsystem:testDebugUnitTest --tests "com.letta.mobile.ui.components.SplitMarkdownBlocksTest"
./gradlew :designsystem:testDebugUnitTest --tests "com.letta.mobile.ui.components.StreamingFlickerTest"
./gradlew :app:testDebugUnitTest --tests "com.letta.mobile.ui.screens.chat.StreamingDisplayTextTest"
./gradlew :app:compileDebugKotlin
./gradlew :app:assembleDebug
```

Then verify on device with a long streaming assistant response that includes:

- paragraphs
- inline emphasis
- a fenced code block
- a list or table if possible

## What not to do

- Do **not** revert to single-pass `MarkdownText(fullAccumulatedText)` streaming.
- Do **not** send the live active tail through mikepenz on every tick.
- Do **not** loosen the boundary rules to commit open code fences early.
- Do **not** delete `StreamingFlickerTest` just to quiet a failure — treat failures there as architecture regressions.

## Short takeaway

The fix is not "better throttling." The fix is **keeping stable markdown blocks stable and keeping the hot streaming tail out of markdown parsing**.
