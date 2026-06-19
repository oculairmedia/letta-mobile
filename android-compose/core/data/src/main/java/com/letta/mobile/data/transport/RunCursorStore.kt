package com.letta.mobile.data.transport

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json


private val Context.runCursorDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "run_cursors")

/**
 * Production implementation: in-memory cache mirrored to
 * Preferences-DataStore (`run_cursors` file). One string key holds the
 * entire JSON-encoded map; writes are debounced by being launched on
 * the store's own IO scope under [mutex].
 */
@Singleton
class DataStoreRunCursorStore @Inject constructor(
    @ApplicationContext context: Context,
) : RunCursorStore {

    private val dataStore: DataStore<Preferences> = context.runCursorDataStore
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val active = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()
    private val terminal = ConcurrentHashMap<String, MutableSet<String>>()

    @Volatile private var loaded: Boolean = false

    override fun ensureLoaded() {
        if (loaded) return
        runBlocking(Dispatchers.IO) {
            mutex.withLock {
                if (loaded) return@withLock
                runCatching {
                    val prefs = dataStore.data.first()
                    val raw = prefs[KEY_ACTIVE_RUNS] ?: "{}"
                    val decoded = json.decodeFromString(SerializedMap.serializer(), raw)
                    decoded.byConv.forEach { (conv, runs) ->
                        active[conv] = ConcurrentHashMap(runs)
                    }
                    decoded.terminalByConv.forEach { (conv, runs) ->
                        terminal[conv] = ConcurrentHashMap.newKeySet<String>().also { it.addAll(runs) }
                    }
                }.onFailure {
                    Log.w(TAG, "Failed to load persisted run cursors: ${it.message}", it)
                }
                loaded = true
            }
        }
    }

    override fun record(conversationId: String, runId: String, seq: Long, isTerminal: Boolean) {
        if (conversationId.isEmpty() || runId.isEmpty() || seq <= 0L) return
        var changed = false
        synchronized(this) {
            if (isTerminal) {
                val perConv = active[conversationId]
                if (perConv?.remove(runId) != null) {
                    changed = true
                    if (perConv.isEmpty()) active.remove(conversationId, perConv)
                }
                val terminalRuns = terminal.getOrPut(conversationId) { ConcurrentHashMap.newKeySet() }
                if (terminalRuns.add(runId)) changed = true
                return@synchronized
            }
            if (terminal[conversationId]?.contains(runId) == true) return@synchronized
            val perConv = active.getOrPut(conversationId) { ConcurrentHashMap() }
            perConv.compute(runId) { _, existing ->
                if (existing == null || seq > existing) {
                    changed = true
                    seq
                } else {
                    existing
                }
            }
        }
        if (changed) flushAsync()
    }

    override fun clear(conversationId: String, runId: String) {
        if (conversationId.isEmpty() || runId.isEmpty()) return
        var changed = false
        synchronized(this) {
            val perConv = active[conversationId]
            if (perConv?.remove(runId) != null) {
                changed = true
                if (perConv.isEmpty()) active.remove(conversationId, perConv)
            }
        }
        if (changed) flushAsync()
    }

    override fun clearTerminal(conversationId: String, runId: String) {
        if (conversationId.isEmpty() || runId.isEmpty()) return
        var changed = false
        synchronized(this) {
            val perConv = active[conversationId]
            if (perConv?.remove(runId) != null) {
                changed = true
                if (perConv.isEmpty()) active.remove(conversationId, perConv)
            }
            val terminalRuns = terminal[conversationId]
            if (terminalRuns?.remove(runId) == true) {
                changed = true
                if (terminalRuns.isEmpty()) terminal.remove(conversationId, terminalRuns)
            }
        }
        if (changed) flushAsync()
    }

    override fun allActiveRuns(): Map<String, Map<String, Long>> =
        active.mapValues { (_, runs) -> HashMap(runs) }

    override fun activeRuns(conversationId: String): Map<String, Long> =
        active[conversationId]?.let { HashMap(it) }.orEmpty()

    private fun flushAsync() {
        scope.launch {
            mutex.withLock {
                val snapshot = SerializedMap(
                    byConv = active.mapValues { (_, runs) -> HashMap(runs) },
                    terminalByConv = terminal.mapValues { (_, runs) -> runs.toList() },
                )
                runCatching {
                    val encoded = json.encodeToString(SerializedMap.serializer(), snapshot)
                    dataStore.edit { prefs -> prefs[KEY_ACTIVE_RUNS] = encoded }
                }.onFailure {
                    Log.w(TAG, "Failed to persist run cursors: ${it.message}", it)
                }
            }
        }
    }

    @Serializable
    private data class SerializedMap(
        val byConv: Map<String, Map<String, Long>> = emptyMap(),
        val terminalByConv: Map<String, List<String>> = emptyMap(),
    )

    companion object {
        private const val TAG = "DataStoreRunCursorStore"
        private val KEY_ACTIVE_RUNS = stringPreferencesKey("active_runs")
    }
}
