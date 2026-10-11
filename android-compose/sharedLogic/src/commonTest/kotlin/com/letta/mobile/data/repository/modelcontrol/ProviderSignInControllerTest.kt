package com.letta.mobile.data.repository.modelcontrol

import com.letta.mobile.data.repository.modelcontrol.ProviderSignInFixtures.CLAUDE_TOKEN
import com.letta.mobile.data.repository.modelcontrol.ProviderSignInFixtures.OPENAI_DEVICE_TOKEN
import com.letta.mobile.data.repository.modelcontrol.ProviderSignInFixtures.OPENAI_TOKEN
import com.letta.mobile.data.repository.modelcontrol.ProviderSignInFixtures.OPENAI_USER_CODE
import com.letta.mobile.data.repository.modelcontrol.ProviderSignInFixtures.OPENROUTER_KEYS
import com.letta.mobile.data.repository.modelcontrol.ProviderSignInFixtures.XAI_DEVICE
import com.letta.mobile.data.repository.modelcontrol.ProviderSignInFixtures.XAI_TOKEN
import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The sign-in state machine against a fake token endpoint and a fake host (letta-mobile-zxk2n.3). */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderSignInControllerTest {
    private class Harness(scope: TestScope, connectFails: Boolean = false) {
        val clock: () -> Long = { scope.testScheduler.currentTime }
        val http = FakeTokenEndpoint(clock)
        val invoker = RecordingInvoker { method, _ ->
            when (method) {
                "provider.list" -> ProviderSignInFixtures.providerList
                "provider.connect" -> if (connectFails) throw ModelControlException("invalid credentials") else ProviderSignInFixtures.mutation()
                "model.list" -> ProviderControlFixtures.modelList
                else -> null
            }
        }
        val providers = ProviderConnectionRepository(invoker)
        val catalog = ModelCatalogRepository(invoker)
        val controller = ProviderSignInController(scope.backgroundScope, http, providers, catalog, clock, random = { n -> ByteArray(n) { it.toByte() } })

        suspend fun provider(id: String): ConnectableProvider = providers.refresh().single { it.id == id }

        fun connectParams(): JsonObject = invoker.calls.single { it.first == "provider.connect" }.second

        fun connects(): Int = invoker.calls.count { it.first == "provider.connect" }
    }

    private fun xaiDevice(expiresIn: Int = 600, interval: Int = 5): OAuthHttpResponse = reply(
        200,
        """{"device_code":"dev-secret-1","user_code":"ABCD-1234","verification_uri":"https://accounts.x.ai/device",
           "verification_uri_complete":"https://accounts.x.ai/device?code=ABCD-1234","expires_in":$expiresIn,"interval":$interval}""",
    )

    private fun error(code: String, status: Int = 400) = reply(status, """{"error":"$code"}""")

    // --- xAI device code ---

    @Test
    fun xaiShowsTheCodePollsAndHandsTheTokensToTheHost() = runTest {
        val h = Harness(this)
        h.http.on(XAI_DEVICE, xaiDevice())
        h.http.on(XAI_TOKEN, error("authorization_pending"), reply(200, """{"access_token":"test-access","refresh_token":"test-refresh","expires_in":7200}"""))

        h.controller.start(h.provider("xai"))
        assertIs<ProviderSignInState.Starting>(h.controller.state.value)
        runCurrent()

        val waiting = assertIs<ProviderSignInState.AwaitingApproval>(h.controller.state.value)
        assertEquals("ABCD-1234", waiting.userCode)
        assertEquals("https://accounts.x.ai/device?code=ABCD-1234", waiting.verificationUrl)
        assertEquals(600_000L, waiting.expiresAtMillis)
        assertFalse(waiting.toString().contains("dev-secret-1"), "the device secret never reaches the state")
        assertEquals(mapOf("client_id" to XAI_CLIENT, "scope" to XAI_SCOPE), h.http.to(XAI_DEVICE).single().body.form())

        advanceUntilIdle()

        val polls = h.http.to(XAI_TOKEN)
        assertEquals(listOf(5_000L, 10_000L), polls.map { it.atMillis }, "the first poll waits one interval")
        assertEquals("dev-secret-1", polls.first().body.form()["device_code"])
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", polls.first().body.form()["grant_type"])
        val params = h.connectParams()
        assertEquals("xai", (params["provider_id"] as JsonPrimitive).content)
        val config = params["oauth_config"] as JsonObject
        assertEquals("oauth", config["type"]!!.jsonPrimitive.content)
        assertEquals("test-access", config["access"]!!.jsonPrimitive.content)
        assertEquals("test-refresh", config["refresh"]!!.jsonPrimitive.content)
        assertEquals(10_000L + 7_200_000L - 300_000L, config["expires"]!!.jsonPrimitive.longOrNull, "expiry minus the refresh skew")
        assertEquals(JsonObject(emptyMap()), params["fields"])
        assertIs<ProviderSignInState.Connected>(h.controller.state.value)
        val modelList = h.invoker.calls.last { it.first == "model.list" }.second
        assertEquals(true, (modelList["force"] as JsonPrimitive).content.toBoolean(), "the new models are re-queried")
    }

    @Test
    fun slowDownStretchesTheIntervalAndIsShown() = runTest {
        val h = Harness(this)
        h.http.on(XAI_DEVICE, xaiDevice())
        h.http.on(XAI_TOKEN, error("slow_down"), reply(400, """{"error":"slow_down","interval":12}"""), error("authorization_pending"))

        h.controller.start(h.provider("xai"))
        advanceTimeBy(5_001)
        assertTrue(assertIs<ProviderSignInState.AwaitingApproval>(h.controller.state.value).slowedDown)
        advanceTimeBy(30_000)

        val times = h.http.to(XAI_TOKEN).map { it.atMillis }
        assertEquals(listOf(5_000L, 15_000L, 27_000L), times.take(3), "+5 s without an interval, then the provider's 12 s")
        h.controller.cancel()
    }

    @Test
    fun aDeniedSignInFails() = runTest {
        val h = Harness(this)
        h.http.on(XAI_DEVICE, xaiDevice())
        h.http.on(XAI_TOKEN, error("access_denied"))

        h.controller.start(h.provider("xai"))
        advanceUntilIdle()

        val failed = assertIs<ProviderSignInState.Failed>(h.controller.state.value)
        assertEquals(SignInFailure.DENIED, failed.failure)
        assertEquals(0, h.connects())
    }

    @Test
    fun anExpiredCodeFails() = runTest {
        val h = Harness(this)
        h.http.on(XAI_DEVICE, xaiDevice())
        h.http.on(XAI_TOKEN, error("expired_token"))

        h.controller.start(h.provider("xai"))
        advanceUntilIdle()

        assertEquals(SignInFailure.EXPIRED, assertIs<ProviderSignInState.Failed>(h.controller.state.value).failure)
    }

    @Test
    fun pollingStopsWhenTheCodesLifetimeRunsOut() = runTest {
        val h = Harness(this)
        h.http.on(XAI_DEVICE, xaiDevice(expiresIn = 12))
        h.http.on(XAI_TOKEN, error("authorization_pending"))

        h.controller.start(h.provider("xai"))
        advanceUntilIdle()

        val failed = assertIs<ProviderSignInState.Failed>(h.controller.state.value)
        assertEquals(SignInFailure.TIMED_OUT, failed.failure)
        assertEquals(listOf(5_000L, 10_000L), h.http.to(XAI_TOKEN).map { it.atMillis })
        assertEquals(12_000L, currentTime)
        assertEquals(0, h.connects())
    }

    @Test
    fun cancelStopsPollingAndNothingReachesTheHost() = runTest {
        val h = Harness(this)
        h.http.on(XAI_DEVICE, xaiDevice())
        h.http.on(XAI_TOKEN, reply(200, """{"access_token":"test-access","refresh_token":"test-refresh"}"""))

        h.controller.start(h.provider("xai"))
        runCurrent()
        h.controller.cancel()
        advanceUntilIdle()

        assertEquals(ProviderSignInState.Idle, h.controller.state.value)
        assertTrue(h.http.to(XAI_TOKEN).isEmpty())
        assertEquals(0, h.connects())
    }

    @Test
    fun aProviderErrorNamesTheErrorButNeverTheBody() = runTest {
        val h = Harness(this)
        h.http.on(XAI_DEVICE, reply(500, """{"error":"server_error","error_description":"try later","device_code":"leak"}"""))

        h.controller.start(h.provider("xai"))
        advanceUntilIdle()

        val failed = assertIs<ProviderSignInState.Failed>(h.controller.state.value)
        assertEquals(SignInFailure.PROVIDER_ERROR, failed.failure)
        assertEquals("xAI device authorization failed (HTTP 500): server_error: try later", failed.message)
    }

    @Test
    fun anUntrustedVerificationAddressIsRefused() = runTest {
        val h = Harness(this)
        h.http.on(XAI_DEVICE, reply(200, """{"device_code":"d","user_code":"u","verification_uri":"http://evil.example","expires_in":600}"""))

        h.controller.start(h.provider("xai"))
        advanceUntilIdle()

        assertTrue(assertIs<ProviderSignInState.Failed>(h.controller.state.value).message.contains("untrusted"))
    }

    @Test
    fun aHostThatRefusesTheTokensFailsAndRetryStartsOver() = runTest {
        val h = Harness(this, connectFails = true)
        h.http.on(XAI_DEVICE, xaiDevice())
        h.http.on(XAI_TOKEN, reply(200, """{"access_token":"test-access","refresh_token":"test-refresh"}"""))

        h.controller.start(h.provider("xai"))
        advanceUntilIdle()

        val failed = assertIs<ProviderSignInState.Failed>(h.controller.state.value)
        assertEquals(SignInFailure.HOST_REJECTED, failed.failure)
        assertTrue(failed.message.contains("invalid credentials"))

        h.controller.retry()
        runCurrent()
        assertIs<ProviderSignInState.AwaitingApproval>(h.controller.state.value)
        assertEquals(2, h.http.to(XAI_DEVICE).size)
        h.controller.cancel()
    }

    // --- ChatGPT device code ---

    @Test
    fun chatGptDeviceCodeExchangesTheCodeAndSendsTheChatGptBundle() = runTest {
        val h = Harness(this)
        val access = ProviderSignInFixtures.chatGptAccessToken("acct-test")
        h.http.on(OPENAI_USER_CODE, reply(200, """{"device_auth_id":"auth-secret","user_code":"WXYZ-9876","interval":"3"}"""))
        h.http.on(
            OPENAI_DEVICE_TOKEN,
            reply(403, "{}"),
            reply(400, """{"error":{"code":"deviceauth_authorization_pending"}}"""),
            reply(200, """{"authorization_code":"test-code","code_verifier":"test-verifier"}"""),
        )
        h.http.on(
            OPENAI_TOKEN,
            reply(200, """{"access_token":"$access","refresh_token":"test-refresh","id_token":"test-id","expires_in":3600}"""),
        )

        h.controller.start(h.provider("openai-codex-oauth"))
        runCurrent()
        val waiting = assertIs<ProviderSignInState.AwaitingApproval>(h.controller.state.value)
        assertEquals("WXYZ-9876", waiting.userCode)
        assertEquals("https://auth.openai.com/codex/device", waiting.verificationUrl)
        advanceUntilIdle()

        assertEquals(listOf(3_000L, 6_000L, 9_000L), h.http.to(OPENAI_DEVICE_TOKEN).map { it.atMillis })
        val exchange = h.http.to(OPENAI_TOKEN).single().body.form()
        assertEquals("test-code", exchange["code"])
        assertEquals("test-verifier", exchange["code_verifier"])
        assertEquals("https://auth.openai.com/deviceauth/callback", exchange["redirect_uri"])
        val config = h.connectParams()["oauth_config"] as JsonObject
        assertEquals(setOf("access_token", "id_token", "refresh_token", "account_id", "expires_at"), config.keys)
        assertEquals("acct-test", config["account_id"]!!.jsonPrimitive.content)
        assertEquals(9_000L + 3_600_000L, config["expires_at"]!!.jsonPrimitive.longOrNull)
        assertIs<ProviderSignInState.Connected>(h.controller.state.value)
    }

    // --- Claude: pasted authorization code ---

    @Test
    fun claudeOpensTheAuthorizePageAndExchangesThePastedRedirect() = runTest {
        val h = Harness(this)
        h.http.on(CLAUDE_TOKEN, reply(200, """{"access_token":"test-access","refresh_token":"test-refresh","expires_in":28800}"""))

        h.controller.start(h.provider("anthropic-oauth"))
        val awaiting = assertIs<ProviderSignInState.AwaitingCode>(h.controller.state.value)
        val url = Url(awaiting.authorizeUrl)
        assertEquals("claude.ai", url.host)
        assertEquals("S256", url.parameters["code_challenge_method"])
        assertEquals("http://localhost:53692/callback", url.parameters["redirect_uri"])
        val state = url.parameters["state"]!!
        assertFalse(awaiting.canSubmit)

        h.controller.updateCode("http://localhost:53692/callback?code=test-code&state=$state")
        h.controller.submitCode()
        advanceUntilIdle()

        val body = h.http.to(CLAUDE_TOKEN).single().body.json()
        assertEquals("test-code", body["code"]!!.jsonPrimitive.content)
        assertEquals(state, body["state"]!!.jsonPrimitive.content)
        assertEquals(Pkce.create { n -> ByteArray(n) { it.toByte() } }.verifier, body["code_verifier"]!!.jsonPrimitive.content)
        val config = h.connectParams()["oauth_config"] as JsonObject
        assertEquals("test-access", config["access"]!!.jsonPrimitive.content)
        assertEquals("anthropic-oauth", (h.connectParams()["provider_id"] as JsonPrimitive).content)
        assertIs<ProviderSignInState.Connected>(h.controller.state.value)
    }

    @Test
    fun aCodeFromAnotherSignInIsRefusedAndTheUserCanPasteAgain() = runTest {
        val h = Harness(this)
        h.controller.start(h.provider("anthropic-oauth"))

        h.controller.updateCode("test-code#someone-elses-state")
        h.controller.submitCode()
        advanceUntilIdle()

        val again = assertIs<ProviderSignInState.AwaitingCode>(h.controller.state.value)
        assertTrue(again.error.orEmpty().contains("different sign-in"))
        assertTrue(h.http.requests.isEmpty())
        h.controller.updateCode("x")
        assertEquals(null, (h.controller.state.value as ProviderSignInState.AwaitingCode).error, "typing clears the error")
    }

    // --- OpenRouter: the code buys an API key ---

    @Test
    fun openRouterSavesTheKeyOnTheApiKeyRow() = runTest {
        val h = Harness(this)
        h.http.on(OPENROUTER_KEYS, reply(200, """{"key":"test-or-key"}"""))

        h.controller.start(h.provider("openrouter-oauth"))
        val awaiting = assertIs<ProviderSignInState.AwaitingCode>(h.controller.state.value)
        assertEquals("openrouter.ai", Url(awaiting.authorizeUrl).host)
        h.controller.updateCode("http://localhost:3000/letta/openrouter/callback?code=test-code")
        h.controller.submitCode()
        advanceUntilIdle()

        assertEquals("test-code", h.http.to(OPENROUTER_KEYS).single().body.json()["code"]!!.jsonPrimitive.content)
        val params = h.connectParams()
        assertEquals("openrouter", (params["provider_id"] as JsonPrimitive).content)
        assertEquals("test-or-key", (params["fields"] as JsonObject)["apiKey"]!!.jsonPrimitive.content)
        assertFalse("oauth_config" in params)
    }

    @Test
    fun aProviderWithoutAnInAppLoginIsNotStarted() = runTest {
        val h = Harness(this)
        val copilot = h.provider("github-copilot")

        assertFalse(h.controller.supports(copilot))
        h.controller.start(copilot)

        assertIs<ProviderSignInState.Failed>(h.controller.state.value)
        assertTrue(h.http.requests.isEmpty())
    }

    private companion object {
        const val XAI_CLIENT = "b1a00492-073a-47ea-816f-4c329264a828"
        const val XAI_SCOPE = "openid profile email offline_access grok-cli:access api:access"
    }
}
