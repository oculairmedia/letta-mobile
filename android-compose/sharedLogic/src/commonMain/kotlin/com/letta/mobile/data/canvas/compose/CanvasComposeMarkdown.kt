package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.CanvasToolContract

/**
 * The markdown subset canvas_compose accepts (letta-mobile-bglj6.8, docs/design/canvas-compose-plan.md
 * section 3.3), read into [MdBlock]s that [CanvasCascadeBlocks] writes as the board's block JSON.
 *
 * The subset: CommonMark paragraphs, ATX headings 1-3, `-`/`*`/`+` bullet items, `1.` numbered
 * items, GFM task items (`- [ ]`, `- [x]`), `>` quotes of plain text, fenced code (``` or ~~~),
 * thematic breaks (`---`), and inline `**bold**`, `*italic*`, `` `code` ``, `[text](url)` and
 * `~~strike~~`, with backslash escapes. Lists nest one level.
 *
 * Strict by design: a construct outside the subset (raw HTML, images, tables, reference links,
 * footnotes, setext headings, deeper nesting, ...) is refused with the character offset where it
 * starts. Nothing degrades to plain text, because an agent that sees its table arrive as a
 * paragraph of pipes has no way to learn it was not understood. Text inside the subset is taken as
 * written: entities are not decoded, nothing else is interpreted.
 *
 * Owned rather than a library: the subset is small, the Iroh host runs sharedLogic without
 * Compose, and refusing (rather than rendering) what is outside the subset is the point.
 */
object CanvasComposeMarkdown {
    /** Problems reported for one field; past this the agent has enough to fix, and the refusal stays small. */
    const val MAX_PROBLEMS = 10

    /** List nesting the board's blocks carry (the item and one level of children). */
    const val MAX_LIST_DEPTH = 2

    const val MAX_HEADING_LEVEL = 3

    /**
     * [markdown] read into blocks, or every problem found (at most [MAX_PROBLEMS]), each at [path]
     * (`/items/2/markdown`) with the character offset in its message. Empty or blank markdown is
     * MISSING_FIELD: a note with nothing in it is not something to compose.
     */
    fun parse(markdown: String, path: String): MdParse {
        if (markdown.isBlank()) {
            return MdParse.Refused(listOf(ComposeProblem(path, ComposeProblemCode.MISSING_FIELD, "the markdown is empty")))
        }
        val problems = Problems(path)
        val blocks = BlockReader(lines(markdown), problems).read()
        return if (problems.isEmpty()) MdParse.Parsed(blocks) else MdParse.Refused(problems.list())
    }

    /** Lines without their line ending (`\n`, `\r\n` or `\r`), each with its offset in the input. */
    internal fun lines(text: String): List<SourceLine> {
        val lines = mutableListOf<SourceLine>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                lines += SourceLine(text.substring(start, i), start)
                i += if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') 2 else 1
                start = i
            } else {
                i++
            }
        }
        if (start < text.length) lines += SourceLine(text.substring(start), start)
        return lines
    }
}

/** Markdown read: either the blocks, or why not. */
sealed interface MdParse {
    data class Parsed(val blocks: List<MdBlock>) : MdParse

    data class Refused(val problems: List<ComposeProblem>) : MdParse
}

enum class MdSpanStyle { BOLD, ITALIC, INLINE_CODE, LINK, STRIKETHROUGH }

/** A style over `[start, end)` of a block's text; [url] only for [MdSpanStyle.LINK]. */
data class MdSpan(val style: MdSpanStyle, val start: Int, val end: Int, val url: String? = null)

/** A block's text with its inline styles, the markdown syntax already removed. */
data class MdText(val text: String, val spans: List<MdSpan> = emptyList())

/**
 * One block of a note. [indent] is the list nesting level (0, or 1 for a child item), which the
 * board stores as the block's indentation rather than as nested blocks.
 */
sealed interface MdBlock {
    data class Paragraph(val content: MdText) : MdBlock

    data class Heading(val level: Int, val content: MdText) : MdBlock

    data class BulletItem(val content: MdText, val indent: Int = 0) : MdBlock

    data class NumberedItem(val number: Int, val content: MdText, val indent: Int = 0) : MdBlock

    data class TodoItem(val checked: Boolean, val content: MdText, val indent: Int = 0) : MdBlock

    data class Quote(val content: MdText) : MdBlock

    /** Verbatim lines; code carries no inline styles. */
    data class Code(val text: String) : MdBlock

    data object Divider : MdBlock
}

internal data class SourceLine(val text: String, val start: Int)

/** Problems for one field, deduplicated (a span re-read after a failed match reports once) and capped. */
private class Problems(private val path: String) {
    private val seen = LinkedHashMap<Pair<Int, String>, ComposeProblem>()

    fun add(code: ComposeProblemCode, offset: Int, what: String) {
        if (seen.size >= CanvasComposeMarkdown.MAX_PROBLEMS) return
        val message = "$what at offset $offset is not supported; see ${CanvasToolContract.COMPOSE_GUIDE}"
        seen.getOrPut(offset to message) { ComposeProblem(path, code, message) }
    }

    fun unsupported(offset: Int, what: String) = add(ComposeProblemCode.UNSUPPORTED_MARKDOWN, offset, what)

    fun isEmpty(): Boolean = seen.isEmpty()

    fun list(): List<ComposeProblem> = seen.values.toList()
}

/** Text gathered for one block before its inline syntax is read: the lines and where each starts. */
private class InlineSource {
    private val text = StringBuilder()
    private val offsets = mutableListOf<Int>()

    fun addLine(line: String, start: Int) {
        if (text.isNotEmpty()) {
            text.append('\n')
            offsets += start - 1
        }
        line.forEachIndexed { i, c ->
            text.append(c)
            offsets += start + i
        }
    }

    fun read(problems: Problems): MdText {
        // One past the end too, so a construct running to the end still has an offset to report.
        val at = IntArray(offsets.size + 1)
        offsets.forEachIndexed { i, offset -> at[i] = offset }
        at[offsets.size] = (offsets.lastOrNull() ?: 0) + 1
        return InlineReader(text.toString(), at, problems).read()
    }
}

/** The block structure, line by line; inline syntax is left to [InlineReader]. */
private class BlockReader(private val lines: List<SourceLine>, private val problems: Problems) {
    private val blocks = mutableListOf<MdBlock>()

    private var paragraph: InlineSource? = null
    private var quote: InlineSource? = null

    // The open list item, if any, and the list around it.
    private var item: PendingItem? = null
    private var inList = false
    private var blankInList = false
    private var parentContentColumn = 0
    private var childContentColumn: Int? = null
    private val runLength = IntArray(CanvasComposeMarkdown.MAX_LIST_DEPTH)
    private val runOrdered = BooleanArray(CanvasComposeMarkdown.MAX_LIST_DEPTH)

    // An open fence.
    private var fence: Fence? = null

    fun read(): List<MdBlock> {
        lines.forEach(::line)
        fence?.let { problems.unsupported(it.offset, "a fenced code block that is never closed") }
        flushAll()
        endList()
        return blocks
    }

    private fun line(line: SourceLine) {
        fence?.let { open ->
            if (open.closedBy(line.text)) {
                blocks += MdBlock.Code(open.lines.joinToString("\n"))
                fence = null
            } else {
                open.lines += stripIndent(line.text, open.indent)
            }
            return
        }
        val text = line.text.trimEnd()
        if (text.isBlank()) {
            flushParagraph()
            flushQuote()
            if (inList) {
                flushItem()
                blankInList = true
            }
            return
        }
        val indent = indentWidth(text)
        val body = text.trimStart()
        val bodyStart = line.start + (text.length - body.length)
        val nested = inList && indent >= parentContentColumn

        if (indent >= 4 && !inList) {
            if (paragraph != null || quote != null) return continuation(body, bodyStart)
            return problems.unsupported(bodyStart, "an indented code block; use a fenced block (```)")
        }
        fenceOpening(body)?.let { (char, length) ->
            if (nested) return problems.unsupported(bodyStart, "a code block inside a list item")
            flushAll()
            endList()
            fence = Fence(char, length, indent, bodyStart)
            return
        }
        if (paragraph != null && SETEXT.matches(body)) {
            return problems.unsupported(bodyStart, "a setext heading underline; use '# ' headings, or leave a blank line before ---")
        }
        if (THEMATIC_BREAK.matches(body)) {
            if (nested) return problems.unsupported(bodyStart, "a divider inside a list item")
            flushAll()
            endList()
            blocks += MdBlock.Divider
            return
        }
        ATX_HEADING.matchEntire(body)?.let { match ->
            if (nested) return problems.unsupported(bodyStart, "a heading inside a list item")
            val level = match.groupValues[1].length
            flushAll()
            endList()
            if (level > CanvasComposeMarkdown.MAX_HEADING_LEVEL) {
                return problems.unsupported(bodyStart, "a level-$level heading; headings go to level ${CanvasComposeMarkdown.MAX_HEADING_LEVEL} (###)")
            }
            val content = headingContent(match.groupValues[2])
            // The heading's text runs to the end of the line.
            val contentStart = bodyStart + body.length - match.groupValues[2].length
            blocks += MdBlock.Heading(level, InlineSource().apply { addLine(content, contentStart) }.read(problems))
            return
        }
        if (body.startsWith("|") || (paragraph != null && body.contains('|') && TABLE_DELIMITER.matches(body))) {
            return problems.unsupported(bodyStart, "a table")
        }
        if (body.startsWith("[^") && DEFINITION.containsMatchIn(body)) return problems.unsupported(bodyStart, "a footnote")
        if (DEFINITION.containsMatchIn(body) && paragraph == null) return problems.unsupported(bodyStart, "a link reference definition")
        if (body.startsWith(">")) return quoteLine(body, bodyStart, nested)
        listMarker(body, interruptsParagraph = paragraph != null)?.let { return listItem(it, indent, body, bodyStart) }
        continuation(body, bodyStart, indent)
    }

    private fun quoteLine(body: String, bodyStart: Int, nested: Boolean) {
        if (nested) return problems.unsupported(bodyStart, "a quote inside a list item")
        val afterMarker = body.substring(1)
        val inner = afterMarker.removePrefix(" ").trimEnd()
        val innerStart = bodyStart + 1 + (afterMarker.length - afterMarker.removePrefix(" ").length)
        flushParagraph()
        endList()
        if (inner.isBlank()) return flushQuote()
        val innerBody = inner.trimStart()
        val innerBodyStart = innerStart + (inner.length - innerBody.length)
        if (innerBody.startsWith(">") || fenceOpening(innerBody) != null || THEMATIC_BREAK.matches(innerBody) ||
            ATX_HEADING.matches(innerBody) || listMarker(innerBody, interruptsParagraph = false) != null
        ) {
            return problems.unsupported(innerBodyStart, "a block inside a quote; a quote holds plain text")
        }
        (quote ?: InlineSource().also { quote = it }).addLine(innerBody, innerBodyStart)
    }

    private fun listItem(marker: ListMarker, indent: Int, body: String, bodyStart: Int) {
        val level = when {
            !inList || indent < parentContentColumn -> 0
            childContentColumn.let { it == null || indent < it } -> 1
            else -> return problems.add(
                ComposeProblemCode.NESTING_TOO_DEEP, bodyStart,
                "a list nested more than one level",
            )
        }
        flushParagraph()
        flushQuote()
        flushItem()
        val contentColumn = indent + marker.width
        if (level == 0) {
            parentContentColumn = contentColumn
            childContentColumn = null
            runLength[1] = 0
        } else {
            childContentColumn = contentColumn
        }
        inList = true
        blankInList = false

        val content = body.substring(marker.width.coerceAtMost(body.length))
        val contentStart = bodyStart + marker.width.coerceAtMost(body.length)
        val task = TASK.find(content)
        val text = if (task != null) content.substring(task.value.length) else content
        val textStart = contentStart + (task?.value?.length ?: 0)

        val continuesRun = runLength[level] > 0 && runOrdered[level] == marker.ordered
        if (marker.ordered && !continuesRun && marker.number != 1) {
            problems.unsupported(bodyStart, "a numbered list starting at ${marker.number}; numbered lists start at 1")
        }
        runLength[level] = if (continuesRun) runLength[level] + 1 else 1
        runOrdered[level] = marker.ordered

        val kind = when {
            task != null -> PendingKind.Todo(checked = task.groupValues[1].isNotBlank())
            marker.ordered -> PendingKind.Numbered(runLength[level])
            else -> PendingKind.Bullet
        }
        item = PendingItem(kind, level, InlineSource().apply { addLine(text, textStart) })
    }

    /** A line of text: more of the open paragraph, item or quote, or a new paragraph. */
    private fun continuation(body: String, bodyStart: Int, indent: Int = 0) {
        item?.let { open ->
            if (!blankInList) return open.source.addLine(body, bodyStart)
        }
        if (inList && blankInList && indent >= parentContentColumn) {
            return problems.unsupported(bodyStart, "a list item with more than one paragraph")
        }
        quote?.let { return it.addLine(body, bodyStart) }
        endList()
        (paragraph ?: InlineSource().also { paragraph = it }).addLine(body, bodyStart)
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

    private fun endList() {
        flushItem()
        inList = false
        blankInList = false
        childContentColumn = null
        runLength.fill(0)
    }

    private fun listMarker(body: String, interruptsParagraph: Boolean): ListMarker? {
        BULLET.find(body)?.let { match ->
            if (interruptsParagraph && match.value.length == body.length) return null
            return ListMarker(ordered = false, number = 0, width = markerWidth(match.groupValues[1].length, body))
        }
        ORDERED.find(body)?.let { match ->
            val number = match.groupValues[1].toIntOrNull() ?: return null
            // CommonMark: inside a paragraph only a list starting at 1 starts a list ("1984. A good year." is text).
            if (interruptsParagraph && (number != 1 || match.value.length == body.length)) return null
            return ListMarker(ordered = true, number = number, width = markerWidth(match.groupValues[1].length + 1, body))
        }
        return null
    }

    /** The marker and the spaces after it (one when the item is empty or the gap is code-sized). */
    private fun markerWidth(markerLength: Int, body: String): Int {
        val spaces = body.drop(markerLength).takeWhile { it == ' ' || it == '\t' }.length
        return markerLength + if (spaces == 0 || spaces > 4 || markerLength + spaces == body.length) 1 else spaces
    }

    private fun fenceOpening(body: String): Pair<Char, Int>? {
        val char = body.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
        val length = body.takeWhile { it == char }.length
        if (length < 3) return null
        // CommonMark: a backtick fence's info string has no backtick (otherwise it is inline code).
        if (char == '`' && body.drop(length).contains('`')) return null
        return char to length
    }

    private fun headingContent(raw: String): String {
        val trimmed = raw.trim()
        // An optional closing sequence of #s, separated by a space (or the whole content).
        val closing = Regex("""(^|\s)#+$""").find(trimmed) ?: return trimmed
        return trimmed.substring(0, closing.range.first).trim()
    }

    private class Fence(val char: Char, val length: Int, val indent: Int, val offset: Int) {
        val lines = mutableListOf<String>()

        fun closedBy(line: String): Boolean {
            val body = line.trim()
            return indentWidth(line) < 4 && body.length >= length && body.all { it == char }
        }
    }

    private class ListMarker(val ordered: Boolean, val number: Int, val width: Int)

    private sealed interface PendingKind {
        data object Bullet : PendingKind

        data class Numbered(val number: Int) : PendingKind

        data class Todo(val checked: Boolean) : PendingKind
    }

    private class PendingItem(val kind: PendingKind, val level: Int, val source: InlineSource)

    private companion object {
        val ATX_HEADING = Regex("""^(#{1,6})(?:[ \t]+(.*))?$""")
        val THEMATIC_BREAK = Regex("""^(?:(?:-[ \t]*){3,}|(?:\*[ \t]*){3,}|(?:_[ \t]*){3,})$""")
        val SETEXT = Regex("""^(?:=+|-+)$""")
        val TABLE_DELIMITER = Regex("""^\|?[ \t]*:?-+:?[ \t]*(?:\|[ \t]*:?-+:?[ \t]*)*\|?$""")
        val DEFINITION = Regex("""^\[[^\]]+]:""")
        val BULLET = Regex("""^([-*+])(?:[ \t]+|$)""")
        val ORDERED = Regex("""^(\d{1,9})[.)](?:[ \t]+|$)""")
        val TASK = Regex("""^\[([ xX])](?:[ \t]+|$)""")

        fun indentWidth(line: String): Int {
            var width = 0
            for (c in line) {
                when (c) {
                    ' ' -> width++
                    '\t' -> width += 4 - width % 4
                    else -> return width
                }
            }
            return width
        }

        fun stripIndent(line: String, indent: Int): String {
            var removed = 0
            var i = 0
            while (i < line.length && removed < indent && line[i] == ' ') {
                removed++
                i++
            }
            return line.substring(i)
        }
    }
}

/**
 * Inline syntax of one block, a small recursive state machine over the characters: escapes first,
 * then code spans (whose content is literal), links, and emphasis by delimiter runs. An opener with
 * no closer is literal text; a failed opener is remembered so a long run of them stays quadratic
 * at worst instead of exponential. Soft line breaks become spaces.
 */
private class InlineReader(private val src: String, private val offsets: IntArray, private val problems: Problems) {
    private val out = StringBuilder()
    private val spans = mutableListOf<MdSpan>()
    private val failed = HashSet<Long>()

    fun read(): MdText {
        readUntil(0, null, src.length, depth = 0)
        val sorted = spans.sortedWith(compareBy<MdSpan>({ it.start }, { -it.end }, { it.style.ordinal }))
        return MdText(out.toString(), sorted)
    }

    /**
     * Reads `[from, limit)` until [closer] (when given) closes the span that opened it. Returns the
     * index after the closer, the limit when [closer] is null, or -1 when the closer never comes.
     */
    private fun readUntil(from: Int, closer: Delimiter?, limit: Int, depth: Int): Int {
        var i = from
        while (i < limit) {
            val c = src[i]
            if (c == '\\' && i + 1 < limit && src[i + 1] in ESCAPABLE) {
                out.append(src[i + 1])
                i += 2
                continue
            }
            if (closer != null && i > from && closes(closer, i, limit)) return i + closer.token.length
            when {
                c == '\n' -> {
                    out.append(' ')
                    i++
                }
                c == '`' -> i = codeSpan(i, limit)
                c == '!' && i + 1 < limit && src[i + 1] == '[' -> {
                    problems.unsupported(offsets[i], "an image")
                    out.append(c)
                    i++
                }
                c == '[' -> i = link(i, limit, depth)
                c == '<' && HTML_OR_AUTOLINK.containsMatchIn(src.substring(i, minOf(limit, i + HTML_LOOKAHEAD))) -> {
                    problems.unsupported(offsets[i], "raw HTML or an autolink (write links as [text](url))")
                    out.append(c)
                    i++
                }
                else -> i = emphasis(i, limit, depth) ?: run {
                    out.append(c)
                    i + 1
                }
            }
        }
        return if (closer == null) limit else -1
    }

    /** An emphasis or strike span opening at [i]: the index after it, or null when [i] opens nothing. */
    private fun emphasis(i: Int, limit: Int, depth: Int): Int? {
        val delimiter = DELIMITERS.firstOrNull { opens(it, i, limit) } ?: return null
        // Real text never nests styles this deep; a long run of unmatched openers would, one stack frame each.
        if (depth >= MAX_DEPTH) return null
        val key = (limit.toLong() shl 32) or (i.toLong() shl 3) or delimiter.ordinal.toLong()
        if (key in failed) return null
        val outMark = out.length
        val spanMark = spans.size
        val end = readUntil(i + delimiter.token.length, delimiter, limit, depth + 1)
        if (end >= 0) {
            spans += MdSpan(delimiter.style, outMark, out.length)
            return end
        }
        failed += key
        out.setLength(outMark)
        while (spans.size > spanMark) spans.removeAt(spans.size - 1)
        // A double run that never closes may still hold a single one (`**a*` is `*` + italic a).
        out.append(src[i])
        return i + 1
    }

    private fun opens(delimiter: Delimiter, i: Int, limit: Int): Boolean {
        if (!src.startsWith(delimiter.token, i)) return false
        val next = i + delimiter.token.length
        if (next >= limit || src[next].isWhitespace()) return false
        if (delimiter.intraword) return true
        return i == 0 || !src[i - 1].isLetterOrDigit()
    }

    private fun closes(delimiter: Delimiter, i: Int, limit: Int): Boolean {
        if (!src.startsWith(delimiter.token, i) || i + delimiter.token.length > limit) return false
        if (src[i - 1].isWhitespace()) return false
        if (delimiter.intraword) return true
        val after = i + delimiter.token.length
        return after >= src.length || !src[after].isLetterOrDigit()
    }

    /** A code span opening at [i] (literal content), or the backtick run as text when it never closes. */
    private fun codeSpan(i: Int, limit: Int): Int {
        val run = countRun(i, '`', limit)
        var j = i + run
        while (j < limit) {
            if (src[j] == '`') {
                val closing = countRun(j, '`', limit)
                if (closing == run) {
                    var content = src.substring(i + run, j).replace('\n', ' ')
                    if (content.length >= 2 && content.first() == ' ' && content.last() == ' ' && content.isNotBlank()) {
                        content = content.substring(1, content.length - 1)
                    }
                    val start = out.length
                    out.append(content)
                    spans += MdSpan(MdSpanStyle.INLINE_CODE, start, out.length)
                    return j + closing
                }
                j += closing
            } else {
                j++
            }
        }
        out.append(src, i, i + run)
        return i + run
    }

    /** A `[text](url)` at [i]; refusals for the forms outside the subset; else `[` as text. */
    private fun link(i: Int, limit: Int, depth: Int): Int {
        if (i + 1 < limit && src[i + 1] == '^') {
            problems.unsupported(offsets[i], "a footnote")
            out.append('[')
            return i + 1
        }
        val close = matchingBracket(i, limit)
        if (close < 0) {
            out.append('[')
            return i + 1
        }
        val after = close + 1
        if (after < limit && src[after] == '[') {
            problems.unsupported(offsets[i], "a reference link; write [text](url)")
            out.append('[')
            return i + 1
        }
        if (after >= limit || src[after] != '(') {
            out.append('[')
            return i + 1
        }
        val destinationEnd = matchingParen(after, limit)
        if (destinationEnd < 0) {
            out.append('[')
            return i + 1
        }
        val destination = src.substring(after + 1, destinationEnd).trim().let {
            if (it.startsWith("<") && it.endsWith(">")) it.substring(1, it.length - 1) else it
        }
        when {
            destination.isEmpty() -> problems.unsupported(offsets[i], "a link without a url")
            destination.any { it.isWhitespace() } -> problems.unsupported(offsets[i], "a link title or a url with spaces")
            close == i + 1 -> problems.unsupported(offsets[i], "a link without text")
        }
        val start = out.length
        readUntil(i + 1, null, close, depth + 1)
        spans += MdSpan(MdSpanStyle.LINK, start, out.length, url = destination)
        return destinationEnd + 1
    }

    private fun matchingBracket(open: Int, limit: Int): Int {
        var depth = 0
        var j = open
        while (j < limit) {
            when (src[j]) {
                '\\' -> j++
                '`' -> {
                    // Brackets inside a code span do not count.
                    val run = countRun(j, '`', limit)
                    val end = src.indexOf("`".repeat(run), j + run).takeIf { it in 0 until limit }
                    if (end != null) j = end + run - 1 else j += run - 1
                }
                '[' -> depth++
                ']' -> if (--depth == 0) return j
            }
            j++
        }
        return -1
    }

    private fun matchingParen(open: Int, limit: Int): Int {
        var depth = 0
        var j = open
        while (j < limit) {
            when (src[j]) {
                '\\' -> j++
                '\n' -> return -1
                '(' -> depth++
                ')' -> if (--depth == 0) return j
            }
            j++
        }
        return -1
    }

    private fun countRun(i: Int, c: Char, limit: Int): Int {
        var j = i
        while (j < limit && src[j] == c) j++
        return j - i
    }

    private enum class Delimiter(val token: String, val style: MdSpanStyle, val intraword: Boolean) {
        STRONG_STAR("**", MdSpanStyle.BOLD, true),
        STRONG_UNDERSCORE("__", MdSpanStyle.BOLD, false),
        STRIKE("~~", MdSpanStyle.STRIKETHROUGH, true),
        EMPHASIS_STAR("*", MdSpanStyle.ITALIC, true),
        EMPHASIS_UNDERSCORE("_", MdSpanStyle.ITALIC, false),
    }

    private companion object {
        val DELIMITERS = Delimiter.entries
        const val MAX_DEPTH = 16

        /**
         * What CommonMark would read as inline HTML (a tag, a comment, a declaration or processing
         * instruction) or as an autolink. `a<b` and `x < y` are text.
         */
        val HTML_OR_AUTOLINK = Regex(
            """^<(?:/?[A-Za-z][A-Za-z0-9-]*(?:\s[^<>]*)?/?>|!--|\?|![A-Za-z]|[A-Za-z][A-Za-z0-9+.-]{1,31}:[^\s<>]*>)""",
        )
        const val HTML_LOOKAHEAD = 512
        const val ESCAPABLE = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
    }
}
