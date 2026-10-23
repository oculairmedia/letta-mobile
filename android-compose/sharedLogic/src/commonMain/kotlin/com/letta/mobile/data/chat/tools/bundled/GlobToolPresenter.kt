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
 * letta-code's `Glob` (and `glob`, `list_dir`, `LS`): "Searched *.kt in src · 12 files", opening to the
 * files it found as chips, each opening its file in the preview. The first [MAX_CHIPS] found are chips;
 * the count says how many there were.
 */
object GlobToolPresenter : ToolPresenter {
    override val id: String = "glob"

    override val matcher: ToolMatcher =
        ToolMatcher(listOf("Glob", "glob", "glob_gemini", "FindFiles", "list_dir", "LS", "list_directory"))

    override fun line(call: ToolCallFacts, scope: ToolPresenterScope): ToolStepLine {
        val pattern = call.string("pattern")
        val root = call.searchRoot()
        val found = foundPaths(call)
        val searched = pattern?.let { inRoot(it, root) } ?: root.orEmpty()
        val count = found.size.takeIf { call.ran() }?.let { if (it == 1) "1 file" else "$it files" }
        return ToolStepLine(
            verb = ToolVerb.Searched,
            target = listOfNotNull(searched.ifBlank { null }, count).joinToString(SEPARATOR),
            previews = found.take(MAX_CHIPS).map { ToolPreviewTarget.File(it) },
            presenterId = id,
        )
    }

    // The approved mockup: what a glob found is its chips, not a text dump.
    override fun inset(call: ToolCallFacts, scope: ToolPresenterScope): ToolInset? = null

    /**
     * The paths the result lists, one per line; prose lines (a "No files found" note) are not paths, and
     * a failed glob found nothing.
     */
    private fun foundPaths(call: ToolCallFacts): List<String> =
        call.resultText()?.takeUnless { call.failed }?.lineSequence()
            ?.map { it.trim() }?.filter { it.isNotEmpty() && ' ' !in it }?.toList().orEmpty()

    private const val MAX_CHIPS = 24
}
