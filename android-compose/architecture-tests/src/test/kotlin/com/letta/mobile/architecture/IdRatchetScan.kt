package com.letta.mobile.architecture

import java.io.File
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.relativeTo

/**
 * Line-based counters behind the typed-id ratchets (letta-mobile-by4vm.23). They are deliberate
 * heuristics, not a Kotlin parser: whole-line comments are skipped, everything else is matched
 * textually. That keeps the scan fast and deterministic; the baselines absorb any noise.
 */
internal object IdRatchetScan {
    /**
     * A string literal that opens with one of the id prefixes IdFormats owns. `agent-` counts
     * only as the bare prefix or a template (`"agent-"`, `"agent-$x"`): `"agent-…"` words are
     * test tags and keys far more often than ids.
     */
    private val prefixLiteral = Regex("\"(?:conv-default-|client-|cm-|agent-(?=[\"$]))")

    private val defaultLiteral = Regex("\"default\"")

    /** A line that talks about an id or a conversation, so its `"default"` is an id. */
    private val idContext = Regex("""\bid\b|[a-z0-9]Ids?\b|\bID\b|_ID\b|[Cc]onversation|CONVERSATION|\bconv\b|[a-z]Conv\b""")

    /**
     * A parameter (function, constructor or lambda) named `*Id`/`*ID` typed `String` or
     * `String?`. Declarations preceded by `val `/`var ` are properties, not parameters.
     */
    private val rawIdParameter = Regex("""(?<!val )(?<!var )\b[a-z]\w*(?:Id|ID)\s*:\s*String\b\??(?![\w.<])""")

    fun idLiteralCount(text: String): Int = codeLines(text).sumOf { line ->
        val defaults = if (idContext.containsMatchIn(line)) defaultLiteral.findAll(line).count() else 0
        prefixLiteral.findAll(line).count() + defaults
    }

    fun rawIdParameterCount(text: String): Int = codeLines(text).sumOf { rawIdParameter.findAll(it).count() }

    /** Per-file counts (relative to [base], `/`-separated), dropping files that count zero. */
    fun countPerFile(base: Path, files: Sequence<File>, counter: (String) -> Int): Map<String, Int> =
        files.mapNotNull { file ->
            val count = counter(file.readText())
            if (count == 0) null else file.toPath().relativeTo(base).invariantSeparatorsPathString to count
        }.toMap().toSortedMap()

    /**
     * Kotlin files under every non-test source set (`src/<set>` where `<set>` does not contain
     * "test") of [modules], relative to [compose]. Build and generated outputs are skipped.
     */
    fun productionKotlinFiles(compose: Path, modules: List<String>): Sequence<File> =
        modules.asSequence().flatMap { module ->
            val src = compose.resolve("$module/src").toFile()
            check(src.isDirectory) { "Missing source root $src" }
            src.listFiles().orEmpty().asSequence()
                .filter { it.isDirectory && !it.name.contains("test", ignoreCase = true) }
                .flatMap { set -> set.walkTopDown().filter { it.isFile && it.extension == "kt" } }
                .filterNot { file -> excludedSegments.any { file.invariantPath().contains(it) } }
        }

    /** Every module directory under [compose] that has a `src` folder, as `a` or `a/b`. */
    fun modulesWithSources(compose: Path): List<String> {
        val root = compose.toFile()
        val top = root.listFiles().orEmpty().filter(File::isDirectory)
        val candidates = top + top.flatMap { it.listFiles().orEmpty().filter(File::isDirectory) }
        return candidates
            .filter { File(it, "src").isDirectory && File(it, "build.gradle.kts").isFile }
            .map { it.toPath().relativeTo(compose).invariantSeparatorsPathString }
            .sorted()
    }

    private fun codeLines(text: String): Sequence<String> =
        text.lineSequence().filterNot { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
        }

    private fun File.invariantPath(): String = "/${toPath().invariantSeparatorsPathString}/"

    private val excludedSegments = listOf("/build/", "/generated/", "/ksp/")
}
