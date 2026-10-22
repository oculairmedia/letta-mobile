package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.chat.tools.ShellTools
import com.letta.mobile.data.chat.tools.ToolArguments
import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.chat.tools.ToolInset
import com.letta.mobile.data.chat.tools.ToolMatcher
import com.letta.mobile.data.chat.tools.ToolPresenter
import com.letta.mobile.data.chat.tools.ToolPresenterScope
import com.letta.mobile.data.chat.tools.ToolStepLine
import com.letta.mobile.data.chat.tools.ToolVerb

/**
 * letta-code's `Bash` (and the shell tools of its other toolsets). A command a CLI's command
 * presenter claims reads as that presenter draws it; any other reads "Ran <command>", opening to
 * `dir $ command` then its output, failed only when it exited non-zero.
 */
object BashToolPresenter : ToolPresenter {
    override val id: String = "bash"

    override val matcher: ToolMatcher = ToolMatcher(ShellTools.NAMES.toList())

    override fun line(call: ToolCallFacts, scope: ToolPresenterScope): ToolStepLine {
        val script = scriptOf(call)
        scope.commands.find(script)?.let { return it.presenter.line(call, it.command) }
        return ToolStepLine(
            verb = ToolVerb.Ran,
            target = ToolArguments.firstLine(script),
            failure = ToolArguments.exitFailure(call.result),
            presenterId = id,
        )
    }

    override fun inset(call: ToolCallFacts, scope: ToolPresenterScope): ToolInset? {
        scope.commands.find(scriptOf(call))?.let { return it.presenter.inset(call, it.command) }
        return ShellTools.commandInset(call)
    }

    private fun scriptOf(call: ToolCallFacts): String = call.arguments?.let(ShellTools::scriptOf).orEmpty()
}
