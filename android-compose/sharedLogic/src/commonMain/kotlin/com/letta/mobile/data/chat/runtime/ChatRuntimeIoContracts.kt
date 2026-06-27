package com.letta.mobile.data.chat.runtime

import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.data.timeline.TimelineTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

interface ChatGateway : TimelineTransport {
    suspend fun listConversations(limit: Int = DEFAULT_CONVERSATION_LIMIT, archiveStatus: String? = null): List<Conversation>
    suspend fun getConversation(conversationId: String): Conversation
    suspend fun deleteConversation(conversationId: String) {
        throw UnsupportedOperationException("deleteConversation is not supported by this gateway")
    }

    companion object {
        const val DEFAULT_CONVERSATION_LIMIT = 40
    }
}

interface ChatSessionGraph<out Repositories : SessionRepositoryGraph> {
    val repositories: Repositories
    val gateway: ChatGateway

    fun close()
}

interface BackendConfigStore {
    val activeConfig: StateFlow<LettaConfig?>

    suspend fun loadActiveConfig(): LettaConfig?

    suspend fun saveActiveConfig(config: LettaConfig)

    suspend fun recentBackendUrls(): List<String> = emptyList()
}

interface SecureTokenStore {
    fun observeHasToken(): Flow<Boolean>

    suspend fun loadToken(): String?

    suspend fun saveToken(token: String?)

    suspend fun clearToken() {
        saveToken(null)
    }
}

object BackendConfigPolicy {
    fun normalize(
        config: LettaConfig,
        fallback: LettaConfig,
        generatedIdPrefix: String,
    ): LettaConfig {
        val serverUrl = config.serverUrl.trim().ifBlank { fallback.serverUrl.trim() }
        return config.copy(
            id = config.id.trim().ifBlank { stableConfigId(generatedIdPrefix, serverUrl) },
            serverUrl = serverUrl,
            accessToken = config.accessToken?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    fun stableConfigId(prefix: String, serverUrl: String): String =
        "$prefix-${stableLowercaseHash(serverUrl.trim())}"

    private fun stableLowercaseHash(value: String): String {
        var hash = 0
        value.lowercase().forEach { char ->
            hash = hash * 31 + char.code
        }
        return hash.toUInt().toString(radix = 16)
    }
}

class SettingsRepositoryBackendConfigStore(
    private val settingsRepository: ISettingsRepository,
) : BackendConfigStore {
    override val activeConfig: StateFlow<LettaConfig?> = settingsRepository.activeConfig

    override suspend fun loadActiveConfig(): LettaConfig? =
        settingsRepository.activeConfig.value ?: settingsRepository.getActiveConfig().firstOrNull()

    override suspend fun saveActiveConfig(config: LettaConfig) {
        settingsRepository.saveConfig(config)
    }

    override suspend fun recentBackendUrls(): List<String> =
        settingsRepository.configs.value
            .map { it.serverUrl.trim() }
            .filter { it.isNotBlank() }
            .distinct()
}

class BackendConfigSecureTokenStore(
    private val configStore: BackendConfigStore,
) : SecureTokenStore {
    override fun observeHasToken(): Flow<Boolean> =
        configStore.activeConfig
            .map { it?.accessToken?.isNotBlank() == true }
            .distinctUntilChanged()

    override suspend fun loadToken(): String? =
        configStore.loadActiveConfig()?.accessToken?.trim()?.takeIf { it.isNotBlank() }

    override suspend fun saveToken(token: String?) {
        val current = configStore.loadActiveConfig()
        if (current == null) {
            require(token.isNullOrBlank()) { "Cannot save token without an active backend config" }
            return
        }
        configStore.saveActiveConfig(
            current.copy(accessToken = token?.trim()?.takeIf { it.isNotBlank() }),
        )
    }
}
