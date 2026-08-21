package com.letta.mobile.data.repository

import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.backendIdentity
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.data.storage.SettingsPlatformDefaults
import com.letta.mobile.data.storage.SettingsPreferencesSnapshot
import com.letta.mobile.data.storage.SettingsPreferencesStore
import com.letta.mobile.data.storage.MutableSettingsPreferencesEditor
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private const val DEFAULT_CHAT_BACKGROUND_KEY = "default"

/** Phase 5q: platform-neutral settings repository; storage adapters stay in platform modules. */
open class CachedSettingsRepository(
    private val preferencesStore: SettingsPreferencesStore,
    private val secureSettingsStore: SecureSettingsStore,
    private val platformDefaults: SettingsPlatformDefaults = SettingsPlatformDefaults(),
    private val clearBackendScopedCaches: SettingsBackendCacheClearer = SettingsBackendCacheClearer {
        SettingsBackendCacheClearResult(successes = 0, failedCacheNames = emptyList())
    },
) : ISettingsRepository {
    private val json = Json { ignoreUnknownKeys = true }

    private val _configs = MutableStateFlow<List<LettaConfig>>(emptyList())
    override val configs: StateFlow<List<LettaConfig>> = _configs.asStateFlow()

    private val _activeConfig = MutableStateFlow<LettaConfig?>(null)
    override val activeConfig: StateFlow<LettaConfig?> = _activeConfig.asStateFlow()

    override val activeConfigChanges: Flow<LettaConfig> = activeConfig
        .drop(1)
        .filterNotNull()
        .distinctUntilChanged { old, new -> old.backendIdentity() == new.backendIdentity() }

    private val _favoriteAgentId = MutableStateFlow<String?>(null)
    override val favoriteAgentId: StateFlow<String?> = _favoriteAgentId.asStateFlow()

    private val _adminAgentId = MutableStateFlow<String?>(null)
    override val adminAgentId: StateFlow<String?> = _adminAgentId.asStateFlow()

    private val _lastChatSelection = MutableStateFlow<LastChatSelection?>(null)
    override val lastChatSelection: StateFlow<LastChatSelection?> = _lastChatSelection.asStateFlow()

    private val _huggingFaceToken = MutableStateFlow<String?>(null)
    override val huggingFaceToken: StateFlow<String?> = _huggingFaceToken.asStateFlow()

    init {
        loadConfigs()
        loadActiveConfig()
        _favoriteAgentId.value = secureSettingsStore.getString(SettingsSecureKeys.FAVORITE_AGENT_ID)
        _adminAgentId.value = secureSettingsStore.getString(SettingsSecureKeys.ADMIN_AGENT_ID)
        _lastChatSelection.value = loadLastChatSelection()
        _huggingFaceToken.value =
            secureSettingsStore.getString(SettingsSecureKeys.HUGGING_FACE_TOKEN)?.takeIf { it.isNotBlank() }
    }

    private fun loadConfigs() {
        val configsJson = secureSettingsStore.getString(SettingsSecureKeys.CONFIGS)
        if (configsJson != null) {
            _configs.value = LettaConfigPersistence.decodeList(configsJson)
        }
    }

    private fun loadActiveConfig() {
        secureSettingsStore.getString(SettingsSecureKeys.ACTIVE_CONFIG_ID)?.let { activeId ->
            _activeConfig.value = _configs.value.find { it.id == activeId }
        }
    }

    override fun getActiveConfig(): Flow<LettaConfig?> = activeConfig

    override suspend fun saveConfig(config: LettaConfig) = withContext(Dispatchers.Default) {
        _configs.update { current ->
            val index = current.indexOfFirst { it.id == config.id }
            if (index >= 0) {
                current.toMutableList().apply { this[index] = config }
            } else {
                current + config
            }
        }
        persistConfigs(_configs.value)
        clearCachesBeforeActiveConfigChange(config)
        _activeConfig.update { config }
        secureSettingsStore.putString(SettingsSecureKeys.ACTIVE_CONFIG_ID, config.id)
    }

    override suspend fun setActiveConfigId(id: String) = withContext(Dispatchers.Default) {
        val config = _configs.value.find { it.id == id } ?: return@withContext
        clearCachesBeforeActiveConfigChange(config)
        _activeConfig.update { config }
        secureSettingsStore.putString(SettingsSecureKeys.ACTIVE_CONFIG_ID, id)
    }

    override suspend fun deleteConfig(id: String) = withContext(Dispatchers.Default) {
        _configs.update { current -> current.filter { it.id != id } }
        persistConfigs(_configs.value)
        if (_activeConfig.value?.id == id) {
            val fallback = _configs.value.firstOrNull()
            clearCachesBeforeActiveConfigChange(fallback)
            _activeConfig.update { fallback }
            if (fallback != null) {
                secureSettingsStore.putString(SettingsSecureKeys.ACTIVE_CONFIG_ID, fallback.id)
            } else {
                secureSettingsStore.remove(SettingsSecureKeys.ACTIVE_CONFIG_ID)
            }
        }
    }

    override fun getTheme(): Flow<AppTheme> = preferencesStore.snapshots.map { prefs ->
        val themeName = prefs.getString(SettingsPreferenceKeys.THEME) ?: AppTheme.SYSTEM.name
        try {
            AppTheme.valueOf(themeName)
        } catch (_: IllegalArgumentException) {
            AppTheme.SYSTEM
        }
    }

    override fun getThemePreset(): Flow<ThemePreset> = preferencesStore.snapshots.map { prefs ->
        val legacyAmoledDarkMode = prefs.getBoolean(SettingsPreferenceKeys.AMOLED_DARK_MODE) ?: false
        if (legacyAmoledDarkMode) {
            return@map ThemePreset.AMOLED_BLACK
        }
        val presetName = prefs.getString(SettingsPreferenceKeys.THEME_PRESET) ?: ThemePreset.DEFAULT.name
        try {
            ThemePreset.valueOf(presetName)
        } catch (_: IllegalArgumentException) {
            ThemePreset.DEFAULT
        }
    }

    override fun getDynamicColor(): Flow<Boolean> = preferencesStore.snapshots.map { prefs ->
        prefs.getBoolean(SettingsPreferenceKeys.DYNAMIC_COLOR)
            ?: ((prefs.getString(SettingsPreferenceKeys.THEME_PRESET) ?: ThemePreset.DEFAULT.name) ==
                ThemePreset.DEFAULT.name &&
                platformDefaults.defaultDynamicColorWhenPresetUnset)
    }

    fun setAdminAgentId(agentId: String?) {
        _adminAgentId.update { agentId }
        if (agentId != null) {
            secureSettingsStore.putString(SettingsSecureKeys.ADMIN_AGENT_ID, agentId)
        } else {
            secureSettingsStore.remove(SettingsSecureKeys.ADMIN_AGENT_ID)
        }
    }

    override fun setFavoriteAgentId(agentId: String?) {
        _favoriteAgentId.update { agentId }
        if (agentId != null) {
            secureSettingsStore.putString(SettingsSecureKeys.FAVORITE_AGENT_ID, agentId)
        } else {
            secureSettingsStore.remove(SettingsSecureKeys.FAVORITE_AGENT_ID)
        }
    }

    override fun setLastChatSelection(agentId: String, agentName: String?, conversationId: String?) {
        val selection = mergeLastChatSelection(
            previous = _lastChatSelection.value,
            agentId = agentId,
            agentName = agentName,
            conversationId = conversationId,
        ) ?: return
        val serialized = LastChatSelectionStorage.serialize(selection) ?: return
        _lastChatSelection.update { selection }
        secureSettingsStore.putString(SettingsSecureKeys.LAST_CHAT_SELECTION, serialized)
    }

    private fun loadLastChatSelection(): LastChatSelection? {
        val stored = secureSettingsStore.getString(SettingsSecureKeys.LAST_CHAT_SELECTION)
        if (stored != null) {
            return LastChatSelectionStorage.deserialize(stored)
        }
        val migrated = LastChatSelectionStorage.migrateLegacy(
            legacyAgentId = secureSettingsStore.getString(SettingsSecureKeys.LAST_CHAT_AGENT_ID),
            legacyAgentName = secureSettingsStore.getString(SettingsSecureKeys.LAST_CHAT_AGENT_NAME),
            legacyConversationId = secureSettingsStore.getString(SettingsSecureKeys.LAST_CHAT_CONVERSATION_ID),
        )
        secureSettingsStore.remove(SettingsSecureKeys.LAST_CHAT_AGENT_ID)
        secureSettingsStore.remove(SettingsSecureKeys.LAST_CHAT_AGENT_NAME)
        secureSettingsStore.remove(SettingsSecureKeys.LAST_CHAT_CONVERSATION_ID)
        if (migrated != null) {
            LastChatSelectionStorage.serialize(migrated)?.let {
                secureSettingsStore.putString(SettingsSecureKeys.LAST_CHAT_SELECTION, it)
            }
        }
        return migrated
    }

    override suspend fun clearAllData() = withContext(Dispatchers.Default) {
        clearCachesBeforeActiveConfigChange(null)
        secureSettingsStore.clear()
        preferencesStore.clearAll()
        _configs.update { emptyList() }
        _activeConfig.update { null }
        _favoriteAgentId.update { null }
        _adminAgentId.update { null }
        _lastChatSelection.update { null }
        _huggingFaceToken.update { null }
    }

    override suspend fun setHuggingFaceToken(token: String?) = withContext(Dispatchers.Default) {
        val normalized = token?.trim()?.takeIf { it.isNotBlank() }
        _huggingFaceToken.update { normalized }
        if (normalized != null) {
            secureSettingsStore.putString(SettingsSecureKeys.HUGGING_FACE_TOKEN, normalized)
        } else {
            secureSettingsStore.remove(SettingsSecureKeys.HUGGING_FACE_TOKEN)
        }
    }

    override suspend fun setTheme(theme: AppTheme) {
        preferencesStore.edit { prefs ->
            prefs.putString(SettingsPreferenceKeys.THEME, theme.name)
            prefs.putString(SettingsPreferenceKeys.CHAT_BACKGROUND, DEFAULT_CHAT_BACKGROUND_KEY)
        }
    }

    override suspend fun setThemePreset(themePreset: ThemePreset) {
        preferencesStore.edit { prefs ->
            prefs.putString(SettingsPreferenceKeys.THEME_PRESET, themePreset.name)
            prefs.putBoolean(SettingsPreferenceKeys.AMOLED_DARK_MODE, false)
            prefs.putString(SettingsPreferenceKeys.CHAT_BACKGROUND, DEFAULT_CHAT_BACKGROUND_KEY)
        }
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        preferencesStore.edit { prefs ->
            prefs.putBoolean(SettingsPreferenceKeys.DYNAMIC_COLOR, enabled)
        }
    }

    suspend fun setAmoledDarkMode(enabled: Boolean) {
        preferencesStore.edit { prefs ->
            prefs.putBoolean(SettingsPreferenceKeys.AMOLED_DARK_MODE, enabled)
        }
    }

    override fun getChatBackgroundKey(): Flow<String> = preferencesStore.snapshots.map { prefs ->
        prefs.getString(SettingsPreferenceKeys.CHAT_BACKGROUND) ?: DEFAULT_CHAT_BACKGROUND_KEY
    }

    override fun getPinnedConversationIds(): Flow<Set<String>> = preferencesStore.snapshots.map { prefs ->
        prefs.getStringSet(SettingsPreferenceKeys.PINNED_CONVERSATION_IDS) ?: emptySet()
    }

    override suspend fun setConversationPinned(conversationId: String, pinned: Boolean) {
        preferencesStore.edit { prefs ->
            val current = prefs.getStringSet(SettingsPreferenceKeys.PINNED_CONVERSATION_IDS) ?: emptySet()
            prefs.putStringSet(
                SettingsPreferenceKeys.PINNED_CONVERSATION_IDS,
                if (pinned) current + conversationId else current - conversationId,
            )
        }
    }

    override fun getPinnedAgentIds(): Flow<Set<String>> =
        getPinnedAgentOrder().map { it.toSet() }

    override fun getPinnedAgentOrder(): Flow<List<String>> = preferencesStore.snapshots.map { prefs ->
        readUnifiedPinnedItems(prefs).mapNotNull(::parseAgentKeyPart)
    }

    override fun getPinnedItemsOrder(): Flow<List<String>> = preferencesStore.snapshots.map { prefs ->
        readUnifiedPinnedItems(prefs)
    }

    override suspend fun setAgentPinned(agentId: String, pinned: Boolean) {
        preferencesStore.edit { prefs ->
            val current = readUnifiedPinnedItems(prefs)
            val key = agentKeyPart(agentId)
            val updated = if (pinned) {
                if (key in current) current else current + key
            } else {
                current - key
            }
            writeUnifiedPinnedItems(prefs, updated)
            if (!pinned) {
                val names = readPinnedAgentNames(prefs).toMutableMap()
                if (names.remove(agentId) != null) {
                    prefs.putString(SettingsPreferenceKeys.PINNED_AGENT_NAMES, json.encodeToString(names))
                }
            }
        }
    }

    override suspend fun setPinnedAgentOrder(order: List<String>) {
        preferencesStore.edit { prefs ->
            val current = readUnifiedPinnedItems(prefs)
            val newAgents = order.distinct().map(::agentKeyPart)
            val unified = replaceTypeSegment(current, newAgents, ::isAgentKeyPart)
            writeUnifiedPinnedItems(prefs, unified)
        }
    }

    override suspend fun setPinnedItemsOrder(order: List<String>) {
        preferencesStore.edit { prefs ->
            writeUnifiedPinnedItems(prefs, order.distinct())
        }
    }

    override fun getPinnedAgentNames(): Flow<Map<String, String>> = preferencesStore.snapshots.map { prefs ->
        readPinnedAgentNames(prefs)
    }

    override suspend fun upsertPinnedAgentName(id: String, name: String) {
        preferencesStore.edit { prefs ->
            val current = readPinnedAgentNames(prefs).toMutableMap()
            if (current[id] == name) return@edit
            current[id] = name
            prefs.putString(SettingsPreferenceKeys.PINNED_AGENT_NAMES, json.encodeToString(current))
        }
    }

    override suspend fun removePinnedAgentName(id: String) {
        preferencesStore.edit { prefs ->
            val current = readPinnedAgentNames(prefs).toMutableMap()
            if (current.remove(id) != null) {
                prefs.putString(SettingsPreferenceKeys.PINNED_AGENT_NAMES, json.encodeToString(current))
            }
        }
    }

    override fun getPinnedProjectIds(): Flow<Set<String>> = preferencesStore.snapshots.map { prefs ->
        prefs.getStringSet(SettingsPreferenceKeys.PINNED_PROJECT_IDS) ?: emptySet()
    }

    override suspend fun setProjectPinned(projectId: String, pinned: Boolean) {
        preferencesStore.edit { prefs ->
            val current = prefs.getStringSet(SettingsPreferenceKeys.PINNED_PROJECT_IDS) ?: emptySet()
            prefs.putStringSet(
                SettingsPreferenceKeys.PINNED_PROJECT_IDS,
                if (pinned) current + projectId else current - projectId,
            )
        }
    }

    override suspend fun setChatBackgroundKey(key: String) {
        preferencesStore.edit { prefs ->
            prefs.putString(SettingsPreferenceKeys.CHAT_BACKGROUND, key)
        }
    }

    override fun getChatFontScale(): Flow<Float> = preferencesStore.snapshots.map { prefs ->
        prefs.getFloat(SettingsPreferenceKeys.CHAT_FONT_SCALE) ?: 1.0f
    }

    override suspend fun setChatFontScale(scale: Float) {
        val clamped = scale.coerceIn(0.7f, 1.6f)
        preferencesStore.edit { prefs ->
            prefs.putFloat(SettingsPreferenceKeys.CHAT_FONT_SCALE, clamped)
        }
    }

    override fun getEnableProjects(): Flow<Boolean> = preferencesStore.snapshots.map { prefs ->
        prefs.getBoolean(SettingsPreferenceKeys.ENABLE_PROJECTS) ?: false
    }

    override fun getHapticsEnabled(): Flow<Boolean> = preferencesStore.snapshots.map { prefs ->
        prefs.getBoolean(SettingsPreferenceKeys.HAPTICS_ENABLED) ?: true
    }

    override suspend fun setHapticsEnabled(enabled: Boolean) {
        preferencesStore.edit { prefs ->
            prefs.putBoolean(SettingsPreferenceKeys.HAPTICS_ENABLED, enabled)
        }
    }

    override fun observeResumeRecentConversation(): Flow<Boolean> = preferencesStore.snapshots.map { prefs ->
        prefs.getBoolean(SettingsPreferenceKeys.RESUME_RECENT_CONVERSATION)
            ?: platformDefaults.defaultResumeRecentConversation
    }

    override suspend fun setEnableProjects(enabled: Boolean) {
        preferencesStore.edit { prefs ->
            prefs.putBoolean(SettingsPreferenceKeys.ENABLE_PROJECTS, enabled)
        }
    }

    override fun getPinnedShortcutOrder(): Flow<List<String>> = preferencesStore.snapshots.map { prefs ->
        readUnifiedPinnedItems(prefs).mapNotNull(::parseShortcutKeyPart)
    }

    override suspend fun setPinnedShortcutOrder(order: List<String>) {
        preferencesStore.edit { prefs ->
            val current = readUnifiedPinnedItems(prefs)
            val newShortcuts = order.distinct().map(::shortcutKeyPart)
            val unified = replaceTypeSegment(current, newShortcuts, ::isShortcutKeyPart)
            writeUnifiedPinnedItems(prefs, unified)
        }
    }

    override suspend fun addPinnedShortcut(name: String) {
        preferencesStore.edit { prefs ->
            val current = readUnifiedPinnedItems(prefs)
            val key = shortcutKeyPart(name)
            if (key !in current) {
                writeUnifiedPinnedItems(prefs, current + key)
            }
        }
    }

    override suspend fun removePinnedShortcut(name: String) {
        preferencesStore.edit { prefs ->
            val current = readUnifiedPinnedItems(prefs)
            writeUnifiedPinnedItems(prefs, current - shortcutKeyPart(name))
        }
    }

    private fun persistConfigs(configs: List<LettaConfig>) {
        secureSettingsStore.putString(SettingsSecureKeys.CONFIGS, LettaConfigPersistence.encodeList(configs))
    }

    private suspend fun clearCachesBeforeActiveConfigChange(nextConfig: LettaConfig?) {
        val currentId = _activeConfig.value?.id
        val nextId = nextConfig?.id
        if (currentId != nextId) {
            val result = clearBackendScopedCaches.clearAll()
            if (!result.allSucceeded) {
                Telemetry.event(
                    "SettingsRepository",
                    "backendSwitchCachePartialClear",
                    "fromConfigId" to (currentId ?: "<none>"),
                    "toConfigId" to (nextId ?: "<none>"),
                    "successes" to result.successes,
                    "failures" to result.failedCacheNames.size,
                    "failedCaches" to result.failedCacheNames.joinToString(","),
                )
            }
        }
    }

    private fun readPinnedAgentNames(prefs: SettingsPreferencesSnapshot): Map<String, String> {
        val raw = prefs.getString(SettingsPreferenceKeys.PINNED_AGENT_NAMES) ?: return emptyMap()
        return try {
            json.decodeFromString<Map<String, String>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun readUnifiedPinnedItems(prefs: SettingsPreferencesSnapshot): List<String> {
        prefs.getString(SettingsPreferenceKeys.PINNED_ITEMS_ORDER)?.let { raw ->
            return try {
                json.decodeFromString<List<String>>(raw)
            } catch (_: Exception) {
                emptyList()
            }
        }
        val shortcuts = readLegacyPinnedShortcutOrder(prefs).map(::shortcutKeyPart)
        val agents = readLegacyPinnedAgentOrder(prefs).map(::agentKeyPart)
        return shortcuts + agents
    }

    private fun writeUnifiedPinnedItems(prefs: MutableSettingsPreferencesEditor, items: List<String>) {
        val deduped = items.distinct()
        prefs.putString(SettingsPreferenceKeys.PINNED_ITEMS_ORDER, json.encodeToString(deduped))
        val agentIds = deduped.mapNotNull(::parseAgentKeyPart)
        val shortcutNames = deduped.mapNotNull(::parseShortcutKeyPart)
        prefs.putString(SettingsPreferenceKeys.PINNED_AGENT_ORDER, json.encodeToString(agentIds))
        prefs.putStringSet(SettingsPreferenceKeys.PINNED_AGENT_IDS, agentIds.toSet())
        prefs.putString(SettingsPreferenceKeys.PINNED_SHORTCUT_ORDER, json.encodeToString(shortcutNames))
    }

    private fun readLegacyPinnedAgentOrder(prefs: SettingsPreferencesSnapshot): List<String> {
        prefs.getString(SettingsPreferenceKeys.PINNED_AGENT_ORDER)?.let { raw ->
            return try {
                json.decodeFromString<List<String>>(raw)
            } catch (_: Exception) {
                emptyList()
            }
        }
        return (prefs.getStringSet(SettingsPreferenceKeys.PINNED_AGENT_IDS) ?: emptySet()).toList()
    }

    private fun readLegacyPinnedShortcutOrder(prefs: SettingsPreferencesSnapshot): List<String> {
        prefs.getString(SettingsPreferenceKeys.PINNED_SHORTCUT_ORDER)?.let { raw ->
            return try {
                json.decodeFromString<List<String>>(raw)
            } catch (_: Exception) {
                DEFAULT_PINNED_SHORTCUTS
            }
        }
        return DEFAULT_PINNED_SHORTCUTS
    }

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

    companion object {
        val DEFAULT_PINNED_SHORTCUTS = listOf(
            "CONVERSATIONS", "AGENTS", "TOOLS", "BLOCKS",
            "USAGE", "FAVORITE_AGENT",
        )
    }
}
