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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * One syntax-highlight span, renderer-neutral so it can be cached and carried between
 * revisions of the same code.
 */
internal data class CodeSpan(val start: Int, val end: Int, val rgb: Int?, val bold: Boolean)

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
internal fun rememberHighlightedCode(
    code: String,
    language: String?,
    highlightsBuilder: Highlights.Builder,
    darkTheme: Boolean,
): AnnotatedString {
    val cacheKey = HighlightCacheKey(code, language, darkTheme)
    val holder = remember { HighlightedCodeHolder() }
    val cached = HighlightedCodeCache.get(cacheKey)
    var spans by remember { mutableStateOf(cached ?: holder.carry(code)) }
    LaunchedEffect(cacheKey) {
        val hit = HighlightedCodeCache.get(cacheKey)
        if (hit != null) {
            spans = hit
            holder.commit(code, hit)
            return@LaunchedEffect
        }
        spans = holder.carry(code)
        // Coalesce: a streaming fence changes every token; the highlight is worth computing
        // once the text has sat still for a beat, not per keystroke of the model.
        delay(HIGHLIGHT_COALESCE_MS)
        val computed = withContext(Dispatchers.Default) { computeCodeSpans(code, language, highlightsBuilder) }
        HighlightedCodeCache.put(cacheKey, computed)
        holder.commit(code, computed)
        spans = computed
    }
    return remember(code, spans) { annotate(code, spans) }
}

/** The fence body: highlighted text on a horizontal scroller, drawn in the markdown text colour. */
@Composable
internal fun HighlightedCodeText(
    code: String,
    language: String?,
    style: TextStyle,
    highlightsBuilder: Highlights.Builder,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    val text = rememberHighlightedCode(code, language, highlightsBuilder, darkTheme)
    Text(
        text = text,
        style = style,
        color = LocalMarkdownColors.current.text,
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(LocalMarkdownPadding.current.codeBlock),
    )
}

/** Keeps the last committed (code, spans) so the next revision can start from it. */
internal class HighlightedCodeHolder {
    private var committedCode: String = ""
    private var committedSpans: List<CodeSpan> = emptyList()

    fun commit(code: String, spans: List<CodeSpan>) {
        committedCode = code
        committedSpans = spans
    }

    /** Spans for [code] before its own highlight exists: the last ones, where they still apply. */
    fun carry(code: String): List<CodeSpan> = carrySpans(committedCode, committedSpans, code)
}

/**
 * The previous highlight, kept where it is still valid on [code]: when the new code extends the
 * old (a streaming fence), every old span still describes the same characters; otherwise only the
 * unchanged prefix does. Nothing is invented for the new text - it renders plain until computed.
 */
internal fun carrySpans(previousCode: String, previousSpans: List<CodeSpan>, code: String): List<CodeSpan> {
    if (previousSpans.isEmpty() || previousCode.isEmpty()) return emptyList()
    val common = commonPrefixLength(previousCode, code)
    if (common == 0) return emptyList()
    return previousSpans.filter { it.end <= common }
}

private fun commonPrefixLength(a: String, b: String): Int {
    val n = minOf(a.length, b.length)
    var i = 0
    while (i < n && a[i] == b[i]) i++
    return i
}

internal fun computeCodeSpans(code: String, language: String?, highlightsBuilder: Highlights.Builder): List<CodeSpan> {
    val syntaxLanguage = language?.let { SyntaxLanguage.getByName(it) }
    return highlightsBuilder
        .code(code)
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

internal fun annotate(code: String, spans: List<CodeSpan>): AnnotatedString = buildAnnotatedString {
    append(code)
    for (span in spans) {
        if (span.end > code.length || span.start >= span.end) continue
        val style = if (span.rgb != null) SpanStyle(color = Color(span.rgb).copy(alpha = 1f)) else SpanStyle(fontWeight = FontWeight.Bold)
        addStyle(style, span.start, span.end)
    }
}

internal data class HighlightCacheKey(val code: String, val language: String?, val darkTheme: Boolean)

/** Finished highlights for fences already seen this process: a row scrolled back into view is styled on frame one. */
internal object HighlightedCodeCache {
    private const val CAPACITY = 96
    private val entries = object : LinkedHashMap<HighlightCacheKey, List<CodeSpan>>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<HighlightCacheKey, List<CodeSpan>>): Boolean = size > CAPACITY
    }

    @Synchronized
    fun get(key: HighlightCacheKey): List<CodeSpan>? = entries[key]

    @Synchronized
    fun put(key: HighlightCacheKey, spans: List<CodeSpan>) {
        entries[key] = spans
    }

    @Synchronized
    internal fun clearForTest() = entries.clear()
}

private const val HIGHLIGHT_COALESCE_MS = 120L
