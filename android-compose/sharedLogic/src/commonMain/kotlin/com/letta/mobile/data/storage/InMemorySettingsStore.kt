package com.letta.mobile.data.storage

/**
 * A [SecureSettingsStore] that lasts as long as it does: the browser tab's preferences (Home pins,
 * project pins, a mascot pick), where nothing secret may reach the page's script-readable storage.
 */
class InMemorySettingsStore : SecureSettingsStore {
    private val values = mutableMapOf<String, String>()

    override fun getString(key: String, defaultValue: String?): String? = values[key] ?: defaultValue

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun clear() = values.clear()
}
