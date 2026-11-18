package com.letta.mobile.ui.markdown

import androidx.compose.runtime.staticCompositionLocalOf
import com.mikepenz.markdown.model.State
import kotlin.jvm.JvmInline

/** A markdown text (a whole message, or one block's source) as the settled parses key it. */
@JvmInline
value class MarkdownSource(val text: String)

/**
 * The parses of settled texts, kept outside the rows (rows leave composition as they scroll away),
 * newest last and at most [capacity] of each kind: the shared renderer's parse, the rich renderer's
 * block document, and its tables. One per chat page, created by the page with its scroll position
 * and provided through [LocalSettledMarkdownParses]; it lives as long as that page's conversation.
 * Composition reads and writes it on the UI thread only. A streaming text is never kept: it changes
 * every token.
 */
internal class SettledMarkdownParses(private val capacity: Int = SETTLED_MARKDOWN_PARSES) {
    private val parses = KeptBySource<State.Success>(capacity)
    private val linkMaps = KeptBySource<Map<String, String>>(capacity)
    private val documents = KeptBySource<StreamingMarkdownDocument>(capacity)
    private val tables = KeptBySource<ParsedMarkdownTable>(capacity)

    operator fun get(source: MarkdownSource): State.Success? = parses[source]

    operator fun contains(source: MarkdownSource): Boolean = source in parses

    /** The reference-style link targets parsed with [source], or empty when it carried none. */
    fun links(source: MarkdownSource): Map<String, String> = linkMaps[source].orEmpty()

    fun keep(parsed: State.Success, links: Map<String, String> = emptyMap()) {
        val source = MarkdownSource(parsed.content)
        parses.keep(source, parsed)
        if (links.isNotEmpty()) linkMaps.keep(source, links) else linkMaps.remove(source)
    }

    /** The rich renderer's block document of [source], when it was kept. */
    fun document(source: MarkdownSource): StreamingMarkdownDocument? = documents[source]

    fun keepDocument(source: MarkdownSource, document: StreamingMarkdownDocument) = documents.keep(source, document)

    /** A table block's parse, by its source, when it was kept. */
    fun table(source: MarkdownSource): ParsedMarkdownTable? = tables[source]

    fun keepTable(source: MarkdownSource, table: ParsedMarkdownTable) = tables.keep(source, table)
}

/** Values by their source, least-recently-used first, the oldest dropped past [capacity]. */
private class KeptBySource<V : Any>(private val capacity: Int) {
    private val kept = LinkedHashMap<MarkdownSource, V>()

    operator fun get(source: MarkdownSource): V? {
        val value = kept.remove(source) ?: return null
        kept[source] = value
        return value
    }

    operator fun contains(source: MarkdownSource): Boolean = source in kept

    fun remove(source: MarkdownSource) {
        kept.remove(source)
    }

    fun keep(source: MarkdownSource, value: V) {
        kept.remove(source)
        kept[source] = value
        while (kept.size > capacity) kept.remove(kept.keys.first())
    }
}

/** The chat page's [SettledMarkdownParses]; null (outside a chat page) parses every time, as before. */
internal val LocalSettledMarkdownParses = staticCompositionLocalOf<SettledMarkdownParses?> { null }

/** How many settled parses of each kind a page keeps: a long conversation's worth of rows to scroll back through. */
private const val SETTLED_MARKDOWN_PARSES = 512
