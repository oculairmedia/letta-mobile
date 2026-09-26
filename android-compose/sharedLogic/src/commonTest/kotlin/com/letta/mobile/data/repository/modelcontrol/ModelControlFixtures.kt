package com.letta.mobile.data.repository.modelcontrol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Wrapper admin RPC results shaped like PR A's handlers produce them. */
internal object ModelControlFixtures {
    val providerList: JsonElement = Json.parseToJsonElement(
        """
        [
          {"id":"amazon-bedrock","display_name":"Amazon Bedrock","description":"d","provider_type":"amazon-bedrock",
           "provider_name":"amazon-bedrock","provider_names":["amazon-bedrock"],"requires_api_key":true,
           "auth_methods":[
             {"id":"iam","label":"AWS Access Keys","description":"keys","fields":[
               {"key":"accessKey","label":"AWS Access Key ID","required":true},
               {"key":"apiKey","label":"AWS Secret Access Key","secret":true,"required":true},
               {"key":"region","label":"AWS Region","placeholder":"us-east-1","required":true}]},
             {"id":"profile","label":"AWS Profile","description":"profile","fields":[
               {"key":"profile","label":"Profile Name","required":true},
               {"key":"region","label":"AWS Region","required":true}]}],
           "connected":{"is_connected":false},"connected_providers":[],"name":"amazon-bedrock","is_connected":false},
          {"id":"lmstudio","display_name":"LM Studio (local)","description":"d","provider_type":"lmstudio_openai",
           "provider_name":"lmstudio","provider_names":["lmstudio","lc-lmstudio"],"requires_api_key":false,
           "fields":[{"key":"baseUrl","label":"Base URL","required":false},{"key":"apiKey","label":"API Key","secret":true,"required":false}],
           "connected":{"is_connected":true,"provider_name":"lc-lmstudio","base_url":"http://127.0.0.1:1234/v1"},
           "connected_providers":[{"is_connected":true,"provider_name":"lc-lmstudio","provider_type":"lmstudio","auth_type":"api","base_url":"http://127.0.0.1:1234/v1"}]},
          {"id":"anthropic-oauth","display_name":"Anthropic (Claude Pro/Max)","description":"d","provider_type":"anthropic",
           "provider_name":"anthropic","provider_names":["anthropic"],"is_oauth":true,"requires_api_key":true,
           "connected":{"is_connected":false},"connected_providers":[]},
          {"description":"row without an id is skipped"}
        ]
        """.trimIndent(),
    )

    val modelList: JsonElement = Json.parseToJsonElement(
        """
        [
          {"id":"gpt-sol-none","name":"GPT Sol","handle":"openai/gpt-sol","provider_type":"openai",
           "selection_handle":"openai/gpt-sol","updateArgs":{"handle":"openai/gpt-sol"},
           "exposed":true,"reasoning_efforts":["none","high"]},
          {"id":"minimax","name":"MiniMax","handle":"lmstudio/minimax-m3","provider_type":"lmstudio",
           "selection_handle":"lmstudio/minimax-m3","updateArgs":{"handle":"lmstudio/minimax-m3"},"exposed":false}
        ]
        """.trimIndent(),
    )

    fun mutation(modelsMayHaveChanged: Boolean): JsonElement = Json.parseToJsonElement(
        """{"providers":$providerList,"models_may_have_changed":$modelsMayHaveChanged}""",
    )
}

/** Records each call; answers from [responder] or throws its message. */
internal class RecordingInvoker(
    private val responder: (String, JsonObject) -> JsonElement?,
) : AdminRpcInvoker {
    val calls = mutableListOf<Pair<String, JsonObject>>()

    override suspend fun invoke(method: String, params: JsonObject): JsonElement? {
        calls += method to params
        return responder(method, params)
    }
}
