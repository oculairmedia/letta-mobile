package com.letta.mobile.data.repository.modelcontrol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * [ProviderCatalogFixtures] plus the rows the settings pages and the picker
 * tiers need, shaped like letta-code 0.32.x `list_connect_providers`: a
 * connected ChatGPT subscription (OpenAI Codex), an unconnected
 * OpenAI-compatible endpoint, and catalog rows that carry their own
 * `updateArgs.reasoning_effort`.
 */
internal object ProviderControlFixtures {
    private val extraProviders = Json.parseToJsonElement(
        """
        [
          {"id":"openai-codex-oauth","display_name":"OpenAI Codex","description":"Connect a subscription account",
           "provider_type":"chatgpt_oauth","provider_name":"chatgpt-plus-pro","provider_names":["chatgpt-plus-pro","openai-codex"],
           "is_oauth":true,"oauth_provider_id":"openai-codex","requires_api_key":true,
           "connected_providers":[{"is_connected":true,"provider_name":"chatgpt-plus-pro","provider_type":"chatgpt_oauth","auth_type":"oauth"}]},
          {"id":"openai-compatible","display_name":"OpenAI-compatible API","description":"Connect an OpenAI-compatible Chat Completions endpoint",
           "provider_type":"openai","provider_name":"openai-compatible","requires_api_key":false,
           "fields":[{"key":"apiKey","label":"API Key","secret":true,"required":false},{"key":"baseUrl","label":"Base URL","required":true}],
           "connected_providers":[]}
        ]
        """.trimIndent(),
    ).jsonArray

    private val extraModels = Json.parseToJsonElement(
        """
        [
          {"id":"chatgpt-plus-pro/gpt-5.5","handle":"chatgpt-plus-pro/gpt-5.5","label":"GPT-5.5","provider_type":"chatgpt_oauth",
           "selection_handle":"chatgpt-plus-pro/gpt-5.5","exposed":true,"updateArgs":{"reasoning_effort":"medium"},
           "reasoning_efforts":["low","medium","high"]},
          {"id":"chatgpt-plus-pro/gpt-5.5-mini","handle":"chatgpt-plus-pro/gpt-5.5-mini","label":"GPT-5.5 Mini","provider_type":"chatgpt_oauth",
           "selection_handle":"chatgpt-plus-pro/gpt-5.5-mini","exposed":false}
        ]
        """.trimIndent(),
    ).jsonArray

    val providerList: JsonElement = JsonArray(ProviderCatalogFixtures.providerList.jsonArray + extraProviders)
    val modelList: JsonElement = JsonArray(ProviderCatalogFixtures.modelList.jsonArray + extraModels)

    /** Providers after the given row ids became connected (a terminal sign-in or a connect). */
    fun providersConnecting(vararg ids: String): JsonElement = JsonArray(
        providerList.jsonArray.map { element ->
            val row = element.jsonObject
            val id = row["id"]?.jsonPrimitive?.content
            if (id !in ids) {
                row
            } else {
                val connection = buildJsonObject {
                    put("is_connected", true)
                    put("provider_name", id)
                }
                JsonObject(row + ("connected_providers" to JsonArray(listOf(connection))))
            }
        },
    )

    fun mutation(modelsMayHaveChanged: Boolean = true): JsonElement = Json.parseToJsonElement(
        """{"providers":$providerList,"models_may_have_changed":$modelsMayHaveChanged}""",
    )

    fun invoker(
        providers: () -> JsonElement = { providerList },
        models: () -> JsonElement = { modelList },
        onOther: (String) -> JsonElement? = { mutation() },
    ): RecordingInvoker = RecordingInvoker { method, _ ->
        when (method) {
            "provider.list" -> providers()
            "model.list" -> models()
            else -> onOther(method)
        }
    }
}
