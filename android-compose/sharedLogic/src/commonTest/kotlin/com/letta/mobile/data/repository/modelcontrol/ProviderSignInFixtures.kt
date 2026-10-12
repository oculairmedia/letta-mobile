package com.letta.mobile.data.repository.modelcontrol

import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A fake provider authorization server for the sign-in tests
 * (letta-mobile-zxk2n.3): each URL answers from a queue (the last answer
 * repeats), and every request is recorded with the test clock. All tokens
 * and codes here are made-up test values.
 */
internal class FakeTokenEndpoint(private val clock: () -> Long) : ProviderOAuthHttp {
    data class Request(val url: String, val body: OAuthRequestBody, val atMillis: Long)

    val requests = mutableListOf<Request>()
    private val routes = mutableMapOf<String, ArrayDeque<OAuthHttpResponse>>()

    fun on(url: String, vararg responses: OAuthHttpResponse) {
        routes.getOrPut(url) { ArrayDeque() }.addAll(responses)
    }

    fun to(url: String): List<Request> = requests.filter { it.url == url }

    override suspend fun post(url: String, body: OAuthRequestBody): OAuthHttpResponse {
        requests += Request(url, body, clock())
        val queue = routes[url] ?: error("no route for $url")
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }
}

internal fun reply(status: Int, json: String) = OAuthHttpResponse(status, json)

internal fun OAuthRequestBody.form(): Map<String, String> = (this as OAuthRequestBody.Form).fields

internal fun OAuthRequestBody.json(): JsonObject = (this as OAuthRequestBody.Json).body

internal object ProviderSignInFixtures {
    const val XAI_DEVICE = "https://auth.x.ai/oauth2/device/code"
    const val XAI_TOKEN = "https://auth.x.ai/oauth2/token"
    const val OPENAI_USER_CODE = "https://auth.openai.com/api/accounts/deviceauth/usercode"
    const val OPENAI_DEVICE_TOKEN = "https://auth.openai.com/api/accounts/deviceauth/token"
    const val OPENAI_TOKEN = "https://auth.openai.com/oauth/token"
    const val CLAUDE_TOKEN = "https://platform.claude.com/v1/oauth/token"
    const val OPENROUTER_KEYS = "https://openrouter.ai/api/v1/auth/keys"

    /** letta-code 0.34.8 `list_connect_providers` rows for the four logins plus OpenRouter's key row. */
    val providerList: JsonElement = Json.parseToJsonElement(
        """
        [
          {"id":"xai","display_name":"xAI (Grok/X subscription)","provider_type":"xai","provider_name":"xai",
           "is_oauth":true,"oauth_provider_id":"xai","requires_api_key":true,"connected_providers":[]},
          {"id":"openai-codex-oauth","display_name":"OpenAI (ChatGPT Plus/Pro)","provider_type":"chatgpt_oauth",
           "provider_name":"chatgpt-plus-pro","is_oauth":true,"oauth_provider_id":"openai-codex","requires_api_key":true,
           "connected_providers":[]},
          {"id":"anthropic-oauth","display_name":"Anthropic (Claude Pro/Max)","provider_type":"anthropic",
           "provider_name":"anthropic","is_oauth":true,"oauth_provider_id":"anthropic","requires_api_key":true,"connected_providers":[]},
          {"id":"openrouter-oauth","display_name":"OpenRouter OAuth","provider_type":"openrouter",
           "provider_name":"openrouter-oauth","is_oauth":true,"oauth_provider_id":"openrouter","requires_api_key":true,
           "connected_providers":[]},
          {"id":"openrouter","display_name":"OpenRouter","provider_type":"openrouter","provider_name":"openrouter",
           "requires_api_key":true,"fields":[{"key":"apiKey","label":"API Key","secret":true,"required":true}],
           "connected_providers":[]},
          {"id":"github-copilot","display_name":"GitHub Copilot","provider_type":"github-copilot",
           "provider_name":"github-copilot","is_oauth":true,"oauth_provider_id":"github-copilot","connected_providers":[]}
        ]
        """.trimIndent(),
    )

    fun mutation(): JsonElement = Json.parseToJsonElement("""{"providers":$providerList,"models_may_have_changed":true}""")

    /** An unsigned JWT whose payload names [accountId] the way OpenAI's access tokens do. */
    fun chatGptAccessToken(accountId: String): String {
        val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        val payload = """{"https://api.openai.com/auth":{"chatgpt_account_id":"$accountId"}}"""
        return "${b64.encode("""{"alg":"none"}""".encodeToByteArray())}.${b64.encode(payload.encodeToByteArray())}.sig"
    }
}
