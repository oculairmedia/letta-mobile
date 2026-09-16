package com.letta.mobile.data.transport.appserver

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class AppServerDiscoveryTest {
    private val requests = mutableListOf<Pair<String, String?>>()

    private fun discovery(handler: (String) -> Pair<HttpStatusCode, String>) = AppServerDiscovery(
        HttpClient(
            MockEngine { request ->
                requests += request.url.toString() to request.headers[HttpHeaders.Authorization]
                val (status, body) = handler(request.url.encodedPath)
                if (status.value >= 400) respondError(status, body) else respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ),
    )

    @Test
    fun healthProbesUseTheHttpOriginOfAWebSocketUrl() = runTest {
        val probes = discovery { path -> if (path == "/readyz") HttpStatusCode.OK to "ok" else HttpStatusCode.ServiceUnavailable to "starting" }

        assertTrue(probes.readyz("ws://127.0.0.1:4500/ws?channel=ignored"))
        assertFalse(probes.healthz("ws://127.0.0.1:4500"))
        assertEquals(listOf("http://127.0.0.1:4500/readyz", "http://127.0.0.1:4500/healthz"), requests.map { it.first })
        assertEquals(listOf(null, null), requests.map { it.second }, "health probes are unauthenticated")
    }

    @Test
    fun appServerInfoDecodesTheLiveBodyAndSendsTheBearer() = runTest {
        // Captured from the live App Server (letta-code 0.32.3) GET /app-server-info.
        val body = """{"type":"app_server_info_response","request_id":"http-info","success":true,"backend":"local","letta_code_version":"0.32.3","protocol_version":1,"capabilities":{"agent_management":true,"conversation_management":true,"memory_management":true,"runtime_start":true,"runtime_workspace_sandbox":true,"runtime_external_tools_update":true,"split_channels":false}}"""
        val info = discovery { HttpStatusCode.OK to body }.appServerInfo("wss://host.example/ws", bearerToken = "tkn")

        assertTrue(info.success)
        assertEquals(1, info.info?.protocolVersion)
        assertFalse(info.info!!.splitChannels)
        assertTrue(info.info!!.hasCapability("runtime_external_tools_update"))
        assertEquals("https://host.example/app-server-info" to "Bearer tkn", requests.single())
    }

    @Test
    fun appServerInfoReportsAnHttpFailureWithoutThrowing() = runTest {
        val info = discovery { HttpStatusCode.Unauthorized to "unauthorized" }.appServerInfo("http://127.0.0.1:4500")

        assertFalse(info.success)
        assertEquals("app-server-info HTTP 401", info.error)
    }

    @Test
    fun httpOriginKeepsSchemeHostAndPortOnly() {
        assertEquals("http://127.0.0.1:4500", AppServerDiscovery.httpOrigin("ws://127.0.0.1:4500/ws"))
        assertEquals("https://host", AppServerDiscovery.httpOrigin("wss://host?x=1"))
        assertEquals("https://host:8443", AppServerDiscovery.httpOrigin("https://host:8443/"))
    }
}
