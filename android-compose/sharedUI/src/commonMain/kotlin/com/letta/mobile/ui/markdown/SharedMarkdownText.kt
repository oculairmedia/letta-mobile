package com.letta.mobile.ui.markdown

import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.rememberMarkdownState
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * A host-supplied markdown renderer for the shared chat page (letta-mobile-bglj6.1.16).
 *
 * The default shared renderer is the stock library paint. A host that owns a richer renderer —
 * Android binds the designsystem one the legacy chat used, with syntax-highlighted code fences
 * carrying a language header and copy button, KaTeX math, Mermaid diagrams, autolinked bare URLs
 * and the editorial block padding — provides it here and the shared rows render through it
 * instead. Desktop and web keep the default. [isStreaming] marks a message whose text is still
 * landing; a rich renderer may own the reveal pacing, cursor and settle animation itself.
 */
fun interface SharedRichMarkdownRenderer {
    @Composable
    fun Render(text: String, paint: MarkdownPaint, isStreaming: Boolean, modifier: Modifier)
}

val LocalSharedRichMarkdownRenderer = staticCompositionLocalOf<SharedRichMarkdownRenderer?> { null }

/**
 * Common Android/Desktop Markdown paint adapter.
 *
 * Mobile keeps its mature extended renderer for math, Mermaid, images, and
 * accessibility while both platforms consume the extracted semantic document
 * model. Desktop uses this common renderer directly instead of raw Compose Text.
 */
@Composable
fun SharedMarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    retainState: Boolean = true,
    isStreaming: Boolean = false,
) {
    SharedMarkdownText(
        text = text,
        paint = MarkdownPaint(textColor),
        modifier = modifier,
        retainState = retainState,
        isStreaming = isStreaming,
    )
}

/** How a markdown body paints: its text colour, and its body type. */
@Immutable
data class MarkdownPaint(
    val textColor: Color,
    /**
     * The body's style, for paragraphs and lists alike (a speech bubble's smaller type); null keeps
     * the timeline's (bodyMedium text, the renderer's bodyLarge paragraphs and lists).
     */
    val textStyle: TextStyle? = null,
)

/** [SharedMarkdownText] painted by [paint]. */
@Composable
fun SharedMarkdownText(
    text: String,
    paint: MarkdownPaint,
    modifier: Modifier = Modifier,
    retainState: Boolean = true,
    isStreaming: Boolean = false,
) {
    if (text.isBlank()) return
    val rich = LocalSharedRichMarkdownRenderer.current
    if (rich != null) {
        rich.Render(text = text, paint = paint, isStreaming = isStreaming, modifier = modifier)
        return
    }
    val repaired = remember(text) { repairIncompleteMarkdownForStreaming(text) }
    val retentionTracker = remember { MarkdownRetentionTracker() }
    val retentionKey = retentionTracker.update(text)
    val components = rememberSharedMarkdownComponents(text)
    // The renderer's retained state intentionally paints the previous AST while
    // parsing an update. That is safe for append-only streaming, but a final
    // reconciliation can shorten or replace the text. In that case old AST
    // offsets may exceed the new content length and crash selectable desktop
    // text in ParagraphBuilder. Reset only on non-prefix changes so ordinary
    // streaming keeps the no-flicker retained-state path.
    key(retentionKey) {
        Markdown(
            markdownState = rememberSharedMarkdownState(repaired, retainState),
            modifier = modifier.fillMaxWidth(),
            components = components,
            colors = sharedMarkdownColors(paint.textColor),
            typography = sharedMarkdownTypography(paint.textStyle),
        )
    }
}

@Composable
private fun sharedMarkdownColors(textColor: Color): MarkdownColors {
    return markdownColor(
        text = textColor,
        codeBackground = MaterialTheme.colorScheme.surfaceVariant,
        inlineCodeBackground = MaterialTheme.colorScheme.surfaceVariant,
        dividerColor = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** The timeline's markdown type; a [bodyStyle] replaces the body text, paragraphs and lists. */
@Composable
private fun sharedMarkdownTypography(bodyStyle: TextStyle?): MarkdownTypography {
    val body = bodyStyle ?: MaterialTheme.typography.bodyLarge
    return markdownTypography(
        text = bodyStyle ?: MaterialTheme.typography.bodyMedium,
        code = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        h1 = MaterialTheme.typography.headlineSmall,
        h2 = MaterialTheme.typography.titleLarge,
        h3 = MaterialTheme.typography.titleMedium,
        h4 = MaterialTheme.typography.titleSmall,
        h5 = MaterialTheme.typography.bodyLarge,
        h6 = MaterialTheme.typography.bodyMedium,
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
    )
}

/**
 * The parse of [text], kept for as long as the text is. The renderer's content overload builds its
 * flavour, parser and link store as default arguments, new on every composition, and a new one
 * re-parses: any recomposition of a row (a pinch's font scale, a colour, a parent re-reading
 * state) dropped the text to the empty loading box until the parse came back off the UI thread.
 * The text blinked, and a held selection, with its Copy / Select all toolbar, went with it.
 */
@Composable
internal fun rememberSharedMarkdownState(text: String, retainState: Boolean): MarkdownState {
    val flavour = remember { GFMFlavourDescriptor() }
    val parser = remember(flavour) { MarkdownParser(flavour) }
    val links = remember { ReferenceLinkHandlerImpl() }
    return rememberMarkdownState(
        content = text,
        retainState = retainState,
        flavour = flavour,
        parser = parser,
        referenceLinkHandler = links,
    )
}

/**
 * Tracks when retained parser state can safely survive a content update.
 *
 * This is deliberately a plain remembered object rather than Compose state:
 * the returned revision is only a structural key for the renderer subtree.
 */
internal class MarkdownRetentionTracker {
    private var previousText: String? = null
    private var revision: Int = 0

    fun update(text: String): Int {
        val previous = previousText
        if (previous != null && !text.startsWith(previous)) revision++
        previousText = text
        return revision
    }
}
