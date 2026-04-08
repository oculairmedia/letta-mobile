package com.letta.mobile.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.util.EncryptedPrefsHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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
        val FAVORITE_AGENT_ID = stringPreferencesKey("favorite_agent_id")
        val ADMIN_AGENT_ID = stringPreferencesKey("admin_agent_id")
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
        val updatedConfigs = _configs.value.toMutableList()
        val existingIndex = updatedConfigs.indexOfFirst { it.id == config.id }

        if (existingIndex >= 0) {
            updatedConfigs[existingIndex] = config
        } else {
            updatedConfigs.add(config)
        }

        _configs.value = updatedConfigs
        persistConfigs(updatedConfigs)

        // Always set this config as active (either new or updated)
        _activeConfig.value = config
        encryptedPrefs.edit().putString(Keys.ACTIVE_CONFIG_ID.name, config.id).apply()
    }

    suspend fun setActiveConfigId(id: String) {
        val config = _configs.value.find { it.id == id }
        if (config != null) {
            _activeConfig.value = config
            encryptedPrefs.edit().putString(Keys.ACTIVE_CONFIG_ID.name, id).apply()
        }
    }

    suspend fun deleteConfig(id: String) {
        val updatedConfigs = _configs.value.filter { it.id != id }
        _configs.value = updatedConfigs
        persistConfigs(updatedConfigs)

        if (_activeConfig.value?.id == id) {
            _activeConfig.value = updatedConfigs.firstOrNull()
            _activeConfig.value?.let { newActive ->
                encryptedPrefs.edit().putString(Keys.ACTIVE_CONFIG_ID.name, newActive.id).apply()
            } ?: run {
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

    fun setAdminAgentId(agentId: String?) {
        _adminAgentId.value = agentId
        if (agentId != null) {
            encryptedPrefs.edit().putString(Keys.ADMIN_AGENT_ID.name, agentId).apply()
        } else {
            encryptedPrefs.edit().remove(Keys.ADMIN_AGENT_ID.name).apply()
        }
    }

    fun setFavoriteAgentId(agentId: String?) {
        _favoriteAgentId.value = agentId
        if (agentId != null) {
            encryptedPrefs.edit().putString(Keys.FAVORITE_AGENT_ID.name, agentId).apply()
        } else {
            encryptedPrefs.edit().remove(Keys.FAVORITE_AGENT_ID.name).apply()
        }
    }

    suspend fun clearAllData() {
        encryptedPrefs.edit().clear().apply()
        dataStore.edit { it.clear() }
        _configs.value = emptyList()
        _activeConfig.value = null
        _favoriteAgentId.value = null
        _adminAgentId.value = null
    }

    suspend fun setTheme(theme: AppTheme) {
        dataStore.edit { prefs ->
            prefs[Keys.THEME] = theme.name
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
