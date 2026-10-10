package com.letta.mobile.data.chat.projection.meridian

/**
 * One simple command of a shell script, as [ShellScript.scan] reads it: its words with quotes
 * removed, and where its stdin comes from.
 */
internal data class ShellCommand(
    val words: List<String>,
    /** The body of its heredoc (`<<'EOF'`) or here-string (`<<<`), when it has one. */
    val stdin: String? = null,
    /** True when stdin is redirected from a file (`< input.json`): the input is not in the script. */
    val stdinFromFile: Boolean = false,
    /** True when the previous command pipes into this one (`echo … | meridian …`). */
    val piped: Boolean = false,
)

/**
 * letta-mobile-jna0o.6: a small, lenient reader of the shell scripts agents pass to their Bash
 * tool. It knows quotes, escapes, comments, command separators (`;`, `&&`, `||`, `|`, `&`,
 * newlines), redirections and heredocs; it does not expand anything. It is only good enough to
 * find a `meridian …` invocation and its stdin, and it never throws: an unterminated quote yields
 * null, an unterminated heredoc takes the rest of the script.
 */
internal object ShellScript {
    fun scan(script: String): List<ShellCommand>? = Scanner(script).run()
}

private class CommandBuilder(val piped: Boolean) {
    val words = mutableListOf<String>()
    var stdin: String? = null
    var stdinFromFile = false

    fun build() = ShellCommand(words.toList(), stdin, stdinFromFile, piped)
}

private enum class Redirect { HEREDOC, HEREDOC_STRIP_TABS, HERE_STRING, FILE_IN, FILE_OUT }

private class PendingHeredoc(val delimiter: String, val stripTabs: Boolean, val target: CommandBuilder)

private class Scanner(private val s: String) {
    private var i = 0
    private val commands = mutableListOf<CommandBuilder>()
    private var current = CommandBuilder(piped = false)
    private var word: StringBuilder? = null
    private var redirect: Redirect? = null
    private val heredocs = mutableListOf<PendingHeredoc>()

    fun run(): List<ShellCommand>? {
        while (i < s.length) {
            if (!step()) return null
        }
        endWord()
        endCommand(piped = false)
        return commands.map { it.build() }
    }

    /** Reads one token's worth of [s]; false on an unterminated quote. */
    private fun step(): Boolean = quoting() ?: structural()

    /** A quote or escape at [i], read into the word; null when [i] is not one. */
    private fun quoting(): Boolean? = when (s[i]) {
        '\'' -> singleQuoted()
        '"' -> doubleQuoted()
        '\\' -> escaped()
        '$' -> if (peek(1) == '\'') ansiQuoted() else null
        else -> null
    }

    private fun structural(): Boolean {
        val c = s[i]
        return when {
            c == '\n' -> newline()
            c == ' ' || c == '\t' || c == '\r' -> advance { endWord() }
            c == '#' && word == null -> comment()
            c in OPERATOR_CHARS -> operator(c)
            c == '<' || c == '>' -> redirection(c)
            else -> advance { wordBuilder().append(c) }
        }
    }

    private inline fun advance(by: Int = 1, block: () -> Unit): Boolean {
        block()
        i += by
        return true
    }

    private fun peek(offset: Int): Char? = s.getOrNull(i + offset)

    private fun wordBuilder(): StringBuilder = word ?: StringBuilder().also { word = it }

    private fun singleQuoted(): Boolean {
        val end = s.indexOf('\'', i + 1)
        if (end < 0) return false
        wordBuilder().append(s, i + 1, end)
        i = end + 1
        return true
    }

    private fun doubleQuoted(): Boolean = quoted(from = i + 1, close = '"') { next ->
        when {
            next !in DOUBLE_QUOTE_ESCAPABLE -> null
            next == '\n' -> ""
            else -> next.toString()
        }
    }

    private fun ansiQuoted(): Boolean = quoted(from = i + 2, close = '\'') { next -> (ANSI_ESCAPES[next] ?: next).toString() }

    /** A quoted span from [from] up to [close], each `\x` read through [unescape] (null keeps it as written). */
    private inline fun quoted(from: Int, close: Char, unescape: (Char) -> String?): Boolean {
        val out = wordBuilder()
        var j = from
        while (j < s.length && s[j] != close) {
            val escape = if (s[j] == '\\') s.getOrNull(j + 1)?.let(unescape) else null
            if (escape != null) out.append(escape) else out.append(s[j])
            j += if (escape != null) 2 else 1
        }
        if (j >= s.length) return false
        i = j + 1
        return true
    }

    private fun escaped(): Boolean {
        val next = peek(1) ?: return advance { }
        if (next != '\n') wordBuilder().append(next)
        i += 2
        return true
    }

    private fun comment(): Boolean {
        val end = s.indexOf('\n', i)
        i = if (end < 0) s.length else end
        return true
    }

    private fun newline(): Boolean {
        endWord()
        endCommand(piped = false)
        i++
        readHeredocBodies()
        return true
    }

    private fun operator(c: Char): Boolean {
        endWord()
        val doubled = peek(1) == c
        val pipe = c == '|' && !doubled
        val width = if (doubled || (c == '|' && peek(1) == '&')) 2 else 1
        endCommand(piped = pipe)
        i += width
        return true
    }

    private fun redirection(c: Char): Boolean {
        // `2>`, `1<`: a file-descriptor number is part of the redirection, not a word.
        if (word?.all { it.isDigit() } == true) word = null
        endWord()
        val (kind, width) = if (c == '>') outRedirect() else inRedirect()
        redirect = kind
        i += width
        return true
    }

    private fun outRedirect(): Pair<Redirect, Int> =
        Redirect.FILE_OUT to if (peek(1) == '>' || peek(1) == '&' || peek(1) == '|') 2 else 1

    private fun inRedirect(): Pair<Redirect, Int> = when {
        peek(1) == '<' && peek(2) == '<' -> Redirect.HERE_STRING to 3
        peek(1) == '<' && peek(2) == '-' -> Redirect.HEREDOC_STRIP_TABS to 3
        peek(1) == '<' -> Redirect.HEREDOC to 2
        peek(1) == '&' || peek(1) == '>' -> Redirect.FILE_IN to 2
        else -> Redirect.FILE_IN to 1
    }

    private fun endWord() {
        val text = word?.toString() ?: return
        word = null
        when (redirect) {
            null -> current.words += text
            Redirect.HEREDOC -> heredocs += PendingHeredoc(text, stripTabs = false, target = current)
            Redirect.HEREDOC_STRIP_TABS -> heredocs += PendingHeredoc(text, stripTabs = true, target = current)
            Redirect.HERE_STRING -> current.stdin = text
            Redirect.FILE_IN -> current.stdinFromFile = true
            Redirect.FILE_OUT -> Unit
        }
        redirect = null
    }

    private fun endCommand(piped: Boolean) {
        redirect = null
        val empty = current.words.isEmpty() && current.stdin == null && heredocs.none { it.target === current }
        // An empty command (`a |` then a newline) keeps the pipe into the next one.
        if (empty) {
            current = CommandBuilder(piped || current.piped)
            return
        }
        commands += current
        current = CommandBuilder(piped)
    }

    /** After a newline, each pending heredoc's body runs to its delimiter line (or the end). */
    private fun readHeredocBodies() {
        val pending = heredocs.toList()
        heredocs.clear()
        pending.forEach { heredoc -> heredoc.target.stdin = readBody(heredoc) }
    }

    private fun readBody(heredoc: PendingHeredoc): String {
        val lines = mutableListOf<String>()
        while (i < s.length) {
            val end = s.indexOf('\n', i).let { if (it < 0) s.length else it }
            val line = s.substring(i, end).let { if (heredoc.stripTabs) it.trimStart('\t') else it }
            i = (end + 1).coerceAtMost(s.length)
            if (line.trimEnd('\r') == heredoc.delimiter) break
            lines += line
        }
        return lines.joinToString("\n")
    }

    private companion object {
        val OPERATOR_CHARS = setOf(';', '&', '|', '(', ')')
        val DOUBLE_QUOTE_ESCAPABLE = setOf('"', '\\', '$', '`', '\n')
        val ANSI_ESCAPES = mapOf('n' to '\n', 't' to '\t', 'r' to '\r', '\'' to '\'', '\\' to '\\', '"' to '"')
    }
}
