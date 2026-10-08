package com.letta.mobile.data.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * letta-mobile-bzvro.18 (F18): the `recentModels` key of letta-code's `~/.letta/settings.json`
 * (`string[]`, most recent first, at most 10), which the TUI's model selector and the reference
 * desktop app share. Desktop reads and writes the same key so a model picked in either app is
 * recent in the other.
 *
 * Only that key is ever touched: [merge] keeps every other key, and its position, as it was.
 */
object LettaSettingsRecentModels {
    const val KEY = "recentModels"

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /** The list in [settingsJson]; empty when the file is missing, unreadable, or has no list. */
    fun read(settingsJson: String?): List<String> {
        val settings = parse(settingsJson) ?: return emptyList()
        return (settings[KEY] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull }
    }

    /**
     * [settingsJson] with `recentModels` set to [models]. A missing or blank file becomes a file
     * with only that key. Null when the file holds something that is not a JSON object: it is not
     * ours to overwrite.
     */
    fun merge(settingsJson: String?, models: List<String>): String? {
        val settings = if (settingsJson.isNullOrBlank()) JsonObject(emptyMap()) else parse(settingsJson) ?: return null
        val updated = LinkedHashMap(settings)
        updated[KEY] = JsonArray(models.map(::JsonPrimitive))
        return json.encodeToString(JsonObject.serializer(), JsonObject(updated)) + "\n"
    }

    private fun parse(settingsJson: String?): JsonObject? =
        settingsJson?.takeIf { it.isNotBlank() }?.let { text -> runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() }
}
