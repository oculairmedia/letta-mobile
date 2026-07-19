package com.letta.mobile.desktop.skills

import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.skills.Skill
import com.letta.mobile.data.skills.SkillsApi

class DesktopIrohSkillsApi(
    private val directory: IrohAdminRpcAgentDirectory,
) : SkillsApi {
    override suspend fun listSkills(): List<Skill> = directory.listSkills()

    override suspend fun listAgentSkills(agentId: String): List<Skill> = directory.listSkills(agentId)

    override suspend fun installSkill(agentId: String, skillName: String) {
        directory.installSkill(agentId, skillName)
    }

    override suspend fun uninstallSkill(agentId: String, skillName: String) {
        directory.uninstallSkill(agentId, skillName)
    }
}
