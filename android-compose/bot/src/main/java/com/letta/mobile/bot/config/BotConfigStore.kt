package com.letta.mobile.bot.config

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.botConfigDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "bot_configs")

/**
 * Persists bot configurations using DataStore.
 * Stores a JSON-encoded list of [BotConfig] objects.
 */
@Singleton
class BotConfigStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val configsKey = stringPreferencesKey("bot_configs_json")

    /** Observable flow of all saved bot configs. */
    val configs: Flow<List<BotConfig>> = context.botConfigDataStore.data.map { prefs ->
        val raw = prefs[configsKey] ?: return@map emptyList()
        try {
            json.decodeFromString<List<BotConfig>>(raw)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse bot configs", e)
            emptyList()
        }
    }

    /** Save a config (insert or update by ID). */
    suspend fun saveConfig(config: BotConfig) {
        context.botConfigDataStore.edit { prefs ->
            val current = parseConfigs(prefs)
            val updated = current.filterNot { it.id == config.id } + config
            prefs[configsKey] = json.encodeToString(updated)
        }
    }

    /** Delete a config by ID. */
    suspend fun deleteConfig(configId: String) {
        context.botConfigDataStore.edit { prefs ->
            val current = parseConfigs(prefs)
            prefs[configsKey] = json.encodeToString(current.filterNot { it.id == configId })
        }
    }

    /** Get all configs synchronously (from cached DataStore). */
    suspend fun getAll(): List<BotConfig> {
        var result = emptyList<BotConfig>()
        context.botConfigDataStore.edit { prefs ->
            result = parseConfigs(prefs)
        }
        return result
    }

    private fun parseConfigs(prefs: Preferences): List<BotConfig> {
        val raw = prefs[configsKey] ?: return emptyList()
        return try {
            json.decodeFromString(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val TAG = "BotConfigStore"
    }
}
