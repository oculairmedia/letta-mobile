package com.letta.mobile.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Streaming-aware markdown renderer (letta-mobile-c8of, ALT-2 design).
 *
 * Splits incoming streaming content at the last "safe" CommonMark block
 * boundary into:
 *  - **prefix**: everything that has reached a stable paragraph / closed-fence
 *    boundary. Rendered via the full [MarkdownText] (markdown formatting,
 *    syntax-highlighted code, math, mermaid, etc.).
 *  - **tail**: the in-progress paragraph (anything after the last safe
 *    boundary). Rendered as plain [Text] for the brief moment it lives.
 *    Pass-through by default; callers can supply [tailTransform] to plug in
 *    e.g. the streaming cursor / word-boundary holdback decorator from
 *    letta-mobile-6p4o.1.
 *
 * Why split: rendering the entire stream through [MarkdownText] on every
 * chunk causes a measurable re-parse + subtree rebuild per chunk (the very
 * cost that letta-mobile-d2z6 originally sidestepped by using plain Text
 * during streaming). The d2z6 approach worked but produced a visible
 * "snap" on stream-end as the bubble switched from plain Text → formatted
 * MarkdownText. The boundary split keeps the prefix as MarkdownText
 * throughout, so on stream-end there is no swap — only the final paragraph's
 * tail collapses into the prefix as the boundary advances one last time.
 *
 * Performance contract:
 *  - Boundary detection is a single O(N) pass over the text tracking
 *    triple-backtick and `$$` parity (cheap — no full markdown parse).
 *  - The MarkdownText prefix only recomposes when the boundary advances,
 *    which happens at PARAGRAPH cadence (~once per second) rather than
 *    chunk cadence (~10/sec).
 *  - Mid-paragraph chunks only mutate the tail Text, which is a flat
 *    plain-text layout — cheap.
 *
 * @param text the live, possibly-incomplete markdown text. Safe to pass on
 *   every chunk.
 * @param modifier applied to the wrapping Column.
 * @param textColor base text color used for both the markdown prefix and
 *   the plain tail.
 * @param tailStyle text style applied to the plain-text tail. Defaults to
 *   `bodyMedium`; supply your chat body style for visual parity with the
 *   formatted prefix.
 * @param tailTransform optional decorator for the in-progress tail —
 *   e.g. word-boundary holdback + streaming cursor (`streamingDisplayText`
 *   from MessageContentFactory). Receives the raw tail string, returns the
 *   string to render. NOT applied to the prefix.
 */
@Composable
fun StreamingMarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    tailStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    tailTransform: (String) -> String = { it },
) {
    if (text.isEmpty()) return

    val boundary = remember(text) { findLastSafeBoundary(text) }
    val prefix = if (boundary > 0) text.substring(0, boundary) else ""
    val rawTail = if (boundary < text.length) text.substring(boundary) else ""
    val tail = remember(rawTail) { if (rawTail.isEmpty()) "" else tailTransform(rawTail) }

    Column(modifier = modifier) {
        if (prefix.isNotEmpty()) {
            // Note: passing the prefix to the full MarkdownText keeps the
            // existing math / mermaid / code-fence / autolink machinery
            // active for everything that has reached a safe boundary.
            // mikepenz's renderer caches per-input subtrees, so when the
            // boundary doesn't advance between chunks the prefix recompose
            // is a structural-equality skip.
            MarkdownText(
                text = prefix,
                textColor = textColor,
            )
        }
        if (tail.isNotEmpty()) {
            // Plain Text for the in-progress paragraph. We intentionally
            // do NOT format the tail mid-stream — formatting partial
            // markdown produces visible flicker (e.g. "**hel" renders as
            // "**hel", then next chunk "lo**" causes "hello" to abruptly
            // become bold). The tail typically lives less than a second
            // before the next \n\n boundary commits it into the prefix.
            Text(
                text = tail,
                color = textColor,
                style = tailStyle,
            )
        }
    }
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
