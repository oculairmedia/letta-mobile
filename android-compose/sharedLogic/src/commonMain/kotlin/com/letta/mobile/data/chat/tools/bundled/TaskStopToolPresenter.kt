package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.chat.tools.ToolInset
import com.letta.mobile.data.chat.tools.ToolMatcher
import com.letta.mobile.data.chat.tools.ToolPresenter
import com.letta.mobile.data.chat.tools.ToolPresenterScope
import com.letta.mobile.data.chat.tools.ToolStepLine
import com.letta.mobile.data.chat.tools.ToolVerb

/** letta-code's `TaskStop` (formerly `KillBash` / `KillShell`): "Stopped task bash_3", and nothing to open. */
object TaskStopToolPresenter : ToolPresenter {
    override val id: String = "task-stop"

    override val matcher: ToolMatcher = ToolMatcher(listOf("TaskStop", "KillBash", "KillShell", "task_stop"))

    override fun line(call: ToolCallFacts, scope: ToolPresenterScope): ToolStepLine =
        ToolStepLine(verb = ToolVerb.Stopped, target = taskLabel(call.taskId()), presenterId = id)

    // The approved mockup: stopping a task is its line.
    override fun inset(call: ToolCallFacts, scope: ToolPresenterScope): ToolInset? = null
}
