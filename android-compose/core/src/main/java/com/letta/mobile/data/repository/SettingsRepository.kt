package com.letta.mobile.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.util.EncryptedPrefsHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "letta_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore = context.dataStore
    private val encryptedPrefs: SharedPreferences = EncryptedPrefsHelper.getEncryptedPrefs(context)
    private val json = Json { ignoreUnknownKeys = true }

    private val _configs = MutableStateFlow<List<LettaConfig>>(emptyList())
    val configs: StateFlow<List<LettaConfig>> = _configs.asStateFlow()

    private val _activeConfig = MutableStateFlow<LettaConfig?>(null)
    val activeConfig: StateFlow<LettaConfig?> = _activeConfig.asStateFlow()

    private val _favoriteAgentId = MutableStateFlow<String?>(null)
    val favoriteAgentId: StateFlow<String?> = _favoriteAgentId.asStateFlow()

    private val _adminAgentId = MutableStateFlow<String?>(null)
    val adminAgentId: StateFlow<String?> = _adminAgentId.asStateFlow()

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
        val PINNED_CONVERSATION_IDS = stringSetPreferencesKey("pinned_conversation_ids")
    }

    init {
        loadConfigs()
        loadActiveConfig()
        _favoriteAgentId.value = encryptedPrefs.getString(Keys.FAVORITE_AGENT_ID.name, null)
        _adminAgentId.value = encryptedPrefs.getString(Keys.ADMIN_AGENT_ID.name, null)
    }

    private fun loadConfigs() {
        val configsJson = encryptedPrefs.getString(Keys.CONFIGS.name, null)
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
        encryptedPrefs.getString(Keys.ACTIVE_CONFIG_ID.name, null)?.let { activeId ->
            _activeConfig.value = _configs.value.find { it.id == activeId }
        }
    }

    fun getConfigs(): Flow<List<LettaConfig>> = configs

    fun getActiveConfig(): Flow<LettaConfig?> = activeConfig

    suspend fun saveConfig(config: LettaConfig) {
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
        encryptedPrefs.edit().putString(Keys.ACTIVE_CONFIG_ID.name, config.id).apply()
    }

    suspend fun setActiveConfigId(id: String) {
        val config = _configs.value.find { it.id == id } ?: return
        _activeConfig.update { config }
        encryptedPrefs.edit().putString(Keys.ACTIVE_CONFIG_ID.name, id).apply()
    }

    suspend fun deleteConfig(id: String) {
        _configs.update { current -> current.filter { it.id != id } }
        persistConfigs(_configs.value)
        if (_activeConfig.value?.id == id) {
            val fallback = _configs.value.firstOrNull()
            _activeConfig.update { fallback }
            if (fallback != null) {
                encryptedPrefs.edit().putString(Keys.ACTIVE_CONFIG_ID.name, fallback.id).apply()
            } else {
                encryptedPrefs.edit().remove(Keys.ACTIVE_CONFIG_ID.name).apply()
            }
        }
    }

    fun getTheme(): Flow<AppTheme> = dataStore.data.map { prefs ->
        val themeName = prefs[Keys.THEME] ?: AppTheme.SYSTEM.name
        try {
            AppTheme.valueOf(themeName)
        } catch (e: IllegalArgumentException) {
            AppTheme.SYSTEM
        }
    }

    fun getThemePreset(): Flow<ThemePreset> = dataStore.data.map { prefs ->
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

    fun getDynamicColor(): Flow<Boolean> = dataStore.data.map { prefs ->
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
            encryptedPrefs.edit().putString(Keys.ADMIN_AGENT_ID.name, agentId).apply()
        } else {
            encryptedPrefs.edit().remove(Keys.ADMIN_AGENT_ID.name).apply()
        }
    }

    fun setFavoriteAgentId(agentId: String?) {
        _favoriteAgentId.update { agentId }
        if (agentId != null) {
            encryptedPrefs.edit().putString(Keys.FAVORITE_AGENT_ID.name, agentId).apply()
        } else {
            encryptedPrefs.edit().remove(Keys.FAVORITE_AGENT_ID.name).apply()
        }
    }

    suspend fun clearAllData() {
        encryptedPrefs.edit().clear().apply()
        dataStore.edit { it.clear() }
        _configs.update { emptyList() }
        _activeConfig.update { null }
        _favoriteAgentId.update { null }
        _adminAgentId.update { null }
    }

    suspend fun setTheme(theme: AppTheme) {
        dataStore.edit { prefs ->
            prefs[Keys.THEME] = theme.name
        }
    }

    suspend fun setThemePreset(themePreset: ThemePreset) {
        dataStore.edit { prefs ->
            prefs[Keys.THEME_PRESET] = themePreset.name
            prefs[Keys.AMOLED_DARK_MODE] = false
        }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.DYNAMIC_COLOR] = enabled
        }
    }

    suspend fun setAmoledDarkMode(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.AMOLED_DARK_MODE] = enabled
        }
    }

    fun getChatBackgroundKey(): Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.CHAT_BACKGROUND] ?: "default"
    }

    fun getPinnedConversationIds(): Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[Keys.PINNED_CONVERSATION_IDS] ?: emptySet()
    }

    suspend fun setConversationPinned(conversationId: String, pinned: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.PINNED_CONVERSATION_IDS] ?: emptySet()
            prefs[Keys.PINNED_CONVERSATION_IDS] = if (pinned) {
                current + conversationId
            } else {
                current - conversationId
            }
        }
    }

    suspend fun setChatBackgroundKey(key: String) {
        dataStore.edit { prefs ->
            prefs[Keys.CHAT_BACKGROUND] = key
        }
    }

    private fun persistConfigs(configs: List<LettaConfig>) {
        val configData = configs.map { LettaConfigData.fromLettaConfig(it) }
        val configsJson = json.encodeToString(configData)
        encryptedPrefs.edit().putString(Keys.CONFIGS.name, configsJson).apply()
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
