package com.letta.mobile.data.repository.modelcontrol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Shared parsing for the provider token endpoints. Error text never includes a token or code. */
internal object OAuthWire {
    const val API_KEY_FIELD = "apiKey"
    const val DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"

    /** Access tokens are refreshed this long before the provider's expiry (letta-code does the same). */
    const val REFRESH_SKEW_MS = 5 * 60 * 1000L
    const val MS_PER_SECOND = 1000L

    private val json = Json { ignoreUnknownKeys = true }

    fun body(response: OAuthHttpResponse): JsonObject =
        runCatching { json.parseToJsonElement(response.body) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

    /** `error` as a string, or `error.code` when it is an object. */
    fun errorCode(body: JsonObject): String? = body.text("error") ?: ((body["error"] as? JsonObject)?.text("code"))

    /** The provider's own error words for a message: `error` and `error_description`, never the raw body. */
    fun failure(kind: ProviderOAuthKind, action: String, response: OAuthHttpResponse): ProviderSignInException {
        val body = body(response)
        val detail = listOfNotNull(errorCode(body), body.text("error_description")).joinToString(": ")
        val suffix = if (detail.isBlank()) "" else ": $detail"
        return ProviderSignInException("${kind.label} $action failed (HTTP ${response.status})$suffix")
    }

    /** Only an https verification page is opened in the browser. */
    fun httpsUrl(raw: String?, kind: ProviderOAuthKind): String {
        val url = raw?.trim().orEmpty()
        if (!url.startsWith("https://")) throw ProviderSignInException("${kind.label} sent an untrusted sign-in address")
        return url
    }
}

/**
 * letta-code's subscription-token `oauth_config`: `{type:"oauth", access,
 * refresh, expires}` with [expiresAtMillis] in epoch milliseconds.
 * [toString] prints no token.
 */
internal class SubscriptionTokens(private val access: String, private val refresh: String, private val expiresAtMillis: Long) {
    fun toOAuthConfig(): JsonObject = JsonObject(
        mapOf(
            "type" to JsonPrimitive(TYPE),
            "access" to JsonPrimitive(access),
            "refresh" to JsonPrimitive(refresh),
            "expires" to JsonPrimitive(expiresAtMillis),
        ),
    )

    override fun toString(): String = "SubscriptionTokens(expiresAtMillis=$expiresAtMillis)"

    companion object {
        private const val TYPE = "oauth"

        /** A token endpoint's answer: the expiry is pulled in by [OAuthWire.REFRESH_SKEW_MS] so the host refreshes early. */
        fun from(tokens: JsonObject, kind: ProviderOAuthKind, nowMillis: Long, lifetimeSeconds: Long): SubscriptionTokens =
            SubscriptionTokens(
                access = tokens.require("access_token", kind),
                refresh = tokens.require("refresh_token", kind),
                expiresAtMillis = nowMillis + lifetimeSeconds * OAuthWire.MS_PER_SECOND - OAuthWire.REFRESH_SKEW_MS,
            )
    }
}
