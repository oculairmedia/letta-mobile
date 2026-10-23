package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.chat.tools.ToolInset
import com.letta.mobile.data.chat.tools.ToolMatcher
import com.letta.mobile.data.chat.tools.ToolPresenter
import com.letta.mobile.data.chat.tools.ToolPresenterScope
import com.letta.mobile.data.chat.tools.ToolStepLine
import com.letta.mobile.data.chat.tools.ToolVerb

/**
 * letta-code's `Grep` (and `grep_files`, `search_file_content`): `Searched "TODO" in src`, opening to
 * its hits, each `path:line` a link to that line of the file.
 */
object GrepToolPresenter : ToolPresenter {
    override val id: String = "grep"

    override val matcher: ToolMatcher = ToolMatcher(listOf("Grep", "grep", "grep_files", "search_file_content", "SearchText"))

    override fun line(call: ToolCallFacts, scope: ToolPresenterScope): ToolStepLine = ToolStepLine(
        verb = ToolVerb.Searched,
        target = call.string("pattern")?.let { inRoot("\"$it\"", call.searchRoot()) }.orEmpty(),
        presenterId = id,
    )

    override fun inset(call: ToolCallFacts, scope: ToolPresenterScope): ToolInset? = call.resultText()?.let(ToolInset::Text)
}
