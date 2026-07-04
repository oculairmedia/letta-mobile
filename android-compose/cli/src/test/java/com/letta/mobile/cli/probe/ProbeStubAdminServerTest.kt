package com.letta.mobile.cli.probe

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProbeStubAdminServerTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun http(method: String, url: String, body: String? = null): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            assertEquals(200, connection.responseCode)
            connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `seed then page messages back with limit and after`() {
        val store = ProbeStubStore()
        ProbeStubAdminServer(store).use { server ->
            val seedResponse = json.parseToJsonElement(
                http(
                    "POST",
                    "${server.baseUrl}/probe/seed",
                    """{"conversation_id":"conv-h","count":7,"payload_bytes":64}""",
                ),
            ).jsonObject
            assertEquals(7, seedResponse["seeded"]!!.jsonPrimitive.content.toInt())
            assertTrue(seedResponse["total_bytes"]!!.jsonPrimitive.content.toLong() >= 7 * 64L)

            val page1 = json.parseToJsonElement(
                http("GET", "${server.baseUrl}/v1/conversations/conv-h/messages?limit=3"),
            ).jsonArray
            assertEquals(3, page1.size)

            val after = page1.last().jsonObject["id"]!!.jsonPrimitive.content
            val page2 = json.parseToJsonElement(
                http("GET", "${server.baseUrl}/v1/conversations/conv-h/messages?limit=3&after=$after"),
            ).jsonArray
            assertEquals(3, page2.size)
            assertTrue(page1.map { it.jsonObject["id"] }.intersect(page2.map { it.jsonObject["id"] }.toSet()).isEmpty())

            val after2 = page2.last().jsonObject["id"]!!.jsonPrimitive.content
            val page3 = json.parseToJsonElement(
                http("GET", "${server.baseUrl}/v1/conversations/conv-h/messages?limit=3&after=$after2"),
            ).jsonArray
            assertEquals(1, page3.size)
        }
    }

    @Test
    fun `conversation list and run status endpoints answer`() {
        val store = ProbeStubStore()
        store.append("conv-a", "assistant_message", "hi")
        store.runStatuses["run-1"] = "cancelled"
        ProbeStubAdminServer(store).use { server ->
            val conversations = json.parseToJsonElement(http("GET", "${server.baseUrl}/v1/conversations")).jsonArray
            assertEquals(1, conversations.size)
            assertEquals("conv-a", conversations.first().jsonObject["id"]!!.jsonPrimitive.content)

            val run = json.parseToJsonElement(http("GET", "${server.baseUrl}/v1/runs/run-1")).jsonObject
            assertEquals("cancelled", run["status"]!!.jsonPrimitive.content)

            val unknown = json.parseToJsonElement(http("GET", "${server.baseUrl}/v1/runs/run-x")).jsonObject
            assertEquals("unknown", unknown["status"]!!.jsonPrimitive.content)
        }
    }
}
