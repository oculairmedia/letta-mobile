package com.letta.mobile.data.repository.modelcontrol

import com.letta.mobile.data.repository.modelcontrol.OAuthWire.number
import com.letta.mobile.data.repository.modelcontrol.OAuthWire.require
import com.letta.mobile.data.repository.modelcontrol.OAuthWire.seconds
import com.letta.mobile.data.repository.modelcontrol.OAuthWire.text
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * xAI device code (RFC 8628), as letta-code 0.34.8 runs it for
 * `oauth_provider_id: "xai"`: ask `auth.x.ai` for a code, let the user
 * approve it, poll the token endpoint, then hand the access/refresh pair to
 * the host as subscription tokens.
 */
internal class XaiDeviceLogin(
    private val provider: ConnectableProvider,
    private val http: ProviderOAuthHttp,
    private val clock: () -> Long,
) : ProviderOAuthLogin.DeviceCode {
    override val kind = ProviderOAuthKind.XAI

    override suspend fun start(): DeviceCodeGrant {
        val response = http.post(DEVICE_CODE_URL, OAuthRequestBody.Form(mapOf("client_id" to CLIENT_ID, "scope" to SCOPE)))
        if (!response.ok) throw OAuthWire.failure(NAME, "device authorization", response)
        val body = OAuthWire.body(response)
        val expiresIn = body.seconds("expires_in")?.takeIf { it > 0 }
            ?: throw ProviderSignInException("$NAME sent an incomplete answer (no expires_in)")
        return DeviceCodeGrant(
            userCode = body.require("user_code", NAME),
            verificationUrl = OAuthWire.httpsUrl(body.text("verification_uri_complete") ?: body.text("verification_uri"), NAME),
            intervalSeconds = body.number("interval")?.takeIf { it > 0 },
            expiresInSeconds = expiresIn,
            deviceSecret = body.require("device_code", NAME),
        )
    }

    override suspend fun poll(grant: DeviceCodeGrant): DeviceCodePoll {
        val form = mapOf("grant_type" to OAuthWire.DEVICE_CODE_GRANT, "client_id" to CLIENT_ID, "device_code" to grant.deviceSecret)
        val response = http.post(TOKEN_URL, OAuthRequestBody.Form(form))
        if (response.ok) return DeviceCodePoll.Approved(request(OAuthWire.body(response)))
        val body = OAuthWire.body(response)
        return when (OAuthWire.errorCode(body)) {
            "authorization_pending" -> DeviceCodePoll.Pending
            "slow_down" -> DeviceCodePoll.SlowDown(body.number("interval"))
            "access_denied", "authorization_denied" -> DeviceCodePoll.Denied
            "expired_token" -> DeviceCodePoll.Expired
            else -> DeviceCodePoll.Failed(OAuthWire.failure(NAME, "sign-in", response).message.orEmpty())
        }
    }

    private fun request(tokens: JsonObject): ProviderConnectRequest {
        val lifetime = tokens.seconds("expires_in")?.takeIf { it > 0 } ?: DEFAULT_LIFETIME_SECONDS
        val expiresAt = clock() + lifetime * OAuthWire.MS_PER_SECOND - OAuthWire.REFRESH_SKEW_MS
        val bundle = OAuthWire.tokenBundle(tokens.require("access_token", NAME), tokens.require("refresh_token", NAME), expiresAt)
        return ProviderConnectRequest(provider.id, authMethodId = null, fields = emptyMap(), oauthConfig = bundle)
    }

    private companion object {
        const val NAME = "xAI"
        const val CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
        const val SCOPE = "openid profile email offline_access grok-cli:access api:access"
        const val DEVICE_CODE_URL = "https://auth.x.ai/oauth2/device/code"
        const val TOKEN_URL = "https://auth.x.ai/oauth2/token"
        const val DEFAULT_LIFETIME_SECONDS = 3600L
    }
}

/**
 * ChatGPT Plus/Pro (Codex) device code, as letta-code 0.34.8 runs it for
 * `oauth_provider_id: "openai-codex"`: OpenAI's device-auth endpoints yield
 * an authorization code and verifier once the user approves, which are
 * exchanged for tokens. The host stores ChatGPT providers from the
 * `chatgpt_oauth` bundle, with the account id read from the access token.
 */
internal class ChatGptDeviceLogin(
    private val provider: ConnectableProvider,
    private val http: ProviderOAuthHttp,
    private val clock: () -> Long,
) : ProviderOAuthLogin.DeviceCode {
    override val kind = ProviderOAuthKind.CHATGPT

    override suspend fun start(): DeviceCodeGrant {
        val response = http.post(USER_CODE_URL, OAuthRequestBody.Json(buildJsonObject { put("client_id", CLIENT_ID) }))
        if (response.status == HTTP_NOT_FOUND) throw ProviderSignInException("ChatGPT device-code sign-in isn't available right now")
        if (!response.ok) throw OAuthWire.failure(NAME, "device authorization", response)
        val body = OAuthWire.body(response)
        return DeviceCodeGrant(
            userCode = body.require("user_code", NAME),
            verificationUrl = VERIFICATION_URL,
            intervalSeconds = body.number("interval")?.takeIf { it >= 0 },
            expiresInSeconds = CODE_LIFETIME_SECONDS,
            deviceSecret = body.require("device_auth_id", NAME),
        )
    }

    override suspend fun poll(grant: DeviceCodeGrant): DeviceCodePoll {
        val params = buildJsonObject {
            put("device_auth_id", grant.deviceSecret)
            put("user_code", grant.userCode)
        }
        val response = http.post(DEVICE_TOKEN_URL, OAuthRequestBody.Json(params))
        if (response.ok) return approved(OAuthWire.body(response))
        // Until the user approves, OpenAI answers 403/404.
        if (response.status == HTTP_FORBIDDEN || response.status == HTTP_NOT_FOUND) return DeviceCodePoll.Pending
        return when (OAuthWire.errorCode(OAuthWire.body(response))) {
            "deviceauth_authorization_pending", "authorization_pending" -> DeviceCodePoll.Pending
            "slow_down" -> DeviceCodePoll.SlowDown(null)
            "access_denied", "authorization_denied" -> DeviceCodePoll.Denied
            "expired_token" -> DeviceCodePoll.Expired
            else -> DeviceCodePoll.Failed(OAuthWire.failure(NAME, "sign-in", response).message.orEmpty())
        }
    }

    private suspend fun approved(device: JsonObject): DeviceCodePoll {
        val form = mapOf(
            "grant_type" to "authorization_code",
            "client_id" to CLIENT_ID,
            "code" to device.require("authorization_code", NAME),
            "code_verifier" to device.require("code_verifier", NAME),
            "redirect_uri" to DEVICE_REDIRECT_URI,
        )
        val response = http.post(TOKEN_URL, OAuthRequestBody.Form(form))
        if (!response.ok) return DeviceCodePoll.Failed(OAuthWire.failure(NAME, "token exchange", response).message.orEmpty())
        return DeviceCodePoll.Approved(request(OAuthWire.body(response)))
    }

    private fun request(tokens: JsonObject): ProviderConnectRequest {
        val access = tokens.require("access_token", NAME)
        val lifetime = tokens.seconds("expires_in") ?: throw ProviderSignInException("$NAME sent an incomplete answer (no expires_in)")
        val accountId = ChatGptAccount.idFrom(access) ?: throw ProviderSignInException("$NAME sign-in didn't name a ChatGPT account")
        val bundle = buildJsonObject {
            put("access_token", access)
            put("id_token", tokens.require("id_token", NAME))
            tokens.text("refresh_token")?.let { put("refresh_token", it) }
            put("account_id", accountId)
            put("expires_at", JsonPrimitive(clock() + lifetime * OAuthWire.MS_PER_SECOND))
        }
        return ProviderConnectRequest(provider.id, authMethodId = null, fields = emptyMap(), oauthConfig = bundle)
    }

    private companion object {
        const val NAME = "ChatGPT"
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val AUTH_BASE = "https://auth.openai.com"
        const val USER_CODE_URL = "$AUTH_BASE/api/accounts/deviceauth/usercode"
        const val DEVICE_TOKEN_URL = "$AUTH_BASE/api/accounts/deviceauth/token"
        const val TOKEN_URL = "$AUTH_BASE/oauth/token"
        const val VERIFICATION_URL = "$AUTH_BASE/codex/device"
        const val DEVICE_REDIRECT_URI = "$AUTH_BASE/deviceauth/callback"
        const val CODE_LIFETIME_SECONDS = 900L
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
    }
}

/** The ChatGPT account an OpenAI access token is for: claim `https://api.openai.com/auth`.`chatgpt_account_id`. */
internal object ChatGptAccount {
    private const val AUTH_CLAIM = "https://api.openai.com/auth"
    private const val JWT_PARTS = 3
    private val urlSafe = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    fun idFrom(accessToken: String): String? {
        val parts = accessToken.split('.')
        if (parts.size != JWT_PARTS) return null
        val payload = runCatching { Json.parseToJsonElement(urlSafe.decode(parts[1]).decodeToString()) as? JsonObject }.getOrNull()
        return (payload?.get(AUTH_CLAIM) as? JsonObject)?.text("chatgpt_account_id")
    }
}
