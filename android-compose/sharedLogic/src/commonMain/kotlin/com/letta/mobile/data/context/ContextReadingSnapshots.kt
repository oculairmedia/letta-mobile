package com.letta.mobile.data.context

import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.util.Telemetry
import kotlinx.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * letta-mobile-wdm6i: the latest context reading per conversation, kept across restarts in the
 * app's existing key-value [SecureSettingsStore] (the store already holding per-agent UI state
 * such as cached mascot identities) — one JSON value under one key, no schema.
 *
 * Bounded: at most [maxEntries] conversations, the least recently updated dropped first.
 * Defensive: anything unreadable — corrupt JSON, an old shape, a blank id, a negative count —
 * reads as no reading, never as a crash.
 */
class ContextReadingSnapshots(
    private val store: SecureSettingsStore,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    /** The saved readings, oldest update first. */
    fun load(): Map<ContextReadingKey, Int> {
        val raw = store.getString(KEY) ?: return emptyMap()
        val entries = decodeOrNull(raw)?.takeIf { it.version == VERSION }?.entries.orEmpty()
        return entries.mapNotNull(SavedReading::toReading).takeLast(maxEntries).toMap()
    }

    /** Saves [readings] (in update order, oldest first), keeping only the newest [maxEntries]. */
    fun save(readings: Map<ContextReadingKey, Int>) {
        val kept = readings.entries.toList().takeLast(maxEntries).map { (key, tokens) ->
            SavedReading(key.agentId, key.conversationId, tokens)
        }
        val encoded = json.encodeToString(SavedReadings.serializer(), SavedReadings(entries = kept))
        // Best effort: a failed write (desktop keeps this store in a file) must not end the
        // observer that called it, or later turns would stop updating the chip.
        try {
            store.putString(KEY, encoded)
        } catch (failure: IOException) {
            Telemetry.event("ContextReadings", "snapshot.saveFailed", "error" to failure.message, level = Telemetry.Level.WARN)
        }
    }

    /** Corrupt or foreign data is no reading; [IllegalArgumentException] covers non-JSON input. */
    private fun decodeOrNull(raw: String): SavedReadings? =
        try {
            json.decodeFromString(SavedReadings.serializer(), raw)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    @Serializable
    private data class SavedReadings(
        @SerialName("v") val version: Int = VERSION,
        @SerialName("e") val entries: List<SavedReading> = emptyList(),
    )

    @Serializable
    private data class SavedReading(
        @SerialName("a") val agentId: String = "",
        @SerialName("c") val conversationId: String = "",
        @SerialName("t") val tokens: Int = -1,
    ) {
        fun toReading(): Pair<ContextReadingKey, Int>? {
            if (tokens < 0) return null
            val key = contextReadingKeyOf(agentId, conversationId) ?: return null
            return key to tokens
        }
    }

    companion object {
        const val KEY: String = "context_readings_v1"
        const val DEFAULT_MAX_ENTRIES: Int = 64
        private const val VERSION = 1
        private val json = Json { ignoreUnknownKeys = true }
    }
}
