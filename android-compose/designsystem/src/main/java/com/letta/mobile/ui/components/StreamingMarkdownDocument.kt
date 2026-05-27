package com.letta.mobile.ui.components

/**
 * Pure streaming markdown document model used by [StreamingMarkdownText].
 *
 * The model is deliberately renderer-agnostic: parsing produces stable block nodes, and Compose
 * decides how to draw each block. This mirrors evented streaming markdown parsers such as
 * `streaming-markdown`: a syntax cue opens a typed node immediately, text appends to that node,
 * and existing completed nodes keep their identity.
 */
internal data class StreamingMarkdownDocument(
    val blocks: List<StreamingMarkdownDocumentBlock>,
) {
    fun stableHeightToken(
        isStreaming: Boolean,
        activeLineCount: Int? = null,
    ): String {
        val stableBlocks = if (isStreaming && blocks.isNotEmpty()) {
            blocks.dropLast(1)
        } else {
            blocks
        }
        val stableToken = stableBlocks.joinToString(separator = "|") { it.key }
        val activeToken = activeLineCount
            ?.takeIf { isStreaming && blocks.isNotEmpty() }
            ?.let { lineCount -> "${blocks.last().key}:lines=$lineCount" }
        return listOfNotNull(stableToken.takeIf { it.isNotEmpty() }, activeToken)
            .joinToString(separator = "|")
    }
}

internal enum class StreamingMarkdownBlockKind {
    Paragraph,
    Heading,
    CodeFence,
    DisplayMath,
    Blockquote,
    BulletList,
    OrderedList,
    Table,
    HorizontalRule,
}

internal data class StreamingMarkdownDocumentBlock(
    val id: Long,
    val kind: StreamingMarkdownBlockKind,
    val source: String,
    val startOffset: Int,
    val closed: Boolean,
) {
    val key: String = "smd-$id"
}

internal val StreamingMarkdownDocumentBlock.allowsInlineCursor: Boolean
    get() = when (kind) {
        StreamingMarkdownBlockKind.CodeFence,
        StreamingMarkdownBlockKind.DisplayMath,
        StreamingMarkdownBlockKind.Table -> false
        else -> true
    }

internal fun StreamingMarkdownDocumentBlock.renderMarkdownSource(sourceOverride: String = source): String {
    return repairIncompleteMarkdownForStreaming(sourceOverride)
}

internal fun StreamingMarkdownDocumentBlock.supportsPlainTextHeightPrediction(
    sourceOverride: String = source,
): Boolean =
    kind == StreamingMarkdownBlockKind.Paragraph &&
        sourceOverride.isPlainStreamingProse()

private fun String.isPlainStreamingProse(): Boolean {
    if (isBlank()) return false
    return none { char -> char in markdownLayoutChangingChars }
}

private val markdownLayoutChangingChars = setOf(
    '`',
    '*',
    '_',
    '[',
    ']',
    '#',
    '|',
    '~',
    '<',
)

/**
 * Stateful identity layer around the pure parser.
 *
 * Callers may either feed full text via [update] or append chunks via [write]. Append-only updates
 * preserve block ids and object identity for unchanged blocks; non-append edits reset identity so
 * hydrated or replaced messages do not accidentally inherit old keys.
 */
internal class StreamingMarkdownDocumentState {
    private var previousRawText: String = ""
    private var previousBlocks: List<StreamingMarkdownDocumentBlock> = emptyList()
    private var nextId: Long = 1L

    fun write(chunk: String): StreamingMarkdownDocument {
        return update(previousRawText + chunk)
    }

    fun update(text: String): StreamingMarkdownDocument {
        if (!text.startsWith(previousRawText)) {
            previousBlocks = emptyList()
        }

        val parsed = StreamingMarkdownDocumentParser.parse(text)
        val reconciled = parsed.mapIndexed { index, block ->
            reconcile(index, block)
        }

        previousRawText = text
        previousBlocks = reconciled
        return StreamingMarkdownDocument(reconciled)
    }

    fun reset() {
        previousRawText = ""
        previousBlocks = emptyList()
        nextId = 1L
    }

    private fun reconcile(
        index: Int,
        parsed: ParsedStreamingMarkdownBlock,
    ): StreamingMarkdownDocumentBlock {
        val previous = previousBlocks.getOrNull(index)
            ?.takeIf { it.kind == parsed.kind && it.startOffset == parsed.startOffset }

        if (previous != null &&
            previous.source == parsed.source &&
            previous.closed == parsed.closed
        ) {
            return previous
        }

        return StreamingMarkdownDocumentBlock(
            id = previous?.id ?: nextId++,
            kind = parsed.kind,
            source = parsed.source,
            startOffset = parsed.startOffset,
            closed = parsed.closed,
        )
    }
}

internal data class ParsedStreamingMarkdownBlock(
    val kind: StreamingMarkdownBlockKind,
    val source: String,
    val startOffset: Int,
    val closed: Boolean,
)
