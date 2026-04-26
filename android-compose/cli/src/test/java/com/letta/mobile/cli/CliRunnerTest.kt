package com.letta.mobile.cli

import org.junit.jupiter.api.Test

/**
 * Bridge between Gradle's JUnit-5 test runner and our CLI's `main()`.
 *
 * `:cli` is built as an Android library so it has uncontested access to
 * `:core`'s TimelineSyncLoop, SseParser, etc. The CLI code runs inside a
 * JUnit test method which Gradle invokes via the custom `:cli:run` task.
 * Args come in via the `letta.cli.args` system property (single string,
 * shell-split here).
 *
 * Usage:
 *   ./gradlew :cli:run -PcliArgs="stream --message hello"
 */
class CliRunnerTest {

    @Test
    fun runCli() {
        val raw = System.getProperty("letta.cli.args").orEmpty().trim()
        val args = if (raw.isEmpty()) emptyArray() else shellSplit(raw).toTypedArray()
        Main.main(args)
    }

    /**
     * Minimal shell-style splitter: handles double-quoted strings as a
     * single token, preserves internal spaces, drops the surrounding
     * quotes. Sufficient for our use; no fancy escaping support.
     */
    private fun shellSplit(input: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuote = false
        for (c in input) {
            when {
                c == '"' -> inQuote = !inQuote
                c == ' ' && !inQuote -> {
                    if (cur.isNotEmpty()) {
                        out += cur.toString(); cur.clear()
                    }
                }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }
}
