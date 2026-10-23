package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.chat.tools.ToolInset
import com.letta.mobile.data.chat.tools.ToolMatcher
import com.letta.mobile.data.chat.tools.ToolPresenter
import com.letta.mobile.data.chat.tools.ToolPresenterScope
import com.letta.mobile.data.chat.tools.ToolStepLine
import com.letta.mobile.data.chat.tools.ToolVerb

/**
 * letta-code's `Glob` (and `glob`, `list_dir`, `LS`): "Searched *.kt in src", opening to the paths it
 * found, each a link to its file.
 */
object GlobToolPresenter : ToolPresenter {
    override val id: String = "glob"

    override val matcher: ToolMatcher =
        ToolMatcher(listOf("Glob", "glob", "glob_gemini", "FindFiles", "list_dir", "LS", "list_directory"))

    override fun line(call: ToolCallFacts, scope: ToolPresenterScope): ToolStepLine {
        val pattern = call.string("pattern")
        val root = call.searchRoot()
        return ToolStepLine(
            verb = ToolVerb.Searched,
            target = pattern?.let { inRoot(it, root) } ?: root.orEmpty(),
            presenterId = id,
        )
    }

    override fun inset(call: ToolCallFacts, scope: ToolPresenterScope): ToolInset? = call.resultText()?.let(ToolInset::Text)
}
