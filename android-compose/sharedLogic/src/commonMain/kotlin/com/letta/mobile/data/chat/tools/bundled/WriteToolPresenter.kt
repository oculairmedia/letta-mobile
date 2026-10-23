package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.chat.tools.ToolInset
import com.letta.mobile.data.chat.tools.ToolMatcher
import com.letta.mobile.data.chat.tools.ToolPresenter
import com.letta.mobile.data.chat.tools.ToolPresenterScope
import com.letta.mobile.data.chat.tools.ToolStepLine
import com.letta.mobile.data.chat.tools.ToolVerb

/**
 * letta-code's `Write` (and `write_file`): "Wrote notes.md · 12 lines", opening to the first lines it
 * wrote with their line numbers and "… N more lines" under them; the whole file is the chip's.
 */
object WriteToolPresenter : ToolPresenter {
    override val id: String = "write"

    override val matcher: ToolMatcher = ToolMatcher(listOf("Write", "write_file", "write_file_gemini", "WriteFile"))

    override fun line(call: ToolCallFacts, scope: ToolPresenterScope): ToolStepLine {
        val path = call.filePath()
        val lines = call.string(CONTENT)?.let(::lineCount)
        return ToolStepLine(
            verb = ToolVerb.Wrote,
            target = listOfNotNull(path, lines?.let(::linesLabel)).joinToString(SEPARATOR),
            previews = filePreview(path),
            presenterId = id,
        )
    }

    override fun inset(call: ToolCallFacts, scope: ToolPresenterScope): ToolInset? =
        call.string(CONTENT)?.let { ToolInset.Text(numberedHead(it, HEAD_LINES)) }

    private const val CONTENT = "content"
    private const val HEAD_LINES = 6
}
