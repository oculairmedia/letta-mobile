package com.letta.mobile.data.repository

import android.os.Build
import com.letta.mobile.core.BuildConfig
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.storage.SecureSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import androidx.datastore.preferences.core.floatPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val DEFAULT_CHAT_BACKGROUND_KEY = "default"

data class LastChatSelection(
    val agentId: String,
    val agentName: String? = null,
    val conversationId: String? = null,
)

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val secureSettingsStore: SecureSettingsStore,
) : ISettingsRepository {
    private val json = Json { ignoreUnknownKeys = true }

    private val _configs = MutableStateFlow<List<LettaConfig>>(emptyList())
    override val configs: StateFlow<List<LettaConfig>> = _configs.asStateFlow()

    private val _activeConfig = MutableStateFlow<LettaConfig?>(null)
    override val activeConfig: StateFlow<LettaConfig?> = _activeConfig.asStateFlow()

    /**
     * letta-mobile-ze5l: emits whenever the active backend's config id
     * changes (drops the initial value at subscription time). Top-level
     * data ViewModels collect this to call their existing refresh entry
     * points so the currently-visible screen re-fetches against the new
     * server without requiring the user to navigate away and back.
     *
     * The first-emission drop is intentional: subscribers do not need
     * to refresh on attach, only on subsequent changes. The id-based
     * distinctness avoids redundant emissions if [_activeConfig] is
     * re-emitted with the same identity (e.g. token rotation).
     */
    override val activeConfigChanges: Flow<LettaConfig> = activeConfig
        .drop(1)
        .filterNotNull()
        .distinctUntilChanged { old, new -> old.id == new.id }

    private val _favoriteAgentId = MutableStateFlow<String?>(null)
    override val favoriteAgentId: StateFlow<String?> = _favoriteAgentId.asStateFlow()

    private val _adminAgentId = MutableStateFlow<String?>(null)
    override val adminAgentId: StateFlow<String?> = _adminAgentId.asStateFlow()

    private val _lastChatSelection = MutableStateFlow<LastChatSelection?>(null)
    override val lastChatSelection: StateFlow<LastChatSelection?> = _lastChatSelection.asStateFlow()

    private object Keys {
        val CONFIGS = stringPreferencesKey("configs")
        val ACTIVE_CONFIG_ID = stringPreferencesKey("active_config_id")
        val THEME = stringPreferencesKey("theme")
        val THEME_PRESET = stringPreferencesKey("theme_preset")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val AMOLED_DARK_MODE = booleanPreferencesKey("amoled_dark_mode")
        val CHAT_BACKGROUND = stringPreferencesKey("chat_background")
        val FAVORITE_AGENT_ID = stringPreferencesKey("favorite_agent_id")
        val ADMIN_AGENT_ID = stringPreferencesKey("admin_agent_id")
        val LAST_CHAT_AGENT_ID = stringPreferencesKey("last_chat_agent_id")
        val LAST_CHAT_AGENT_NAME = stringPreferencesKey("last_chat_agent_name")
        val LAST_CHAT_CONVERSATION_ID = stringPreferencesKey("last_chat_conversation_id")
        val PINNED_CONVERSATION_IDS = stringSetPreferencesKey("pinned_conversation_ids")
        val PINNED_AGENT_IDS = stringSetPreferencesKey("pinned_agent_ids")
        // JSON-encoded List<String> — source of truth for pinned agent
        // display order. The legacy Set key above is kept in sync for any
        // direct readers; new readers should prefer this ordered list.
        val PINNED_AGENT_ORDER = stringPreferencesKey("pinned_agent_order")
        val PINNED_PROJECT_IDS = stringSetPreferencesKey("pinned_project_ids")
        val CHAT_FONT_SCALE = floatPreferencesKey("chat_font_scale")
        val ENABLE_PROJECTS = booleanPreferencesKey("enable_projects")
        val PINNED_SHORTCUT_ORDER = stringPreferencesKey("pinned_shortcut_order")
        // JSON-encoded List<String> of qualified pinned-item keys
        // ("shortcut:<NAME>" / "agent:<ID>"). Source of truth for the
        // unified drag-to-reorder grid; per-type legacy keys above are
        // derived from this on read and kept in sync on write.
        val PINNED_ITEMS_ORDER = stringPreferencesKey("pinned_items_order")
        // JSON-encoded Map<String, String> of agentId → display name.
        // Persisted so the pinned grid can show tiles immediately on
        // backend switch without waiting for the new server's agent
        // cache to load.
        val PINNED_AGENT_NAMES = stringPreferencesKey("pinned_agent_names")
        // letta-mobile-h2b8: feature flag for the resume-most-recent-conversation
        // behaviour. When true, opening an agent without an explicit conversation
        // id picks the most-recent non-archived conversation for that agent.
        // When false (or unset), falls through to the pre-h2b8 create-on-send
        // path. Default-on in BuildConfig.DEBUG via observeResumeRecentConversation
        // so internal builds get the new behaviour automatically; release builds
        // keep the legacy fresh-chat path until the flag is flipped explicitly.
        val RESUME_RECENT_CONVERSATION = booleanPreferencesKey("resume_recent_conversation")
    }

    init {
        loadConfigs()
        loadActiveConfig()
        _favoriteAgentId.value = secureSettingsStore.getString(Keys.FAVORITE_AGENT_ID.name)
        _adminAgentId.value = secureSettingsStore.getString(Keys.ADMIN_AGENT_ID.name)
        _lastChatSelection.value = loadLastChatSelection()
    }

    private fun loadConfigs() {
        val configsJson = secureSettingsStore.getString(Keys.CONFIGS.name)
        if (configsJson != null) {
            try {
                val configList = json.decodeFromString<List<LettaConfigData>>(configsJson)
                _configs.value = configList.map { it.toLettaConfig() }
            } catch (e: Exception) {
                _configs.value = emptyList()
            }
        }
    }

    private fun loadActiveConfig() {
        secureSettingsStore.getString(Keys.ACTIVE_CONFIG_ID.name)?.let { activeId ->
            _activeConfig.value = _configs.value.find { it.id == activeId }
        }
    }

    fun getConfigs(): Flow<List<LettaConfig>> = configs

    override fun getActiveConfig(): Flow<LettaConfig?> = activeConfig

    // EncryptedSharedPreferences runs AES-GCM on the calling thread when it
    // serializes each value, so dispatch the config writes to IO. Otherwise
    // tapping Save runs the encrypt + JSON encode synchronously on Main from
    // viewModelScope.launch (default = Dispatchers.Main.immediate), which
    // blocks the press handler long enough that the ripple state hangs and
    // follow-up taps look like they're being ignored.
    override suspend fun saveConfig(config: LettaConfig) = withContext(Dispatchers.IO) {
        _configs.update { current ->
            val index = current.indexOfFirst { it.id == config.id }
            if (index >= 0) {
                current.toMutableList().apply { this[index] = config }
            } else {
                current + config
            }
        }
        persistConfigs(_configs.value)
        _activeConfig.update { config }
        secureSettingsStore.putString(Keys.ACTIVE_CONFIG_ID.name, config.id)
    }

    override suspend fun setActiveConfigId(id: String) = withContext(Dispatchers.IO) {
        val config = _configs.value.find { it.id == id } ?: return@withContext
        _activeConfig.update { config }
        secureSettingsStore.putString(Keys.ACTIVE_CONFIG_ID.name, id)
    }

    override suspend fun deleteConfig(id: String) = withContext(Dispatchers.IO) {
        _configs.update { current -> current.filter { it.id != id } }
        persistConfigs(_configs.value)
        if (_activeConfig.value?.id == id) {
            val fallback = _configs.value.firstOrNull()
            _activeConfig.update { fallback }
            if (fallback != null) {
                secureSettingsStore.putString(Keys.ACTIVE_CONFIG_ID.name, fallback.id)
            } else {
                secureSettingsStore.remove(Keys.ACTIVE_CONFIG_ID.name)
            }
        }
    }

    override fun getTheme(): Flow<AppTheme> = dataStore.data.map { prefs ->
        val themeName = prefs[Keys.THEME] ?: AppTheme.SYSTEM.name
        try {
            AppTheme.valueOf(themeName)
        } catch (e: IllegalArgumentException) {
            AppTheme.SYSTEM
        }
    }

    override fun getThemePreset(): Flow<ThemePreset> = dataStore.data.map { prefs ->
        val legacyAmoledDarkMode = prefs[Keys.AMOLED_DARK_MODE] ?: false
        if (legacyAmoledDarkMode) {
            return@map ThemePreset.AMOLED_BLACK
        }
        val presetName = prefs[Keys.THEME_PRESET] ?: ThemePreset.DEFAULT.name
        try {
            ThemePreset.valueOf(presetName)
        } catch (e: IllegalArgumentException) {
            ThemePreset.DEFAULT
        }
    }

    override fun getDynamicColor(): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.DYNAMIC_COLOR]
            ?: ((prefs[Keys.THEME_PRESET] ?: ThemePreset.DEFAULT.name) == ThemePreset.DEFAULT.name &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
    }

    fun getAmoledDarkMode(): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.AMOLED_DARK_MODE] ?: false
    }

    fun setAdminAgentId(agentId: String?) {
        _adminAgentId.update { agentId }
        if (agentId != null) {
            secureSettingsStore.putString(Keys.ADMIN_AGENT_ID.name, agentId)
        } else {
            secureSettingsStore.remove(Keys.ADMIN_AGENT_ID.name)
        }
    }

    override fun setFavoriteAgentId(agentId: String?) {
        _favoriteAgentId.update { agentId }
        if (agentId != null) {
            secureSettingsStore.putString(Keys.FAVORITE_AGENT_ID.name, agentId)
        } else {
            secureSettingsStore.remove(Keys.FAVORITE_AGENT_ID.name)
        }
    }

    override fun setLastChatSelection(agentId: String, agentName: String?, conversationId: String?) {
        val normalizedAgentId = agentId.takeIf { it.isNotBlank() } ?: return
        val selection = LastChatSelection(
            agentId = normalizedAgentId,
            agentName = agentName?.takeIf { it.isNotBlank() },
            conversationId = conversationId?.takeIf { it.isNotBlank() },
        )
        _lastChatSelection.update { selection }
        secureSettingsStore.putString(Keys.LAST_CHAT_AGENT_ID.name, selection.agentId)
        if (selection.agentName != null) {
            secureSettingsStore.putString(Keys.LAST_CHAT_AGENT_NAME.name, selection.agentName)
        } else {
            secureSettingsStore.remove(Keys.LAST_CHAT_AGENT_NAME.name)
        }
        if (selection.conversationId != null) {
            secureSettingsStore.putString(Keys.LAST_CHAT_CONVERSATION_ID.name, selection.conversationId)
        } else {
            secureSettingsStore.remove(Keys.LAST_CHAT_CONVERSATION_ID.name)
        }
    }

    private fun loadLastChatSelection(): LastChatSelection? {
        val agentId = secureSettingsStore.getString(Keys.LAST_CHAT_AGENT_ID.name)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return LastChatSelection(
            agentId = agentId,
            agentName = secureSettingsStore.getString(Keys.LAST_CHAT_AGENT_NAME.name)
                ?.takeIf { it.isNotBlank() },
            conversationId = secureSettingsStore.getString(Keys.LAST_CHAT_CONVERSATION_ID.name)
                ?.takeIf { it.isNotBlank() },
        )
    }

    override suspend fun clearAllData() = withContext(Dispatchers.IO) {
        secureSettingsStore.clear()
        dataStore.edit { it.clear() }
        _configs.update { emptyList() }
        _activeConfig.update { null }
        _favoriteAgentId.update { null }
        _adminAgentId.update { null }
        _lastChatSelection.update { null }
    }

    override suspend fun setTheme(theme: AppTheme) {
        dataStore.edit { prefs ->
            prefs[Keys.THEME] = theme.name
            prefs[Keys.CHAT_BACKGROUND] = DEFAULT_CHAT_BACKGROUND_KEY
        }
    }

    override suspend fun setThemePreset(themePreset: ThemePreset) {
        dataStore.edit { prefs ->
            prefs[Keys.THEME_PRESET] = themePreset.name
            prefs[Keys.AMOLED_DARK_MODE] = false
            prefs[Keys.CHAT_BACKGROUND] = DEFAULT_CHAT_BACKGROUND_KEY
        }
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.DYNAMIC_COLOR] = enabled
        }
    }

    suspend fun setAmoledDarkMode(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.AMOLED_DARK_MODE] = enabled
        }
    }

    override fun getChatBackgroundKey(): Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.CHAT_BACKGROUND] ?: DEFAULT_CHAT_BACKGROUND_KEY
    }

    override fun getPinnedConversationIds(): Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[Keys.PINNED_CONVERSATION_IDS] ?: emptySet()
    }

    override suspend fun setConversationPinned(conversationId: String, pinned: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.PINNED_CONVERSATION_IDS] ?: emptySet()
            prefs[Keys.PINNED_CONVERSATION_IDS] = if (pinned) {
                current + conversationId
            } else {
                current - conversationId
            }
        }
    }

    override fun getPinnedAgentIds(): Flow<Set<String>> =
        getPinnedAgentOrder().map { it.toSet() }

    override fun getPinnedAgentOrder(): Flow<List<String>> = dataStore.data.map { prefs ->
        readUnifiedPinnedItems(prefs).mapNotNull(::parseAgentKeyPart)
    }

    override fun getPinnedItemsOrder(): Flow<List<String>> = dataStore.data.map { prefs ->
        readUnifiedPinnedItems(prefs)
    }

    override suspend fun setAgentPinned(agentId: String, pinned: Boolean) {
        dataStore.edit { prefs ->
            val current = readUnifiedPinnedItems(prefs)
            val key = agentKeyPart(agentId)
            val updated = if (pinned) {
                if (key in current) current else current + key
            } else {
                current - key
            }
            writeUnifiedPinnedItems(prefs, updated)
            if (!pinned) {
                // Explicit unpin: also drop the persisted display-name
                // cache entry so it doesn't linger. (Orphan agents on
                // other backends are NOT unpinned — their entries are
                // intentionally preserved so switching back restores them.)
                val names = readPinnedAgentNames(prefs).toMutableMap()
                if (names.remove(agentId) != null) {
                    prefs[Keys.PINNED_AGENT_NAMES] = json.encodeToString(names)
                }
            }
        }
    }

    override suspend fun setPinnedAgentOrder(order: List<String>) {
        // Legacy per-type setter: replace agents in the unified list in
        // their existing absolute positions where possible; otherwise
        // place them after the shortcuts.
        dataStore.edit { prefs ->
            val current = readUnifiedPinnedItems(prefs)
            val newAgents = order.distinct().map(::agentKeyPart)
            val unified = replaceTypeSegment(current, newAgents, ::isAgentKeyPart)
            writeUnifiedPinnedItems(prefs, unified)
        }
    }

    override suspend fun setPinnedItemsOrder(order: List<String>) {
        dataStore.edit { prefs ->
            writeUnifiedPinnedItems(prefs, order.distinct())
        }
    }

    override fun getPinnedAgentNames(): Flow<Map<String, String>> = dataStore.data.map { prefs ->
        readPinnedAgentNames(prefs)
    }

    override suspend fun upsertPinnedAgentName(id: String, name: String) {
        dataStore.edit { prefs ->
            val current = readPinnedAgentNames(prefs).toMutableMap()
            if (current[id] == name) return@edit
            current[id] = name
            prefs[Keys.PINNED_AGENT_NAMES] = json.encodeToString(current)
        }
    }

    override suspend fun removePinnedAgentName(id: String) {
        dataStore.edit { prefs ->
            val current = readPinnedAgentNames(prefs).toMutableMap()
            if (current.remove(id) != null) {
                prefs[Keys.PINNED_AGENT_NAMES] = json.encodeToString(current)
            }
        }
    }

    private fun readPinnedAgentNames(prefs: Preferences): Map<String, String> {
        val raw = prefs[Keys.PINNED_AGENT_NAMES] ?: return emptyMap()
        return try {
            json.decodeFromString<Map<String, String>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun readUnifiedPinnedItems(prefs: Preferences): List<String> {
        prefs[Keys.PINNED_ITEMS_ORDER]?.let { raw ->
            return try {
                json.decodeFromString<List<String>>(raw)
            } catch (_: Exception) {
                emptyList()
            }
        }
        // Migration: build the unified list from the legacy per-type
        // storage. Shortcuts first (default or persisted), then agents
        // (from the persisted order, falling back to the legacy Set).
        val shortcuts = readLegacyPinnedShortcutOrder(prefs).map(::shortcutKeyPart)
        val agents = readLegacyPinnedAgentOrder(prefs).map(::agentKeyPart)
        return shortcuts + agents
    }

    private fun writeUnifiedPinnedItems(prefs: MutablePreferences, items: List<String>) {
        val deduped = items.distinct()
        prefs[Keys.PINNED_ITEMS_ORDER] = json.encodeToString(deduped)
        // Keep per-type legacy keys in sync for any direct readers.
        val agentIds = deduped.mapNotNull(::parseAgentKeyPart)
        val shortcutNames = deduped.mapNotNull(::parseShortcutKeyPart)
        prefs[Keys.PINNED_AGENT_ORDER] = json.encodeToString(agentIds)
        prefs[Keys.PINNED_AGENT_IDS] = agentIds.toSet()
        prefs[Keys.PINNED_SHORTCUT_ORDER] = json.encodeToString(shortcutNames)
    }

    private fun readLegacyPinnedAgentOrder(prefs: Preferences): List<String> {
        prefs[Keys.PINNED_AGENT_ORDER]?.let { raw ->
            return try {
                json.decodeFromString<List<String>>(raw)
            } catch (_: Exception) {
                emptyList()
            }
        }
        return (prefs[Keys.PINNED_AGENT_IDS] ?: emptySet()).toList()
    }

    private fun readLegacyPinnedShortcutOrder(prefs: Preferences): List<String> {
        prefs[Keys.PINNED_SHORTCUT_ORDER]?.let { raw ->
            return try {
                json.decodeFromString<List<String>>(raw)
            } catch (_: Exception) {
                DEFAULT_PINNED_SHORTCUTS
            }
        }
        return DEFAULT_PINNED_SHORTCUTS
    }

    /**
     * Replace all entries matching [typePredicate] in [current] with
     * [replacement], preserving the positions of non-matching entries.
     * If the new list's size differs from the existing matches, the
     * extra items are appended (or trimmed) at the end of the section.
     */
    private fun replaceTypeSegment(
        current: List<String>,
        replacement: List<String>,
        typePredicate: (String) -> Boolean,
    ): List<String> {
        val existingMatches = current.count(typePredicate)
        if (existingMatches == replacement.size) {
            val iter = replacement.iterator()
            return current.map { if (typePredicate(it)) iter.next() else it }
        }
        val others = current.filterNot(typePredicate)
        return replacement + others
    }

    private fun shortcutKeyPart(name: String) = "shortcut:$name"
    private fun agentKeyPart(id: String) = "agent:$id"
    private fun isShortcutKeyPart(key: String) = key.startsWith("shortcut:")
    private fun isAgentKeyPart(key: String) = key.startsWith("agent:")
    private fun parseShortcutKeyPart(key: String): String? =
        if (isShortcutKeyPart(key)) key.removePrefix("shortcut:") else null
    private fun parseAgentKeyPart(key: String): String? =
        if (isAgentKeyPart(key)) key.removePrefix("agent:") else null

    override fun getPinnedProjectIds(): Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[Keys.PINNED_PROJECT_IDS] ?: emptySet()
    }

    override suspend fun setProjectPinned(projectId: String, pinned: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.PINNED_PROJECT_IDS] ?: emptySet()
            prefs[Keys.PINNED_PROJECT_IDS] = if (pinned) {
                current + projectId
            } else {
                current - projectId
            }
        }
    }

    override suspend fun setChatBackgroundKey(key: String) {
        dataStore.edit { prefs ->
            prefs[Keys.CHAT_BACKGROUND] = key
        }
    }

    override fun getChatFontScale(): Flow<Float> = dataStore.data.map { prefs ->
        prefs[Keys.CHAT_FONT_SCALE] ?: 1.0f
    }

    override suspend fun setChatFontScale(scale: Float) {
        val clamped = scale.coerceIn(0.7f, 1.6f)
        dataStore.edit { prefs ->
            prefs[Keys.CHAT_FONT_SCALE] = clamped
        }
    }

    override fun getEnableProjects(): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.ENABLE_PROJECTS] ?: false
    }

    /**
     * letta-mobile-h2b8: feature flag governing the resume-most-recent
     * conversation behaviour. Defaults to [BuildConfig.DEBUG] so internal
     * builds opt in automatically; release flips when the behaviour has
     * soaked. Stored value (when present) wins over the default.
     */
    override fun observeResumeRecentConversation(): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.RESUME_RECENT_CONVERSATION] ?: BuildConfig.DEBUG
    }

    suspend fun setResumeRecentConversation(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.RESUME_RECENT_CONVERSATION] = enabled
        }
    }

    override suspend fun setEnableProjects(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.ENABLE_PROJECTS] = enabled
        }
    }

    override fun getPinnedShortcutOrder(): Flow<List<String>> = dataStore.data.map { prefs ->
        readUnifiedPinnedItems(prefs).mapNotNull(::parseShortcutKeyPart)
    }

    companion object {
        /** Shortcuts pinned by default on first launch. */
        val DEFAULT_PINNED_SHORTCUTS = listOf(
            "CONVERSATIONS", "AGENTS", "TOOLS", "BLOCKS",
            "USAGE", "FAVORITE_AGENT",
        )
    }

    override suspend fun setPinnedShortcutOrder(order: List<String>) {
        dataStore.edit { prefs ->
            val current = readUnifiedPinnedItems(prefs)
            val newShortcuts = order.distinct().map(::shortcutKeyPart)
            val unified = replaceTypeSegment(current, newShortcuts, ::isShortcutKeyPart)
            writeUnifiedPinnedItems(prefs, unified)
        }
    }

    override suspend fun addPinnedShortcut(name: String) {
        dataStore.edit { prefs ->
            val current = readUnifiedPinnedItems(prefs)
            val key = shortcutKeyPart(name)
            if (key !in current) {
                writeUnifiedPinnedItems(prefs, current + key)
            }
        }
    }

    override suspend fun removePinnedShortcut(name: String) {
        dataStore.edit { prefs ->
            val current = readUnifiedPinnedItems(prefs)
            writeUnifiedPinnedItems(prefs, current - shortcutKeyPart(name))
        }
    }

    private fun persistConfigs(configs: List<LettaConfig>) {
        val configData = configs.map { LettaConfigData.fromLettaConfig(it) }
        val configsJson = json.encodeToString(configData)
        secureSettingsStore.putString(Keys.CONFIGS.name, configsJson)
    }

    @kotlinx.serialization.Serializable
    private data class LettaConfigData(
        val id: String,
        val mode: String,
        val serverUrl: String,
        val accessToken: String? = null,
    ) {
        fun toLettaConfig() = LettaConfig(
            id = id,
            mode = LettaConfig.Mode.valueOf(mode),
            serverUrl = serverUrl,
            accessToken = accessToken
        )

        companion object {
            fun fromLettaConfig(config: LettaConfig) = LettaConfigData(
                id = config.id,
                mode = config.mode.name,
                serverUrl = config.serverUrl,
                accessToken = config.accessToken
            )
        }
    }
}
