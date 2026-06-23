package com.letta.mobile.ui.components

/**
 * Line-oriented markdown block parser for streaming chat output.
 *
 * This intentionally targets the LLM-output subset we render in chat. It is not a full CommonMark
 * implementation; it is a stable block opener. The rich inline/block rendering still goes through
 * [MarkdownText], while this layer owns block identity and early recognition of structural cues.
 */
internal object StreamingMarkdownDocumentParser {
    private val fenceRegex = Regex("""^(\s{0,3})(`{3,}|~{3,})\s*(.*?)\s*$""")
    private val headingRegex = Regex("""^\s{0,3}#{1,6}(?:\s+.*)?\s*$""")
    private val bulletRegex = Regex("""^(\s*)([-*+])(\s+)(.*)$""")
    private val orderedRegex = Regex("""^(\s*)(\d{1,9})([.)])(\s+)(.*)$""")
    private val blockquoteRegex = Regex("""^\s{0,3}>\s?.*$""")
    private val horizontalRuleRegex = Regex(
        """^\s{0,3}(?:-(?:[ \t]*-){2,}|\*(?:[ \t]*\*){2,}|_(?:[ \t]*_){2,})\s*$""",
    )

    fun parse(text: String): List<ParsedStreamingMarkdownBlock> = parse(text, startOffset = 0)

    fun parse(text: String, startOffset: Int): List<ParsedStreamingMarkdownBlock> {
        if (text.isEmpty()) return emptyList()

        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = mutableListOf<ParsedStreamingMarkdownBlock>()
        var cursor = startOffset.coerceIn(0, normalized.length)
        while (cursor < normalized.length) {
            val line = readLine(normalized, cursor)
            if (line.isBlank) {
                cursor = line.end
                continue
            }

            val fenceMatch = line.fenceMatch
            val parsed = when {
                fenceMatch != null -> parseCodeFence(normalized, line, fenceMatch)
                line.opensDisplayMath() -> parseDisplayMath(normalized, line)
                line.isTableHeaderCandidate(normalized) -> parseTable(normalized, line)
                line.isBulletListItem() -> parseList(normalized, line, StreamingMarkdownBlockKind.BulletList)
                line.isOrderedListItem() -> parseList(normalized, line, StreamingMarkdownBlockKind.OrderedList)
                line.isBlockquote() -> parseRun(normalized, line, StreamingMarkdownBlockKind.Blockquote) { it.isBlockquote() }
                line.isHorizontalRule() -> parseSingleLine(normalized, line, StreamingMarkdownBlockKind.HorizontalRule)
                line.isHeading() -> parseSingleLine(normalized, line, StreamingMarkdownBlockKind.Heading)
                else -> parseParagraph(normalized, line)
            }

            blocks.add(parsed)
            cursor = parsed.startOffset + parsed.source.length
        }

        return blocks
    }

    private fun parseCodeFence(
        text: String,
        opener: MarkdownLine,
        openerMatch: MatchResult,
    ): ParsedStreamingMarkdownBlock {
        val fence = openerMatch.groupValues[2]
        val fenceChar = fence[0]
        val fenceLength = fence.length
        var end = opener.end
        var closed = false

        while (end < text.length) {
            val line = readLine(text, end)
            val closer = line.fenceMatch
            end = line.end
            if (closer != null) {
                val candidate = closer.groupValues[2]
                val info = closer.groupValues[3]
                if (candidate.isNotEmpty() &&
                    candidate[0] == fenceChar &&
                    candidate.length >= fenceLength &&
                    info.isBlank()
                ) {
                    closed = true
                    break
                }
            }
        }

        val finalEnd = if (closed) consumeFollowingBlankLines(text, end) else end
        return block(
            kind = StreamingMarkdownBlockKind.CodeFence,
            text = text,
            start = opener.start,
            end = finalEnd,
            closed = closed,
        )
    }

    private fun parseDisplayMath(
        text: String,
        opener: MarkdownLine,
    ): ParsedStreamingMarkdownBlock {
        val trimmed = opener.content.trim()
        val closePredicate: (MarkdownLine) -> Boolean = when {
            trimmed.startsWith("\\[") -> { line -> line.content.trim() == "\\]" }
            else -> { line -> line.content.trim() == "$$" }
        }

        if (trimmed.hasInlineDisplayMathClose()) {
            return parseSingleLine(text, opener, StreamingMarkdownBlockKind.DisplayMath)
        }

        var end = opener.end
        var closed = false
        while (end < text.length) {
            val line = readLine(text, end)
            end = line.end
            if (closePredicate(line)) {
                closed = true
                break
            }
        }

        val finalEnd = if (closed) consumeFollowingBlankLines(text, end) else end
        return block(
            kind = StreamingMarkdownBlockKind.DisplayMath,
            text = text,
            start = opener.start,
            end = finalEnd,
            closed = closed,
        )
    }

    private fun parseTable(
        text: String,
        firstLine: MarkdownLine,
    ): ParsedStreamingMarkdownBlock {
        var end = firstLine.end
        var lineCount = 1
        while (end < text.length) {
            val line = readLine(text, end)
            if (line.isBlank || !line.content.contains('|')) break
            end = line.end
            lineCount++
        }

        val finalEnd = consumeFollowingBlankLines(text, end)
        return block(
            kind = StreamingMarkdownBlockKind.Table,
            text = text,
            start = firstLine.start,
            end = finalEnd,
            closed = lineCount >= 2,
        )
    }

    private fun parseList(
        text: String,
        firstLine: MarkdownLine,
        kind: StreamingMarkdownBlockKind,
    ): ParsedStreamingMarkdownBlock {
        var end = firstLine.end
        while (end < text.length) {
            val line = readLine(text, end)
            if (line.isBlank) {
                val afterBlanks = consumeFollowingBlankLines(text, end)
                val next = if (afterBlanks < text.length) readLine(text, afterBlanks) else null
                if (next != null && next.isListContinuation(kind)) {
                    end = afterBlanks
                    continue
                }
                end = afterBlanks
                break
            }
            if (line.isListContinuation(kind) || line.isIndentedContinuation()) {
                end = line.end
                continue
            }
            break
        }
        return block(kind = kind, text = text, start = firstLine.start, end = end, closed = true)
    }

    private fun parseRun(
        text: String,
        firstLine: MarkdownLine,
        kind: StreamingMarkdownBlockKind,
        keepGoing: (MarkdownLine) -> Boolean,
    ): ParsedStreamingMarkdownBlock {
        var end = firstLine.end
        while (end < text.length) {
            val line = readLine(text, end)
            if (line.isBlank || !keepGoing(line)) break
            end = line.end
        }
        val finalEnd = consumeFollowingBlankLines(text, end)
        return block(kind = kind, text = text, start = firstLine.start, end = finalEnd, closed = true)
    }

    private fun parseSingleLine(
        text: String,
        line: MarkdownLine,
        kind: StreamingMarkdownBlockKind,
    ): ParsedStreamingMarkdownBlock {
        val end = consumeFollowingBlankLines(text, line.end)
        return block(kind = kind, text = text, start = line.start, end = end, closed = true)
    }

    private fun parseParagraph(
        text: String,
        firstLine: MarkdownLine,
    ): ParsedStreamingMarkdownBlock {
        var end = firstLine.end
        while (end < text.length) {
            val line = readLine(text, end)
            if (line.isBlank) {
                end = consumeFollowingBlankLines(text, end)
                break
            }
            if (line.startsInterruptingBlock()) break
            end = line.end
        }
        return block(
            kind = StreamingMarkdownBlockKind.Paragraph,
            text = text,
            start = firstLine.start,
            end = end,
            closed = end < text.length || text.endsWith('\n'),
        )
    }

    private fun block(
        kind: StreamingMarkdownBlockKind,
        text: String,
        start: Int,
        end: Int,
        closed: Boolean,
    ): ParsedStreamingMarkdownBlock {
        return ParsedStreamingMarkdownBlock(
            kind = kind,
            source = text.substring(start, end),
            startOffset = start,
            closed = closed,
        )
    }

    private fun readLine(text: String, start: Int): MarkdownLine {
        val newline = text.indexOf('\n', start)
        val contentEnd = if (newline < 0) text.length else newline
        val end = if (newline < 0) text.length else newline + 1
        val content = text.substring(start, contentEnd)
        return MarkdownLine(
            start = start,
            contentEnd = contentEnd,
            end = end,
            content = content,
        )
    }

    private fun consumeFollowingBlankLines(text: String, start: Int): Int {
        var cursor = start
        while (cursor < text.length) {
            val line = readLine(text, cursor)
            if (!line.isBlank) break
            cursor = line.end
        }
        return cursor
    }

    private fun MarkdownLine.isTableHeaderCandidate(text: String): Boolean {
        if (!content.contains('|')) return false
        if (end >= text.length) return false
        val next = readLine(text, end)
        return lineLooksLikeTableSeparator(next.content, 0, next.content.length)
    }

    private fun MarkdownLine.startsInterruptingBlock(): Boolean {
        if (fenceMatch != null) return true
        if (opensDisplayMath()) return true
        if (isHeading()) return true
        if (isBlockquote()) return true
        if (isBulletListItem() || isOrderedListItem()) return true
        if (isHorizontalRule()) return true
        return false
    }

    private fun MarkdownLine.opensDisplayMath(): Boolean {
        val trimmed = content.trim()
        return trimmed.startsWith("$$") || trimmed.startsWith("\\[")
    }

    private fun String.hasInlineDisplayMathClose(): Boolean {
        if (startsWith("$$")) return indexOf("$$", startIndex = 2) >= 0
        if (startsWith("\\[")) return contains("\\]")
        return false
    }

    private fun MarkdownLine.isHeading(): Boolean = headingRegex.matchEntire(content) != null

    private fun MarkdownLine.isBulletListItem(): Boolean = bulletRegex.matchEntire(content) != null

    private fun MarkdownLine.isOrderedListItem(): Boolean = orderedRegex.matchEntire(content) != null

    private fun MarkdownLine.isBlockquote(): Boolean = blockquoteRegex.matchEntire(content) != null

    private fun MarkdownLine.isHorizontalRule(): Boolean = horizontalRuleRegex.matchEntire(content) != null

    private fun MarkdownLine.isListContinuation(kind: StreamingMarkdownBlockKind): Boolean {
        return when (kind) {
            StreamingMarkdownBlockKind.BulletList -> isBulletListItem()
            StreamingMarkdownBlockKind.OrderedList -> isOrderedListItem()
            else -> false
        }
    }

    private fun MarkdownLine.isIndentedContinuation(): Boolean {
        val leadingSpaces = content.takeWhile { it == ' ' }.length
        return leadingSpaces >= 2 && content.isNotBlank()
    }

    private val MarkdownLine.fenceMatch: MatchResult?
        get() = fenceRegex.matchEntire(content)

    private data class MarkdownLine(
        val start: Int,
        val contentEnd: Int,
        val end: Int,
        val content: String,
    ) {
        val isBlank: Boolean = content.isBlank()
    }
}
