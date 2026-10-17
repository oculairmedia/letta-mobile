package com.letta.mobile.data.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The small synchronous key-value port every platform binds: string preferences read on the UI
 * thread at composition time (pins, archived ids, layout, dock placement, text size). Android keeps
 * it in shared preferences, desktop in a properties file, the web in localStorage. Larger or many
 * values (canvases, images) go in a [KeyValueStore] instead.
 *
 * The web's binding is not secret: localStorage is readable by any script on the page, so it keeps
 * access tokens in memory only (see the web's localStorage binding).
 */
interface SecureSettingsStore {
    fun getString(key: String, defaultValue: String? = null): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun clear()
}

/**
 * A [SecureSettingsStore] in memory: tests, previews, and where a platform's storage is unavailable
 * (a private browser window). Safe to share between threads.
 */
class InMemorySecureSettingsStore(initialValues: Map<String, String> = emptyMap()) : SecureSettingsStore {
    private val values = MutableStateFlow(initialValues)

    override fun getString(key: String, defaultValue: String?): String? = values.value[key] ?: defaultValue

    override fun putString(key: String, value: String) = values.update { it + (key to value) }

    override fun remove(key: String) = values.update { it - key }

    override fun clear() {
        values.value = emptyMap()
    }
}
