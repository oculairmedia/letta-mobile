package com.letta.mobile.runtime.local

import com.sun.net.httpserver.HttpServer
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AndroidNetworkBridgeStreamingTest {
    @Test
    fun `fetch streams SSE chunks incrementally instead of buffering until completion`() {
        val firstChunkWritten = CountDownLatch(1)
        val finishUpstream = CountDownLatch(1)
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/sse") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { body ->
                body.write("data: first\n\n".toByteArray(Charsets.UTF_8))
                body.flush()
                firstChunkWritten.countDown()
                assertTrue(finishUpstream.await(2, TimeUnit.SECONDS))
                body.write("data: second\n\n".toByteArray(Charsets.UTF_8))
                body.flush()
            }
        }
        upstream.start()

        withLoopbackFetchAllowed { bridge().start().use { session ->
            Socket("127.0.0.1", java.net.URI(session.baseUrl).port).use { socket ->
                socket.soTimeout = 2_000
                postFetch(socket.getOutputStream(), session.authToken, "http://127.0.0.1:${upstream.address.port}/sse")
                val input = socket.getInputStream()
                val headers = readUntil(input, "\r\n\r\n")
                assertTrue(headers.startsWith("HTTP/1.1 200"))
                assertTrue(headers.lowercase().contains("content-type: text/event-stream"))
                assertTrue(firstChunkWritten.await(2, TimeUnit.SECONDS))
                val first = readUntil(input, "\n\n")
                assertEquals("data: first\n\n", first)
                finishUpstream.countDown()
                val second = readUntil(input, "\n\n")
                assertEquals("data: second\n\n", second)
            }
        } }
        upstream.stop(0)
    }

    @Test
    fun `fetch preserves normal JSON status headers and body`() {
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/json") { exchange ->
            val bytes = "{\"ok\":true}".toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.responseHeaders.add("X-Test-Header", "present")
            exchange.sendResponseHeaders(201, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        upstream.start()

        withLoopbackFetchAllowed { bridge().start().use { session ->
            Socket("127.0.0.1", java.net.URI(session.baseUrl).port).use { socket ->
                postFetch(socket.getOutputStream(), session.authToken, "http://127.0.0.1:${upstream.address.port}/json")
                val response = socket.getInputStream().bufferedReader().readText()
                assertTrue(response.startsWith("HTTP/1.1 201"))
                val lowerResponse = response.lowercase()
                assertTrue(lowerResponse.contains("content-type: application/json"))
                assertTrue(lowerResponse.contains("x-test-header: present"))
                assertTrue(response.endsWith("{\"ok\":true}"))
            }
        } }
        upstream.stop(0)
    }

    @Test
    fun `fetch still rejects missing auth before upstream request`() {
        bridge().start().use { session ->
            Socket("127.0.0.1", java.net.URI(session.baseUrl).port).use { socket ->
                postFetch(socket.getOutputStream(), null, "http://example.com/")
                val response = socket.getInputStream().bufferedReader().readText()
                assertTrue(response.startsWith("HTTP/1.1 401"))
            }
        }
    }

    @Test
    fun `fetch still blocks loopback hosts by default`() {
        LocalAndroidNetworkBridge().start().use { session ->
            Socket("127.0.0.1", java.net.URI(session.baseUrl).port).use { socket ->
                postFetch(socket.getOutputStream(), session.authToken, "http://127.0.0.1:1/")
                val response = socket.getInputStream().bufferedReader().readText()
                assertTrue(response.startsWith("HTTP/1.1 403"))
            }
        }
    }

    private fun postFetch(output: OutputStream, authToken: String?, url: String) {
        val body = "{\"url\":\"$url\",\"method\":\"GET\"}"
        val bytes = body.toByteArray(Charsets.UTF_8)
        val request = buildString {
            append("POST /fetch HTTP/1.1\r\n")
            append("Host: 127.0.0.1\r\n")
            append("Content-Type: application/json\r\n")
            authToken?.let { append("Authorization: Bearer $it\r\n") }
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(request.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun readUntil(input: java.io.InputStream, terminator: String): String {
        val target = terminator.toByteArray(Charsets.UTF_8)
        val bytes = mutableListOf<Byte>()
        while (true) {
            val next = input.read()
            assertTrue("stream ended before $terminator", next >= 0)
            bytes.add(next.toByte())
            if (bytes.size >= target.size && bytes.takeLast(target.size).toByteArray().contentEquals(target)) {
                return bytes.toByteArray().toString(Charsets.UTF_8)
            }
        }
    }

    private fun bridge(): LocalAndroidNetworkBridge = LocalAndroidNetworkBridge()

    private fun withLoopbackFetchAllowed(block: () -> Unit) {
        val property = "com.letta.mobile.androidNetworkBridge.allowLoopbackFetchForTests"
        val previous = System.getProperty(property)
        System.setProperty(property, "true")
        try {
            block()
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }
}
