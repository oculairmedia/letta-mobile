package com.letta.mobile.ui.screens.skills

import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.activeBackendIsIroh
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.repository.iroh.IrohSkillsApi
import com.letta.mobile.data.skills.Skill
import com.letta.mobile.data.skills.SkillApi
import com.letta.mobile.data.skills.SkillsApi
import com.letta.mobile.data.transport.api.IChannelTransport
import javax.inject.Inject

/**
 * Android's [SkillsApi] for the shared Skills page (letta-mobile-zxk2n.32), resolved per call
 * against the active backend: over the Iroh admin_rpc channel on an `iroh://` backend (the same
 * IrohSkillsApi desktop binds), otherwise the HTTP `/v1/skills` routes on the admin API.
 */
class SessionSkillsApi @Inject constructor(
    private val settingsRepository: ISettingsRepository,
    private val channelTransport: IChannelTransport,
    private val lettaApiClient: LettaApiClient,
) : SkillsApi {
    private val iroh by lazy { IrohSkillsApi(IrohAdminRpcAgentDirectory(channelTransport)) }

    override suspend fun listSkills(): List<Skill> = delegate().listSkills()

    override suspend fun listAgentSkills(agentId: String): List<Skill> = delegate().listAgentSkills(agentId)

    override suspend fun installSkill(agentId: String, skillName: String) = delegate().installSkill(agentId, skillName)

    override suspend fun uninstallSkill(agentId: String, skillName: String) = delegate().uninstallSkill(agentId, skillName)

    /** The HTTP client is the admin API's shared one (auth already installed); it is never closed here. */
    private suspend fun delegate(): SkillsApi {
        if (settingsRepository.activeBackendIsIroh()) return iroh
        val session = lettaApiClient.session()
        val config = LettaConfig(id = HTTP_CONFIG_ID, mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = session.baseUrl)
        return SkillApi(config, session.client)
    }

    private companion object {
        const val HTTP_CONFIG_ID = "skills-admin-api"
    }
}
