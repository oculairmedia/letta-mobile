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
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.State
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import com.letta.mobile.ui.theme.tokens.LettaTextStyles

/**
 * A host-supplied markdown renderer for the shared chat page.
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
 *
 * Retained parser state (the previous AST kept visible while an update parses) is
 * derived here: a settled row keeps it — the no-flicker path across recompositions —
 * while a streaming row does not, because at paint cadence a retained-but-reshaped
 * block can pair stale annotation offsets with new content and crash selectable
 * desktop text. The retention key rebuilds the renderer on any non-prefix change,
 * so a settled row whose text is later reconciled resets instead of retaining.
 */
@Composable
fun SharedMarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    isStreaming: Boolean = false,
) {
    SharedMarkdownText(
        text = text,
        paint = MarkdownPaint(textColor),
        modifier = modifier,
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
    // Retained state paints the previous AST while parsing an update: the no-flicker path for a
    // row that keeps growing. A streaming row instead re-parses per update, where a retained
    // reshaped block could pair stale annotation offsets with new content; and the retention
    // key below rebuilds the renderer on any non-prefix change, so a settled row whose text is
    // reconciled (shortened or replaced) resets instead of retaining stale offsets.
    key(retentionKey) {
        Markdown(
            markdownState = rememberSharedMarkdownState(repaired, retainState = !isStreaming),
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
        code = LettaTextStyles.mono,
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
    // A settled text already parsed once (its row scrolled away and back) starts from that parse:
    // its first frame is its final layout. Parsing it again started from the empty loading box and
    // the renderer grew the text in from the top, so every row replayed an entrance on scroll back.
    val parses = LocalSettledMarkdownParses.current?.takeIf { retainState }
    val settled = parses?.let { kept -> remember(kept, text) { kept[text] } }
    if (settled != null) return remember(settled) { settledMarkdownState(settled) }
    val state = rememberParsedMarkdownState(text, retainState)
    parses?.let { KeepSettledParse(it, state, text) }
    return state
}

@Composable
private fun rememberParsedMarkdownState(text: String, retainState: Boolean): MarkdownState {
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

/** Keeps [state]'s parse of [text] in [parses] once it lands, for the row's next time in composition. */
@Composable
private fun KeepSettledParse(parses: SettledMarkdownParses, state: MarkdownState, text: String) {
    LaunchedEffect(parses, state, text) {
        val parsed = state.state.first { it !is State.Loading }
        if (parsed is State.Success && parsed.content == text) parses.keep(text, parsed)
    }
}

/**
 * The parses of settled texts, kept outside the rows (rows leave composition as they scroll away),
 * newest last and at most [capacity] of them. One per timeline, created by the list's owner and
 * provided through [LocalSettledMarkdownParses]; it lives as long as that timeline. Composition reads
 * and writes it on the UI thread only. A streaming text is never kept: it changes every token.
 */
internal class SettledMarkdownParses(private val capacity: Int = SETTLED_MARKDOWN_PARSES) {
    private val parses = LinkedHashMap<String, State.Success>()

    operator fun get(text: String): State.Success? = parses[text]

    operator fun contains(text: String): Boolean = text in parses

    fun keep(text: String, parsed: State.Success) {
        parses.remove(text)
        parses[text] = parsed
        while (parses.size > capacity) parses.remove(parses.keys.first())
    }
}

/** The timeline's [SettledMarkdownParses]; null (outside a timeline) parses every time, as before. */
internal val LocalSettledMarkdownParses = staticCompositionLocalOf<SettledMarkdownParses?> { null }

/** A state that is already [parsed]: the renderer draws it on its first frame. */
private fun settledMarkdownState(parsed: State.Success): MarkdownState = object : MarkdownState {
    override val state: StateFlow<State> = MutableStateFlow(parsed)
    override val links: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap())

    override suspend fun parse(): State = parsed
}

/** How many settled parses a timeline keeps: a long conversation's worth of rows to scroll back through. */
private const val SETTLED_MARKDOWN_PARSES = 512

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
