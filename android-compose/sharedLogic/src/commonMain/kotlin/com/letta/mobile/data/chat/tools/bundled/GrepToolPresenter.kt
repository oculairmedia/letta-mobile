package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.chat.tools.ToolInset
import com.letta.mobile.data.chat.tools.ToolMatcher
import com.letta.mobile.data.chat.tools.ToolPresenter
import com.letta.mobile.data.chat.tools.ToolPresenterScope
import com.letta.mobile.data.chat.tools.ToolPreviewTarget
import com.letta.mobile.data.chat.tools.ToolStepLine
import com.letta.mobile.data.chat.tools.ToolVerb

/**
 * letta-code's `Grep` (and `grep_files`, `search_file_content`): `Searched "TODO" in src · 4 matches`,
 * opening to its matches grouped by file, each with its line number, and a chip per match (the first
 * [MAX_CHIPS]) opening its file at that line. A result that lists only files (`files_with_matches`)
 * reads as those files.
 */
object GrepToolPresenter : ToolPresenter {
    override val id: String = "grep"

    override val matcher: ToolMatcher = ToolMatcher(listOf("Grep", "grep", "grep_files", "search_file_content", "SearchText"))

    override fun line(call: ToolCallFacts, scope: ToolPresenterScope): ToolStepLine {
        val hits = hitsOf(call)
        val searched = call.string("pattern")?.let { inRoot("\"$it\"", call.searchRoot()) }
        val count = hits.size.takeIf { call.ran() }?.let { if (it == 1) "1 match" else "$it matches" }
        return ToolStepLine(
            verb = ToolVerb.Searched,
            target = listOfNotNull(searched, count).joinToString(SEPARATOR),
            previews = hits.take(MAX_CHIPS).map { ToolPreviewTarget.File(it.path, it.line?.let { line -> line..line }) },
            presenterId = id,
        )
    }

    override fun inset(call: ToolCallFacts, scope: ToolPresenterScope): ToolInset? {
        val hits = hitsOf(call)
        if (hits.isEmpty()) return call.resultText()?.let(ToolInset::Text)
        val width = hits.mapNotNull { it.line }.maxOrNull()?.toString()?.length ?: 0
        val grouped = hits.groupBy { it.path }.entries.joinToString("\n") { (path, inFile) ->
            val lines = inFile.mapNotNull { hit -> hit.line?.let { "  ${it.toString().padStart(width)}  ${hit.text}" } }
            (listOf(path) + lines).joinToString("\n")
        }
        return ToolInset.Text(grouped)
    }

    /**
     * The result's matches, in order: `path:line:text` (or `path:line-text`) lines, or bare paths; a
     * path may start with a Windows drive (`C:\src\a.kt:3:text`). A failed grep matched nothing.
     */
    private fun hitsOf(call: ToolCallFacts): List<Hit> =
        call.resultText()?.takeUnless { call.failed }?.lineSequence()?.mapNotNull(::hitOf)?.toList().orEmpty()

    private fun hitOf(raw: String): Hit? {
        val line = raw.trimEnd()
        if (line.isEmpty()) return null
        val match = HIT.matchEntire(line) ?: return Hit(line, null, "").takeIf { ' ' !in line }
        return Hit(match.groupValues[1], match.groupValues[2].toInt(), match.groupValues[3])
    }

    /** One match: its file, its line (null when the result lists only files) and the matching text. */
    private class Hit(val path: String, val line: Int?, val text: String)

    private const val MAX_CHIPS = 12
    private val HIT = Regex("""^((?:[A-Za-z]:[\\/])?[^:\s][^:]*?):(\d+)[:-](.*)$""")
}
