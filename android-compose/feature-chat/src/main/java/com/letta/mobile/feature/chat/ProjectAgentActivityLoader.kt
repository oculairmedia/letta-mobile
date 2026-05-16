package com.letta.mobile.feature.chat

import com.letta.mobile.bot.protocol.BotAgentInfo
import com.letta.mobile.bot.protocol.InternalBotClient
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.FolderRepository
import kotlinx.coroutines.flow.first

internal class ProjectAgentActivityLoader(
    private val internalBotClient: InternalBotClient,
    private val agentRepository: AgentRepository,
    private val folderRepository: FolderRepository,
) {
    suspend fun load(
        project: ProjectChatContext,
        currentAgentId: String,
    ): List<ProjectAgentActivity> {
        val liveStatuses = runCatching { internalBotClient.listAgents() }
            .getOrDefault(emptyList())
            .associateBy { it.id }

        val folderId = project.lettaFolderId
        if (folderId.isNullOrBlank()) {
            return liveStatuses[currentAgentId]
                ?.let { listOf(it.toProjectAgentActivity(agent = null)) }
                ?: emptyList()
        }

        agentRepository.refreshAgentsIfStale(maxAgeMs = 60_000)
        val folderAgentIds = folderRepository.listAgentsForFolder(folderId)
        val agents = folderAgentIds.mapNotNull { id ->
            agentRepository.getCachedAgent(id)
                ?: runCatching { agentRepository.getAgent(id).first() }.getOrNull()
        }

        return agents.map { agent ->
            liveStatuses[agent.id.value].toProjectAgentActivity(agent)
        }.sortedWith(compareBy<ProjectAgentActivity> { it.statusLabel }.thenBy { it.name.lowercase() })
    }

    private fun BotAgentInfo?.toProjectAgentActivity(agent: Agent?): ProjectAgentActivity {
        val rawStatus = this?.status?.lowercase()
        val (statusLabel, statusTone) = when {
            rawStatus == null && agent?.lastRunCompletion != null -> "Idle" to ProjectAgentStatusTone.Neutral
            rawStatus == null -> "Disconnected" to ProjectAgentStatusTone.Neutral
            rawStatus.contains("error") || rawStatus.contains("fail") -> "Error" to ProjectAgentStatusTone.Error
            rawStatus.contains("working") || rawStatus.contains("running") || rawStatus.contains("busy") -> "Working" to ProjectAgentStatusTone.Busy
            rawStatus.contains("connected") || rawStatus.contains("ready") || rawStatus.contains("idle") -> rawStatus.replaceFirstChar { it.uppercase() } to ProjectAgentStatusTone.Good
            else -> rawStatus.replaceFirstChar { it.uppercase() } to ProjectAgentStatusTone.Neutral
        }

        val metadataWork = agent?.metadata?.get("current_work")?.toString()?.trim('"')
        val detail = metadataWork
            ?: agent?.lastRunCompletion
            ?: if (this != null) "Embedded bot session available" else null

        return ProjectAgentActivity(
            id = agent?.id?.value ?: this?.id.orEmpty(),
            name = agent?.name ?: this?.name.orEmpty(),
            statusLabel = statusLabel,
            statusTone = statusTone,
            detail = detail,
            model = agent?.model,
            lastActivity = agent?.updatedAt ?: agent?.createdAt,
        )
    }
}
