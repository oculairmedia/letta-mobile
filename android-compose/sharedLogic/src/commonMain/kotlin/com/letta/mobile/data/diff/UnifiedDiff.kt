package com.letta.mobile.data.diff

/** The role of a line in a parsed unified diff. */
enum class DiffLineKind { FileHeader, Hunk, Added, Removed, Context }

/**
 * One rendered diff line: its [kind], the display [text] (marker stripped), and
 * the old/new line numbers (null on the side where the line doesn't exist).
 */
data class DiffLine(
    val kind: DiffLineKind,
    val text: String,
    val oldLine: Int?,
    val newLine: Int?,
)

/**
 * Parses unified-diff text (e.g. a file-edit tool's output) into renderable
 * lines with old/new line numbers, matching the "Diff review" board. Shared in
 * commonMain so the desktop diff block and a future mobile one render identically.
 */
object UnifiedDiff {
    private val HUNK = Regex("""@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

    /** A conservative "is this a unified diff?" check — requires a real hunk header. */
    fun looksLikeDiff(text: String): Boolean =
        text.lineSequence().any { it.startsWith("@@ ") && HUNK.containsMatchIn(it) }

    fun parse(text: String): List<DiffLine> {
        val out = mutableListOf<DiffLine>()
        var oldLine = 0
        var newLine = 0
        for (raw in text.split('\n')) {
            when {
                raw.startsWith("@@") -> {
                    HUNK.find(raw)?.let {
                        oldLine = it.groupValues[1].toInt()
                        newLine = it.groupValues[2].toInt()
                    }
                    out.add(DiffLine(DiffLineKind.Hunk, raw, null, null))
                }
                raw.startsWith("diff --git") ||
                    raw.startsWith("index ") ||
                    raw.startsWith("--- ") ||
                    raw.startsWith("+++ ") -> out.add(DiffLine(DiffLineKind.FileHeader, raw, null, null))
                raw.startsWith("+") -> {
                    out.add(DiffLine(DiffLineKind.Added, raw.substring(1), null, newLine))
                    newLine++
                }
                raw.startsWith("-") -> {
                    out.add(DiffLine(DiffLineKind.Removed, raw.substring(1), oldLine, null))
                    oldLine++
                }
                else -> {
                    out.add(DiffLine(DiffLineKind.Context, raw.removePrefix(" "), oldLine, newLine))
                    oldLine++
                    newLine++
                }
            }
        }
        return out
    }

    /** Added / removed line counts, for a "+N -N" summary chip. */
    fun stats(lines: List<DiffLine>): Pair<Int, Int> =
        lines.count { it.kind == DiffLineKind.Added } to lines.count { it.kind == DiffLineKind.Removed }
}
