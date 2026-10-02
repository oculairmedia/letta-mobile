package com.letta.mobile.architecture

/**
 * JVM-only API a commonMain file reaches without importing it (letta-mobile-o4ygk.4): java.lang
 * names (`System.`, `Math.`, `Thread.`...) resolve implicitly on the JVM, so an import rule never
 * sees them, yet they do not exist on wasm or native. Also catches fully qualified `java.` /
 * `javax.` references, `::class.java`, `@Synchronized`, and the stdlib's JVM-only `synchronized`
 * (atomicfu's common `synchronized` is allowed when the file imports it).
 *
 * A source-text scan: comments and string literals are blanked first, so prose and messages that
 * mention these names are not hits.
 */
internal object ImplicitJvmApiScan {
    private val commentsAndStrings = Regex(
        """//[^\n]*|/\*[\s\S]*?\*/|\"\"\"[\s\S]*?\"\"\"|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'""",
    )

    private val implicitJvmApi = Regex(
        """(?<![\w.])(?:System|Math|Thread|Runtime|Integer|Character)\.""" +
            """|(?<![\w.])javax?\.[a-z]""" +
            """|::class\.java\b|\.javaClass\b|@Synchronized\b""",
    )

    private val stdlibSynchronized = Regex("""(?<![\w.])synchronized\s*\(""")

    private const val ATOMICFU_SYNCHRONIZED = "import kotlinx.atomicfu.locks.synchronized"

    fun hits(source: String): List<String> {
        val code = source.lineSequence()
            .filterNot { it.trimStart().startsWith("import ") || it.trimStart().startsWith("package ") }
            .joinToString("\n")
            .replace(commentsAndStrings, " ")
        val synchronizedHits = if (ATOMICFU_SYNCHRONIZED in source) emptySequence() else stdlibSynchronized.findAll(code)
        return (implicitJvmApi.findAll(code) + synchronizedHits).map { it.value.trim() }.toList()
    }
}
