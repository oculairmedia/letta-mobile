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
private const val MAX_HELD_TAIL_CHARS = 80
private val WORD_BOUNDARY_CHARS = setOf('.', ',', ';', ':', '!', '?', ')', '\'', '"', '`', ']', '}', '>')

internal fun streamingDisplayText(raw: String): String {
    if (raw.isEmpty()) return STREAMING_CURSOR
    if (insideOpenCodeFence(raw)) {
        // Inside an open ``` fence — leave content alone, no cursor
        // (would render as literal text inside the code block).
        return raw
    }
    // Find the last word/sentence boundary.
    var boundary = -1
    for (i in raw.indices.reversed()) {
        val c = raw[i]
        if (c.isWhitespace() || c in WORD_BOUNDARY_CHARS) {
            boundary = i
            break
        }
    }
    if (boundary < 0) {
        // No boundary at all in the entire text (very short stream or
        // long unbroken token). Render as-is to avoid permanent stall.
        return raw + STREAMING_CURSOR
    }
    val heldTailLen = raw.length - (boundary + 1)
    return if (heldTailLen > MAX_HELD_TAIL_CHARS) {
        // Tail exceeds max-hold — emit everything to keep UI moving.
        raw + STREAMING_CURSOR
    } else {
        raw.substring(0, boundary + 1) + STREAMING_CURSOR
    }
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
