package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.CanvasToolContract

/** A line of the markdown without its line ending, or a piece of one, and where it starts in the input. */
internal data class SourceLine(val text: String, val start: Int) {
    /** Columns of leading whitespace, a tab running to the next multiple of four. */
    fun indentWidth(): Int {
        var width = 0
        for (c in text) {
            when (c) {
                ' ' -> width++
                '\t' -> width += 4 - width % 4
                else -> return width
            }
        }
        return width
    }

    companion object {
        private val LINE_BREAK = Regex("\r\n|\r|\n")

        /** [text]'s lines without their line endings (`\n`, `\r\n` or `\r`), each with its offset. */
        fun split(text: String): List<SourceLine> {
            val lines = mutableListOf<SourceLine>()
            var start = 0
            LINE_BREAK.findAll(text).forEach { lineBreak ->
                lines += SourceLine(text.substring(start, lineBreak.range.first), start)
                start = lineBreak.range.last + 1
            }
            if (start < text.length) lines += SourceLine(text.substring(start), start)
            return lines
        }
    }
}

/** Problems for one field, deduplicated (a span re-read after a failed match reports once) and capped. */
internal class MdProblems(private val path: String) {
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
internal class MdInlineSource {
    private val text = StringBuilder()
    private val offsets = mutableListOf<Int>()

    fun addLine(line: SourceLine) {
        if (text.isNotEmpty()) {
            text.append('\n')
            offsets += line.start - 1
        }
        line.text.forEachIndexed { i, c ->
            text.append(c)
            offsets += line.start + i
        }
    }

    fun read(problems: MdProblems): MdText {
        // One past the end too, so a construct running to the end still has an offset to report.
        val at = IntArray(offsets.size + 1)
        offsets.forEachIndexed { i, offset -> at[i] = offset }
        at[offsets.size] = (offsets.lastOrNull() ?: 0) + 1
        return MdInlineReader(text.toString(), at, problems).read()
    }

    companion object {
        fun of(line: SourceLine): MdInlineSource = MdInlineSource().apply { addLine(line) }
    }
}
