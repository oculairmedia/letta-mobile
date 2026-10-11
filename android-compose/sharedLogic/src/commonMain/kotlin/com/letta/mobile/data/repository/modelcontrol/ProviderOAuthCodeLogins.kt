package com.letta.mobile.data.repository.modelcontrol

import com.letta.mobile.data.canvas.compose.Sha256
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.formUrlEncode
import io.ktor.http.parseQueryString
import kotlin.io.encoding.Base64
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Claude Pro/Max sign-in, as letta-code 0.34.8 runs it for
 * `oauth_provider_id: "anthropic"`: authorization code with PKCE. The
 * redirect goes to a loopback address that nothing on a phone listens on,
 * so the user pastes back the code or the address the browser ended on
 * (letta-code's own "paste the redirect URL" path).
 */
internal class ClaudeCodeLogin(
    private val provider: ConnectableProvider,
    private val http: ProviderOAuthHttp,
    private val clock: () -> Long,
    private val random: (Int) -> ByteArray,
) : ProviderOAuthLogin.AuthCode {
    override val kind = ProviderOAuthKind.CLAUDE

    override fun begin(): AuthCodeGrant {
        val pkce = Pkce.create(random)
        val state = Pkce.token(random(STATE_BYTES))
        val query = Parameters.build {
            append("code", "true")
            append("client_id", CLIENT_ID)
            append("response_type", "code")
            append("redirect_uri", REDIRECT_URI)
            append("scope", SCOPES)
            append("code_challenge", pkce.challenge)
            append("code_challenge_method", Pkce.METHOD)
            append("state", state)
        }.formUrlEncode()
        return AuthCodeGrant("$AUTHORIZE_URL?$query", verifier = pkce.verifier, state = state)
    }

    override suspend fun exchange(grant: AuthCodeGrant, pasted: String): ProviderConnectRequest {
        val code = PastedCode.parse(pasted, expectedState = grant.state)
        val params = buildJsonObject {
            put("grant_type", "authorization_code")
            put("client_id", CLIENT_ID)
            put("code", code.code)
            put("state", code.state ?: grant.state)
            put("redirect_uri", REDIRECT_URI)
            put("code_verifier", grant.verifier)
        }
        val response = http.post(TOKEN_URL, OAuthRequestBody.Json(params))
        if (!response.ok) throw OAuthWire.failure(kind, "token exchange", response)
        val tokens = OAuthWire.body(response)
        val lifetime = tokens.seconds("expires_in") ?: throw ProviderSignInException("${kind.label} sent an incomplete answer (no expires_in)")
        val bundle = SubscriptionTokens.from(tokens, kind, clock(), lifetime).toOAuthConfig()
        return ProviderConnectRequest(provider.id, authMethodId = null, fields = emptyMap(), oauthConfig = bundle)
    }

    private companion object {
        // Claude Code's public OAuth client, the one letta-code signs in with.
        const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
        const val AUTHORIZE_URL = "https://claude.ai/oauth/authorize"
        const val TOKEN_URL = "https://platform.claude.com/v1/oauth/token"
        const val REDIRECT_URI = "http://localhost:53692/callback"
        const val SCOPES = "org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload"
        const val STATE_BYTES = 16
    }
}

/**
 * OpenRouter sign-in: PKCE, and the code buys a user API key. The App
 * Server's subscription-token shape needs a refresh token OpenRouter never
 * issues, so the key is saved on the host's OpenRouter API-key row
 * ([keyRow]), which is what the key is.
 */
internal class OpenRouterCodeLogin(
    private val keyRow: ConnectableProvider?,
    private val http: ProviderOAuthHttp,
    private val random: (Int) -> ByteArray,
) : ProviderOAuthLogin.AuthCode {
    override val kind = ProviderOAuthKind.OPENROUTER

    override fun begin(): AuthCodeGrant {
        val pkce = Pkce.create(random)
        val query = Parameters.build {
            append("callback_url", CALLBACK_URL)
            append("code_challenge", pkce.challenge)
            append("code_challenge_method", Pkce.METHOD)
        }.formUrlEncode()
        return AuthCodeGrant("$AUTHORIZE_URL?$query", verifier = pkce.verifier, state = null)
    }

    override suspend fun exchange(grant: AuthCodeGrant, pasted: String): ProviderConnectRequest {
        val row = keyRow ?: throw ProviderSignInException("This host has no OpenRouter API key provider to save the key to")
        val code = PastedCode.parse(pasted, expectedState = null).code
        val params = buildJsonObject {
            put("code", code)
            put("code_verifier", grant.verifier)
            put("code_challenge_method", Pkce.METHOD)
        }
        val response = http.post(KEYS_URL, OAuthRequestBody.Json(params))
        if (!response.ok) throw OAuthWire.failure(kind, "key exchange", response)
        val key = OAuthWire.body(response).require("key", kind)
        val method = row.authMethods.firstOrNull { m -> m.fields.any { it.key == OAuthWire.API_KEY_FIELD } }
        return ProviderConnectRequest(row.id, authMethodId = method?.id, fields = mapOf(OAuthWire.API_KEY_FIELD to key))
    }

    private companion object {
        const val AUTHORIZE_URL = "https://openrouter.ai/auth"
        const val KEYS_URL = "https://openrouter.ai/api/v1/auth/keys"
        const val CALLBACK_URL = "http://localhost:3000/letta/openrouter/callback"
    }
}

/** RFC 7636 S256 verifier and challenge. */
internal class Pkce private constructor(val verifier: String, val challenge: String) {
    override fun toString(): String = "Pkce(<redacted>)"

    companion object {
        const val METHOD = "S256"
        private const val VERIFIER_BYTES = 32
        private val urlSafe = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

        fun create(random: (Int) -> ByteArray): Pkce {
            val verifier = token(random(VERIFIER_BYTES))
            return Pkce(verifier, urlSafe.encode(Sha256.digest(verifier.encodeToByteArray())))
        }

        /** Base64url without padding. */
        fun token(bytes: ByteArray): String = urlSafe.encode(bytes)
    }
}

/**
 * What the user pasted after approving: the whole address the browser ended
 * on, a `code=…&state=…` query, `code#state`, or the bare code.
 */
internal data class PastedCode(val code: String, val state: String?) {
    override fun toString(): String = "PastedCode(<redacted>)"

    companion object {
        fun parse(pasted: String, expectedState: String?): PastedCode {
            val parsed = split(pasted.trim())
            if (parsed.code.isBlank()) throw ProviderSignInException("Paste the code from the sign-in page", SignInFailure.BAD_CODE)
            if (parsed.contradicts(expectedState)) {
                throw ProviderSignInException("That code is from a different sign-in; start again", SignInFailure.BAD_CODE)
            }
            return parsed
        }

        /** A pasted state that differs from the one this sign-in sent; no state on either side is no contradiction. */
        private fun PastedCode.contradicts(expectedState: String?): Boolean {
            val pastedState = state ?: return false
            return expectedState?.let { it != pastedState } ?: false
        }

        private fun split(value: String): PastedCode = when {
            value.startsWith("http://") || value.startsWith("https://") -> fromQuery(Url(value).encodedQuery)
            value.contains("code=") -> fromQuery(value.substringAfter('?'))
            value.contains('#') -> PastedCode(value.substringBefore('#'), value.substringAfter('#').ifBlank { null })
            else -> PastedCode(value, null)
        }

        private fun fromQuery(query: String): PastedCode {
            val params = parseQueryString(query)
            return PastedCode(params["code"].orEmpty(), params["state"])
        }
    }
}

/**
 * Random bytes for PKCE and state from [Uuid.random], which the standard
 * library documents as drawn from a cryptographically secure source on every
 * target. Each UUID gives 122 random bits; enough are concatenated to cover
 * [size] bytes.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun secureRandomBytes(size: Int): ByteArray {
    val out = ByteArray(size)
    var filled = 0
    while (filled < size) {
        val chunk = Uuid.random().toByteArray()
        val take = minOf(chunk.size, size - filled)
        chunk.copyInto(out, destinationOffset = filled, endIndex = take)
        filled += take
    }
    return out
}
