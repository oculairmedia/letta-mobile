package com.letta.mobile.data.transport.appserver

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

class AppServerProbeTest {
    private val requests = mutableListOf<Pair<String, String?>>()

    private fun probe(
        timeoutMs: Long = 10_000,
        handler: suspend () -> Pair<HttpStatusCode, String>,
    ) = AppServerProbe(
        http = HttpClient(
            MockEngine { request ->
                requests += request.url.toString() to request.headers[HttpHeaders.Authorization]
                val (status, body) = handler()
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ),
        timeout = timeoutMs.milliseconds,
    )

    private fun info(
        protocolVersion: Int = 1,
        capabilities: String = """"agent_management":true,"conversation_management":true,"memory_management":true,"runtime_start":true,"split_channels":false""",
    ) = """{"type":"app_server_info_response","request_id":"http-info","success":true,"backend":"local","letta_code_version":"0.33.6","protocol_version":$protocolVersion,"capabilities":{$capabilities}}"""

    @Test
    fun okCarriesTheServerIdentityAndSendsTheBearerToTheHttpOrigin() = runTest {
        val result = probe { HttpStatusCode.OK to info() }.probeReal("wss://host.example/ws?x=1", bearerToken = " tkn ")

        assertEquals(AppServerProbeResult.Ok(AppServerIdentity("local", "0.33.6", 1)), result)
        assertFalse(result.isTerminal)
        assertEquals("https://host.example/app-server-info" to "Bearer tkn", requests.single())
    }

    @Test
    fun unauthorizedIsAuthentication() = runTest {
        val result = probe { HttpStatusCode.Unauthorized to "nope" }.probeReal("ws://127.0.0.1:4500")

        assertIs<AppServerProbeResult.Authentication>(result)
        assertTrue(result.isTerminal)
        assertEquals(null, requests.single().second, "a blank token sends no Authorization header")
    }

    @Test
    fun forbiddenIsAuthentication() = runTest {
        assertIs<AppServerProbeResult.Authentication>(probe { HttpStatusCode.Forbidden to "" }.probeReal("http://h:1"))
    }

    @Test
    fun notFoundIsIncompatible() = runTest {
        val result = probe { HttpStatusCode.NotFound to "" }.probeReal("http://h:1")

        assertIs<AppServerProbeResult.Incompatible>(result)
        assertTrue(result.isTerminal)
    }

    @Test
    fun malformedJsonIsIncompatible() = runTest {
        assertIs<AppServerProbeResult.Incompatible>(probe { HttpStatusCode.OK to "{not json" }.probeReal("http://h:1"))
    }

    @Test
    fun aNonObjectBodyIsIncompatible() = runTest {
        assertIs<AppServerProbeResult.Incompatible>(probe { HttpStatusCode.OK to "[1,2]" }.probeReal("http://h:1"))
    }

    @Test
    fun anotherProtocolVersionIsIncompatible() = runTest {
        val result = probe { HttpStatusCode.OK to info(protocolVersion = 2) }.probeReal("http://h:1")

        assertIs<AppServerProbeResult.Incompatible>(result)
        assertTrue(result.reason.contains("protocol_version=2"), result.reason)
    }

    @Test
    fun aMissingCapabilityIsIncompatible() = runTest {
        val result = probe {
            HttpStatusCode.OK to info(capabilities = """"agent_management":true,"conversation_management":true,"runtime_start":true,"split_channels":false""")
        }.probeReal("http://h:1")

        assertIs<AppServerProbeResult.Incompatible>(result)
        assertTrue(result.reason.contains("memory_management"), result.reason)
    }

    @Test
    fun splitChannelsEnabledIsIncompatible() = runTest {
        val result = probe {
            HttpStatusCode.OK to info(capabilities = """"agent_management":true,"conversation_management":true,"memory_management":true,"runtime_start":true,"split_channels":true""")
        }.probeReal("http://h:1")

        assertIs<AppServerProbeResult.Incompatible>(result)
    }

    @Test
    fun serverErrorIsUnavailable() = runTest {
        val result = probe { HttpStatusCode.ServiceUnavailable to "starting" }.probeReal("http://h:1")

        assertIs<AppServerProbeResult.Unavailable>(result)
        assertFalse(result.isTerminal)
    }

    @Test
    fun timeoutIsUnavailable() = runTest {
        val result = probe(timeoutMs = 50) {
            delay(30.seconds)
            HttpStatusCode.OK to info()
        }.probeReal("http://h:1")

        assertIs<AppServerProbeResult.Unavailable>(result)
    }

    @Test
    fun aNetworkErrorIsUnavailable() = runTest {
        val result = probe { throw IllegalStateException("connection refused") }.probeReal("http://h:1")

        assertEquals(AppServerProbeResult.Unavailable("connection refused"), result)
    }

    @Test
    fun aUrlThatIsNotAnAppServerUrlIsIncompatibleWithoutARequest() = runTest {
        assertIs<AppServerProbeResult.Incompatible>(probe { HttpStatusCode.OK to info() }.probeReal("iroh://abc"))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun probeClientClassifiesTheSocketAnswer() = runTest {
        val ok = AppServerProbe.probeClient(InfoClient(AppServerProbe.decodeForTest(info())), requestId = "r1")
        val unsupported = AppServerProbe.probeClient(InfoClient(null), requestId = "r2")

        assertIs<AppServerProbeResult.Ok>(ok)
        assertIs<AppServerProbeResult.Incompatible>(unsupported)
    }

    private class InfoClient(private val response: AppServerInboundFrame.AppServerInfoResponse?) : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = emptyFlow()

        override suspend fun appServerInfo(command: AppServerCommand.AppServerInfo): AppServerInboundFrame.AppServerInfoResponse =
            response ?: throw UnsupportedOperationException("app_server_info is not supported by this client")

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = error("unused")

        override suspend fun input(command: AppServerCommand.Input) = Unit

        override suspend fun sync(command: AppServerCommand.Sync) = error("unused")

        override suspend fun abort(command: AppServerCommand.AbortMessage) = error("unused")

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = error("unused")

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit
    }
}

/** MockEngine answers on real threads; keep the probe's timeout on the real clock, not virtual time. */
private suspend fun AppServerProbe.probeReal(baseUrl: String, bearerToken: String? = null) =
    withContext(Dispatchers.Default) { probe(baseUrl, bearerToken) }

private fun AppServerProbe.Companion.decodeForTest(body: String) = AppServerDiscovery.decodeAppServerInfoBody(body)
