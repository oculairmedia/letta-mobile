package com.letta.mobile.web.storage

import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.util.Telemetry
import kotlinx.browser.localStorage
import kotlin.js.JsException

/**
 * The web's [SecureSettingsStore]: the browser's localStorage, every key under [prefix].
 *
 * Not secret: any script on the page can read localStorage, so a key that names a token
 * ([isSecretKey]) is kept in memory only and lasts the tab. Storage can be unavailable (a private
 * window, blocked site data, a full quota): the first failure is logged at ERROR and every value
 * from then on lasts the tab, with no crash.
 */
class LocalStorageSettingsStore(private val prefix: String = DEFAULT_PREFIX) : SecureSettingsStore {
    private val memory = InMemorySecureSettingsStore()
    private var available = true

    override fun getString(key: String, defaultValue: String?): String? {
        if (isSecretKey(key)) return memory.getString(key, defaultValue)
        return browser("read") { localStorage.getItem(prefix + key) } ?: memory.getString(key, defaultValue)
    }

    override fun putString(key: String, value: String) {
        memory.putString(key, value)
        if (!isSecretKey(key)) browser("write") { localStorage.setItem(prefix + key, value) }
    }

    override fun remove(key: String) {
        memory.remove(key)
        browser("remove") { localStorage.removeItem(prefix + key) }
    }

    override fun clear() {
        memory.clear()
        browser("clear") {
            val ours = List(localStorage.length) { localStorage.key(it) }.filterNotNull().filter { it.startsWith(prefix) }
            ours.forEach(localStorage::removeItem)
        }
    }

    /** [action] on localStorage, or null once it has failed: the values then live in [memory]. */
    private fun <T> browser(what: String, action: () -> T): T? {
        if (!available) return null
        return try {
            action()
        } catch (error: JsException) {
            available = false
            Telemetry.error(TAG, "unavailable", error, "operation" to what)
            null
        }
    }

    companion object {
        const val DEFAULT_PREFIX: String = "letta.web."
        private const val TAG = "LocalStorageSettings"

        /** Access tokens and the like: never written where other scripts on the page could read them. */
        fun isSecretKey(key: String): Boolean = key.contains("token", ignoreCase = true) || key.contains("secret", ignoreCase = true)
    }
}
