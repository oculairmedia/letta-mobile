package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.skills.Skill
import com.letta.mobile.data.skills.SkillsApi

class IrohSkillsApi(
    private val directory: IrohAdminRpcAgentDirectory,
) : SkillsApi {
    override suspend fun listSkills(): List<Skill> = directory.listSkills()

    override suspend fun listAgentSkills(agentId: String): List<Skill> = directory.listSkills(AgentId(agentId))

    override suspend fun installSkill(agentId: String, skillName: String) {
        directory.installSkill(AgentId(agentId), SkillName(skillName))
    }

    override suspend fun uninstallSkill(agentId: String, skillName: String) {
        directory.uninstallSkill(AgentId(agentId), SkillName(skillName))
    }
}
