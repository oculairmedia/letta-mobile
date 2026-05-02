package com.letta.mobile.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.components.MarkdownText
import com.letta.mobile.ui.components.StreamingMarkdownText
import com.letta.mobile.ui.theme.LocalChatFontScale
import com.letta.mobile.ui.theme.chatTypography
import com.letta.mobile.ui.theme.scaledBy
import kotlinx.collections.immutable.toImmutableList

object GeneratedUiRenderer : MessageContentRenderer {
    override fun canRender(message: UiMessage): Boolean = message.generatedUi != null

    @Composable
    override fun Render(
        message: UiMessage,
        textColor: Color,
        modifier: Modifier,
        onGeneratedUiMessage: ((String) -> Unit)?,
        isStreaming: Boolean,
    ) {
        val generatedUi = message.generatedUi ?: return
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (message.content.isNotBlank()) {
                val displayText = if (isStreaming && message.role == "assistant") {
                    val smoothed = rememberSmoothedStreamingText(
                        rawText = message.content,
                        isStreaming = true,
                    )
                    streamingDisplayText(smoothed)
                } else {
                    message.content
                }
                MarkdownText(text = displayText, textColor = textColor)
            }

            val renderer = GeneratedUiRegistry.resolve(generatedUi.name)
            if (renderer != null) {
                renderer.Render(
                    component = generatedUi,
                    onGeneratedUiMessage = onGeneratedUiMessage,
                )
            } else {
                GeneratedUiFallbackCard(component = generatedUi)
            }
        }
    }
}

interface MessageContentRenderer {
    fun canRender(message: UiMessage): Boolean

    @Composable
    fun Render(
        message: UiMessage,
        textColor: Color,
        modifier: Modifier,
        onGeneratedUiMessage: ((String) -> Unit)? = null,
        isStreaming: Boolean = false,
    )
}

/**
 * letta-mobile-6p4o.1 — perceived garbling fix (A + C).
 *
 * Background: lettabot (and Letta direct) emit assistant deltas at
 * arbitrary character boundaries — frequently mid-word. Wire-level
 * trace from `:cli wsstream` shows splits like
 *   frame N:   "...suspension is cheap, no O"
 *   frame N+1: "S thread per coroutine\n- ..."
 * The merge math is correct, but the user briefly sees `"...no O"` on
 * screen between recompositions. That's the "garbled text" reported in
 * letta-mobile-6p4o.
 *
 * Fix:
 *  - **A**: while streaming, render only the prefix up to the last
 *    whitespace/punctuation — i.e. hold the trailing partial-word
 *    until the next chunk completes it. When `isStreaming = false`,
 *    render the full text.
 *  - **C**: append a thin vertical bar (`▎`) at the cursor position so
 *    partial text reads as "in progress" rather than "broken".
 *
 * Long-tail safety:
 *  - If the unrendered trailing fragment grows past
 *    `MAX_HELD_TAIL_CHARS`, emit it anyway. Avoids stalling on long
 *    base64-/URL-/non-whitespace chunks.
 *  - Inside an unclosed fenced code block we skip the cursor (it'd be
 *    rendered as literal text inside the code) and skip the clamp
 *    (whitespace inside code blocks is meaningful and we don't want to
 *    swallow it).
 */
private const val STREAMING_CURSOR = "\u258E" // ▎ LEFT VERTICAL BAR

internal fun streamingDisplayText(raw: String): String {
    // letta-mobile-flk2 (revision 11): markdown-stability clamp.
    //
    // Background: mikepenz's renderer parses the full text on every
    // re-emission. While streaming, the latest tail almost always
    // contains an INCOMPLETE markdown construct — an open `**`, an
    // open `` ` ``, an open `[link`, etc. The parser refuses to
    // commit partial syntax: it falls back to rendering those bytes
    // as literal text. A few ms (one paint tick) later the closer
    // arrives in the next chunk and the parser re-parses, this time
    // emitting the formatted markup. The user sees: raw asterisks
    // and brackets briefly, then they "snap in" to bold/italic/link.
    // Emmanuel reported this as "flashing the content that streamed
    // in but hasn't been markdownified yet".
    //
    // Mitigation: hold back the trailing region that LOOKS like it's
    // mid-construct, render only the markdown-stable PREFIX, and
    // append the cursor at the prefix boundary. The held tail will
    // be released on the next paint tick (≤50ms) once we can prove
    // it's either complete or no longer dangling.
    //
    // We are intentionally CONSERVATIVE — we'd rather hold a
    // few extra chars than display unconverted markup. The held
    // region is small (typically <30 chars) so the visual lag is
    // imperceptible; the cursor still advances within those bounds.
    //
    // Inside an open ``` fence we skip the clamp entirely (whitespace
    // is meaningful in code, and the parser already renders the
    // partial fence as a code block — no flicker).
    // letta-mobile-flk2 (revision 11): suppress cursor-only render
    // before any chunks have arrived. Previously empty text returned
    // STREAMING_CURSOR, so a fresh assistant bubble flashed a lone
    // cursor character for one paint tick before content arrived.
    // Emmanuel reported this as "a flash before streaming starts
    // when I send a prompt". Returning empty makes
    // StreamingMarkdownText short-circuit to no render at all
    // (the typing indicator already covers the pre-content state).
    if (raw.isEmpty()) return ""
    if (insideOpenCodeFence(raw)) {
        // Inside an open ``` fence — leave content alone, no cursor
        // (would render as literal text inside the code block).
        return raw
    }
    val safe = clampToStableMarkdown(raw)
    return safe + STREAMING_CURSOR
}

/**
 * Returns the longest prefix of [raw] that does NOT end inside an
 * open markdown construct. The returned prefix is what the renderer
 * can safely re-parse without producing a "raw markup flash" when
 * the construct's closer arrives in a later chunk.
 *
 * Constructs we hold back:
 *  - Trailing `*`/`**`/`***` with no matching closer in the
 *    *current line* (could be opening italic/bold).
 *  - Trailing `_`/`__` with no matching closer in the current line.
 *  - Trailing `` ` `` with no matching closer in the current line.
 *  - Trailing `~~` with no matching closer.
 *  - Trailing `[...]` link text without a `(url)` yet.
 *  - Trailing partial autolink/URL not yet ended by space/newline.
 *
 * The clamp scans only the LAST line — most markdown constructs
 * (emphasis, code spans, links) close on the same line, so we don't
 * need to look back further. Block constructs (lists, headings,
 * paragraphs) are stable as soon as their content tokens land; the
 * parser handles those incrementally without flashing.
 */
private fun clampToStableMarkdown(raw: String): String {
    val lastBreak = raw.lastIndexOf('\n')
    val lineStart = if (lastBreak < 0) 0 else lastBreak + 1
    val line = raw.substring(lineStart)
    if (line.isEmpty()) return raw

    // Scan for unmatched span openers in this line. Track whether the
    // last unmatched opener exists; if so, return raw clipped to BEFORE
    // that opener.
    val unmatchedOpenIdx = findUnmatchedOpenerInLine(line)
    if (unmatchedOpenIdx >= 0) {
        return raw.substring(0, lineStart + unmatchedOpenIdx)
    }
    return raw
}

/**
 * Returns the index (relative to [line]) of the FIRST unmatched
 * span opener — the position where a closer hasn't yet arrived.
 * Returns -1 if the line is fully balanced.
 *
 * Tokens checked: `***`, `**`, `*`, `___`, `__`, `_`, `` `` ``,
 * `` ` ``, `~~`, `[`.
 *
 * Implementation note: we use a simple stack-based scanner. When we
 * see an opener whose matching closer isn't found later in the line,
 * we record its index. If multiple unmatched openers exist we return
 * the EARLIEST one, since clipping there hides all of them.
 */
private fun findUnmatchedOpenerInLine(line: String): Int {
    var earliestUnmatched = -1
    var i = 0
    val len = line.length
    while (i < len) {
        val c = line[i]
        when (c) {
            '`' -> {
                // Inline code: find matching ` (single backtick) or
                // ``...`` style. We only handle single-backtick spans
                // here — multi-backtick is rare in prose.
                val close = line.indexOf('`', startIndex = i + 1)
                if (close < 0) {
                    if (earliestUnmatched < 0) earliestUnmatched = i
                    break
                }
                i = close + 1
            }
            '*', '_' -> {
                // Count run length
                var run = 1
                while (i + run < len && line[i + run] == c) run++
                // Look for a matching closer of run length later in the line.
                // For simplicity treat any later run of >= our length as a closer.
                val searchFrom = i + run
                val closerIdx = findEmphasisCloser(line, searchFrom, c, run)
                if (closerIdx < 0) {
                    if (earliestUnmatched < 0) earliestUnmatched = i
                    // Stop scanning — anything after is moot since we
                    // already need to clip here.
                    break
                }
                i = closerIdx + run
            }
            '~' -> {
                if (i + 1 < len && line[i + 1] == '~') {
                    val close = line.indexOf("~~", startIndex = i + 2)
                    if (close < 0) {
                        if (earliestUnmatched < 0) earliestUnmatched = i
                        break
                    }
                    i = close + 2
                } else {
                    i++
                }
            }
            '[' -> {
                // Look for closing `]` then `(...)` in the same line.
                val closeBracket = line.indexOf(']', startIndex = i + 1)
                if (closeBracket < 0 || closeBracket + 1 >= len ||
                    line[closeBracket + 1] != '(') {
                    if (earliestUnmatched < 0) earliestUnmatched = i
                    break
                }
                val closeParen = line.indexOf(')', startIndex = closeBracket + 2)
                if (closeParen < 0) {
                    if (earliestUnmatched < 0) earliestUnmatched = i
                    break
                }
                i = closeParen + 1
            }
            else -> i++
        }
    }
    return earliestUnmatched
}

private fun findEmphasisCloser(line: String, from: Int, marker: Char, runLen: Int): Int {
    var i = from
    val len = line.length
    while (i < len) {
        if (line[i] == marker) {
            var run = 1
            while (i + run < len && line[i + run] == marker) run++
            if (run >= runLen) return i
            i += run
        } else {
            i++
        }
    }
    return -1
}

/** True if the text contains an odd number of ``` fences (i.e. a code block is currently open). */
private fun insideOpenCodeFence(text: String): Boolean {
    var count = 0
    var i = 0
    while (i <= text.length - 3) {
        if (text[i] == '`' && text[i + 1] == '`' && text[i + 2] == '`') {
            count++
            i += 3
        } else {
            i++
        }
    }
    return count % 2 == 1
}

object TextMessageRenderer : MessageContentRenderer {
    override fun canRender(message: UiMessage) =
        message.role == "user" || (message.role == "assistant" && message.toolCalls.isNullOrEmpty())

    @Composable
    override fun Render(
        message: UiMessage,
        textColor: Color,
        modifier: Modifier,
        onGeneratedUiMessage: ((String) -> Unit)?,
        isStreaming: Boolean,
    ) {
        if (message.role == "user") {
            Text(
                text = message.content,
                style = MaterialTheme.chatTypography.messageBody,
                color = textColor,
                modifier = modifier,
            )
        } else if (isStreaming) {
            // letta-mobile-c8of (ALT-2): boundary-aware incremental
            // markdown streaming. The committed prefix (everything up to
            // the last safe paragraph/closed-fence boundary) renders
            // through the full MarkdownText pipeline so lists, headings,
            // bold, inline code etc. format LIVE during streaming. Only
            // the in-progress paragraph (the tail after the last \n\n)
            // renders as plain Text — and that tail still gets the
            // letta-mobile-6p4o.1 word-boundary holdback + streaming
            // cursor via tailTransform = ::streamingDisplayText.
            //
            // Why this kills the d2z6 stream-end "snap": the prior
            // architecture rendered the ENTIRE bubble as plain Text
            // during streaming, then swapped to MarkdownText on settle —
            // a visible reflow as plain-text layout (literal \n\n,
            // unindented lists) became formatted markdown layout. With
            // boundary-aware streaming, the prefix is ALREADY rendered
            // as MarkdownText throughout streaming, so on settle only
            // the final paragraph's tail gets promoted into the prefix.
            // No layout swap, no snap.
            //
            // Why a paragraph-cadence prefix re-render is cheap: chunks
            // typically arrive every 80–150ms; new paragraph boundaries
            // arrive every ~1s. The prefix only re-parses on boundary
            // advances, which happens at PARAGRAPH cadence (~once per second) rather than
            // chunk cadence (~10/sec).
            //
            // d2z6 plain-Text fallback preserved by setting tailTransform
            // — if anything mid-paragraph regresses, the visible content
            // is still the same string the user saw before this change.
            //
            // d2z6.s1 stream-smoothing: the smoother meters out bursty
            // arrivals at a steady per-character cadence before they
            // reach the boundary splitter. StreamingMarkdownText still
            // gets the same tailTransform for word-boundary holdback +
            // cursor.
            val smoothedText = rememberSmoothedStreamingText(
                rawText = message.content,
                isStreaming = true,
            )
            StreamingMarkdownText(
                text = smoothedText,
                textColor = textColor,
                tailStyle = MaterialTheme.chatTypography.messageBody,
                tailTransform = ::streamingDisplayText,
                cursorText = STREAMING_CURSOR,
                modifier = modifier,
            )
        } else {
            val displayText = if (isStreaming) {
                streamingDisplayText(message.content)
            } else {
                message.content
            }
            MarkdownText(
                text = displayText,
                textColor = textColor,
                modifier = modifier,
            )
        }
    }
}

object ToolCallRenderer : MessageContentRenderer {
    override fun canRender(message: UiMessage) =
        !message.toolCalls.isNullOrEmpty()

    @Composable
    override fun Render(
        message: UiMessage,
        textColor: Color,
        modifier: Modifier,
        onGeneratedUiMessage: ((String) -> Unit)?,
        isStreaming: Boolean,
    ) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (message.content.isNotBlank()) {
                val displayText = if (isStreaming && message.role == "assistant") {
                    val smoothed = rememberSmoothedStreamingText(
                        rawText = message.content,
                        isStreaming = true,
                    )
                    streamingDisplayText(smoothed)
                } else {
                    message.content
                }
                MarkdownText(text = displayText, textColor = textColor)
            }
            message.toolCalls?.takeIf { it.isNotEmpty() }?.let { toolCalls ->
                // Wrap at the call-site so MessageToolCalls receives a stable
                // ImmutableList param (o7ob.2.6). UiMessage still uses raw List
                // to avoid rippling the migration through MessageMapper.
                val stableToolCalls = remember(toolCalls) {
                    toolCalls.toImmutableList()
                }
                MessageToolCalls(toolCalls = stableToolCalls)
            }
        }
    }
}

@Composable
private fun GeneratedUiFallbackCard(component: com.letta.mobile.data.model.UiGeneratedComponent) {
    val fontScale = LocalChatFontScale.current
    GeneratedUiCard(title = component.name) {
        component.fallbackText?.takeIf { it.isNotBlank() }?.let {
            Text(text = it, style = MaterialTheme.typography.bodyMedium.scaledBy(fontScale))
        }
        Text(
            text = component.propsJson,
            style = MaterialTheme.typography.bodySmall.scaledBy(fontScale),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

val defaultRenderers = listOf(GeneratedUiRenderer, ToolCallRenderer, TextMessageRenderer)

fun resolveRenderer(message: UiMessage): MessageContentRenderer {
    return defaultRenderers.firstOrNull { it.canRender(message) } ?: TextMessageRenderer
}
