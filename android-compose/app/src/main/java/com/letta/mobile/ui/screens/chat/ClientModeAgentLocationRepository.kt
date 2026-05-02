package com.letta.mobile.ui.screens.chat

import com.letta.mobile.bot.protocol.BotAgentInfo
import com.letta.mobile.bot.protocol.WsBotClient
import com.letta.mobile.data.repository.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * Reads client-mode agent filesystem location metadata from the target lettabot
 * server. Newer lettabot builds can expose these fields in /api/v1/status
 * agent_details. Older servers simply return nulls and the UI falls back to the
 * project start path or a user-sent location-change instruction.
 */
class ClientModeAgentLocationRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    suspend fun getLocation(agentId: String): ClientModeAgentLocation? {
        val baseUrl = settingsRepository.observeClientModeBaseUrl().first().trim()
        if (baseUrl.isBlank()) return null
        val apiKey = settingsRepository.getClientModeApiKey()?.trim()?.takeIf { it.isNotBlank() }

        return withTimeout(10_000) {
            WsBotClient(baseUrl = baseUrl, apiKey = apiKey).use { client ->
                val status = client.getStatus()
                val agent = status.agentDetails.firstOrNull { it.id == agentId }
                    ?: status.agentDetails.firstOrNull { it.name == agentId }
                    ?: status.agentDetails.firstOrNull()
                agent?.toLocation()
            }
        }
    }

    private fun BotAgentInfo.toLocation(): ClientModeAgentLocation = ClientModeAgentLocation(
        currentPath = currentWorkingDirectory?.takeIf { it.isNotBlank() },
        defaultPath = defaultWorkingDirectory?.takeIf { it.isNotBlank() }
            ?: projectPath?.takeIf { it.isNotBlank() },
    )
}

data class ClientModeAgentLocation(
    val currentPath: String?,
    val defaultPath: String?,
)
