package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.chat.tools.ToolArguments
import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.chat.tools.ToolInset
import com.letta.mobile.data.chat.tools.ToolMatcher
import com.letta.mobile.data.chat.tools.ToolPresenter
import com.letta.mobile.data.chat.tools.ToolPresenterScope
import com.letta.mobile.data.chat.tools.ToolPreviewTarget
import com.letta.mobile.data.chat.tools.ToolStepLine
import com.letta.mobile.data.chat.tools.ToolVerb

/**
 * letta-code's `Agent` (formerly `Task`): `Delegated to explore · "Audit the build" · 12 steps · done`,
 * what its result reports once the subagent has (its `<task-notification>`'s status and tool uses),
 * opening to the prompt it gave the subagent. Its chip opens the subagent's view; the subagent's id
 * is blank until the result names it, and the host then opens it by the call.
 */
object AgentToolPresenter : ToolPresenter {
    override val id: String = "agent"

    override val matcher: ToolMatcher = ToolMatcher(listOf("Agent", "Task", "dispatch_agent"))

    override fun line(call: ToolCallFacts, scope: ToolPresenterScope): ToolStepLine {
        val type = call.string("subagent_type") ?: call.string("subagentType")
        val task = (call.string("description") ?: call.string("prompt"))?.let(ToolArguments::firstLine)
        val report = call.result?.takeUnless { call.failed }
        val parts = listOfNotNull(
            type?.let { "to $it" },
            task?.let { "\"$it\"" },
            stepsOf(report)?.let { if (it == 1) "1 step" else "$it steps" },
            tagged(report, "status")?.let(::statusWord),
        )
        return ToolStepLine(
            verb = ToolVerb.Delegated,
            target = parts.joinToString(SEPARATOR),
            previews = listOf(ToolPreviewTarget.Subagent(agentIdOf(report).orEmpty())),
            presenterId = id,
        )
    }

    override fun inset(call: ToolCallFacts, scope: ToolPresenterScope): ToolInset? =
        call.string("prompt")?.let(ToolInset::Text) ?: call.resultInset()

    /** The subagent's tool uses, from its notification's `<usage>` (`tool_uses: 12`). */
    private fun stepsOf(report: String?): Int? =
        tagged(report, "usage")?.let { TOOL_USES.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /** The subagent's id, from its notification or the dispatch's JSON result. */
    private fun agentIdOf(report: String?): String? =
        tagged(report, "agent_id") ?: report?.let { AGENT_ID.find(it)?.groupValues?.get(1) }

    private fun statusWord(status: String): String = when (status.lowercase()) {
        "completed", "complete", "success", "succeeded" -> "done"
        else -> status.lowercase()
    }

    private val TOOL_USES = Regex("""tool_uses\s*[:=]\s*(\d+)""")
    private val AGENT_ID = Regex(""""(?:subagent_agent_id|subagentAgentId|agent_id|agentId)"\s*:\s*"([^"]+)"""")
}
