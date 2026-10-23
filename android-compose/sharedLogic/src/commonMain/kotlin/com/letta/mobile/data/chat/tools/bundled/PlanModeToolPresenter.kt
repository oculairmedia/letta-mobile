package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.chat.tools.ToolArguments
import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.chat.tools.ToolInset
import com.letta.mobile.data.chat.tools.ToolMatcher
import com.letta.mobile.data.chat.tools.ToolPresenter
import com.letta.mobile.data.chat.tools.ToolPresenterScope
import com.letta.mobile.data.chat.tools.ToolStepLine
import com.letta.mobile.data.chat.tools.ToolVerb

/**
 * letta-code's `EnterPlanMode` and `ExitPlanMode`. Hosts draw these as thin "Entered plan mode" /
 * "Left plan mode" dividers ([isPlanModeChange], [isLeaving]); a plan waiting on the person keeps
 * its approval card. The line is "Planned", its target the plan's first line, opening to the plan.
 */
object PlanModeToolPresenter : ToolPresenter {
    private val ENTER = listOf("EnterPlanMode", "enter_plan_mode")
    private val LEAVE = listOf("ExitPlanMode", "exit_plan_mode")

    override val id: String = "plan-mode"

    override val matcher: ToolMatcher = ToolMatcher(ENTER + LEAVE)

    override fun line(call: ToolCallFacts, scope: ToolPresenterScope): ToolStepLine = ToolStepLine(
        verb = ToolVerb.Planned,
        target = call.string(PLAN)?.let(ToolArguments::firstLine)?.removePrefix("# ").orEmpty(),
        presenterId = id,
    )

    override fun inset(call: ToolCallFacts, scope: ToolPresenterScope): ToolInset? = call.string(PLAN)?.let(ToolInset::Text)

    /** Whether [toolName] enters or leaves plan mode. */
    fun isPlanModeChange(toolName: String): Boolean = toolName in ENTER || toolName in LEAVE

    /** Whether [toolName] leaves plan mode (proposing its plan). */
    fun isLeaving(toolName: String): Boolean = toolName in LEAVE

    private const val PLAN = "plan"
}
