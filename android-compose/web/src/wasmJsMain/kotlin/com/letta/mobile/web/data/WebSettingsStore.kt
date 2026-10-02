package com.letta.mobile.web.data

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.storage.MutableSettingsPreferencesEditor
import com.letta.mobile.data.storage.SettingsPreferencesSnapshot
import com.letta.mobile.data.storage.SettingsPreferencesStore
import kotlinx.browser.localStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive

/** One string slot the settings live in; the browser's localStorage in production. */
interface WebSettingsSlot {
    fun read(): String?

    fun write(value: String?)
}

/**
 * The browser's localStorage under [key]. Storage can be unavailable (a private window, blocked
 * site data), so a failure reads as empty and a write is dropped: the settings then last the tab.
 */
class LocalStorageSettingsSlot(private val key: String = DEFAULT_KEY) : WebSettingsSlot {
    override fun read(): String? = runCatching { localStorage.getItem(key) }.getOrNull()

    override fun write(value: String?) {
        runCatching { if (value == null) localStorage.removeItem(key) else localStorage.setItem(key, value) }
    }

    private companion object {
        const val DEFAULT_KEY = "letta.web.settings"
    }
}

/**
 * letta-mobile-o4ygk.4.5: the web's [SettingsPreferencesStore], one JSON object in a
 * [WebSettingsSlot]. Non-secret settings only: the access token never goes here (see [WebSettings]).
 */
class WebSettingsPreferencesStore(private val slot: WebSettingsSlot = LocalStorageSettingsSlot()) : SettingsPreferencesStore {
    private val state = MutableStateFlow(WebSettingsSnapshot(decode(slot.read())))

    override val snapshots: StateFlow<SettingsPreferencesSnapshot> = state.asStateFlow()

    override suspend fun edit(block: suspend (MutableSettingsPreferencesEditor) -> Unit) {
        val editor = WebSettingsEditor(state.value.values.toMutableMap())
        block(editor)
        commit(editor.values.toMap())
    }

    override suspend fun clearAll() = commit(emptyMap())

    private fun commit(values: Map<String, JsonElement>) {
        slot.write(if (values.isEmpty()) null else JsonObject(values).toString())
        state.value = WebSettingsSnapshot(values)
    }

    private fun decode(raw: String?): Map<String, JsonElement> =
        raw?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }.orEmpty()
}

private open class WebSettingsSnapshot(open val values: Map<String, JsonElement>) : SettingsPreferencesSnapshot {
    override fun getString(key: String): String? = primitive(key)?.contentOrNull

    override fun getBoolean(key: String): Boolean? = primitive(key)?.booleanOrNull

    override fun getFloat(key: String): Float? = primitive(key)?.floatOrNull

    override fun getStringSet(key: String): Set<String>? =
        (values[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet()

    private fun primitive(key: String): JsonPrimitive? = values[key] as? JsonPrimitive
}

private class WebSettingsEditor(
    override val values: MutableMap<String, JsonElement>,
) : WebSettingsSnapshot(values), MutableSettingsPreferencesEditor {
    override fun putString(key: String, value: String) = values.set(key, JsonPrimitive(value))

    override fun putBoolean(key: String, value: Boolean) = values.set(key, JsonPrimitive(value))

    override fun putFloat(key: String, value: Float) = values.set(key, JsonPrimitive(value))

    override fun putStringSet(key: String, value: Set<String>) = values.set(key, JsonArray(value.map(::JsonPrimitive)))

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun clear() = values.clear()
}

/**
 * The settings the web shell keeps across reloads: the backend address and whether conversations
 * open on the canvas. The access token is deliberately not stored: localStorage is readable by any
 * script on the page, so the token lasts the tab and is entered again after a reload.
 */
class WebSettings(private val store: SettingsPreferencesStore) {
    fun config(snapshot: SettingsPreferencesSnapshot): LettaConfig = LettaConfig(
        id = CONFIG_ID,
        mode = snapshot.getString(KEY_MODE)?.let { mode -> LettaConfig.Mode.entries.firstOrNull { it.name == mode } }
            ?: LettaConfig.Mode.SELF_HOSTED,
        serverUrl = snapshot.getString(KEY_SERVER_URL).orEmpty(),
        accessToken = null,
    )

    fun openChatsOnCanvas(snapshot: SettingsPreferencesSnapshot): Boolean = snapshot.getBoolean(KEY_OPEN_ON_CANVAS) ?: true

    suspend fun saveConfig(config: LettaConfig) = store.edit { editor ->
        editor.putString(KEY_SERVER_URL, config.serverUrl)
        editor.putString(KEY_MODE, config.mode.name)
    }

    private companion object {
        const val CONFIG_ID = "default"
        const val KEY_SERVER_URL = "backend.server_url"
        const val KEY_MODE = "backend.mode"
        const val KEY_OPEN_ON_CANVAS = "chat.open_on_canvas"
    }
}
