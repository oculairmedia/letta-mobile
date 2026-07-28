package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import com.letta.mobile.data.transport.appserver.AppServerProtocol

/**
 * Exact App Server `list_models` presentation entry (0.28.8 / 0.29.7 shape).
 * Not a canonical [LlmModel] — callers must adapt explicitly.
 */
@Serializable
data class AppServerListModelEntry(
    val id: String = "",
    val handle: String? = null,
    val label: String? = null,
    val description: String? = null,
    val flags: JsonObject? = null,
    @SerialName("updateArgs") val updateArgs: JsonObject? = null,
)

/**
 * Explicit presentation → mobile model choice / [LlmModel] projection.
 * Context-window and reasoning fields stay unset (unavailable), never invented.
 */
object AppServerListModelsAdapter {
    fun decodeEntries(entries: JsonArray): List<AppServerListModelEntry> =
        entries.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val decoded = runCatching {
                AppServerProtocol.json.decodeFromJsonElement(AppServerListModelEntry.serializer(), obj)
            }.getOrNull()
            // Always merge presentation + already-adapted LlmModel-shaped keys so
            // client + server adapter call sites stay idempotent.
            AppServerListModelEntry(
                id = decoded?.id?.takeIf { it.isNotBlank() }
                    ?: firstString(obj, "id").orEmpty(),
                handle = decoded?.handle?.takeIf { it.isNotBlank() }
                    ?: firstString(obj, "handle"),
                label = decoded?.label?.takeIf { it.isNotBlank() }
                    ?: firstString(obj, "label", "display_name", "name"),
                description = decoded?.description ?: firstString(obj, "description"),
                flags = decoded?.flags ?: obj["flags"] as? JsonObject,
                updateArgs = decoded?.updateArgs
                    ?: (obj["updateArgs"] ?: obj["update_args"]) as? JsonObject,
            )
        }

    fun toLlmModel(entry: AppServerListModelEntry): LlmModel {
        val handle = entry.handle?.takeIf { it.isNotBlank() }
            ?: selectionHandle(entry)
            ?: entry.id.takeIf { it.isNotBlank() }
        val display = entry.label?.takeIf { it.isNotBlank() }
        val name = display ?: handle ?: entry.id
        return LlmModel(
            id = entry.id.ifBlank { handle.orEmpty() },
            name = name,
            handle = handle,
            displayNameOverride = display,
            providerType = providerFromHandle(handle.orEmpty()),
            // Presentation entries do not carry authoritative catalog metadata.
            contextWindow = null,
            enableReasoner = null,
        )
    }

    /** Prefer this over deserializing presentation JSON as [LlmModel] directly. */
    fun toLlmModels(entries: JsonArray): List<LlmModel> =
        decodeEntries(entries).map(::toLlmModel)

    fun toLlmModelArray(entries: JsonArray): JsonArray = buildJsonArray {
        decodeEntries(entries).forEach { entry ->
            add(toLlmModelObject(entry))
        }
    }

    fun toLlmModelObject(entry: AppServerListModelEntry): JsonObject {
        val model = toLlmModel(entry)
        return buildJsonObject {
            put("id", model.id)
            put("name", model.name)
            model.handle?.let { put("handle", it) }
            model.displayNameOverride?.let { put("display_name", it) }
            put("provider_type", model.providerType)
            entry.description?.let { put("description", it) }
            // Selection payload for model switches — exact updateArgs when present.
            selectionHandle(entry)?.let { put("selection_handle", it) }
            entry.updateArgs?.let { put("updateArgs", it) }
        }
    }

    /** Handle / model id to send on agent/conversation model update. */
    fun selectionHandle(entry: AppServerListModelEntry): String? {
        val fromArgs = entry.updateArgs?.let { args ->
            firstString(args, "handle", "model", "model_handle", "id")
        }
        return fromArgs
            ?: entry.handle?.takeIf { it.isNotBlank() }
            ?: entry.id.takeIf { it.isNotBlank() }
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
