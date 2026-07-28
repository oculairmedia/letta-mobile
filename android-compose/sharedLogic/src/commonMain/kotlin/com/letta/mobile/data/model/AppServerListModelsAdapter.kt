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
import kotlinx.serialization.json.intOrNull
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
 *
 * When the presentation entry (or `updateArgs` / `flags`) carries context or
 * output limits, those values are preserved. Missing limits may still be filled
 * by [ModelCatalogNormalizer] for known models (Grok / MiniMax).
 */
object AppServerListModelsAdapter {
    fun decodeEntries(entries: JsonArray): List<AppServerListModelEntry> =
        decodeEntriesWithRaw(entries).map { it.first }

    /**
     * Decode presentation entries while retaining each source [JsonObject] for
     * limit extraction (top-level / flags / updateArgs / already-adapted keys).
     */
    internal fun decodeEntriesWithRaw(entries: JsonArray): List<Pair<AppServerListModelEntry, JsonObject?>> =
        entries.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val decoded = runCatching {
                AppServerProtocol.json.decodeFromJsonElement(AppServerListModelEntry.serializer(), obj)
            }.getOrNull()
            val entry = AppServerListModelEntry(
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
            entry to obj
        }

    fun toLlmModel(entry: AppServerListModelEntry, raw: JsonObject? = null): LlmModel {
        // Prefer selection target (updateArgs / selection_handle) over presentation alias.
        val selection = selectionHandle(entry, raw)
        val presentation = entry.handle?.takeIf { it.isNotBlank() }
        val handle = selection ?: presentation ?: entry.id.takeIf { it.isNotBlank() }
        val display = entry.label?.takeIf { it.isNotBlank() }
            ?: presentation?.takeIf { it != handle }
        val name = display ?: handle ?: entry.id
        val limits = extractLimits(entry, raw)
        val routingScopes = listOfNotNull(raw, entry.updateArgs, entry.flags)
        val provider = providerFromHandle(handle.orEmpty())
            .ifBlank { raw?.let { firstString(it, "provider_type", "providerType") }.orEmpty() }
            .ifBlank { providerFromHandle(presentation.orEmpty()) }
        val model = LlmModel(
            id = entry.id.ifBlank { handle.orEmpty() },
            name = name,
            handle = handle,
            displayNameOverride = display,
            providerType = provider,
            // Preserve routing provenance so normalizePaired can keep distinct
            // BYOK / custom endpoints that share an openai/... handle suffix.
            providerName = routingScopes.firstNotNullOfOrNull {
                firstString(it, "provider_name", "providerName")
            },
            providerCategory = routingScopes.firstNotNullOfOrNull {
                firstString(it, "provider_category", "providerCategory")
            },
            modelEndpointType = routingScopes.firstNotNullOfOrNull {
                firstString(it, "model_endpoint_type", "modelEndpointType")
            },
            modelEndpoint = routingScopes.firstNotNullOfOrNull {
                firstString(it, "model_endpoint", "modelEndpoint")
            },
            contextWindow = limits.first,
            maxOutputTokens = limits.second,
            maxTokens = limits.second,
            enableReasoner = null,
        )
        return ModelCatalogNormalizer.enrichLimits(model)
    }

    /** Prefer this over deserializing presentation JSON as [LlmModel] directly. */
    fun toLlmModels(entries: JsonArray): List<LlmModel> =
        ModelCatalogNormalizer.normalizePaired(
            decodeEntriesWithRaw(entries).map { (entry, raw) -> entry to toLlmModel(entry, raw) },
        ).map { it.second }

    fun toLlmModelArray(entries: JsonArray): JsonArray {
        val pairs = decodeEntriesWithRaw(entries).map { (entry, raw) ->
            entry to toLlmModel(entry, raw)
        }
        return buildJsonArray {
            // Keep each winning model paired with its own source entry (not last-wins by handle).
            ModelCatalogNormalizer.normalizePaired(pairs).forEach { (sourceEntry, model) ->
                add(toLlmModelObject(model, sourceEntry))
            }
        }
    }

    fun toLlmModelObject(entry: AppServerListModelEntry): JsonObject =
        toLlmModelObject(toLlmModel(entry), entry)

    fun toLlmModelObject(model: LlmModel, sourceEntry: AppServerListModelEntry? = null): JsonObject {
        val selection = sourceEntry?.let { selectionHandle(it) } ?: model.handle
        return buildJsonObject {
            put("id", model.id)
            put("name", model.name)
            model.handle?.let { put("handle", it) }
            model.displayNameOverride?.let { put("display_name", it) }
            put("provider_type", model.providerType)
            model.providerName?.let { put("provider_name", it) }
            model.providerCategory?.let { put("provider_category", it) }
            model.modelEndpointType?.let { put("model_endpoint_type", it) }
            model.modelEndpoint?.let { put("model_endpoint", it) }
            sourceEntry?.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
            model.contextWindow?.takeIf { it > 0 }?.let { put("context_window", it) }
            model.maxOutputTokens?.takeIf { it > 0 }?.let { put("max_output_tokens", it) }
            model.maxTokens?.takeIf { it > 0 }?.let { put("max_tokens", it) }
            selection?.let { put("selection_handle", it) }
            // Merge normalized limits into the server-provided updateArgs so extra
            // selection fields (model / model_handle / id) survive adaptation.
            // Explicit updateArgs caps are never overwritten by catalog defaults.
            put("updateArgs", mergeUpdateArgs(sourceEntry?.updateArgs, selection, model))
        }
    }

    /** Handle / model id to send on agent/conversation model update. */
    fun selectionHandle(entry: AppServerListModelEntry, raw: JsonObject? = null): String? {
        // Already-adapted Iroh payloads carry selection_handle explicitly.
        raw?.let { firstString(it, "selection_handle", "selectionHandle") }?.let { return it }
        val fromArgs = entry.updateArgs?.let { args ->
            firstString(args, "handle", "model", "model_handle", "id")
        }
        return fromArgs
            ?: entry.handle?.takeIf { it.isNotBlank() }
            ?: entry.id.takeIf { it.isNotBlank() }
    }

    private fun mergeUpdateArgs(
        original: JsonObject?,
        selection: String?,
        model: LlmModel,
    ): JsonObject = buildJsonObject {
        original?.forEach { (key, value) -> put(key, value) }
        selection?.let { put("handle", it) }
        if (!hasExplicitLimit(original, "context_window_limit", "contextWindowLimit", "context_window", "contextWindow")) {
            model.contextWindow?.takeIf { it > 0 }?.let { put("context_window_limit", it) }
        }
        if (!hasExplicitLimit(original, "max_output_tokens", "maxOutputTokens", "max_tokens", "maxTokens")) {
            model.maxOutputTokens?.takeIf { it > 0 }?.let { put("max_output_tokens", it) }
        }
    }

    private fun extractLimits(
        entry: AppServerListModelEntry,
        raw: JsonObject?,
    ): Pair<Int?, Int?> {
        // Prefer selection-specific updateArgs caps over catalog flags / top-level capacity.
        val scopes = listOfNotNull(entry.updateArgs, entry.flags, raw)
        val context = scopes.firstNotNullOfOrNull { scope ->
            firstInt(
                scope,
                "context_window",
                "contextWindow",
                "context_window_limit",
                "contextWindowLimit",
                "max_input_tokens",
                "maxInputTokens",
            )
        }
        val output = scopes.firstNotNullOfOrNull { scope ->
            firstInt(
                scope,
                "max_output_tokens",
                "maxOutputTokens",
                "max_tokens",
                "maxTokens",
            )
        }
        return context to output
    }

    private fun hasExplicitLimit(original: JsonObject?, vararg keys: String): Boolean {
        if (original == null) return false
        return keys.any { key -> firstInt(original, key) != null }
    }

    private fun providerFromHandle(handle: String): String {
        val slash = handle.indexOf('/')
        return if (slash > 0) handle.substring(0, slash) else ""
    }

    private fun firstString(raw: JsonObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            (raw[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }

    private fun firstInt(raw: JsonObject, vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key ->
            val prim = raw[key] as? JsonPrimitive ?: return@firstNotNullOfOrNull null
            prim.contentOrNull?.toIntOrNull()?.takeIf { it > 0 }
                ?: prim.intOrNull?.takeIf { it > 0 }
        }
}
