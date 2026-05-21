package com.letta.mobile.testutil

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.ISettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

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
    initialClientModeEnabled: Boolean = false,
    initialClientModeBaseUrl: String = "",
    initialClientModeApiKey: String? = null,
) : ISettingsRepository {

    val activeConfigState: MutableStateFlow<LettaConfig?> =
        MutableStateFlow(initialActiveConfig)

    val clientModeEnabled: MutableStateFlow<Boolean> =
        MutableStateFlow(initialClientModeEnabled)

    val clientModeBaseUrl: MutableStateFlow<String> =
        MutableStateFlow(initialClientModeBaseUrl)

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

    var apiKey: String? = initialClientModeApiKey

    override val activeConfig: StateFlow<LettaConfig?> = activeConfigState.asStateFlow()

    override val activeConfigChanges: Flow<LettaConfig> = emptyFlow()

    override val favoriteAgentId: StateFlow<String?> = favoriteAgentIdState.asStateFlow()

    override val adminAgentId: StateFlow<String?> = adminAgentIdState.asStateFlow()

    override fun getActiveConfig(): Flow<LettaConfig?> = activeConfigState

    override fun observeClientModeEnabled(): Flow<Boolean> = clientModeEnabled

    override fun observeClientModeBaseUrl(): Flow<String> = clientModeBaseUrl

    override fun getClientModeApiKey(): String? = apiKey

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
}
