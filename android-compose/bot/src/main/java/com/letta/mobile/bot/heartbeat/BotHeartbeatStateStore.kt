package com.letta.mobile.bot.heartbeat

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.botHeartbeatStateDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "bot_heartbeat_state")

@Singleton
class BotHeartbeatStateStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val stateKey = stringPreferencesKey("bot_heartbeat_last_run_json")

    suspend fun getLastRunAt(configId: String): Long? = readState()[configId]

    suspend fun setLastRunAt(configId: String, timestampMillis: Long) {
        context.botHeartbeatStateDataStore.edit { prefs ->
            val updated = readState(prefs).toMutableMap().apply {
                put(configId, timestampMillis)
            }
            prefs[stateKey] = json.encodeToString(updated)
        }
    }

    suspend fun getLastScheduledRunAt(configId: String, jobId: String): Long? {
        return readJobState()[jobStateKey(configId, jobId)]?.lastRunAt
    }

    suspend fun setLastScheduledRunAt(configId: String, jobId: String, timestampMillis: Long) {
        context.botHeartbeatStateDataStore.edit { prefs ->
            val updated = readJobState(prefs).toMutableMap().apply {
                put(jobStateKey(configId, jobId), BotScheduledJobState(lastRunAt = timestampMillis))
            }
            prefs[jobStateKey] = json.encodeToString(updated)
        }
    }

    private suspend fun readState(): Map<String, Long> {
        var state = emptyMap<String, Long>()
        context.botHeartbeatStateDataStore.edit { prefs ->
            state = readState(prefs)
        }
        return state
    }

    private suspend fun readJobState(): Map<String, BotScheduledJobState> {
        var state = emptyMap<String, BotScheduledJobState>()
        context.botHeartbeatStateDataStore.edit { prefs ->
            state = readJobState(prefs)
        }
        return state
    }

    private fun readState(prefs: Preferences): Map<String, Long> {
        val raw = prefs[stateKey] ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, Long>>(raw) }
            .getOrDefault(emptyMap())
    }

    private fun readJobState(prefs: Preferences): Map<String, BotScheduledJobState> {
        val raw = prefs[jobStateKey] ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, BotScheduledJobState>>(raw) }
            .getOrDefault(emptyMap())
    }

    private fun jobStateKey(configId: String, jobId: String): String = "$configId::$jobId"

    @Serializable
    data class BotScheduledJobState(
        @SerialName("last_run_at") val lastRunAt: Long,
    )

    companion object {
        private val jobStateKey = stringPreferencesKey("bot_scheduled_job_last_run_json")
    }
}
