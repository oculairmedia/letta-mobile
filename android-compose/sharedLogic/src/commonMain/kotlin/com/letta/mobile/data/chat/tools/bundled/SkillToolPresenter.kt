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
 * letta-code's `Skill`: "Used skill pdf · Launching skill: pdf", what it did being the first line its
 * result says, opening to the whole result.
 */
object SkillToolPresenter : ToolPresenter {
    override val id: String = "skill"

    override val matcher: ToolMatcher = ToolMatcher(listOf("Skill", "skill", "load_skill"))

    override fun line(call: ToolCallFacts, scope: ToolPresenterScope): ToolStepLine {
        val name = SKILL_KEYS.firstNotNullOfOrNull(call::string)
        val did = call.resultText()?.takeUnless { call.failed }?.let(ToolArguments::firstLine)?.ifBlank { null }
        return ToolStepLine(
            verb = ToolVerb.Used,
            target = listOfNotNull(name?.let { "skill $it" } ?: "skill", did).joinToString(SEPARATOR),
            presenterId = id,
        )
    }

    override fun inset(call: ToolCallFacts, scope: ToolPresenterScope): ToolInset? = call.resultInset()

    private val SKILL_KEYS = listOf("skill", "command", "name")
}
