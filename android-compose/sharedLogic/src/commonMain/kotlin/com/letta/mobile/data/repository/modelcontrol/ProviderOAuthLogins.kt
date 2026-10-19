package com.letta.mobile.data.repository.modelcontrol

/**
 * Subscription sign-ins the app runs itself (epic G54).
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
