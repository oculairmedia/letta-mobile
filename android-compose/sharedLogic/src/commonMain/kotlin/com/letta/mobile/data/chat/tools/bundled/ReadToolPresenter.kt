package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.chat.tools.ToolInset
import com.letta.mobile.data.chat.tools.ToolMatcher
import com.letta.mobile.data.chat.tools.ToolPresenter
import com.letta.mobile.data.chat.tools.ToolPresenterScope
import com.letta.mobile.data.chat.tools.ToolStepLine
import com.letta.mobile.data.chat.tools.ToolVerb

/**
 * letta-code's `Read` (and its other toolsets' `read_file`): "Read src/Main.kt", opening to what it
 * read, its chip opening the file at the lines it read (`offset`, `limit`).
 */
object ReadToolPresenter : ToolPresenter {
    override val id: String = "read"

    override val matcher: ToolMatcher = ToolMatcher(listOf("Read", "read_file", "read_file_gemini", "ReadFile"))

    override fun line(call: ToolCallFacts, scope: ToolPresenterScope): ToolStepLine = ToolStepLine(
        verb = ToolVerb.Read,
        target = call.filePath().orEmpty(),
        previews = filePreview(call.filePath(), linesRead(call)),
        presenterId = id,
    )

    override fun inset(call: ToolCallFacts, scope: ToolPresenterScope): ToolInset? = call.resultText()?.let(ToolInset::Text)

    /** The 1-based lines read: from `offset` (1 when absent) for `limit` lines; null when the whole file was read. */
    private fun linesRead(call: ToolCallFacts): IntRange? {
        val limit = call.int("limit")?.takeIf { it > 0 }
        val offset = call.int("offset")?.takeIf { it > 0 }
        if (limit == null && offset == null) return null
        val first = offset ?: 1
        return first until first + (limit ?: 1)
    }
}
