package com.letta.mobile.data.repository

import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.backendIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf

/**
 * Minimal [ISettingsRepository] that only carries an active [LettaConfig].
 * Used by desktop to drive Iroh admin_rpc sources that gate on
 * [activeBackendIsIroh] without Room/Hilt settings storage.
 */
class ActiveConfigSettingsRepository(
    initialActiveConfig: LettaConfig?,
) : ISettingsRepository {
    private val activeConfigState = MutableStateFlow(initialActiveConfig)
    private val configsState = MutableStateFlow(initialActiveConfig?.let(::listOf).orEmpty())

    fun updateActiveConfig(config: LettaConfig?) {
        activeConfigState.value = config
        configsState.value = config?.let(::listOf).orEmpty()
    }

    override val configs: StateFlow<List<LettaConfig>> = configsState.asStateFlow()
    override val activeConfig: StateFlow<LettaConfig?> = activeConfigState.asStateFlow()
    override val activeConfigChanges: Flow<LettaConfig> = activeConfigState
        .drop(1)
        .filterNotNull()
        .distinctUntilChanged { old, new -> old.backendIdentity() == new.backendIdentity() }
    override val favoriteAgentId: StateFlow<String?> = MutableStateFlow(null)
    override val adminAgentId: StateFlow<String?> = MutableStateFlow(null)
    override val lastChatSelection: StateFlow<LastChatSelection?> = MutableStateFlow(null)
    override val huggingFaceToken: StateFlow<String?> = MutableStateFlow(null)

    override fun getActiveConfig(): Flow<LettaConfig?> = activeConfig
    override suspend fun saveConfig(config: LettaConfig) = updateActiveConfig(config)
    override suspend fun setActiveConfigId(id: String) = Unit
    override suspend fun deleteConfig(id: String) = Unit
    override suspend fun clearAllData() = Unit
    override suspend fun setHuggingFaceToken(token: String?) = Unit
    override fun getTheme(): Flow<AppTheme> = flowOf(AppTheme.SYSTEM)
    override fun getThemePreset(): Flow<ThemePreset> = flowOf(ThemePreset.DEFAULT)
    override fun getDynamicColor(): Flow<Boolean> = flowOf(true)
    override fun observeResumeRecentConversation(): Flow<Boolean> = flowOf(false)
    override fun getPinnedAgentIds(): Flow<Set<String>> = flowOf(emptySet())
    override fun getPinnedAgentOrder(): Flow<List<String>> = flowOf(emptyList())
    override fun getPinnedConversationIds(): Flow<Set<String>> = flowOf(emptySet())
    override fun setLastChatSelection(agentId: String, agentName: String?, conversationId: String?) = Unit
    override suspend fun setConversationPinned(conversationId: String, pinned: Boolean) = Unit
    override fun setFavoriteAgentId(agentId: String?) = Unit
    override suspend fun setAgentPinned(agentId: String, pinned: Boolean) = Unit
    override suspend fun setPinnedAgentOrder(order: List<String>) = Unit
    override fun getPinnedProjectIds(): Flow<Set<String>> = flowOf(emptySet())
    override suspend fun setProjectPinned(projectId: String, pinned: Boolean) = Unit
    override fun getPinnedShortcutOrder(): Flow<List<String>> = flowOf(emptyList())
    override suspend fun setPinnedShortcutOrder(order: List<String>) = Unit
    override suspend fun addPinnedShortcut(name: String) = Unit
    override suspend fun removePinnedShortcut(name: String) = Unit
    override fun getPinnedItemsOrder(): Flow<List<String>> = flowOf(emptyList())
    override suspend fun setPinnedItemsOrder(order: List<String>) = Unit
    override fun getPinnedAgentNames(): Flow<Map<String, String>> = flowOf(emptyMap())
    override suspend fun upsertPinnedAgentName(id: String, name: String) = Unit
    override suspend fun removePinnedAgentName(id: String) = Unit
    override fun getChatBackgroundKey(): Flow<String> = flowOf("default")
    override suspend fun setChatBackgroundKey(key: String) = Unit
    override fun getChatFontScale(): Flow<Float> = flowOf(1f)
    override suspend fun setChatFontScale(scale: Float) = Unit
    override fun getEnableProjects(): Flow<Boolean> = flowOf(true)
    override fun getHapticsEnabled(): Flow<Boolean> = flowOf(true)
    override suspend fun setTheme(theme: AppTheme) = Unit
    override suspend fun setThemePreset(themePreset: ThemePreset) = Unit
    override suspend fun setDynamicColor(enabled: Boolean) = Unit
    override suspend fun setEnableProjects(enabled: Boolean) = Unit
    override suspend fun setHapticsEnabled(enabled: Boolean) = Unit
}
