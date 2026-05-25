package com.letta.mobile.testutil

import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.repository.LastChatSelection
import com.letta.mobile.data.repository.api.ISettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull

/**
 * Hand-written test double for [ISettingsRepository] — see
 * letta-mobile-0dnn for why mockk on the concrete final
 * [com.letta.mobile.data.repository.SettingsRepository] is forbidden
 * in unit tests (StateFlow / EncryptedSharedPreferences state on a
 * final class leaks across the reused daemon JVM).
 *
 * Backing flows are public so tests can either seed initial state via
 * the constructor or mutate it mid-test by calling `.value =` / `.emit`.
 */
class FakeSettingsRepository(
    initialActiveConfig: LettaConfig? = null,
    initialResumeRecentConversation: Boolean = false,
) : ISettingsRepository {

    val activeConfigState: MutableStateFlow<LettaConfig?> =
        MutableStateFlow(initialActiveConfig)

    val configsState: MutableStateFlow<List<LettaConfig>> =
        MutableStateFlow(initialActiveConfig?.let(::listOf).orEmpty())

    val resumeRecentConversation: MutableStateFlow<Boolean> =
        MutableStateFlow(initialResumeRecentConversation)

    val pinnedProjectIds: MutableStateFlow<Set<String>> =
        MutableStateFlow(emptySet())

    val pinnedAgentIds: MutableStateFlow<Set<String>> =
        MutableStateFlow(emptySet())

    val pinnedAgentOrder: MutableStateFlow<List<String>> =
        MutableStateFlow(emptyList())

    val pinnedShortcutOrder: MutableStateFlow<List<String>> =
        MutableStateFlow(emptyList())

    private val favoriteAgentIdState = MutableStateFlow<String?>(null)
    private val adminAgentIdState = MutableStateFlow<String?>(null)
    private val lastChatSelectionState = MutableStateFlow<LastChatSelection?>(null)
    private val themeState = MutableStateFlow(AppTheme.SYSTEM)
    private val themePresetState = MutableStateFlow(ThemePreset.DEFAULT)
    private val dynamicColorState = MutableStateFlow(true)
    private val chatBackgroundKeyState = MutableStateFlow("default")
    private val chatFontScaleState = MutableStateFlow(1f)
    private val enableProjectsState = MutableStateFlow(false)

    override val configs: StateFlow<List<LettaConfig>> = configsState.asStateFlow()

    override val activeConfig: StateFlow<LettaConfig?> = activeConfigState.asStateFlow()

    override val activeConfigChanges: Flow<LettaConfig> = activeConfigState
        .drop(1)
        .filterNotNull()
        .distinctUntilChanged { old, new -> old.id == new.id }

    override val favoriteAgentId: StateFlow<String?> = favoriteAgentIdState.asStateFlow()

    override val adminAgentId: StateFlow<String?> = adminAgentIdState.asStateFlow()

    override val lastChatSelection: StateFlow<LastChatSelection?> = lastChatSelectionState.asStateFlow()

    override fun getActiveConfig(): Flow<LettaConfig?> = activeConfigState

    override suspend fun saveConfig(config: LettaConfig) {
        configsState.value = configsState.value.filterNot { it.id == config.id } + config
    }

    override suspend fun setActiveConfigId(id: String) {
        activeConfigState.value = configsState.value.firstOrNull { it.id == id }
    }

    override suspend fun deleteConfig(id: String) {
        configsState.value = configsState.value.filterNot { it.id == id }
        if (activeConfigState.value?.id == id) {
            activeConfigState.value = null
        }
    }

    override suspend fun clearAllData() {
        configsState.value = emptyList()
        activeConfigState.value = null
        lastChatSelectionState.value = null
    }

    override fun getTheme(): Flow<AppTheme> = themeState

    override fun getThemePreset(): Flow<ThemePreset> = themePresetState

    override fun getDynamicColor(): Flow<Boolean> = dynamicColorState

    override fun observeResumeRecentConversation(): Flow<Boolean> = resumeRecentConversation

    override fun setLastChatSelection(agentId: String, agentName: String?, conversationId: String?) {
        lastChatSelectionState.value = LastChatSelection(
            agentId = agentId,
            agentName = agentName,
            conversationId = conversationId,
        )
    }

    override fun getPinnedAgentIds(): Flow<Set<String>> = pinnedAgentIds

    override fun getPinnedAgentOrder(): Flow<List<String>> = pinnedAgentOrder

    val pinnedConversationIds: MutableStateFlow<Set<String>> =
        MutableStateFlow(emptySet())

    override fun getPinnedConversationIds(): Flow<Set<String>> = pinnedConversationIds

    override suspend fun setConversationPinned(conversationId: String, pinned: Boolean) {
        pinnedConversationIds.value = if (pinned) {
            pinnedConversationIds.value + conversationId
        } else {
            pinnedConversationIds.value - conversationId
        }
    }

    override fun setFavoriteAgentId(agentId: String?) {
        favoriteAgentIdState.value = agentId
    }

    override suspend fun setAgentPinned(agentId: String, pinned: Boolean) {
        pinnedAgentIds.value = if (pinned) {
            pinnedAgentIds.value + agentId
        } else {
            pinnedAgentIds.value - agentId
        }
        pinnedAgentOrder.value = if (pinned) {
            if (agentId in pinnedAgentOrder.value) pinnedAgentOrder.value
            else pinnedAgentOrder.value + agentId
        } else {
            pinnedAgentOrder.value - agentId
        }
    }

    override suspend fun setPinnedAgentOrder(order: List<String>) {
        val deduped = order.distinct()
        pinnedAgentOrder.value = deduped
        pinnedAgentIds.value = deduped.toSet()
    }

    override fun getPinnedProjectIds(): Flow<Set<String>> = pinnedProjectIds

    override suspend fun setProjectPinned(projectId: String, pinned: Boolean) {
        pinnedProjectIds.value = if (pinned) {
            pinnedProjectIds.value + projectId
        } else {
            pinnedProjectIds.value - projectId
        }
    }

    override fun getPinnedShortcutOrder(): Flow<List<String>> = pinnedShortcutOrder

    override suspend fun setPinnedShortcutOrder(order: List<String>) {
        pinnedShortcutOrder.value = order
    }

    override suspend fun addPinnedShortcut(name: String) {
        if (name !in pinnedShortcutOrder.value) {
            pinnedShortcutOrder.value = pinnedShortcutOrder.value + name
        }
    }

    override suspend fun removePinnedShortcut(name: String) {
        pinnedShortcutOrder.value = pinnedShortcutOrder.value - name
    }

    val pinnedItemsOrder: MutableStateFlow<List<String>> =
        MutableStateFlow(emptyList())

    override fun getPinnedItemsOrder(): Flow<List<String>> = pinnedItemsOrder

    override suspend fun setPinnedItemsOrder(order: List<String>) {
        pinnedItemsOrder.value = order.distinct()
    }

    val pinnedAgentNames: MutableStateFlow<Map<String, String>> =
        MutableStateFlow(emptyMap())

    override fun getPinnedAgentNames(): Flow<Map<String, String>> = pinnedAgentNames

    override suspend fun upsertPinnedAgentName(id: String, name: String) {
        pinnedAgentNames.value = pinnedAgentNames.value + (id to name)
    }

    override suspend fun removePinnedAgentName(id: String) {
        pinnedAgentNames.value = pinnedAgentNames.value - id
    }

    override fun getChatBackgroundKey(): Flow<String> = chatBackgroundKeyState

    override suspend fun setChatBackgroundKey(key: String) {
        chatBackgroundKeyState.value = key
    }

    override fun getChatFontScale(): Flow<Float> = chatFontScaleState
    override suspend fun setChatFontScale(scale: Float) {
        chatFontScaleState.value = scale
    }

    override fun getEnableProjects(): Flow<Boolean> = enableProjectsState

    override suspend fun setTheme(theme: AppTheme) {
        themeState.value = theme
    }

    override suspend fun setThemePreset(themePreset: ThemePreset) {
        themePresetState.value = themePreset
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        dynamicColorState.value = enabled
    }

    override suspend fun setEnableProjects(enabled: Boolean) {
        enableProjectsState.value = enabled
    }
}
