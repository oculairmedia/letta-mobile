package com.letta.mobile.data.meridian

/**
 * Splits a command line the way a POSIX shell splits simple words (letta-mobile-jna0o.3), for front
 * doors that receive one string: the meta-tool's `command`, or a recognizer reading an agent's
 * `meridian ...` Bash call. Whitespace separates words; 'single quotes' are literal; "double
 * quotes" honour backslash before `"`, `\` and `$`; a bare backslash escapes the next character.
 * No expansion, globbing, pipes or heredocs: JSON belongs on stdin, never here.
 *
 * Returns null when a quote is left open, so the caller can answer a usage error instead of
 * guessing.
 */
object MeridianArgv {
    fun split(commandLine: String): List<String>? {
        val state = SplitState()
        var index = 0
        while (index < commandLine.length) {
            index = state.consume(commandLine, index) ?: return null
        }
        return state.finish()
    }

    /** [argv] without a leading `meridian` program name, so both `meridian canvas list` and `canvas list` work. */
    fun withoutProgram(argv: List<String>): List<String> =
        if (argv.firstOrNull() == PROGRAM) argv.drop(1) else argv

    const val PROGRAM = "meridian"

    private class SplitState {
        private val words = mutableListOf<String>()
        private val current = StringBuilder()
        private var inWord = false

        /** Consumes the token starting at [index]; the index after it, or null on an unterminated quote. */
        fun consume(line: String, index: Int): Int? {
            val char = line[index]
            return when {
                char.isWhitespace() -> endWord().let { index + 1 }
                char == '\'' -> singleQuoted(line, index + 1)
                char == '"' -> doubleQuoted(line, index + 1)
                char == '\\' && index + 1 < line.length -> append(line[index + 1]).let { index + 2 }
                else -> append(char).let { index + 1 }
            }
        }

        fun finish(): List<String> {
            endWord()
            return words.toList()
        }

        private fun append(char: Char) {
            current.append(char)
            inWord = true
        }

        private fun endWord() {
            if (inWord) words += current.toString()
            current.clear()
            inWord = false
        }

        private fun singleQuoted(line: String, start: Int): Int? {
            val end = line.indexOf('\'', start).takeIf { it >= 0 } ?: return null
            current.append(line, start, end)
            inWord = true
            return end + 1
        }

        private fun doubleQuoted(line: String, start: Int): Int? {
            inWord = true
            var index = start
            while (index < line.length) {
                val char = line[index]
                when {
                    char == '"' -> return index + 1
                    char == '\\' && index + 1 < line.length && line[index + 1] in DOUBLE_QUOTE_ESCAPES -> {
                        current.append(line[index + 1])
                        index += 2
                    }
                    else -> {
                        current.append(char)
                        index += 1
                    }
                }
            }
            return null
        }
    }

    private val DOUBLE_QUOTE_ESCAPES = setOf('"', '\\', '$', '`')
}
