package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.chat.tools.ToolPresenter

/**
 * Task and agent tools: TaskOutput, TaskStop, Agent, Skill, TodoWrite, EnterPlanMode, ExitPlanMode.
 *
 * One area's registration entries, each presenter in its own file: the builder of this area adds
 * a line here, and [BundledToolPresenters] combines the areas, so no two areas share a list.
 */
internal object TaskToolPresenters {
    val tools: List<ToolPresenter> = listOf()
}
