package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.LettaConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow surface of [com.letta.mobile.data.repository.SettingsRepository]
 * used by collaborators that need to be test-isolated. Promoted to an
 * interface so tests can substitute a hand-written
 * [com.letta.mobile.testutil.FakeSettingsRepository] instead of mocking
 * the stateful concrete class (mockk on a final class with internal
 * StateFlows / EncryptedSharedPreferences leaks across the daemon JVM —
 * see letta-mobile-0dnn).
 *
 * Members here are limited to what consumers (ShimBackendDetector,
 * ChannelHeartbeatSync, ClientModeController) actually read. The
 * concrete [SettingsRepository] still owns the full configuration
 * surface; widen this interface only when a new consumer needs it.
 */
interface ISettingsRepository {
    val activeConfig: StateFlow<LettaConfig?>
    val activeConfigChanges: Flow<LettaConfig>
    val favoriteAgentId: StateFlow<String?>
    val adminAgentId: StateFlow<String?>
    fun getActiveConfig(): Flow<LettaConfig?>
    fun observeClientModeEnabled(): Flow<Boolean>
    fun observeClientModeBaseUrl(): Flow<String>
    fun getClientModeApiKey(): String?
    fun getPinnedAgentIds(): Flow<Set<String>>
    fun getPinnedAgentOrder(): Flow<List<String>>
    fun setFavoriteAgentId(agentId: String?)
    suspend fun setAgentPinned(agentId: String, pinned: Boolean)
    suspend fun setPinnedAgentOrder(order: List<String>)
    fun getPinnedProjectIds(): Flow<Set<String>>
    suspend fun setProjectPinned(projectId: String, pinned: Boolean)
    fun getPinnedShortcutOrder(): Flow<List<String>>
    suspend fun setPinnedShortcutOrder(order: List<String>)
    suspend fun addPinnedShortcut(name: String)
    suspend fun removePinnedShortcut(name: String)

    /**
     * Unified pinned-item order (shortcuts + pinned agents) used to drive
     * the single drag-to-reorder grid on the Home/Admin tab. Stored as a
     * list of qualified keys: "shortcut:<NAME>" or "agent:<ID>".
     */
    fun getPinnedItemsOrder(): Flow<List<String>>
    suspend fun setPinnedItemsOrder(order: List<String>)

    /**
     * Persisted display-name cache for pinned agents, keyed by agent id.
     * Lets the Home/Admin pinned grid render tiles instantly when the
     * user switches backends instead of waiting for the new server's
     * agent cache to load. The dashboard ViewModel writes through to
     * this whenever it sees a pinned agent in the live agent cache.
     */
    fun getPinnedAgentNames(): Flow<Map<String, String>>
    suspend fun upsertPinnedAgentName(id: String, name: String)
    suspend fun removePinnedAgentName(id: String)
}
