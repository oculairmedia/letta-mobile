package com.letta.mobile.ui.screens.skills

import com.letta.mobile.data.backend.AppServerRouteUnavailable
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.repository.activeBackendIsIroh
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.repository.iroh.IrohSkillsApi
import com.letta.mobile.data.skills.Skill
import com.letta.mobile.data.skills.SkillsApi
import com.letta.mobile.data.transport.api.IChannelTransport
import javax.inject.Inject

/**
 * Android's [SkillsApi] for the shared Skills page (letta-mobile-zxk2n.32): over the Iroh admin_rpc
 * channel on an `iroh://` backend (the same IrohSkillsApi desktop binds). letta-mobile-ckmub.4: the
 * Letta REST `/v1/skills` fallback is gone, so other backends report that skills have no route.
 */
class SessionSkillsApi @Inject constructor(
    private val settingsRepository: ISettingsRepository,
    private val channelTransport: IChannelTransport,
) : SkillsApi {
    private val iroh by lazy { IrohSkillsApi(IrohAdminRpcAgentDirectory(channelTransport)) }

    override suspend fun listSkills(): List<Skill> = delegate("listSkills").listSkills()

    override suspend fun listAgentSkills(agentId: String): List<Skill> = delegate("listAgentSkills").listAgentSkills(agentId)

    override suspend fun installSkill(agentId: String, skillName: String) =
        delegate("installSkill").installSkill(agentId, skillName)

    override suspend fun uninstallSkill(agentId: String, skillName: String) =
        delegate("uninstallSkill").uninstallSkill(agentId, skillName)

    private suspend fun delegate(operation: String): SkillsApi {
        if (settingsRepository.activeBackendIsIroh()) return iroh
        throw AppServerRouteUnavailable(SessionRoute.SKILLS, "Skills.$operation")
    }
}
