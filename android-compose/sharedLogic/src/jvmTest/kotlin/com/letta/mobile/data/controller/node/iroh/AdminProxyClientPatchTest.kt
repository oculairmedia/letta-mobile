package com.letta.mobile.data.controller.node.iroh

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Purity-gap regression (drawer model switch): HttpURLConnection throws
 * `ProtocolException: Invalid HTTP method: PATCH`, so admin_rpc agent.update
 * failed server-side with "handler.error method=agent.update error=Invalid
 * HTTP method: PATCH" and the UI surfaced "Couldn't switch model". This test
 * is the red/green gate for the reflection-based PATCH compat in
 * [HttpUrlConnectionAdminProxyTransport]: it fails with the raw
 * `requestMethod = "PATCH"` assignment and passes with the compat shim.
 */
class AdminProxyClientPatchTest {
    private lateinit var server: HttpServer
    private var receivedMethod: String? = null
    private var receivedBody: String? = null

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/agents/agent-1") { exchange ->
            receivedMethod = exchange.requestMethod
            receivedBody = exchange.requestBody.readBytes().decodeToString()
            val response = """{"id":"agent-1","model":"gpt-5.5"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun patchReachesServerAsRealPatchMethod() {
        val client = AdminProxyClient("http://127.0.0.1:${server.address.port}")
        val result = client.patch(
            adminProxyRequest("v1", "agents", "agent-1").build(),
            """{"model":"gpt-5.5"}""",
        )
        assertEquals("PATCH", receivedMethod)
        assertEquals("""{"model":"gpt-5.5"}""", receivedBody)
        assertEquals("agent-1", result.toString().let { if (it.contains("agent-1")) "agent-1" else it })
    }

    @Test
    fun getStillWorksThroughCompatPath() {
        val client = AdminProxyClient("http://127.0.0.1:${server.address.port}")
        client.get(adminProxyRequest("v1", "agents", "agent-1").build())
        assertEquals("GET", receivedMethod)
    }
}
