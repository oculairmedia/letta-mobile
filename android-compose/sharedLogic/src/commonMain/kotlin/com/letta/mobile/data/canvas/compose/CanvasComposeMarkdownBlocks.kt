package com.letta.mobile.data.canvas.compose

/** The block structure of [CanvasComposeMarkdown], line by line; inline syntax is left to [MdInlineReader]. */
internal class MdBlockReader(private val lines: List<SourceLine>, private val problems: MdProblems) {
    private val blocks = mutableListOf<MdBlock>()

    private var paragraph: MdInlineSource? = null
    private var quote: MdInlineSource? = null

    // The open list item, if any, and the list around it.
    private var item: PendingItem? = null
    private var inList = false
    private var blankInList = false
    private var parentContentColumn = 0
    private var childContentColumn: Int? = null
    private val runLength = IntArray(CanvasComposeMarkdown.MAX_LIST_DEPTH)
    private val runOrdered = BooleanArray(CanvasComposeMarkdown.MAX_LIST_DEPTH)

    // An open fence.
    private var fence: MdFence? = null

    /** What a line can start, in the order they are tried; the first to take the line ends the search. */
    private val starts: List<(MdLine) -> Boolean> = listOf(
        ::indentedCode,
        ::fenceStart,
        ::setextUnderline,
        ::divider,
        ::heading,
        ::refusedConstruct,
        ::quoteStart,
        ::listStart,
    )

    fun read(): List<MdBlock> {
        lines.forEach(::line)
        fence?.let { problems.unsupported(it.offset, "a fenced code block that is never closed") }
        flushAll()
        endList()
        return blocks
    }

    private fun line(line: SourceLine) {
        val open = fence
        when {
            open != null -> fenceLine(open, line)
            line.text.isBlank() -> blankLine()
            else -> blockLine(mdLine(line))
        }
    }

    private fun fenceLine(open: MdFence, line: SourceLine) {
        if (!open.closedBy(line)) return open.add(line)
        blocks += open.code()
        fence = null
    }

    private fun blankLine() {
        flushParagraph()
        flushQuote()
        if (!inList) return
        flushItem()
        blankInList = true
    }

    private fun mdLine(line: SourceLine): MdLine {
        val text = line.text.trimEnd()
        val body = text.trimStart()
        val indent = line.indentWidth()
        return MdLine(body, line.start + (text.length - body.length), indent, nested = inList && indent >= parentContentColumn)
    }

    private fun blockLine(line: MdLine) {
        if (starts.none { start -> start(line) }) continuation(line)
    }

    private fun indentedCode(line: MdLine): Boolean {
        if (line.indent < 4 || inList) return false
        if (paragraph != null || quote != null) {
            continuation(line)
        } else {
            problems.unsupported(line.start, "an indented code block; use a fenced block (```)")
        }
        return true
    }

    private fun fenceStart(line: MdLine): Boolean {
        val opening = line.fenceOpening() ?: return false
        if (!refusedInItem(line, "a code block")) {
            closeBlocks()
            fence = opening
        }
        return true
    }

    private fun setextUnderline(line: MdLine): Boolean {
        if (paragraph == null || !line.isSetextUnderline()) return false
        problems.unsupported(line.start, "a setext heading underline; use '# ' headings, or leave a blank line before ---")
        return true
    }

    private fun divider(line: MdLine): Boolean {
        if (!line.isThematicBreak()) return false
        if (!refusedInItem(line, "a divider")) {
            closeBlocks()
            blocks += MdBlock.Divider
        }
        return true
    }

    private fun heading(line: MdLine): Boolean {
        val heading = line.heading() ?: return false
        if (!refusedInItem(line, "a heading")) addHeading(heading, line)
        return true
    }

    private fun addHeading(heading: MdHeading, line: MdLine) {
        closeBlocks()
        if (heading.level > CanvasComposeMarkdown.MAX_HEADING_LEVEL) {
            return problems.unsupported(
                line.start,
                "a level-${heading.level} heading; headings go to level ${CanvasComposeMarkdown.MAX_HEADING_LEVEL} (###)",
            )
        }
        blocks += MdBlock.Heading(heading.level, MdInlineSource.of(heading.content).read(problems))
    }

    private fun refusedConstruct(line: MdLine): Boolean {
        val what = refusedConstructName(line) ?: return false
        problems.unsupported(line.start, what)
        return true
    }

    private fun refusedConstructName(line: MdLine): String? = when {
        line.startsTable() || isTableDelimiterRow(line) -> "a table"
        line.isFootnoteDefinition() -> "a footnote"
        paragraph == null && line.isLinkDefinition() -> "a link reference definition"
        else -> null
    }

    private fun isTableDelimiterRow(line: MdLine): Boolean = paragraph != null && line.isTableDelimiter()

    private fun quoteStart(line: MdLine): Boolean {
        if (!line.isQuote()) return false
        quoteLine(line)
        return true
    }

    private fun quoteLine(line: MdLine) {
        if (refusedInItem(line, "a quote")) return
        val inner = line.quoteContent()
        flushParagraph()
        endList()
        when {
            inner == null -> flushQuote()
            inner.startsBlock() -> problems.unsupported(inner.start, "a block inside a quote; a quote holds plain text")
            else -> (quote ?: MdInlineSource().also { quote = it }).addLine(inner.source)
        }
    }

    private fun listStart(line: MdLine): Boolean {
        val marker = line.listMarker() ?: return false
        if (paragraph != null && !marker.canInterruptParagraph()) return false
        listItem(marker, line)
        return true
    }

    private fun listItem(marker: MdListMarker, line: MdLine) {
        val level = listLevel(line)
            ?: return problems.add(ComposeProblemCode.NESTING_TOO_DEEP, line.start, "a list nested more than one level")
        flushAll()
        enterLevel(level, line.indent + marker.width)
        val content = line.drop(marker.width)
        val task = content.task()
        val text = if (task == null) content else content.drop(task.value.length)
        val position = countInRun(level, marker)
        if (marker.startsMisnumbered(position)) {
            problems.unsupported(line.start, "a numbered list starting at ${marker.number}; numbered lists start at 1")
        }
        item = PendingItem(kindOf(marker, task, position), level, MdInlineSource.of(text.source))
    }

    /** The nesting level an item at [line]'s indentation opens, or null when that is too deep. */
    private fun listLevel(line: MdLine): Int? {
        if (!inList || line.indent < parentContentColumn) return 0
        val child = childContentColumn
        if (child == null || line.indent < child) return 1
        return null
    }

    private fun enterLevel(level: Int, contentColumn: Int) {
        if (level == 0) {
            parentContentColumn = contentColumn
            childContentColumn = null
            runLength[1] = 0
        } else {
            childContentColumn = contentColumn
        }
        inList = true
        blankInList = false
    }

    /** The item's position in the run of like items at [level] it continues or starts. */
    private fun countInRun(level: Int, marker: MdListMarker): Int {
        val continuesRun = runLength[level] > 0 && runOrdered[level] == marker.ordered
        runLength[level] = if (continuesRun) runLength[level] + 1 else 1
        runOrdered[level] = marker.ordered
        return runLength[level]
    }

    private fun kindOf(marker: MdListMarker, task: MatchResult?, position: Int): PendingKind = when {
        task != null -> PendingKind.Todo(checked = task.groupValues[1].isNotBlank())
        marker.ordered -> PendingKind.Numbered(position)
        else -> PendingKind.Bullet
    }

    /** A line of text: more of the open paragraph, item or quote, or a new paragraph. */
    private fun continuation(line: MdLine) {
        val open = item
        if (open != null && !blankInList) return open.source.addLine(line.source)
        if (isLaterItemParagraph(line)) {
            return problems.unsupported(line.start, "a list item with more than one paragraph")
        }
        quote?.let { return it.addLine(line.source) }
        endList()
        (paragraph ?: MdInlineSource().also { paragraph = it }).addLine(line.source)
    }

    /** Text indented under an item after a blank line: a second paragraph of the item. */
    private fun isLaterItemParagraph(line: MdLine): Boolean {
        if (!inList || !blankInList) return false
        return line.indent >= parentContentColumn
    }

    /** Refuses [what] when [line] sits inside a list item; whether it did. */
    private fun refusedInItem(line: MdLine, what: String): Boolean {
        if (line.nested) problems.unsupported(line.start, "$what inside a list item")
        return line.nested
    }

    private fun flushParagraph() {
        paragraph?.let { blocks += MdBlock.Paragraph(it.read(problems)) }
        paragraph = null
    }

    private fun flushQuote() {
        quote?.let { blocks += MdBlock.Quote(it.read(problems)) }
        quote = null
    }

    private fun flushItem() {
        val open = item ?: return
        val content = open.source.read(problems)
        blocks += when (val kind = open.kind) {
            PendingKind.Bullet -> MdBlock.BulletItem(content, open.level)
            is PendingKind.Numbered -> MdBlock.NumberedItem(kind.number, content, open.level)
            is PendingKind.Todo -> MdBlock.TodoItem(kind.checked, content, open.level)
        }
        item = null
    }

    private fun flushAll() {
        flushParagraph()
        flushQuote()
        flushItem()
    }

    /** Before a block that ends whatever was open: the paragraph, quote, item and the list. */
    private fun closeBlocks() {
        flushAll()
        endList()
    }

    private fun endList() {
        flushItem()
        inList = false
        blankInList = false
        childContentColumn = null
        runLength.fill(0)
    }

    private sealed interface PendingKind {
        data object Bullet : PendingKind

        data class Numbered(val number: Int) : PendingKind

        data class Todo(val checked: Boolean) : PendingKind
    }

    private class PendingItem(val kind: PendingKind, val level: Int, val source: MdInlineSource)
}
