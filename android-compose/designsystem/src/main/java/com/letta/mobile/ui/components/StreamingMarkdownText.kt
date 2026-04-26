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
 * 1. The position is immediately after a paragraph break (`\n\n`).
 * 2. The number of unescaped triple-backtick fences in `text[0..pos]`
 *    is even (no unclosed code fence — an unclosed fence would absorb
 *    subsequent content into a code block on the next chunk).
 * 3. The number of `$$` markers in `text[0..pos]` is even (no unclosed
 *    display-math fence — same reasoning).
 *
 * Returns the index immediately AFTER the last `\n\n` that satisfies these
 * conditions, or `0` if no safe boundary exists yet (e.g. the entire
 * stream so far is one paragraph, or contains an unclosed fence with no
 * subsequent paragraph break).
 *
 * Inline emphasis closure (`**bold**`, `*ital*`, `` `code` ``) is NOT
 * tracked — those are inline constructs that mikepenz re-parses cheaply
 * and don't catastrophically break subsequent paragraph rendering. Only
 * BLOCK-level fences (code fence, display math) need parity tracking
 * because they greedily absorb following content.
 *
 * Single-pass O(N), no allocations beyond the index integer.
 */
internal fun findLastSafeBoundary(text: String): Int {
    if (text.length < 2) return 0

    var lastSafe = 0
    var fenceParity = 0 // count of ``` mod 2
    var displayMathParity = 0 // count of $$ mod 2
    var i = 0
    val n = text.length

    while (i < n) {
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
        // Paragraph break: \n\n
        if (text[i] == '\n' && i + 1 < n && text[i + 1] == '\n') {
            // Both block-level fences must be CLOSED at this point for
            // the boundary to be safe. If a fence is open the next chunk
            // could close it with prose that visually belongs in the
            // fence — splitting here would render that prose twice (once
            // as a paragraph in the prefix, then again inside the fence
            // when the boundary moves past the closing ```).
            if (fenceParity == 0 && displayMathParity == 0) {
                // Boundary lives AFTER the second \n so the prefix ends
                // with a clean paragraph break and the tail starts at
                // the first non-newline character of the next paragraph.
                lastSafe = i + 2
            }
            // Skip past additional consecutive newlines — they're all
            // part of the same paragraph break for our purposes.
            i += 2
            while (i < n && text[i] == '\n') {
                lastSafe = i + 1 // consume into the boundary
                i++
            }
            continue
        }
        i++
    }

    return lastSafe
}
