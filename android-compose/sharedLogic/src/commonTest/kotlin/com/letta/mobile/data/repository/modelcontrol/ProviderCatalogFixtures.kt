package com.letta.mobile.data.repository.modelcontrol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * A host shaped like the live Meridian one (2026-10-01): three routes serving
 * models, the same model reachable through two of them, one hidden handle, and
 * a provider with nothing connected yet.
 */
internal object ProviderCatalogFixtures {
    val providerList: JsonElement = Json.parseToJsonElement(
        """
        [
          {"id":"lmstudio","display_name":"LM Studio (local)","description":"d","provider_type":"lmstudio_openai",
           "provider_name":"lmstudio","requires_api_key":false,
           "fields":[{"key":"baseUrl","label":"Base URL"},{"key":"apiKey","label":"API Key","secret":true}],
           "connected_providers":[{"is_connected":true,"provider_name":"lc-lmstudio","provider_type":"lmstudio","auth_type":"api","base_url":"http://127.0.0.1:8082/v1"}]},
          {"id":"openai","display_name":"OpenAI","description":"d","provider_type":"openai","provider_name":"openai","requires_api_key":true,
           "fields":[{"key":"apiKey","label":"API Key","secret":true,"required":true}],
           "connected_providers":[{"is_connected":true,"provider_name":"openai","provider_type":"openai","auth_type":"api"}]},
          {"id":"anthropic-oauth","display_name":"Anthropic (Claude Pro/Max)","description":"d","provider_type":"anthropic",
           "provider_name":"anthropic","is_oauth":true,"requires_api_key":true,"connected_providers":[]},
          {"id":"anthropic","display_name":"Anthropic","description":"d","provider_type":"anthropic","provider_name":"anthropic","requires_api_key":true,
           "fields":[{"key":"apiKey","label":"API Key","secret":true,"required":true}],"connected_providers":[]},
          {"id":"amazon-bedrock","display_name":"Amazon Bedrock","description":"d","provider_type":"amazon-bedrock","provider_name":"amazon-bedrock",
           "requires_api_key":true,"fields":[{"key":"apiKey","label":"Secret","secret":true,"required":true}],"connected_providers":[]}
        ]
        """.trimIndent(),
    )

    val modelList: JsonElement = Json.parseToJsonElement(
        """
        [
          {"id":"anthropic/claude-fable-5-1","handle":"anthropic/claude-fable-5-1","label":"Claude Fable 5.1","provider_type":"anthropic",
           "selection_handle":"anthropic/claude-fable-5-1","exposed":true},
          {"id":"lmstudio/claude-fable-5-1","handle":"lmstudio/claude-fable-5-1","label":"claude-fable-5-1","provider_type":"lmstudio",
           "selection_handle":"lmstudio/claude-fable-5-1","exposed":true},
          {"id":"lmstudio/minimax-m3","handle":"lmstudio/minimax-m3","label":"MiniMax M3","provider_type":"lmstudio",
           "selection_handle":"lmstudio/minimax-m3","exposed":false},
          {"id":"lmstudio/gpt-5.6-sol","handle":"lmstudio/gpt-5.6-sol","label":"gpt-5.6-sol","provider_type":"lmstudio",
           "selection_handle":"lmstudio/gpt-5.6-sol","exposed":true,"reasoning_efforts":["low","high"]},
          {"id":"openai/gpt-5.6-sol","handle":"openai/gpt-5.6-sol","label":"GPT-5.6 Sol","provider_type":"openai",
           "selection_handle":"openai/gpt-5.6-sol","exposed":true}
        ]
        """.trimIndent(),
    )

    fun mutation(modelsMayHaveChanged: Boolean): JsonElement = Json.parseToJsonElement(
        """{"providers":$providerList,"models_may_have_changed":$modelsMayHaveChanged}""",
    )

    /** Answers the three listing RPCs from the fixtures; everything else gets [JsonNull]-like success. */
    fun invoker(onOther: (String) -> JsonElement? = { mutation(true) }): RecordingInvoker = RecordingInvoker { method, _ ->
        when (method) {
            "provider.list" -> providerList
            "model.list" -> modelList
            else -> onOther(method)
        }
    }
}
