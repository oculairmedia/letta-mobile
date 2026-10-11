package com.letta.mobile.data.repository.modelcontrol

import com.letta.mobile.data.secrets.AgentSecretsRedaction
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** PKCE, pasted-code parsing, login selection and credential redaction of the sign-ins (letta-mobile-zxk2n.3). */
class ProviderOAuthWireTest {
    @Test
    fun pkceMatchesTheRfc7636Example() {
        val octets = intArrayOf(
            116, 24, 223, 180, 151, 153, 224, 37, 79, 250, 96, 125, 216, 173, 187, 186,
            22, 212, 37, 77, 105, 214, 191, 240, 91, 88, 5, 88, 83, 132, 141, 121,
        ).map { it.toByte() }.toByteArray()

        val pkce = Pkce.create { octets }

        assertEquals("dBjftJeZ4CVP-mJ92K9qbKgSFJ2t3W4f-BvBTnc-5gw", pkce.verifier)
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkce.challenge)
        assertFalse(pkce.toString().contains(pkce.verifier))
    }

    @Test
    fun secureRandomBytesFillsTheRequestedSize() {
        assertEquals(32, secureRandomBytes(32).size)
        assertEquals(5, secureRandomBytes(5).size)
        assertFalse(secureRandomBytes(32).contentEquals(secureRandomBytes(32)))
    }

    @Test
    fun aPastedCodeIsReadFromEveryShapeTheBrowserShows() {
        assertEquals(PastedCode("abc", "st"), PastedCode.parse("http://localhost:53692/callback?code=abc&state=st", "st"))
        assertEquals(PastedCode("abc", "st"), PastedCode.parse("  code=abc&state=st ", "st"))
        assertEquals(PastedCode("abc", "st"), PastedCode.parse("abc#st", "st"))
        assertEquals(PastedCode("abc", null), PastedCode.parse("abc", "st"))
        assertEquals(PastedCode("abc", null), PastedCode.parse("https://example.test/cb?code=abc", null))
        val blank = assertFailsWith<ProviderSignInException> { PastedCode.parse("   ", null) }
        assertEquals(SignInFailure.BAD_CODE, blank.failure)
        val other = assertFailsWith<ProviderSignInException> { PastedCode.parse("abc#other", "st") }
        assertEquals(SignInFailure.BAD_CODE, other.failure)
    }

    @Test
    fun theLoginFollowsTheOauthProviderId() {
        val rows = ModelControlWire.providers(ProviderSignInFixtures.providerList).associateBy { it.id }

        assertEquals(ProviderOAuthKind.XAI, ProviderOAuthKind.of(rows.getValue("xai")))
        assertEquals(ProviderOAuthKind.CHATGPT, ProviderOAuthKind.of(rows.getValue("openai-codex-oauth")))
        assertEquals(ProviderOAuthKind.CLAUDE, ProviderOAuthKind.of(rows.getValue("anthropic-oauth")))
        assertEquals(ProviderOAuthKind.OPENROUTER, ProviderOAuthKind.of(rows.getValue("openrouter-oauth")))
        assertNull(ProviderOAuthKind.of(rows.getValue("github-copilot")))
        assertNull(ProviderOAuthKind.of(rows.getValue("openrouter")), "an API-key row signs in with a form")
        assertTrue(ProviderOAuthKind.XAI.isDeviceCode && ProviderOAuthKind.CHATGPT.isDeviceCode)
        assertFalse(ProviderOAuthKind.CLAUDE.isDeviceCode || ProviderOAuthKind.OPENROUTER.isDeviceCode)
    }

    @Test
    fun aConnectRequestCarriesTheTokensButNeverPrintsThem() {
        val bundle = OAuthWire.tokenBundle("test-access", "test-refresh", 42L)
        val request = ProviderConnectRequest("xai", authMethodId = null, fields = emptyMap(), oauthConfig = bundle)

        val params = ModelControlWire.connectParams(request)

        assertEquals(bundle, params["oauth_config"])
        assertFalse(request.toString().contains("test-access"))
        assertFalse(OAuthRequestBody.Form(mapOf("device_code" to "secret")).toString().contains("secret"))
        assertFalse(OAuthHttpResponse(200, """{"access_token":"secret"}""").toString().contains("secret"))
    }

    @Test
    fun connectFramesAreRedactedOnTheWireAndOverTheRelay() {
        val redacted = AppServerProtocol.REDACTED_PLACEHOLDER
        val direct = Json.parseToJsonElement(
            """{"type":"connect_provider","provider_id":"xai","fields":{"apiKey":"sk-test"},
               "oauth_config":{"type":"oauth","access":"test-access","refresh":"test-refresh","expires":1}}""",
        ).jsonObject
        val relayed = Json.parseToJsonElement(
            """{"type":"admin_rpc","method":"provider.connect","params":{"provider_id":"xai",
               "oauth_config":{"access_token":"test-access","id_token":"test-id"}}}""",
        ).jsonObject

        val a = AgentSecretsRedaction.redact(direct)
        val b = AgentSecretsRedaction.redact(relayed)

        assertEquals(redacted, a["fields"]!!.jsonObject["apiKey"]!!.jsonPrimitive.content)
        assertTrue((a["oauth_config"] as JsonObject).values.all { it.jsonPrimitive.content == redacted })
        assertEquals("xai", a["provider_id"]!!.jsonPrimitive.content)
        val params = b["params"]!!.jsonObject
        assertTrue((params["oauth_config"] as JsonObject).values.all { it.jsonPrimitive.content == redacted })
        assertFalse(b.toString().contains("test-access"))
    }
}
