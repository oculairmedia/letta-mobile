package com.letta.mobile.data.storage

import com.letta.mobile.data.serialization.LettaJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/**
 * One structured preference kept as JSON under [key] in a [SecureSettingsStore]. Absent or
 * unreadable reads as null, so a damaged value costs the preference, never the screen.
 */
class JsonPreference<T : Any>(
    private val store: SecureSettingsStore,
    private val key: String,
    private val serializer: KSerializer<T>,
) {
    fun load(): T? {
        val stored = store.getString(key) ?: return null
        return try {
            LettaJson.persisted.decodeFromString(serializer, stored)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun save(value: T) = store.putString(key, LettaJson.persisted.encodeToString(serializer, value))
}
