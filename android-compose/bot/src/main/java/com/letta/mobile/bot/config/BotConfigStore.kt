package com.letta.mobile.bot.config

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    /**
     * Schema version for the persisted bot config blob.
     *
     *   1 — pre-w2hx.4: each config has a required top-level `agent_id`
     *       binding the bot to one Letta agent.
     *   2 — w2hx.4+: `agent_id` removed; heartbeat-bound agent moved to
     *       `heartbeat_agent_id`. Migration promotes the v1 `agent_id`
     *       to `heartbeat_agent_id` only when `heartbeat_enabled=true`,
     *       otherwise the field is dropped (chats now supply agents
     *       per-message).
     */
    private val schemaVersionKey = intPreferencesKey("bot_configs_schema_version")
    private val currentSchemaVersion = 2

    /** Observable flow of all saved bot configs. */
    val configs: Flow<List<BotConfig>> = context.botConfigDataStore.data.map { prefs ->
        val raw = prefs[configsKey] ?: return@map emptyList()
        val storedVersion = prefs[schemaVersionKey] ?: 1
        val migrated = if (storedVersion < currentSchemaVersion) migrateRaw(raw, storedVersion) else raw
        try {
            json.decodeFromString<List<BotConfig>>(migrated)
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
            prefs[schemaVersionKey] = currentSchemaVersion
        }
    }

    /** Delete a config by ID. */
    suspend fun deleteConfig(configId: String) {
        context.botConfigDataStore.edit { prefs ->
            val current = parseConfigs(prefs)
            prefs[configsKey] = json.encodeToString(current.filterNot { it.id == configId })
            prefs[schemaVersionKey] = currentSchemaVersion
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
        val storedVersion = prefs[schemaVersionKey] ?: 1
        val migrated = if (storedVersion < currentSchemaVersion) migrateRaw(raw, storedVersion) else raw
        return try {
            json.decodeFromString(migrated)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun migrateRaw(raw: String, fromVersion: Int): String {
        return try {
            migrateBotConfigBlob(raw, fromVersion, currentSchemaVersion, json)
        } catch (e: Exception) {
            Log.w(TAG, "Migration failed; falling back to raw blob (unknown keys will be ignored)", e)
            raw
        }
    }

    companion object {
        private const val TAG = "BotConfigStore"
    }
}

/**
 * letta-mobile-w2hx.4: in-place migration of persisted config blobs.
 *
 * v1 → v2 maps each entry's top-level `agent_id` to `heartbeat_agent_id`
 * *iff* heartbeat was enabled on that entry. For entries that didn't use
 * heartbeat, the v1 `agent_id` is just dropped — those configs were
 * never relying on a server-side "default agent" anyway, since
 * interactive chat already routes via the active chat row's agent.
 *
 * Top-level (not method on `BotConfigStore`) so unit tests can exercise
 * the migrator without standing up a DataStore.
 */
internal fun migrateBotConfigBlob(
    raw: String,
    fromVersion: Int,
    toVersion: Int,
    json: Json,
): String {
    var current = raw
    if (fromVersion < 2 && toVersion >= 2) current = migrateBotConfigV1ToV2(current, json)
    return current
}

private fun migrateBotConfigV1ToV2(raw: String, json: Json): String {
    val tree = json.parseToJsonElement(raw)
    if (tree !is JsonArray) return raw
    val migrated = buildJsonArray {
        for (entry in tree) {
            val obj = entry.jsonObject
            val agentId = obj["agent_id"]?.jsonPrimitive?.contentOrNull
            val heartbeatEnabled = obj["heartbeat_enabled"]?.jsonPrimitive?.boolean ?: false
            add(
                buildJsonObject {
                    for ((k, v) in obj) {
                        // Strip the legacy field — it's gone from the data class.
                        if (k == "agent_id") continue
                        put(k, v)
                    }
                    // Only promote when heartbeat was actually in use.
                    if (heartbeatEnabled && !agentId.isNullOrBlank() &&
                        obj["heartbeat_agent_id"] == null
                    ) {
                        put("heartbeat_agent_id", JsonPrimitive(agentId))
                    }
                }
            )
        }
    }
    return json.encodeToString(JsonArray.serializer(), migrated)
}
