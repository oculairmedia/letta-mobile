package com.letta.mobile.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay

/**
 * Streaming-aware markdown renderer with a stable committed-prefix path.
 *
 * The stream is rate-limited to a paint cadence, then split at the last
 * safe block boundary. Completed markdown blocks render as independently
 * keyed [MarkdownText] instances, while only the active tail keeps
 * re-parsing as chunks arrive. This preserves live inline formatting in the
 * active block while preventing already-complete code blocks / paragraphs /
 * tables from being torn down on every tick.
 *
 * Earlier designs ([letta-mobile-c8of] ALT-2 + [letta-mobile-flk.1]) also
 * split at a safe boundary, but rendered the tail as plain text. That
 * achieved per-block render skipping but produced two visible regressions:
 *  - Short single-paragraph replies never had a `\n\n` boundary, so the
 *    boundary stayed at 0 and the entire bubble rendered as plain Text
 *    with literal asterisks until the stream completed.
 *  - When the boundary advanced mid-stream, content briefly appeared to
 *    duplicate as the tail moved into a new prefix block while a new
 *    tail was being computed for the next paragraph (the chunk-
 *    accumulation regression).
 *
 * @param text the live, possibly-incomplete markdown text. Safe to pass on
 *   every chunk.
 * @param modifier forwarded to the outer container.
 * @param textColor base text color.
 * @param tailStyle typography used only when the active tail is empty and we
 *   still need to show a standalone cursor after a committed prefix.
 * @param tailTransform optional decorator applied to the active tail only —
 *   e.g. word-boundary holdback + streaming cursor (`streamingDisplayText`
 *   from MessageContentFactory).
 * @param cursorText optional standalone cursor glyph rendered when the text
 *   currently ends exactly on a committed boundary.
 */
@Composable
fun StreamingMarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    tailStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    tailTransform: (String) -> String = { it },
    cursorText: String? = null,
) {
    if (text.isEmpty()) return

    // Stable block-prefix renderer.
    //
    // The original c8of design split the text at the last safe paragraph
    // boundary into a markdown-rendered prefix and a plain-text tail.
    // That worked when responses had paragraph breaks but completely
    // failed for short single-paragraph replies (boundary stayed at 0,
    // entire bubble rendered as plain Text with literal asterisks).
    //
    // The later full-bubble MarkdownText pass fixed that inline-formatting
    // regression but still re-emitted the entire markdown subtree on each
    // tick, which is especially visible for streaming code blocks, lists,
    // and tables. The current design keeps the committed prefix stable and
    // limits re-parse churn to the active tail.
    // letta-mobile-flk2 (revision 3): paint-rate coalescer using
    // rememberUpdatedState + a single long-running polling effect.
    //
    // The flk.3.1 design re-rendered the full MarkdownText subtree on
    // every char. With the new ClientModeStreamSmoother delivering
    // chars at 90–180 cps, the per-char re-parse caused visible
    // flicker.
    //
    // Revision 1 used `LaunchedEffect(text) { delay(50) }` which
    // cancelled+restarted on every char and never completed until
    // the stream ended (chars arrive every ~10ms; 50ms delay never
    // wins) — content popped in at end-of-stream.
    //
    // Revision 2 used `mutableStateOf` + write-during-composition,
    // which is illegal in Compose and fails silently or freezes.
    // Symptom: first three lines appear (initial composition), then
    // stalled poll loop reads stale state, then content pops at end.
    //
    // Revision 3 uses `rememberUpdatedState(text)` — Compose's
    // canonical pattern for "the long-running effect needs to read
    // the latest value of an input without restarting". The state
    // is updated via Compose's snapshot system at the END of
    // composition, so the polling effect reads the freshest value
    // safely on each tick.
    val latestText by rememberUpdatedState(text)

    var displayed by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        // Tick at fixed cadence. Push displayed = latest whenever
        // they differ. When text stops changing (stream end),
        // `displayed` catches up within one PAINT_INTERVAL_MS window
        // and the loop idles cheaply (no MarkdownText re-render
        // when value is unchanged — Compose elides via structural
        // equality).
        //
        // First push happens within one tick (≤50ms), which is
        // imperceptible. We don't special-case first-paint anymore
        // because the cancel/restart logic that needed special-casing
        // is gone.
        while (true) {
            if (displayed != latestText) {
                displayed = latestText
            }
            delay(PAINT_INTERVAL_MS)
        }
    }

    // letta-mobile-flk2 (revision 15): split-render with plain-Text
    // active tail.
    //
    // Architecture: committed blocks (append-only, partitioned at safe
    // markdown boundaries by `partitionStreamingMarkdown`) render
    // through MarkdownText with stable Compose keys. The active tail
    // (in-progress paragraph past the last safe boundary) renders as
    // PLAIN Text — mikepenz never sees the high-cadence string changes.
    //
    // Why this is necessary: revision 14 instrumentation measured
    // ~17-18Hz (50ms coalesce) and ~10Hz (100ms coalesce) of
    // MarkdownTextRaw recompositions during streaming. At BOTH rates
    // Emmanuel observed flicker (18Hz "jarring", 10Hz "less jarring,
    // still apparent"). Single-pass MarkdownText cannot achieve
    // no-flicker because mikepenz tears down + re-emits the subtree on
    // every paint tick regardless of cadence — flicker is content-
    // independent ("every message"), confirming subtree rebuild rather
    // than mid-construct parse churn.
    //
    // Trade-off: while typing a paragraph, raw inline markdown like
    // `**bold**` is shown as literal text until the paragraph
    // completes (paragraph break commits the block to mikepenz). For
    // prose this is mostly invisible — most paragraphs don't contain
    // emphasis — and matches what every modern markdown chat app does
    // (ChatGPT, Claude.ai, Gemini all hold off live emphasis until the
    // line/paragraph completes). Block constructs (lists, code fences,
    // tables) still render live because partitionStreamingMarkdown
    // commits them as soon as they close.
    //
    // No swap-flash: when a paragraph break commits, the rendered
    // markdown prefix is unchanged (existing blocks keep their stable
    // keys, no recomposition); only a new MarkdownText block appears
    // and the plain-Text tail string shrinks. Compose treats this as
    // a layout step, not a render swap.
    val partition = remember(displayed) { partitionStreamingMarkdown(displayed) }
    val transformedTail = remember(partition.activeTail) {
        tailTransform(partition.activeTail)
    }

    Column(modifier = modifier) {
        // Committed blocks: each rendered through MarkdownText, keyed
        // by content hash. Compose elides recomposition for blocks
        // whose key is unchanged across ticks. mikepenz sees each
        // block exactly once.
        partition.committedBlocks.forEach { block ->
            key(block.key) {
                MarkdownText(
                    text = block.text,
                    textColor = textColor,
                )
            }
        }

        // Active tail: plain Text. mikepenz NEVER sees this string.
        // The tailTransform decorator handles cursor injection
        // (▎) and the markdown-stability clamp
        // (clampToStableMarkdown) so any unmatched span openers are
        // clipped from the rendered text.
        if (transformedTail.isNotEmpty()) {
            Text(
                text = transformedTail,
                style = tailStyle,
                color = textColor,
            )
        } else if (cursorText != null && partition.committedBlocks.isNotEmpty()) {
            // Edge case: text ends exactly at a committed boundary
            // (e.g. just after a paragraph break with no chars typed
            // yet). Show a standalone cursor so streaming still feels
            // live.
            Text(
                text = cursorText,
                style = tailStyle,
                color = textColor,
            )
        }
    }
}

/**
 * letta-mobile-flk2: maximum cadence for live markdown re-parse during
 * streaming. 50ms ≈ 20Hz — slow enough that mikepenz + Compose can
 * absorb the re-emit without visible flicker, fast enough that the
 * tail cursor still feels live.
 */
// letta-mobile-flk2 (revision 15): paint coalescer at 50ms (20Hz).
// With the split-render architecture this rate now drives only the
// plain-Text active tail update — mikepenz update rate is governed by
// paragraph/block-boundary commits (~1-3Hz natural). 20Hz on plain
// Text is cheap (no parse, no subtree allocation), and the cursor
// feels live without bursting.
//
// The 100ms experiment (revision 14) reduced flicker but did not
// eliminate it because single-pass MarkdownText still re-emitted the
// subtree on every recomp. Split-render breaks that link entirely.
private const val PAINT_INTERVAL_MS = 50L

internal data class StreamingMarkdownPartition(
    val committedBlocks: List<MarkdownBlock>,
    val activeTail: String,
)

internal fun partitionStreamingMarkdown(text: String): StreamingMarkdownPartition {
    if (text.isEmpty()) {
        return StreamingMarkdownPartition(committedBlocks = emptyList(), activeTail = "")
    }

    val boundary = findLastSafeBoundary(text)
    val prefix = text.substring(0, boundary)
    val activeTail = text.substring(boundary)
    return StreamingMarkdownPartition(
        committedBlocks = splitMarkdownBlocks(prefix),
        activeTail = activeTail,
    )
}

/**
 * Walks [text] once to find the index of the last position that is "safe"
 * to use as a markdown render boundary. A safe boundary satisfies all of:
 *
 * 1. The position is immediately after either:
 *    a. A paragraph break (`\n\n`), or
 *    b. A GFM table that has just structurally ended (the line after
 *       the last `|`-bearing row contains no pipes — see
 *       [letta-mobile-c8of.6]).
 * 2. The number of unescaped triple-backtick fences in `text[0..pos]`
 *    is even (no unclosed code fence — an unclosed fence would absorb
 *    subsequent content into a code block on the next chunk).
 * 3. The number of `$$` markers in `text[0..pos]` is even (no unclosed
 *    display-math fence — same reasoning).
 *
 * Returns the index of the boundary, or `0` if no safe boundary exists
 * yet (e.g. the entire stream so far is one paragraph, or contains an
 * unclosed fence with no subsequent paragraph break).
 *
 * Inline emphasis closure (`**bold**`, `*ital*`, `` `code` ``) is NOT
 * tracked — those are inline constructs that mikepenz re-parses cheaply
 * and don't catastrophically break subsequent paragraph rendering. Only
 * BLOCK-level fences (code fence, display math) need parity tracking
 * because they greedily absorb following content.
 *
 * Single-pass O(N), no allocations beyond the index integer + a small
 * line-window string for the table-close check.
 */
internal fun findLastSafeBoundary(text: String): Int {
    if (text.length < 2) return 0

    var lastSafe = 0
    var fenceParity = 0 // count of ``` mod 2
    var displayMathParity = 0 // count of $$ mod 2
    var i = 0
    val n = text.length

    // Track recent consecutive non-empty lines for the table-close
    // detector. We only need to know:
    //   - how many lines are in the current run
    //   - whether every line so far in the run contains at least one '|'
    //   - whether line index 1 (zero-indexed) matches the GFM separator
    //     row pattern: optional leading |, then dash-runs separated by
    //     pipes, with optional :alignment colons
    //
    // The run resets on every blank line.
    var runLineCount = 0
    var runAllHavePipe = true
    var runSeparatorMatches = false
    var lineStart = 0

    while (i < n) {
        // Block-level constructs first — they take precedence over
        // line bookkeeping because they can contain newlines that don't
        // count as paragraph breaks for our purposes.

        // Triple backtick fence: ```
        if (i + 2 < n && text[i] == '`' && text[i + 1] == '`' && text[i + 2] == '`') {
            fenceParity = fenceParity xor 1
            i += 3
            continue
        }
        // Display math fence: $$
        if (i + 1 < n && text[i] == '$' && text[i + 1] == '$') {
            displayMathParity = displayMathParity xor 1
            i += 2
            continue
        }

        if (text[i] == '\n') {
            // Inside an open block fence, treat newlines as opaque —
            // the line-tracking heuristics don't apply to fenced content.
            if (fenceParity != 0 || displayMathParity != 0) {
                // Still need to advance lineStart so we don't emit a
                // stale "line" once the fence eventually closes.
                lineStart = i + 1
                i++
                continue
            }

            val lineEnd = i
            val lineLen = lineEnd - lineStart
            val lineIsBlank = lineLen == 0

            if (lineIsBlank) {
                // We're sitting on a `\n` that was preceded by another
                // `\n` (the previous line had length 0). That's the
                // SECOND `\n` of a paragraph break — commit the boundary
                // AFTER this `\n` so the prefix ends with `\n\n` and the
                // tail starts at the next paragraph's first char.
                //
                // Both block-level fences are already CLOSED here (the
                // open-fence branch above returned early before entering
                // line bookkeeping).
                lastSafe = i + 1
                i++
                // Skip additional blank lines into the boundary so e.g.
                // "A\n\n\n\nB" still anchors the boundary just before B.
                while (i < n && text[i] == '\n') {
                    lastSafe = i + 1
                    i++
                }
                lineStart = i
                runLineCount = 0
                runAllHavePipe = true
                runSeparatorMatches = false
                continue
            }

            // Non-blank line just completed. Check pipe presence + (if
            // it's the second line of a run) the GFM separator pattern.
            val hasPipe = lineHasPipe(text, lineStart, lineEnd)
            if (!hasPipe) {
                // letta-mobile-c8of.6: table-close detector.
                // The line we just consumed is non-empty AND has no
                // pipes. If the immediately-preceding run is a complete
                // table (≥2 lines, all-pipe, line[1] is separator), the
                // table structurally ended at the previous \n. Commit a
                // soft boundary right BEFORE this current line — the
                // table goes into the prefix, this current line starts
                // the tail.
                //
                // Why this is safe: a GFM table is delimited by either a
                // blank line OR the first subsequent line that doesn't
                // contain a pipe. mikepenz's renderer has already
                // committed the table by the time we reach this point.
                //
                // Why no false positive on "yes | no | maybe" prose:
                // we require the SECOND line of the run to match the
                // separator pattern (| --- | --- |), which is the GFM
                // table signature. Plain prose with stray pipes will
                // never match that.
                if (runLineCount >= 2 && runAllHavePipe && runSeparatorMatches) {
                    lastSafe = lineStart
                }
                // This line is now line 1 of a NEW run (no pipe → can't
                // ever satisfy the separator condition for *this* run,
                // but the run continues for accounting purposes).
                runLineCount = 1
                runAllHavePipe = false
                runSeparatorMatches = false
            } else {
                // Line has a pipe — extend the current run.
                runLineCount += 1
                if (runLineCount == 2) {
                    runSeparatorMatches = lineLooksLikeTableSeparator(text, lineStart, lineEnd)
                }
                // runAllHavePipe stays true (already true, this line has pipe)

                // letta-mobile-c8of.6: PROGRESSIVE row-by-row commit.
                //
                // As soon as we have header + separator + ≥1 data row
                // committed (runLineCount >= 3), each subsequent
                // row-terminating \n produces a VALID truncated GFM
                // table prefix. mikepenz parses the prefix as a
                // complete N-row table — so committing the boundary at
                // i+1 (right after this \n) renders the table grid up
                // to and including this row.
                //
                // Without this branch, the table sits entirely in the
                // raw-text tail until a non-pipe line arrives —
                // exactly the bug Emmanuel reported (rows don't render
                // until the model emits the post-table prose).
                //
                // Note: we deliberately do NOT commit at
                // runLineCount == 2 (after the separator alone) because
                // a header + separator with no data row is rendered by
                // some markdown engines as an empty table that flickers
                // when the first row arrives.
                if (runAllHavePipe && runSeparatorMatches && runLineCount >= 3) {
                    lastSafe = i + 1
                }
            }

            lineStart = i + 1
            i++
            continue
        }

        i++
    }

    // End-of-text handling for in-flight content (no trailing \n).
    //
    // The per-line bookkeeping above only fires on `\n`, so a streaming
    // chunk that ends mid-line never reaches the line branches. Two
    // cases matter for the table progressive-render:
    //
    //   (A) After a complete table run (header + separator + ≥1 data
    //       row), the model has just emitted the FIRST non-pipe char of
    //       a new line — table is structurally closed by the appearance
    //       of any non-pipe character outside the grid. Commit at
    //       lineStart so the table goes into the prefix and the prose
    //       starts the tail.
    //
    //   (B) After header + separator (runLineCount == 2 + separator),
    //       the model is mid-way through emitting the FIRST data row
    //       (partial line starts with a pipe). Commit at lineStart so
    //       the header+separator land in the prefix; the partial row
    //       sits briefly in the tail until its \n lands and the row
    //       commit branch in the main loop takes over.
    //
    //       Note: we DO NOT commit here when runLineCount >= 3 because
    //       the post-newline branch in the main loop already committed
    //       at the end of the last completed row (lastSafe was set when
    //       we processed that \n). The partial in-flight row is the
    //       tail by construction.
    //
    // We only commit when we're sure the in-flight content commits to
    // a particular shape: at least one non-whitespace char must exist.
    if (runAllHavePipe && runSeparatorMatches && runLineCount >= 2 && lineStart < n) {
        var sawNonWs = false
        var sawPipe = false
        for (j in lineStart until n) {
            val c = text[j]
            if (c == '|') {
                sawPipe = true
                // Don't break — we want the full sawNonWs picture too.
            }
            if (c != ' ' && c != '\t' && c != '\n') sawNonWs = true
        }
        if (sawNonWs) {
            // Case A: non-pipe line after complete table → table-close.
            // Case B: pipe-bearing line right after separator with no
            // completed data row yet → commit so header+separator render.
            // Either way, the boundary lives at lineStart.
            //
            // When sawPipe AND runLineCount >= 3, the main-loop row
            // commit already moved lastSafe forward to end of the last
            // completed row, which equals lineStart for this in-flight
            // partial. So setting lastSafe = lineStart here is a no-op
            // in that case (correct).
            lastSafe = lineStart
        }
    }

    return lastSafe
}

/**
 * Returns true if the substring `text[start..end)` contains any `|`
 * character, ignoring whitespace.
 *
 * Used by the table-close detector. We DON'T require the pipe to be
 * unescaped because in practice the GFM spec lets `\|` mean a literal
 * pipe inside a cell; it still counts as a table cell. False positives
 * here are bounded by the SEPARATOR-row check on line 2 of the run,
 * which is the actual GFM table signature.
 */
private fun lineHasPipe(text: String, start: Int, end: Int): Boolean {
    for (j in start until end) {
        if (text[j] == '|') return true
    }
    return false
}

/**
 * Returns true if the substring `text[start..end)` looks like a GFM
 * table separator row, e.g. one of:
 *
 *   `| --- | --- |`
 *   `|---|---|`
 *   `| :--- | ---: | :---: |`
 *   `--- | ---`
 *
 * The pattern: optional leading `|`, then one or more dash-runs
 * (optionally surrounded by `:` for alignment), separated by `|`,
 * with optional trailing `|`. Whitespace around tokens is ignored.
 *
 * This is the unambiguous GFM table signature — prose can contain
 * stray pipes but won't match a separator row by accident.
 */
private fun lineLooksLikeTableSeparator(text: String, start: Int, end: Int): Boolean {
    // Walk the line, skipping whitespace, expecting a sequence of
    // separator cells (each one or more `-` optionally bracketed by `:`),
    // delimited by `|`.
    var j = start
    fun skipSpaces() {
        while (j < end && (text[j] == ' ' || text[j] == '\t')) j++
    }
    skipSpaces()
    // Optional leading pipe.
    if (j < end && text[j] == '|') {
        j++
        skipSpaces()
    }
    var cellsSeen = 0
    while (j < end) {
        // Optional leading colon for alignment.
        if (j < end && text[j] == ':') j++
        // Must have at least one dash.
        var dashes = 0
        while (j < end && text[j] == '-') {
            dashes++
            j++
        }
        if (dashes == 0) return false
        // Optional trailing colon for alignment.
        if (j < end && text[j] == ':') j++
        skipSpaces()
        cellsSeen++
        if (j >= end) break
        if (text[j] != '|') return false
        j++
        skipSpaces()
        // Trailing pipe with no further cell is fine.
        if (j >= end) break
    }
    // GFM tables require at least one column, but since prose can
    // contain a single "-----" line we require at least one explicit
    // cell delimiter to avoid matching "thematic break" lines like
    // `---` that produce an HR, not a table.
    return cellsSeen >= 2 || (cellsSeen >= 1 && containsPipe(text, start, end))
}

private fun containsPipe(text: String, start: Int, end: Int): Boolean {
    for (j in start until end) if (text[j] == '|') return true
    return false
}

/**
 * letta-mobile-flk.3: returns true if [tail] contains an UNCLOSED
 * triple-backtick or `$$` block fence.
 *
 * The streaming tail (everything past the last safe paragraph boundary)
 * is normally rendered through [MarkdownText] so emphasis, lists, and
 * inline code format live during the stream. But if the tail begins or
 * contains a `\`\`\`` or `$$` with no matching close yet, mikepenz will
 * interpret the open fence as starting a giant code/math block that
 * absorbs the rest of the bubble — producing a visual artifact much
 * worse than the inline-emphasis flicker we accept elsewhere.
 *
 * Counts unescaped triple-backticks and `$$` markers; returns true iff
 * either count is odd (i.e. an unclosed fence is open). O(N) over the
 * tail, no allocations.
 *
 * Single backtick (inline code) and single `$` (inline math) are NOT
 * tracked here — those are inline constructs that mikepenz tolerates
 * being unclosed, and they don't catastrophically absorb subsequent
 * content.
 */
internal fun tailHasOpenBlockFence(tail: String): Boolean {
    var fenceParity = 0
    var displayMathParity = 0
    var i = 0
    val n = tail.length
    while (i < n) {
        if (i + 2 < n && tail[i] == '`' && tail[i + 1] == '`' && tail[i + 2] == '`') {
            fenceParity = fenceParity xor 1
            i += 3
            continue
        }
        if (i + 1 < n && tail[i] == '$' && tail[i + 1] == '$') {
            displayMathParity = displayMathParity xor 1
            i += 2
            continue
        }
        i++
    }
    return fenceParity != 0 || displayMathParity != 0
}

// ─── letta-mobile-flk.1: per-block prefix splitter ─────────────────────

/**
 * One renderable block emitted by [splitMarkdownBlocks]. The [key] is
 * stable across recompositions for any block whose [text] hasn't changed,
 * so wrapping the renderer in `key(block.key) { ... }` lets Compose
 * skip-recompose unchanged blocks during streaming. [text] is the raw
 * markdown source for the block (suitable input to [MarkdownText]).
 */
internal data class MarkdownBlock(val key: String, val text: String)

/**
 * Split a committed markdown [prefix] into independently-keyed blocks
 * for incremental rendering. The boundary detector ([findLastSafeBoundary])
 * already guarantees [prefix] ends at a "safe" point (no open code/math
 * fence, no in-flight table row), so we can scan forward from the start
 * and emit one block per:
 *
 *   - **Paragraph** — a run of text terminated by `\n\n` (the standard
 *     CommonMark paragraph break).
 *   - **Closed code fence** — `` ``` ... ``` ``, treated as a single
 *     block even if it contains internal blank lines (otherwise we'd
 *     shred fenced code and lose syntax highlighting context).
 *   - **Closed display-math fence** — `$$ ... $$`, same reasoning.
 *   - **Complete GFM table** — header + separator + ≥1 data row,
 *     terminated by either `\n\n` or a non-pipe line. Splitting
 *     mid-table would render rows as separate one-row tables, which
 *     mikepenz cannot stitch back together.
 *
 * Block keys encode `(index, contentHash)` — the index makes ordering
 * explicit (so swapping two structurally-similar paragraphs invalidates
 * both keys, not neither), and the hash defends against the rare case
 * where a snapshot-shaped re-emission silently mutates an
 * already-committed block (we want the renderer to invalidate, not
 * silently reuse the stale tree).
 *
 * Performance: O(N) over [prefix] length with no allocations beyond the
 * block list itself (substring views into the source string). Called
 * inside `remember(prefix)` so it runs once per prefix-advance, not
 * per-recompose.
 *
 * Returns an empty list for an empty prefix.
 */
internal fun splitMarkdownBlocks(prefix: String): List<MarkdownBlock> {
    if (prefix.isEmpty()) return emptyList()

    val blocks = mutableListOf<MarkdownBlock>()
    val n = prefix.length
    var blockStart = 0
    var i = 0

    // Per-line state for table detection. Mirrors the bookkeeping in
    // findLastSafeBoundary so the splitter agrees with the boundary
    // detector about what counts as a complete table.
    var lineStart = 0
    var runStart = -1            // index where current pipe-bearing run started
    var runLineCount = 0
    var runAllHavePipe = true
    var runSeparatorMatches = false

    fun emitBlock(end: Int) {
        if (end <= blockStart) return
        // Trim only LEADING blank-line padding from the block text;
        // preserve the trailing \n\n that terminated it so mikepenz
        // sees a fully-formed paragraph break.
        val raw = prefix.substring(blockStart, end)
        if (raw.isEmpty()) return
        val key = "blk-${blocks.size}-${raw.hashCode()}"
        blocks.add(MarkdownBlock(key, raw))
        blockStart = end
        // Reset table-run state — block boundary always ends any run.
        runStart = -1
        runLineCount = 0
        runAllHavePipe = true
        runSeparatorMatches = false
    }

    while (i < n) {
        // Closed code fence: scan to the matching ``` and treat the
        // whole span as one atomic block. NB: i must be at line-start
        // (immediately after a \n or at index 0) for this to be a real
        // fence — backticks mid-line are inline code and don't open
        // block-level fences. We approximate "line-start" by checking
        // i == blockStart after a fresh emit OR i == lineStart.
        val atLineStart = (i == 0) || (i == lineStart)
        if (atLineStart && i + 2 < n &&
            prefix[i] == '`' && prefix[i + 1] == '`' && prefix[i + 2] == '`'
        ) {
            // Anything before this fence (a paragraph that ended
            // without a \n\n terminator) becomes its own block first.
            if (i > blockStart) {
                emitBlock(i)
            }
            // Find the matching close fence. The boundary detector
            // guarantees one exists in the committed prefix.
            var j = i + 3
            while (j + 2 <= n) {
                if (prefix[j] == '`' && prefix[j + 1] == '`' && prefix[j + 2] == '`') {
                    j += 3
                    break
                }
                j++
            }
            // Consume any trailing \n\n after the close so the fence
            // and its paragraph-break go into the same block. (A fence
            // followed by another fence with no blank line between is
            // legal but unusual; we handle the common case.)
            while (j < n && prefix[j] == '\n') j++
            // Emit the fence (plus its trailing \n\n) as its own block.
            emitBlock(j)
            i = j
            // Update line tracking to start fresh after the fence.
            lineStart = i
            continue
        }

        // Closed display-math fence: same atomic-block treatment.
        if (atLineStart && i + 1 < n && prefix[i] == '$' && prefix[i + 1] == '$') {
            if (i > blockStart) {
                emitBlock(i)
            }
            var j = i + 2
            while (j + 1 <= n) {
                if (prefix[j] == '$' && prefix[j + 1] == '$') {
                    j += 2
                    break
                }
                j++
            }
            while (j < n && prefix[j] == '\n') j++
            emitBlock(j)
            i = j
            lineStart = i
            continue
        }

        if (prefix[i] == '\n') {
            val lineEnd = i
            val lineLen = lineEnd - lineStart
            val lineIsBlank = lineLen == 0

            if (lineIsBlank) {
                // Blank line = paragraph break. Emit everything up to
                // and including this \n as a block; advance past any
                // additional blank \n's (collapse them into the
                // preceding block's terminator).
                var end = i + 1
                while (end < n && prefix[end] == '\n') end++
                emitBlock(end)
                i = end
                lineStart = i
                continue
            }

            // Non-blank line just completed.
            val hasPipe = lineHasPipe(prefix, lineStart, lineEnd)
            if (hasPipe) {
                if (runStart < 0) {
                    runStart = lineStart
                    runLineCount = 1
                    runAllHavePipe = true
                } else {
                    runLineCount += 1
                }
                if (runLineCount == 2) {
                    runSeparatorMatches =
                        lineLooksLikeTableSeparator(prefix, lineStart, lineEnd)
                }
            } else {
                // Non-pipe line ends any in-progress table run. If the
                // run was a complete table (≥3 lines, separator on
                // line 2), emit the table as its own block — without
                // including this current non-pipe line. The non-pipe
                // line starts a new prose run.
                if (runLineCount >= 3 && runAllHavePipe && runSeparatorMatches) {
                    emitBlock(lineStart)
                }
                runStart = -1
                runLineCount = 1
                runAllHavePipe = false
                runSeparatorMatches = false
            }

            lineStart = i + 1
            i++
            continue
        }

        i++
    }

    // Tail of the prefix: handle the case where a complete table is
    // followed by a non-pipe prose line that has no terminating \n
    // (the prose line is the LAST committed line in the prefix). We
    // need to detect "current line has started but isn't terminated,
    // and the run before it is a complete table" and split there.
    //
    // The boundary detector commits at lineStart for this case, so
    // the prefix DOES end with a partial-but-committed prose line. We
    // mirror that here: if there's a complete table run still active
    // AND there's content past the last \n that doesn't have pipes,
    // emit the table as its own block before the EOF emit.
    if (lineStart > blockStart && lineStart < n) {
        // There's content past the last \n — check if it's pipe-free.
        var hasPipeInTail = false
        for (j in lineStart until n) {
            if (prefix[j] == '|') { hasPipeInTail = true; break }
            if (prefix[j] == '\n') break
        }
        if (!hasPipeInTail &&
            runLineCount >= 3 && runAllHavePipe && runSeparatorMatches
        ) {
            emitBlock(lineStart)
        }
    }

    // Tail of the prefix: emit anything left as a final block. The
    // boundary detector's contract means this is always a complete
    // paragraph or complete table; we don't need to handle in-flight
    // partial structures here (those live in the tail, not the prefix).
    if (blockStart < n) {
        emitBlock(n)
    }

    return blocks
}
