package com.letta.mobile.data.canvas.compose

/**
 * Inline syntax of one [CanvasComposeMarkdown] block, a small recursive reader over the characters:
 * escapes first, then code spans (whose content is literal), links, and emphasis by delimiter runs.
 * An opener with no closer is literal text; a failed opener is remembered so a long run of them
 * stays quadratic at worst instead of exponential. Soft line breaks become spaces.
 *
 * [offsets] maps each index of [src] to its offset in the field, for the problems it reports.
 */
internal class MdInlineReader(private val src: String, private val offsets: IntArray, private val problems: MdProblems) {
    private val out = StringBuilder()
    private val spans = mutableListOf<MdSpan>()
    private val failed = HashSet<Long>()

    /** The next character to read. */
    private var pos = 0

    fun read(): MdText {
        readUntil(Scope(from = 0, limit = src.length, depth = 0), closer = null)
        val sorted = spans.sortedWith(compareBy<MdSpan>({ it.start }, { -it.end }, { it.style.ordinal }))
        return MdText(out.toString(), sorted)
    }

    /**
     * Reads [scope] until [closer] (when given) closes the span that opened it, leaving [pos] after
     * the closer. False when the closer never comes; true otherwise, including when there is no
     * closer and the scope was read to its limit.
     */
    private fun readUntil(scope: Scope, closer: Delimiter?): Boolean {
        while (pos < scope.limit) {
            if (escape(scope)) continue
            val closed = closer?.takeIf { closesHere(it, scope) }
            if (closed != null) {
                pos += closed.token.length
                return true
            }
            inline(scope)
        }
        return closer == null
    }

    /** A backslash escape at [pos]: the escaped character as text. */
    private fun escape(scope: Scope): Boolean {
        val next = pos + 1
        if (src[pos] != '\\' || next >= scope.limit) return false
        if (src[next] !in ESCAPABLE) return false
        out.append(src[next])
        pos += 2
        return true
    }

    /** One construct (or character) at [pos]. */
    private fun inline(scope: Scope) {
        val c = src[pos]
        when {
            c == '\n' -> {
                out.append(' ')
                pos++
            }
            c == '`' -> codeSpan(scope)
            opensImage(scope) -> refuseHere("an image")
            c == '[' -> link(scope)
            opensHtml(scope) -> refuseHere("raw HTML or an autolink (write links as [text](url))")
            !emphasis(scope) -> copy()
        }
    }

    private fun copy() {
        out.append(src[pos])
        pos++
    }

    /** Reports what starts at [pos], then takes its first character as text. */
    private fun refuseHere(what: String) {
        problems.unsupported(offsets[pos], what)
        copy()
    }

    private fun opensImage(scope: Scope): Boolean = pos + 1 < scope.limit && src.startsWith("![", pos)

    private fun opensHtml(scope: Scope): Boolean =
        src[pos] == '<' && HTML_OR_AUTOLINK.containsMatchIn(src.substring(pos, minOf(scope.limit, pos + HTML_LOOKAHEAD)))

    /** An emphasis or strike span opening at [pos]; false when [pos] opens nothing. */
    private fun emphasis(scope: Scope): Boolean {
        val delimiter = DELIMITERS.firstOrNull { opens(it, scope) } ?: return false
        val key = (scope.limit.toLong() shl 32) or (pos.toLong() shl 3) or delimiter.ordinal.toLong()
        // Real text never nests styles this deep; a long run of unmatched openers would, one stack frame each.
        if (scope.depth >= MAX_DEPTH || key in failed) return false
        val start = pos
        val mark = Mark()
        pos += delimiter.token.length
        if (readUntil(Scope(pos, scope.limit, scope.depth + 1), delimiter)) {
            spans += MdSpan(delimiter.style, mark.outLength, out.length)
            return true
        }
        failed += key
        mark.restore()
        // A double run that never closes may still hold a single one (`**a*` is `*` + italic a).
        pos = start
        copy()
        return true
    }

    private fun opens(delimiter: Delimiter, scope: Scope): Boolean {
        if (!src.startsWith(delimiter.token, pos)) return false
        val next = pos + delimiter.token.length
        if (next >= scope.limit || src[next].isWhitespace()) return false
        if (delimiter.intraword) return true
        return pos == 0 || !src[pos - 1].isLetterOrDigit()
    }

    /** Whether [delimiter] closes at [pos]; never at the scope's first character. */
    private fun closesHere(delimiter: Delimiter, scope: Scope): Boolean {
        if (pos == scope.from) return false
        val after = pos + delimiter.token.length
        if (!src.startsWith(delimiter.token, pos) || after > scope.limit) return false
        if (src[pos - 1].isWhitespace()) return false
        if (delimiter.intraword) return true
        return after >= src.length || !src[after].isLetterOrDigit()
    }

    /** A code span opening at [pos] (literal content), or the backtick run as text when it never closes. */
    private fun codeSpan(scope: Scope) {
        val run = backtickRun(pos, scope)
        val close = closingRun(run, scope)
        if (close < 0) {
            out.append(src, pos, pos + run)
            pos += run
            return
        }
        appendCode(src.substring(pos + run, close))
        pos = close + run
    }

    /** Where a backtick run as long as the [run] opening at [pos] starts, or -1. */
    private fun closingRun(run: Int, scope: Scope): Int {
        var j = pos + run
        while (j < scope.limit) {
            val length = backtickRun(j, scope)
            if (length == run) return j
            j += length.coerceAtLeast(1)
        }
        return -1
    }

    /** A code span's content: line breaks as spaces, one space of padding each side removed. */
    private fun appendCode(raw: String) {
        val content = raw.replace('\n', ' ')
        val start = out.length
        out.append(if (content.isBlank()) content else content.removeSurrounding(" "))
        spans += MdSpan(MdSpanStyle.INLINE_CODE, start, out.length)
    }

    /** A `[text](url)` at [pos]; refusals for the forms outside the subset; else `[` as text. */
    private fun link(scope: Scope) {
        val target = linkTarget(scope) ?: return copy()
        linkProblem(target)?.let { problems.unsupported(offsets[pos], it) }
        val start = out.length
        pos++
        readUntil(Scope(pos, target.textEnd, scope.depth + 1), closer = null)
        spans += MdSpan(MdSpanStyle.LINK, start, out.length, url = target.url)
        pos = target.end
    }

    private fun linkTarget(scope: Scope): LinkTarget? {
        if (refusedFootnote(scope)) return null
        val close = matchingBracket(scope)
        if (close < 0 || refusedReferenceLink(close + 1, scope)) return null
        val destinationEnd = destinationEnd(close + 1, scope)
        if (destinationEnd < 0) return null
        val url = src.substring(close + 2, destinationEnd).trim().removeSurrounding("<", ">")
        return LinkTarget(textEnd = close, url = url, end = destinationEnd + 1)
    }

    private fun linkProblem(target: LinkTarget): String? = when {
        target.url.isEmpty() -> "a link without a url"
        target.url.any { it.isWhitespace() } -> "a link title or a url with spaces"
        target.textEnd == pos + 1 -> "a link without text"
        else -> null
    }

    private fun refusedFootnote(scope: Scope): Boolean {
        val next = pos + 1
        if (next >= scope.limit || src[next] != '^') return false
        problems.unsupported(offsets[pos], "a footnote")
        return true
    }

    private fun refusedReferenceLink(after: Int, scope: Scope): Boolean {
        if (after >= scope.limit || src[after] != '[') return false
        problems.unsupported(offsets[pos], "a reference link; write [text](url)")
        return true
    }

    /** The `)` closing a destination that opens right [after] the link text, or -1. */
    private fun destinationEnd(after: Int, scope: Scope): Int {
        if (after >= scope.limit || src[after] != '(') return -1
        return matchingParen(after, scope)
    }

    private fun matchingBracket(scope: Scope): Int {
        var depth = 0
        var j = pos
        while (j < scope.limit) {
            when (src[j]) {
                '\\' -> j++
                // Brackets inside a code span do not count.
                '`' -> j = afterCodeSpan(j, scope) - 1
                '[' -> depth++
                ']' -> if (--depth == 0) return j
            }
            j++
        }
        return -1
    }

    /** Past the code span opening at [at] (its closing run), or past the run when it never closes. */
    private fun afterCodeSpan(at: Int, scope: Scope): Int {
        val run = backtickRun(at, scope)
        val end = src.indexOf("`".repeat(run), at + run)
        return if (end in 0 until scope.limit) end + run else at + run
    }

    private fun matchingParen(open: Int, scope: Scope): Int {
        var depth = 0
        var j = open
        while (j < scope.limit) {
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

    private fun backtickRun(at: Int, scope: Scope): Int {
        var j = at
        while (j < scope.limit && src[j] == '`') j++
        return j - at
    }

    /** What one recursive read covers: `[from, limit)` at a nesting [depth]. */
    private class Scope(val from: Int, val limit: Int, val depth: Int)

    /** A link's text ends at [textEnd] (its `]`); the whole link at [end]. */
    private class LinkTarget(val textEnd: Int, val url: String, val end: Int)

    /** The output so far, to roll back to when a span never closes. */
    private inner class Mark {
        val outLength = out.length
        private val spanCount = spans.size

        fun restore() {
            out.setLength(outLength)
            spans.subList(spanCount, spans.size).clear()
        }
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
