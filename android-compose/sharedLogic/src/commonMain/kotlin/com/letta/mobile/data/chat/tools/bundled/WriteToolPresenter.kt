package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.chat.tools.ToolInset
import com.letta.mobile.data.chat.tools.ToolMatcher
import com.letta.mobile.data.chat.tools.ToolPresenter
import com.letta.mobile.data.chat.tools.ToolPresenterScope
import com.letta.mobile.data.chat.tools.ToolStepLine
import com.letta.mobile.data.chat.tools.ToolVerb

/** letta-code's `Write` (and `write_file`): "Wrote src/Main.kt", opening to what it wrote, every line added. */
object WriteToolPresenter : ToolPresenter {
    override val id: String = "write"

    override val matcher: ToolMatcher = ToolMatcher(listOf("Write", "write_file", "write_file_gemini", "WriteFile"))

    override fun line(call: ToolCallFacts, scope: ToolPresenterScope): ToolStepLine = ToolStepLine(
        verb = ToolVerb.Wrote,
        target = call.filePath().orEmpty(),
        previews = filePreview(call.filePath()),
        presenterId = id,
    )

    override fun inset(call: ToolCallFacts, scope: ToolPresenterScope): ToolInset? =
        call.string("content")?.let { ToolInset.BeforeAfter(before = "", after = it) }
}
