package com.letta.mobile.data.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Projects App Server `list_models` presentation entries into the mobile
 * [LlmModel] wire shape so clients do not rely on unknown-key defaults.
 */
object AppServerListModelsAdapter {
    fun toLlmModelArray(entries: JsonArray): JsonArray = buildJsonArray {
        entries.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            add(toLlmModelObject(obj))
        }
    }

    fun toLlmModelObject(entry: JsonObject): JsonObject {
        val id = firstString(entry, "id", "handle", "name", "label").orEmpty()
        val handle = firstString(entry, "handle", "id", "name")
        val name = firstString(entry, "name", "label", "handle", "id").orEmpty()
        val displayName = firstString(entry, "label", "display_name", "name", "handle")
        val provider = providerFromHandle(handle ?: id)
        return buildJsonObject {
            put("id", id)
            put("name", name)
            handle?.let { put("handle", it) }
            displayName?.let { put("display_name", it) }
            put("provider_type", firstString(entry, "provider_type", "providerType") ?: provider)
            firstString(entry, "provider_name", "providerName")?.let { put("provider_name", it) }
            firstString(entry, "description")?.let { put("description", it) }
            // Canonical catalog fields are absent on presentation entries —
            // leave them unset rather than inventing provider/context defaults.
        }
    }

    private fun providerFromHandle(handle: String): String {
        val slash = handle.indexOf('/')
        return if (slash > 0) handle.substring(0, slash) else ""
    }

    private fun firstString(raw: JsonObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            (raw[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }
}
