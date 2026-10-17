package com.letta.mobile.web.data

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.storage.SecureSettingsStore

/**
 * letta-mobile-o4ygk.4.5: the backend the web shell keeps across reloads, its address and mode, in
 * the web's settings store. The access token is deliberately not kept: localStorage is readable by
 * any script on the page, so the token lasts the tab and is entered again after a reload.
 */
class WebSettingsStore(private val store: SecureSettingsStore) {
    /** The saved backend without a token, or the default one when nothing (or nothing readable) is stored. */
    fun load(): LettaConfig {
        val storedMode = store.getString(KEY_MODE)
        return LettaConfig(
            id = CONFIG_ID,
            mode = LettaConfig.Mode.entries.firstOrNull { it.name == storedMode } ?: LettaConfig.Mode.SELF_HOSTED,
            serverUrl = store.getString(KEY_SERVER_URL).orEmpty(),
            accessToken = null,
        )
    }

    /** Keeps the backend address and mode of [config]; its token is dropped. */
    fun saveBackend(config: LettaConfig) {
        store.putString(KEY_SERVER_URL, config.serverUrl)
        store.putString(KEY_MODE, config.mode.name)
    }

    private companion object {
        const val CONFIG_ID = "default"
        const val KEY_SERVER_URL = "backend.server_url"
        const val KEY_MODE = "backend.mode"
    }
}
