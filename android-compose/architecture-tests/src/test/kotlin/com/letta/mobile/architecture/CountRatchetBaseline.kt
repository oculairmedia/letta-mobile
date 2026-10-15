package com.letta.mobile.architecture

import java.nio.file.Path
import kotlin.io.path.readLines

/**
 * A committed `<path> <count>` list of known per-file debt (letta-mobile-by4vm.23). Blank lines
 * and `#` comments are ignored; entries must be sorted by path with one line per path.
 *
 * A file whose count rises above its line (or that appears without one) is always reported as
 * NEW. With [Shrink.MUST_UPDATE] a file whose count falls is reported as STALE, so the baseline
 * is lowered in the same change that fixes the debt; with [Shrink.FREE] shrinking needs no edit.
 */
internal object CountRatchetBaseline {
    enum class Shrink { MUST_UPDATE, FREE }

    fun read(path: Path): List<Pair<String, Int>> =
        path.readLines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.map { line ->
            val parts = line.split(' ')
            require(parts.size == 2 && parts[1].toIntOrNull() != null) { "Malformed baseline line: '$line'" }
            parts[0] to parts[1].toInt()
        }

    fun drift(
        actual: Map<String, Int>,
        baseline: List<Pair<String, Int>>,
        baselineName: String,
        shrink: Shrink,
    ): List<String> {
        val known = baseline.toMap()
        return buildList {
            actual.toSortedMap().forEach { (path, count) ->
                val allowed = known[path] ?: 0
                if (count > allowed) add("NEW: $path has $count, $baselineName allows $allowed; fix it rather than raising the baseline")
            }
            if (shrink == Shrink.MUST_UPDATE) {
                known.toSortedMap().forEach { (path, allowed) ->
                    val count = actual[path] ?: 0
                    if (count < allowed) add("STALE: $path has $count, lower its $baselineName line from $allowed (delete it at 0)")
                }
            }
            val paths = baseline.map { it.first }
            if (paths != paths.sorted() || paths.size != known.size) {
                add("$baselineName must be sorted by path with one unique entry per line")
            }
        }
    }

    /** Renders [counts] in baseline format, for regenerating a baseline by hand. */
    fun render(counts: Map<String, Int>): String =
        counts.toSortedMap().entries.joinToString(separator = "\n", postfix = "\n") { "${it.key} ${it.value}" }
}
