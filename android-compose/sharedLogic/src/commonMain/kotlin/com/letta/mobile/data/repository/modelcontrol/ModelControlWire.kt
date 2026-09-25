package com.letta.mobile.data.repository.modelcontrol

import com.letta.mobile.data.model.AppServerListModelsAdapter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Decoders and param builders for the wrapper's provider/model admin RPC
 * (letta-mobile-w4q4p). Tolerant: unknown keys are ignored and malformed rows
 * are skipped rather than failing the whole listing.
 */
internal object ModelControlWire {
    const val PROVIDER_LIST = "provider.list"
    const val PROVIDER_CONNECT = "provider.connect"
    const val PROVIDER_DISCONNECT = "provider.disconnect"
    const val MODEL_LIST = "model.list"
    const val MODEL_UPDATE = "model.update"
    const val MODEL_EXPOSURE_SET = "model.exposure.set"

    fun providers(element: JsonElement?): List<ConnectableProvider> =
        (element as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::provider) }

    fun mutation(element: JsonElement?): ProviderMutationResult {
        val obj = element as? JsonObject
        return ProviderMutationResult(
            providers = providers(obj?.get("providers")),
            modelsMayHaveChanged = obj?.bool("models_may_have_changed") ?: true,
        )
    }

    fun catalog(element: JsonElement?): List<CatalogModel> {
        val rows = (element as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        return AppServerListModelsAdapter.decodeEntriesWithRaw(JsonArray(rows)).map { (entry, raw) ->
            val row = raw ?: JsonObject(emptyMap())
            CatalogModel(
                // Rows are already adapted by the wrapper; the adapter keeps
                // their selection_handle and limits (one row per handle).
                model = AppServerListModelsAdapter.toLlmModel(entry, row),
                exposed = row.bool("exposed") ?: true,
                reasoningEfforts = row.strings("reasoning_efforts"),
            )
        }
    }

    fun connectParams(request: ProviderConnectRequest): JsonObject = buildJsonObject {
        put("provider_id", request.providerId)
        request.authMethodId?.let { put("auth_method_id", it) }
        put("fields", JsonObject(request.fields.mapValues { JsonPrimitive(it.value) }))
    }

    fun updateParams(target: ConversationModelTarget, handle: String?, effort: ReasoningEffortChoice): JsonObject =
        buildJsonObject {
            put("agent_id", target.agentId)
            put("conversation_id", target.conversationId)
            handle?.let { put("model_handle", it) }
            when (effort) {
                ReasoningEffortChoice.Unchanged -> Unit
                ReasoningEffortChoice.ProviderDefault -> put("reasoning_effort", JsonNull)
                is ReasoningEffortChoice.Named -> put("reasoning_effort", effort.effort)
            }
        }

    fun modelUpdate(element: JsonElement?): ConversationModelUpdate {
        val obj = element as? JsonObject
        return ConversationModelUpdate(appliedTo = obj?.string("applied_to"), modelHandle = obj?.string("model_handle"))
    }

    private fun provider(row: JsonObject): ConnectableProvider? {
        val id = row.string("id") ?: return null
        return ConnectableProvider(
            id = id,
            displayName = row.string("display_name") ?: id,
            description = row.string("description").orEmpty(),
            providerType = row.string("provider_type").orEmpty(),
            providerName = row.string("provider_name") ?: id,
            isOauth = row.bool("is_oauth") ?: false,
            requiresApiKey = row.bool("requires_api_key") ?: false,
            authMethods = authMethods(row),
            connections = (row["connected_providers"] as? JsonArray).orEmpty()
                .filterIsInstance<JsonObject>()
                .filter { it.bool("is_connected") == true }
                .map(::connection),
        )
    }

    /** Explicit `auth_methods`, else one implicit method from the top-level `fields`. */
    private fun authMethods(row: JsonObject): List<ProviderAuthMethod> {
        val explicit = (row["auth_methods"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { method ->
            ProviderAuthMethod(
                id = method.string("id") ?: return@mapNotNull null,
                label = method.string("label").orEmpty(),
                description = method.string("description").orEmpty(),
                fields = fields(method["fields"]),
            )
        }
        if (explicit.isNotEmpty()) return explicit
        val topLevel = row["fields"] as? JsonArray ?: return emptyList()
        return listOf(ProviderAuthMethod(id = null, label = "API key", fields = fields(topLevel)))
    }

    private fun fields(element: JsonElement?): List<ProviderField> =
        (element as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { field ->
            ProviderField(
                key = field.string("key") ?: return@mapNotNull null,
                label = field.string("label") ?: field.string("key").orEmpty(),
                placeholder = field.string("placeholder"),
                secret = field.bool("secret") ?: false,
                required = field.bool("required") ?: false,
            )
        }

    private fun connection(state: JsonObject) = ProviderConnection(
        providerName = state.string("provider_name"),
        providerType = state.string("provider_type"),
        authType = state.string("auth_type"),
        baseUrl = state.string("base_url"),
    )

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}

/** `provider.connect` input: field values keyed by [ProviderField.key]. */
data class ProviderConnectRequest(
    val providerId: String,
    val authMethodId: String?,
    val fields: Map<String, String>,
) {
    override fun toString(): String =
        "ProviderConnectRequest(providerId=$providerId, authMethodId=$authMethodId, fieldKeys=${fields.keys.sorted()})"
}

/** The agent + conversation a model switch applies to. */
data class ConversationModelTarget(val agentId: String, val conversationId: String)
