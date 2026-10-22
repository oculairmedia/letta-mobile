package com.letta.mobile.data.controller.node.iroh

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpUrlConnectionAdminProxyTransportTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/big") { exchange ->
            val body = """{"pad":"${"x".repeat(10_000)}"}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        createContext("/small") { exchange ->
            val body = """{"ok":true}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        createContext("/error") { exchange ->
            val body = "e".repeat(200_000).toByteArray()
            exchange.sendResponseHeaders(500, body.size.toLong())
            runCatching { exchange.responseBody.use { it.write(body) } }
        }
        start()
    }
    private val baseUrl = "http://127.0.0.1:${server.address.port}"

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun aBodyUnderTheCapIsReturned() {
        val response = HttpUrlConnectionAdminProxyTransport(maxBodyBytes = 1_000).execute("GET", "$baseUrl/small", null)

        assertEquals(200, response.statusCode)
        assertEquals("""{"ok":true}""", response.bodyText)
    }

    @Test
    fun aSuccessBodyPastTheCapFailsInsteadOfBuffering() {
        val error = assertFailsWith<IOException> {
            HttpUrlConnectionAdminProxyTransport(maxBodyBytes = 1_000).execute("GET", "$baseUrl/big", null)
        }

        assertTrue(error.message.orEmpty().contains("exceeded 1000 bytes"), error.message)
    }

    @Test
    fun anErrorBodyIsCutRatherThanFailed() {
        val response = HttpUrlConnectionAdminProxyTransport().execute("GET", "$baseUrl/error", null)

        assertEquals(500, response.statusCode)
        assertEquals(64 * 1024, response.bodyText.length)
    }
}
