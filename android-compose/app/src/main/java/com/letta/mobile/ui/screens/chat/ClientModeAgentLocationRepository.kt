package com.letta.mobile.ui.screens.chat

import com.letta.mobile.bot.protocol.BotAgentInfo
import com.letta.mobile.bot.protocol.WsBotClient
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
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
        val (baseUrl, apiKey) = clientModeConnection() ?: return null

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

    suspend fun browseDirectories(path: String?): ClientModeDirectoryListing? {
        val (baseUrl, apiKey) = clientModeConnection() ?: return null

        return withTimeout(10_000) {
            WsBotClient(baseUrl = baseUrl, apiKey = apiKey).use { client ->
                val response = client.browseFilesystem(path = path)
                ClientModeDirectoryListing(
                    path = response.path,
                    parent = response.parent,
                    entries = response.entries
                        .filter { it.type == "directory" }
                        .map { entry ->
                            ClientModeDirectoryEntry(
                                name = entry.name,
                                path = entry.path,
                                isSymlink = entry.isSymlink,
                            )
                        }
                        .toImmutableList(),
                    truncated = response.truncated,
                )
            }
        }
    }

    private suspend fun clientModeConnection(): Pair<String, String?>? {
        val baseUrl = settingsRepository.observeClientModeBaseUrl().first().trim()
        if (baseUrl.isBlank()) return null
        val apiKey = settingsRepository.getClientModeApiKey()?.trim()?.takeIf { it.isNotBlank() }
        return baseUrl to apiKey
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

@androidx.compose.runtime.Immutable
data class ClientModeDirectoryListing(
    val path: String,
    val parent: String?,
    val entries: ImmutableList<ClientModeDirectoryEntry>,
    val truncated: Boolean,
)

@androidx.compose.runtime.Immutable
data class ClientModeDirectoryEntry(
    val name: String,
    val path: String,
    val isSymlink: Boolean,
)
