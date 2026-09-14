package com.letta.mobile.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One syntax-highlight span, renderer-neutral so it can be cached and carried between
 * revisions of the same code.
 */
internal data class CodeSpan(val start: Int, val end: Int, val rgb: Int?, val bold: Boolean)

/** What a fence asks to have highlighted; also the cache key for the finished result. */
internal data class HighlightRequest(val code: String, val language: String?, val darkTheme: Boolean)

/** A piece of code together with the spans that describe it. */
internal data class HighlightedRevision(val code: String, val spans: List<CodeSpan>) {
    companion object {
        val EMPTY = HighlightedRevision("", emptyList())
    }
}

/**
 * Syntax highlighting that never flips.
 *
 * The library's highlighted fence resets to plain text every time the code changes and only
 * restyles once a background job finishes, so a streaming fence relaid out twice per token and a
 * row scrolled back into view drew plain for a frame, then styled: the code box visibly janked
 * the timeline. Here the previous spans stay on screen (carried onto the appended prefix while a
 * fence streams), the recompute is coalesced behind a short delay, and finished results are
 * cached so a fence seen before is styled on its first frame.
 */
@Composable
internal fun rememberHighlightedCode(request: HighlightRequest): AnnotatedString {
    val code = request.code
    val holder = remember { HighlightedCodeHolder() }
    // Keyed by the request: spans remembered for an earlier revision must never style this one,
    // so the first frame of a new revision only sees cached spans or ones carried onto its prefix.
    var spans by remember(request) { mutableStateOf(HighlightedCodeCache.get(request) ?: holder.carry(code)) }
    LaunchedEffect(request) {
        val hit = HighlightedCodeCache.get(request)
        if (hit != null) {
            spans = hit
            holder.commit(HighlightedRevision(code, hit))
            return@LaunchedEffect
        }
        spans = holder.carry(code)
        // The whole wait-and-compute runs on Default and writes the snapshot state from there
        // (a snapshot write is thread-safe), the way the library's produceState did. Resuming on
        // the composition's dispatcher after a background hop tripped Robolectric's
        // wrong-thread check in the recomposition-gate test.
        launch(Dispatchers.Default) {
            // Coalesce: a streaming fence changes every token; the highlight is worth computing
            // once the text has sat still for a beat, not per keystroke of the model.
            delay(HIGHLIGHT_COALESCE_MS)
            val computed = computeCodeSpans(request)
            HighlightedCodeCache.put(request, computed)
            holder.commit(HighlightedRevision(code, computed))
            spans = computed
        }
    }
    return remember(code, spans) { annotate(HighlightedRevision(code, spans)) }
}

/** The fence body: highlighted text on a horizontal scroller, drawn in the markdown text colour. */
@Composable
internal fun HighlightedCodeText(
    request: HighlightRequest,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    Text(
        text = rememberHighlightedCode(request),
        style = style,
        color = LocalMarkdownColors.current.text,
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(LocalMarkdownPadding.current.codeBlock),
    )
}

/**
 * Keeps the last committed revision so the next one can start from it. Committed from the
 * highlight worker and read on the main thread, so the revision is swapped as one volatile value.
 */
internal class HighlightedCodeHolder {
    @Volatile
    private var committed: HighlightedRevision = HighlightedRevision.EMPTY

    fun commit(revision: HighlightedRevision) {
        committed = revision
    }

    /** Spans for [code] before its own highlight exists: the last ones, where they still apply. */
    fun carry(code: String): List<CodeSpan> = carrySpans(committed, code)
}

/**
 * The previous highlight, kept where it is still valid on [code]: when the new code extends the
 * old (a streaming fence), every old span still describes the same characters; otherwise only the
 * unchanged prefix does. Nothing is invented for the new text - it renders plain until computed.
 */
internal fun carrySpans(previous: HighlightedRevision, code: String): List<CodeSpan> {
    if (previous.spans.isEmpty() || previous.code.isEmpty()) return emptyList()
    val common = previous.code.commonPrefixWith(code).length
    if (common == 0) return emptyList()
    return previous.spans.filter { it.end <= common }
}

/**
 * Highlights [request] with a builder of its own: a shared [Highlights.Builder] is mutable, and
 * fences computing concurrently on Default would otherwise race on its code and language.
 */
internal fun computeCodeSpans(request: HighlightRequest): List<CodeSpan> {
    val syntaxLanguage = request.language?.let { SyntaxLanguage.getByName(it) }
    return Highlights.Builder()
        .theme(SyntaxThemes.atom(darkMode = request.darkTheme))
        .code(request.code)
        .let { if (syntaxLanguage != null) it.language(syntaxLanguage) else it }
        .build()
        .getHighlights()
        .map {
            when (it) {
                is ColorHighlight -> CodeSpan(it.location.start, it.location.end, it.rgb, bold = false)
                is BoldHighlight -> CodeSpan(it.location.start, it.location.end, null, bold = true)
            }
        }
}

internal fun annotate(revision: HighlightedRevision): AnnotatedString = buildAnnotatedString {
    val code = revision.code
    append(code)
    for (span in revision.spans) {
        if (span.end > code.length || span.start >= span.end) continue
        val style = if (span.rgb != null) SpanStyle(color = Color(span.rgb).copy(alpha = 1f)) else SpanStyle(fontWeight = FontWeight.Bold)
        addStyle(style, span.start, span.end)
    }
}

/** Finished highlights for fences already seen this process: a row scrolled back into view is styled on frame one. */
internal object HighlightedCodeCache {
    private const val CAPACITY = 96
    private val entries = object : LinkedHashMap<HighlightRequest, List<CodeSpan>>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<HighlightRequest, List<CodeSpan>>): Boolean = size > CAPACITY
    }

    @Synchronized
    fun get(key: HighlightRequest): List<CodeSpan>? = entries[key]

    @Synchronized
    fun put(key: HighlightRequest, spans: List<CodeSpan>) {
        entries[key] = spans
    }

    @Synchronized
    internal fun clearForTest() = entries.clear()
}

private const val HIGHLIGHT_COALESCE_MS = 120L
