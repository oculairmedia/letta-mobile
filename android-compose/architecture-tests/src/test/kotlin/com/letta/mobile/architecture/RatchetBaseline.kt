package com.letta.mobile.architecture

import java.nio.file.Path
import kotlin.io.path.readLines

/**
 * A committed list of known debt that may only shrink (letta-mobile-by4vm.27). Blank lines and
 * `#` comments are ignored. [drift] reports entries that are new (not baselined) and entries that
 * are stale (baselined but gone), so fixing debt forces the matching line to be deleted.
 */
internal object RatchetBaseline {
    fun read(path: Path): List<String> =
        path.readLines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }

    fun drift(actual: Collection<String>, baseline: List<String>, baselineName: String): List<String> {
        val found = actual.toSet()
        val known = baseline.toSet()
        return buildList {
            (found - known).sorted().forEach { add("NEW, fix it rather than baselining it: $it") }
            (known - found).sorted().forEach { add("STALE, delete this line from $baselineName: $it") }
            if (baseline != baseline.sorted() || baseline.size != known.size) {
                add("$baselineName must be sorted with one unique entry per line")
            }
        }
    }
}
