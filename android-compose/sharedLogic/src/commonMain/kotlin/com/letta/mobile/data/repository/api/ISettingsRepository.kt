package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.repository.LastChatSelection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Surface of [com.letta.mobile.data.repository.SettingsRepository] used by
 * collaborators that need to be test-isolated. Promoted to an interface so
 * tests can substitute a hand-written
 * [com.letta.mobile.testutil.FakeSettingsRepository] instead of mocking the
 * stateful concrete class (mockk on a final class with internal StateFlows /
 * EncryptedSharedPreferences leaks across the daemon JVM — see letta-mobile-0dnn).
 *
 * Covers the reads used by:
 *  - Infra collaborators: ShimBackendDetector, ChannelHeartbeatSync.
 *  - VM-facing reads: ConversationsViewModel (pinned-conversation state) and
 *    DashboardViewModel (favorite + pinned agents + shortcuts). Adding new
 *    VM consumers should widen the interface here rather than reaching for
 *    the concrete class — see letta-mobile-9x4 / letta-mobile-utc.
 */
interface ISettingsRepository {
    val configs: StateFlow<List<LettaConfig>>
    val activeConfig: StateFlow<LettaConfig?>
    val activeConfigChanges: Flow<LettaConfig>
    val favoriteAgentId: StateFlow<String?>
    val adminAgentId: StateFlow<String?>
    val lastChatSelection: StateFlow<LastChatSelection?>
    fun getActiveConfig(): Flow<LettaConfig?>
    suspend fun saveConfig(config: LettaConfig)
    suspend fun setActiveConfigId(id: String)
    suspend fun deleteConfig(id: String)
    suspend fun clearAllData()
    fun getTheme(): Flow<AppTheme>
    fun getThemePreset(): Flow<ThemePreset>
    fun getDynamicColor(): Flow<Boolean>
    fun observeResumeRecentConversation(): Flow<Boolean>
    fun getPinnedAgentIds(): Flow<Set<String>>
    fun getPinnedAgentOrder(): Flow<List<String>>
    fun getPinnedConversationIds(): Flow<Set<String>>
    fun setLastChatSelection(agentId: String, agentName: String?, conversationId: String?)
    suspend fun setConversationPinned(conversationId: String, pinned: Boolean)
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
    fun getChatBackgroundKey(): Flow<String>
    suspend fun setChatBackgroundKey(key: String)
    fun getChatFontScale(): Flow<Float>
    suspend fun setChatFontScale(scale: Float)
    fun getEnableProjects(): Flow<Boolean>
    suspend fun setTheme(theme: AppTheme)
    suspend fun setThemePreset(themePreset: ThemePreset)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setEnableProjects(enabled: Boolean)
}
