package com.letta.mobile.data.repository

import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.objectverbs.ObjectRef
import com.letta.mobile.data.objectverbs.VerbOutcome
import com.letta.mobile.data.objectverbs.binding.DevicePins
import com.letta.mobile.data.pins.LegacyPinSources
import com.letta.mobile.data.repository.api.FeatureFlag
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.backendIdentity
import com.letta.mobile.data.storage.OPEN_CHATS_ON_CANVAS_DEFAULT
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.data.storage.SettingsPlatformDefaults
import com.letta.mobile.data.storage.SettingsPreferencesStore
import com.letta.mobile.data.flow.DerivedStateFlow
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

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
        return LastChatSelectionStorage.deserialize(secureSettingsStore.getString(SettingsSecureKeys.LAST_CHAT_SELECTION))
    }

    override suspend fun clearAllData() = withContext(Dispatchers.Default) {
        clearCachesBeforeActiveConfigChange(null)
        secureSettingsStore.clear()
        objectPins.store.reload()
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
            prefs.putString(SettingsPreferenceKeys.CHAT_BACKGROUND, DEFAULT_CHAT_BACKGROUND_KEY)
        }
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        preferencesStore.edit { prefs ->
            prefs.putBoolean(SettingsPreferenceKeys.DYNAMIC_COLOR, enabled)
        }
    }

    override fun getChatBackgroundKey(): Flow<String> = preferencesStore.snapshots.map { prefs ->
        prefs.getString(SettingsPreferenceKeys.CHAT_BACKGROUND) ?: DEFAULT_CHAT_BACKGROUND_KEY
    }

    private val pinSource = DerivedStateFlow(
        activeConfig.map { it?.let { config -> "remote-letta:${config.id}" } },
        { activeConfig.value?.let { "remote-letta:${it.id}" } },
    )
    val objectPins = DevicePins.bind(secureSettingsStore, pinSource)

    private suspend fun importPins() {
        val snapshot = preferencesStore.snapshots.first()
        objectPins.store.tryImportLegacy(LegacyPinSources.settings(snapshot))
    }

    private fun pinnedIds(ids: StateFlow<List<String>>): Flow<List<String>> = flow {
        importPins()
        emitAll(ids)
    }

    override fun getPinnedConversationIds(): Flow<Set<String>> = pinnedIds(objectPins.ids("conversation")).map { it.toSet() }
    override fun getPinnedAgentIds(): Flow<Set<String>> = getPinnedAgentOrder().map { it.toSet() }
    override fun getPinnedAgentOrder(): Flow<List<String>> = pinnedIds(objectPins.ids("agent"))
    override fun getPinnedProjectIds(): Flow<Set<String>> = pinnedIds(objectPins.ids("project")).map { it.toSet() }

    private suspend fun setPinned(ref: ObjectRef, pinned: Boolean) {
        importPins()
        val outcome = objectPins.setPinned(ref, pinned)
        check(outcome is VerbOutcome.Updated) { "Pin update failed" }
    }

    override suspend fun setConversationPinned(conversationId: String, pinned: Boolean) =
        setPinned(ObjectRef("conversation", conversationId), pinned)
    override suspend fun setAgentPinned(agentId: String, pinned: Boolean) = setPinned(ObjectRef("agent", agentId), pinned)
    override suspend fun setProjectPinned(projectId: String, pinned: Boolean) = setPinned(ObjectRef("project", projectId), pinned)

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

    override fun getOpenChatsOnCanvas(): Flow<Boolean> = preferencesStore.snapshots.map { prefs ->
        prefs.getBoolean(SettingsPreferenceKeys.OPEN_CHATS_ON_CANVAS) ?: OPEN_CHATS_ON_CANVAS_DEFAULT
    }

    override suspend fun setOpenChatsOnCanvas(enabled: Boolean) {
        preferencesStore.edit { prefs ->
            prefs.putBoolean(SettingsPreferenceKeys.OPEN_CHATS_ON_CANVAS, enabled)
        }
    }

    override fun getFeatureFlag(flag: FeatureFlag): Flow<Boolean> = preferencesStore.snapshots.map { prefs ->
        prefs.getBoolean(flag.preferenceKey) ?: flag.defaultEnabled
    }

    override suspend fun setFeatureFlag(flag: FeatureFlag, enabled: Boolean) {
        preferencesStore.edit { prefs ->
            prefs.putBoolean(flag.preferenceKey, enabled)
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
                    "fromConfigId" attr (currentId ?: "<none>"),
                    "toConfigId" attr (nextId ?: "<none>"),
                    "successes" attr result.successes,
                    "failures" attr result.failedCacheNames.size,
                    "failedCaches" attr result.failedCacheNames.joinToString(","),
                )
            }
        }
    }

}
