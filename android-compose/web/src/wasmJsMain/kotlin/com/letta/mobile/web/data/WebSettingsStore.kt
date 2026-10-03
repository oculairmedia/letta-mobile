package com.letta.mobile.web.data

import com.letta.mobile.data.model.LettaConfig
import kotlinx.browser.localStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The settings document as stored: one JSON object. */
value class WebSettingsDocument(val json: String)

/** Where the settings document lives; the browser's localStorage in production. */
interface WebSettingsSlot {
    fun read(): WebSettingsDocument?

    fun write(document: WebSettingsDocument?)
}

/**
 * The browser's localStorage. Storage can be unavailable (a private window, blocked site data), so
 * a failure reads as empty and a write is dropped: the settings then last the tab.
 */
class LocalStorageSettingsSlot : WebSettingsSlot {
    override fun read(): WebSettingsDocument? =
        runCatching { localStorage.getItem(STORAGE_KEY) }.getOrNull()?.let(::WebSettingsDocument)

    override fun write(document: WebSettingsDocument?) {
        runCatching {
            if (document == null) localStorage.removeItem(STORAGE_KEY) else localStorage.setItem(STORAGE_KEY, document.json)
        }
    }

    private companion object {
        const val STORAGE_KEY = "letta.web.settings"
    }
}

/**
 * The settings the web shell keeps across reloads: the backend address and mode, and whether
 * conversations open on the canvas. The access token is deliberately not here: localStorage is
 * readable by any script on the page, so the token lasts the tab and is entered again after a reload.
 */
data class WebSavedSettings(
    val serverUrl: String = "",
    val mode: LettaConfig.Mode = LettaConfig.Mode.SELF_HOSTED,
    val openChatsOnCanvas: Boolean = true,
) {
    /** The backend config these settings describe, without a token. */
    val config: LettaConfig
        get() = LettaConfig(id = CONFIG_ID, mode = mode, serverUrl = serverUrl, accessToken = null)

    private companion object {
        const val CONFIG_ID = "default"
    }
}

/** letta-mobile-o4ygk.4.5: reads and writes [WebSavedSettings] in a [WebSettingsSlot]. */
class WebSettingsStore(private val slot: WebSettingsSlot = LocalStorageSettingsSlot()) {
    /** The saved settings, or the defaults when nothing (or nothing readable) is stored. */
    fun load(): WebSavedSettings = slot.read()?.let(::decode) ?: WebSavedSettings()

    fun save(settings: WebSavedSettings) = slot.write(encode(settings))

    /** Keeps the backend address and mode of [config]; its token is dropped. */
    fun saveBackend(config: LettaConfig) = save(load().copy(serverUrl = config.serverUrl, mode = config.mode))

    private fun encode(settings: WebSavedSettings) = WebSettingsDocument(
        buildJsonObject {
            put(KEY_SERVER_URL, settings.serverUrl)
            put(KEY_MODE, settings.mode.name)
            put(KEY_OPEN_ON_CANVAS, settings.openChatsOnCanvas)
        }.toString(),
    )

    private fun decode(document: WebSettingsDocument): WebSavedSettings? {
        val fields = runCatching { Json.parseToJsonElement(document.json) as? JsonObject }.getOrNull() ?: return null
        val defaults = WebSavedSettings()
        return WebSavedSettings(
            serverUrl = fields[KEY_SERVER_URL]?.jsonPrimitive?.contentOrNull ?: defaults.serverUrl,
            mode = fields[KEY_MODE]?.jsonPrimitive?.contentOrNull
                ?.let { name -> LettaConfig.Mode.entries.firstOrNull { it.name == name } } ?: defaults.mode,
            openChatsOnCanvas = fields[KEY_OPEN_ON_CANVAS]?.jsonPrimitive?.booleanOrNull ?: defaults.openChatsOnCanvas,
        )
    }

    private companion object {
        const val KEY_SERVER_URL = "backend.server_url"
        const val KEY_MODE = "backend.mode"
        const val KEY_OPEN_ON_CANVAS = "chat.open_on_canvas"
    }
}
