package com.letta.mobile.data.repository.modelcontrol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Subscription sign-ins the app runs itself (letta-mobile-zxk2n.3, epic G54).
 *
 * letta-code's App Server (0.34.8) runs no login of its own: `connect_provider`
 * accepts a finished `oauth_config` (`{type:"oauth", access, refresh, expires}`
 * for subscription tokens, or the ChatGPT bundle `{access_token, id_token,
 * refresh_token?, account_id, expires_at}`) and stores it. The client does the
 * provider's device-code or authorization-code exchange and hands the result
 * over in one `provider.connect`; nothing is kept on the client afterwards.
 */
enum class ProviderOAuthKind(val label: String) {
    /** xAI (SuperGrok / X Premium): RFC 8628 device code. */
    XAI("xAI"),

    /** ChatGPT Plus/Pro (Codex): OpenAI's device-auth endpoints, then a code exchange. */
    CHATGPT("ChatGPT"),

    /** Claude Pro/Max: authorization code with PKCE; the user pastes the code back. */
    CLAUDE("Claude"),

    /** OpenRouter: PKCE code exchanged for a user API key; the user pastes the code back. */
    OPENROUTER("OpenRouter"),
    ;

    /** Device-code logins are approved on another page and polled; the others need a pasted code. */
    val isDeviceCode: Boolean get() = this == XAI || this == CHATGPT

    companion object {
        /** The login an OAuth row takes, from `oauth_provider_id` (else its id); null when the app has none. */
        fun of(provider: ConnectableProvider): ProviderOAuthKind? {
            if (!provider.isOauth) return null
            val id = (provider.oauthProviderId ?: provider.id.removeSuffix(OAUTH_ID_SUFFIX)).lowercase()
            return when {
                id == "xai" -> XAI
                id == "openai-codex" || provider.providerType == CHATGPT_PROVIDER_TYPE -> CHATGPT
                id == "anthropic" -> CLAUDE
                id == "openrouter" -> OPENROUTER
                else -> null
            }
        }

        private const val OAUTH_ID_SUFFIX = "-oauth"
        private const val CHATGPT_PROVIDER_TYPE = "chatgpt_oauth"
    }
}

/** A failed sign-in step, with what went wrong for the state machine. */
class ProviderSignInException(message: String, val failure: SignInFailure = SignInFailure.PROVIDER_ERROR) : Exception(message)

/** Why a sign-in ended without a connection. */
enum class SignInFailure {
    /** The user declined on the provider's page. */
    DENIED,

    /** The provider says the code expired. */
    EXPIRED,

    /** The code's lifetime ran out while polling. */
    TIMED_OUT,

    /** The provider's endpoint refused or answered something unusable. */
    PROVIDER_ERROR,

    /** The pasted code was empty or belongs to another sign-in. */
    BAD_CODE,

    /** The App Server refused the tokens. */
    HOST_REJECTED,
}

/**
 * A code the user approves on [verificationUrl]. The device secret the poll
 * needs stays inside; [toString] leaves it out.
 */
class DeviceCodeGrant internal constructor(
    val userCode: String,
    val verificationUrl: String,
    val intervalSeconds: Double?,
    val expiresInSeconds: Long,
    internal val deviceSecret: String,
) {
    override fun toString(): String = "DeviceCodeGrant(verificationUrl=$verificationUrl, expiresInSeconds=$expiresInSeconds)"
}

/** One poll of a device-code login. */
sealed interface DeviceCodePoll {
    data object Pending : DeviceCodePoll

    /** Poll less often: the provider's new interval, or null for "add five seconds". */
    data class SlowDown(val intervalSeconds: Double?) : DeviceCodePoll

    data object Denied : DeviceCodePoll

    data object Expired : DeviceCodePoll

    data class Failed(val message: String) : DeviceCodePoll

    /** Approved: what `provider.connect` should send. */
    class Approved(val request: ProviderConnectRequest) : DeviceCodePoll {
        override fun toString(): String = "Approved(providerId=${request.providerId})"
    }
}

/** An authorization-code login: open [authorizeUrl], approve, paste back the code or the page address. */
class AuthCodeGrant internal constructor(
    val authorizeUrl: String,
    internal val verifier: String,
    internal val state: String?,
) {
    override fun toString(): String = "AuthCodeGrant(authorizeUrl=<${authorizeUrl.substringBefore('?')}>)"
}

/** A login the app can run for one provider row. */
sealed interface ProviderOAuthLogin {
    val kind: ProviderOAuthKind

    interface DeviceCode : ProviderOAuthLogin {
        suspend fun start(): DeviceCodeGrant

        suspend fun poll(grant: DeviceCodeGrant): DeviceCodePoll
    }

    interface AuthCode : ProviderOAuthLogin {
        fun begin(): AuthCodeGrant

        suspend fun exchange(grant: AuthCodeGrant, pasted: String): ProviderConnectRequest
    }

    companion object {
        /**
         * The login for [provider], or null when the app has none for it.
         * [providers] is the host's whole list: OpenRouter's key lands on the
         * host's OpenRouter API-key row.
         */
        fun create(
            provider: ConnectableProvider,
            providers: List<ConnectableProvider>,
            http: ProviderOAuthHttp,
            clock: () -> Long,
            random: (Int) -> ByteArray = ::secureRandomBytes,
        ): ProviderOAuthLogin? = when (ProviderOAuthKind.of(provider)) {
            ProviderOAuthKind.XAI -> XaiDeviceLogin(provider, http, clock)
            ProviderOAuthKind.CHATGPT -> ChatGptDeviceLogin(provider, http, clock)
            ProviderOAuthKind.CLAUDE -> ClaudeCodeLogin(provider, http, clock, random)
            ProviderOAuthKind.OPENROUTER -> OpenRouterCodeLogin(openRouterKeyRow(providers), http, random)
            null -> null
        }

        /** The host's OpenRouter API-key row (not the OAuth one). */
        private fun openRouterKeyRow(providers: List<ConnectableProvider>): ConnectableProvider? =
            providers.firstOrNull { !it.isOauth && it.providerType == "openrouter" && it.takesApiKey() }

        private fun ConnectableProvider.takesApiKey(): Boolean =
            authMethods.any { method -> method.fields.any { it.key == OAuthWire.API_KEY_FIELD } }
    }
}

/** Shared parsing for the provider token endpoints. Error text never includes a token or code. */
internal object OAuthWire {
    const val API_KEY_FIELD = "apiKey"
    const val DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
    const val SUBSCRIPTION_TOKENS_TYPE = "oauth"

    /** Access tokens are refreshed this long before the provider's expiry (letta-code does the same). */
    const val REFRESH_SKEW_MS = 5 * 60 * 1000L
    const val MS_PER_SECOND = 1000L

    private val json = Json { ignoreUnknownKeys = true }

    fun body(response: OAuthHttpResponse): JsonObject =
        runCatching { json.parseToJsonElement(response.body) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

    fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

    /** A number, or a numeric string (OpenAI sends `interval` as either). */
    fun JsonObject.number(key: String): Double? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.doubleOrNull ?: primitive.contentOrNull?.trim()?.toDoubleOrNull()
    }

    fun JsonObject.seconds(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull ?: number(key)?.toLong()

    fun JsonObject.require(key: String, provider: String): String =
        text(key) ?: throw ProviderSignInException("$provider sent an incomplete answer (no $key)")

    /** `error` as a string, or `error.code` when it is an object. */
    fun errorCode(body: JsonObject): String? = body.text("error") ?: ((body["error"] as? JsonObject)?.text("code"))

    /** The provider's own error words for a message: `error` and `error_description`, never the raw body. */
    fun failure(provider: String, action: String, response: OAuthHttpResponse): ProviderSignInException {
        val body = body(response)
        val detail = listOfNotNull(errorCode(body), body.text("error_description")).joinToString(": ")
        val suffix = if (detail.isBlank()) "" else ": $detail"
        return ProviderSignInException("$provider $action failed (HTTP ${response.status})$suffix")
    }

    /** Only an https verification page is opened in the browser. */
    fun httpsUrl(raw: String?, provider: String): String {
        val url = raw?.trim().orEmpty()
        if (!url.startsWith("https://")) throw ProviderSignInException("$provider sent an untrusted sign-in address")
        return url
    }

    fun tokenBundle(access: String, refresh: String, expiresAt: Long): JsonObject = JsonObject(
        mapOf(
            "type" to JsonPrimitive(SUBSCRIPTION_TOKENS_TYPE),
            "access" to JsonPrimitive(access),
            "refresh" to JsonPrimitive(refresh),
            "expires" to JsonPrimitive(expiresAt),
        ),
    )
}
