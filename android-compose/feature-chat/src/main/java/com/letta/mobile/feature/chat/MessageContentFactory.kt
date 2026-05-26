package com.letta.mobile.feature.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.components.MarkdownText
import com.letta.mobile.ui.components.StreamingMarkdownText
import com.letta.mobile.ui.components.rememberReducedMotionEnabled
import com.letta.mobile.ui.theme.LocalChatFontScale
import com.letta.mobile.ui.theme.chatTypography
import com.letta.mobile.ui.theme.scaledBy
import kotlinx.collections.immutable.toImmutableList

internal object GeneratedUiRenderer : MessageContentRenderer {
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
                if (message.role == "assistant") {
                    AssistantResponseText(
                        messageId = message.id,
                        text = message.content,
                        textColor = textColor,
                        isStreaming = isStreaming,
                    )
                } else {
                    MarkdownText(text = message.content, textColor = textColor)
                }
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

internal interface MessageContentRenderer {
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

@Composable
private fun AssistantResponseText(
    messageId: String,
    text: String,
    textColor: Color,
    modifier: Modifier = Modifier,
    isStreaming: Boolean,
) {
    // letta-mobile-yh0c: only treat the message as actively streaming once
    // its text has actually GROWN since first composition. The upstream
    // `isStreaming` flag flips true the moment the user taps Send, even
    // before the optimistic user Local is appended — during that gap,
    // ChatMessageList's per-message `isStreaming = state.isStreaming &&
    // message.id == lastOrNull?.id` momentarily lights up the PRIOR
    // assistant response. Without the growth gate, the smoother engaged
    // for that prior message and visually replayed its full content. By
    // anchoring `hasStreamed` to real text growth, a momentarily-true
    // isStreaming with unchanged text no longer kicks off the smoother.
    var hasStreamed by remember(messageId) { mutableStateOf(false) }
    val initialText = remember(messageId) { text }
    val textHasGrown = text.length > initialText.length
    LaunchedEffect(isStreaming, textHasGrown) {
        if (isStreaming && textHasGrown) hasStreamed = true
    }
    val hasTable = remember(text) { text.containsMarkdownTable() }

    // Gate every "use the streaming renderer / smoother" decision on real
    // text growth, not just the bare `isStreaming` flag. The flag flickers
    // true the moment the user taps Send (before the new user Local is
    // appended), and any path that engages the smoother during that window
    // wipes the prior message's `displayed` back to "" and replays it.
    // `useStreamingRenderer` is the single decision point — short-circuit
    // and smoother gating both flow from it so the renderer choice stays
    // stable across the flicker.
    val useStreamingRenderer = (isStreaming && textHasGrown) || hasStreamed

    if (!useStreamingRenderer && !hasTable) {
        MarkdownText(
            text = text,
            textColor = textColor,
            modifier = modifier,
        )
        return
    }

    val smoothedText = if (useStreamingRenderer) {
        rememberSmoothedStreamingText(
            rawText = text,
            isStreaming = isStreaming && textHasGrown,
        )
    } else {
        text
    }
    // letta-mobile-d9zy.5 (retry): cursor fade-out.
    // `showCursor` decides whether the cursor SHOULD be visible based on
    // whether content is still arriving (isStreaming) or the smoother is
    // still catching up. `cursorAlpha` then tweens 1f → 0f over 500ms when
    // showCursor flips false so the cursor fades out instead of hard-cutting.
    // We keep the cursor character in the tree (cursorText non-null) until
    // the alpha actually reaches zero, otherwise the span would disappear
    // before the fade finishes.
    val showCursor = isStreaming || smoothedText != text
    val cursorEligible = shouldShowStreamingCursor(smoothedText)
    val targetCursorVisible = showCursor && cursorEligible
    val reducedMotion = rememberReducedMotionEnabled()
    val cursorAlpha by animateFloatAsState(
        targetValue = if (targetCursorVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = when {
                targetCursorVisible -> 0
                reducedMotion -> 0
                else -> 500
            },
            easing = LinearEasing,
        ),
        label = "streaming_cursor_alpha",
    )
    val displayCursor = targetCursorVisible || cursorAlpha > 0.001f
    // Keep the same streaming renderer for messages that streamed in this composition even after
    // the smoother catches up. Swapping to settled MarkdownText at stream termination causes a
    // final parsed-subtree/spacing handoff flash; hydrated messages still use MarkdownText because
    // hasStreamed is false for them. Once caught up after stream end, hide the cursor while keeping
    // the stable streaming layout.
    // letta-mobile-9hcg.b: pass an effectively-streaming bit that stays
    // true while the smoother is still catching up. Without this, the
    // moment `isStreaming` flips false the height animation collapses
    // mid-catch-up and the bubble flashes back to the smoothed length.
    val effectivelyStreaming = isStreaming || smoothedText != text
    StreamingMarkdownText(
        text = smoothedText,
        textColor = textColor,
        tailStyle = MaterialTheme.chatTypography.messageBody,
        tailTransform = ::streamingDisplayText,
        cursorText = if (displayCursor) STREAMING_CURSOR else null,
        cursorAlpha = cursorAlpha,
        deferUnstableMarkdown = showCursor,
        stabilizeTables = hasStreamed || hasTable,
        isStreaming = effectivelyStreaming,
        modifier = modifier,
    )
}

private fun String.containsMarkdownTable(): Boolean {
    val lines = lineSequence().filter { it.isNotBlank() }.toList()
    if (lines.size < 2) return false

    for (i in 0 until lines.lastIndex) {
        if (lines[i].contains('|') && lines[i + 1].looksLikeMarkdownTableSeparator()) {
            return true
        }
    }
    return false
}

private fun String.looksLikeMarkdownTableSeparator(): Boolean {
    val cells = trim()
        .trim('|')
        .split('|')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (cells.isEmpty()) return false
    return cells.all { cell ->
        val withoutAlignment = cell.trim(':')
        withoutAlignment.isNotEmpty() && withoutAlignment.all { it == '-' }
    }
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
private const val MAX_HELD_TAIL_CHARS = 24

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
    // mid-construct, render only the markdown-stable PREFIX. The held
    // tail will be released on the next paint tick (≤50ms) once we can
    // prove it's either complete or no longer dangling.
    //
    // We are intentionally CONSERVATIVE — we'd rather hold a
    // few extra chars than display unconverted markup. The held
    // region is small (typically <30 chars) so the visual lag is
    // imperceptible.
    //
    // Inside an open ``` fence we skip the clamp entirely (whitespace
    // is meaningful in code, and the parser already renders the
    // partial fence as a code block — no flicker).
    //
    // letta-mobile-d9zy.5: cursor injection moved out of this function.
    // [StreamingMarkdownText] now owns cursor rendering so the cursor
    // glyph can fade independently via cursorAlpha. Empty input still
    // returns empty so the renderer short-circuits to no render at all
    // (the typing indicator already covers the pre-content state).
    if (raw.isEmpty()) return ""
    if (insideOpenCodeFence(raw)) {
        return raw
    }
    return clampToWordBoundary(clampToStableMarkdown(raw))
}

private fun clampToWordBoundary(raw: String): String {
    if (raw.isEmpty()) return raw
    if (raw.last().isStreamingBoundary()) return raw
    val boundary = raw.indexOfLast { it.isStreamingBoundary() }
    if (boundary < 0) return raw
    if (raw.length - boundary - 1 > MAX_HELD_TAIL_CHARS) return raw
    return raw.substring(0, boundary + 1)
}

private fun Char.isStreamingBoundary(): Boolean =
    isWhitespace() || this in setOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '—', '-', '/', '\\')

/**
 * Whether a streaming cursor glyph is appropriate for the supplied raw
 * text. Returns false for empty text (would flash a lone cursor before
 * any chunks arrive) and inside open ``` code fences (would render as
 * literal content inside the code block).
 *
 * Companion to [streamingDisplayText]; callers pair the two so the
 * markdown-stability clamp and cursor-eligibility decision agree about
 * the in-fence carve-out.
 */
internal fun shouldShowStreamingCursor(raw: String): Boolean =
    raw.isNotEmpty() && !insideOpenCodeFence(raw)

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

internal object TextMessageRenderer : MessageContentRenderer {
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
        } else {
            AssistantResponseText(
                messageId = message.id,
                text = message.content,
                textColor = textColor,
                modifier = modifier,
                isStreaming = isStreaming,
            )
        }
    }
}

internal object ToolCallRenderer : MessageContentRenderer {
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
                if (message.role == "assistant") {
                    AssistantResponseText(
                        messageId = message.id,
                        text = message.content,
                        textColor = textColor,
                        isStreaming = isStreaming,
                    )
                } else {
                    MarkdownText(text = message.content, textColor = textColor)
                }
            }
            message.toolCalls?.takeIf { it.isNotEmpty() }?.let { toolCalls ->
                // Wrap at the call-site so MessageToolCalls receives a stable
                // ImmutableList param (o7ob.2.6). UiMessage still uses raw List
                // to avoid rippling the migration through MessageMapper.
                val stableToolCalls = remember(toolCalls) {
                    toolCalls.toImmutableList()
                }
                MessageToolCalls(
                    toolCalls = stableToolCalls,
                    messageId = message.id,
                    animateEntrance = shouldAnimateToolCallEntrance(isStreaming),
                    approvalRequest = message.approvalRequest,
                )
            }
        }
    }
}

internal fun shouldAnimateToolCallEntrance(isStreaming: Boolean): Boolean = isStreaming

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

internal val defaultRenderers = listOf(GeneratedUiRenderer, ToolCallRenderer, TextMessageRenderer)

internal fun resolveRenderer(message: UiMessage): MessageContentRenderer {
    return defaultRenderers.firstOrNull { it.canRender(message) } ?: TextMessageRenderer
}
