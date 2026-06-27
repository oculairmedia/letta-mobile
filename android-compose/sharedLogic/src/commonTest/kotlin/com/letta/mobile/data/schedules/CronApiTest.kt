package com.letta.mobile.data.schedules

import com.letta.mobile.data.model.LettaConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CronApiTest {
    private val config = LettaConfig(
        id = "test-config",
        mode = LettaConfig.Mode.CLOUD,
        serverUrl = "https://api.example.com/",
        accessToken = "test-token"
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private fun createApi(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): CronApi {
        val mockEngine = MockEngine { request ->
            handler(request)
        }
        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(json)
            }
        }
        return CronApi(config, httpClient)
    }

    @Test
    fun testListCrons() = runTest {
        var requestedUrl = ""
        var requestedMethod: HttpMethod? = null
        var requestedAuthHeader: String? = null

        val api = createApi { request ->
            requestedUrl = request.url.toString()
            requestedMethod = request.method
            requestedAuthHeader = request.headers[HttpHeaders.Authorization]

            respond(
                content = """
                    {
                        "tasks": [
                            {
                                "id": "cron-123",
                                "agent_id": "agent-456",
                                "name": "Daily Update",
                                "cron": "0 0 * * *",
                                "timezone": "UTC",
                                "recurring": true
                            }
                        ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val tasks = api.listCrons()

        assertEquals("https://api.example.com/v1/crons", requestedUrl)
        assertEquals(HttpMethod.Get, requestedMethod)
        assertEquals("Bearer test-token", requestedAuthHeader)

        assertEquals(1, tasks.size)
        val task = tasks[0]
        assertEquals("cron-123", task.id)
        assertEquals("agent-456", task.agentId)
        assertEquals("Daily Update", task.name)
        assertEquals("0 0 * * *", task.cron)
        assertEquals("UTC", task.timezone)
        assertTrue(task.recurring)

        api.close()
    }

    @Test
    fun testDeleteCron() = runTest {
        var requestedUrl = ""
        var requestedMethod: HttpMethod? = null
        var requestedAuthHeader: String? = null

        val api = createApi { request ->
            requestedUrl = request.url.toString()
            requestedMethod = request.method
            requestedAuthHeader = request.headers[HttpHeaders.Authorization]

            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        api.deleteCron("cron-123")

        assertEquals("https://api.example.com/v1/crons/cron-123", requestedUrl)
        assertEquals(HttpMethod.Delete, requestedMethod)
        assertEquals("Bearer test-token", requestedAuthHeader)

        api.close()
    }

    @Test
    fun testCreateCron() = runTest {
        var requestedUrl = ""
        var requestedMethod: HttpMethod? = null
        var requestedAuthHeader: String? = null
        var requestedContentType: String? = null
        var requestedBodyText = ""

        val api = createApi { request ->
            requestedUrl = request.url.toString()
            requestedMethod = request.method
            requestedAuthHeader = request.headers[HttpHeaders.Authorization]
            val content = request.body
            if (content is io.ktor.http.content.TextContent) {
                requestedBodyText = content.text
                requestedContentType = content.contentType?.toString()
            }

            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        api.createCron(
            agentId = "agent-456",
            name = "Morning Report",
            description = "A daily morning report",
            prompt = "Generate a report",
            cron = "0 8 * * *",
            timezone = "America/New_York",
            recurring = true
        )

        assertEquals("https://api.example.com/v1/crons", requestedUrl)
        assertEquals(HttpMethod.Post, requestedMethod)
        assertEquals("Bearer test-token", requestedAuthHeader)
        assertEquals("application/json", requestedContentType)

        val bodyJson = json.decodeFromString<JsonObject>(requestedBodyText)
        assertEquals("agent-456", bodyJson["agent_id"]?.let { it as kotlinx.serialization.json.JsonPrimitive }?.content)
        assertEquals("Morning Report", bodyJson["name"]?.let { it as kotlinx.serialization.json.JsonPrimitive }?.content)
        assertEquals("A daily morning report", bodyJson["description"]?.let { it as kotlinx.serialization.json.JsonPrimitive }?.content)
        assertEquals("Generate a report", bodyJson["prompt"]?.let { it as kotlinx.serialization.json.JsonPrimitive }?.content)
        assertEquals("0 8 * * *", bodyJson["cron"]?.let { it as kotlinx.serialization.json.JsonPrimitive }?.content)
        assertEquals("America/New_York", bodyJson["timezone"]?.let { it as kotlinx.serialization.json.JsonPrimitive }?.content)
        assertEquals("true", bodyJson["recurring"]?.let { it as kotlinx.serialization.json.JsonPrimitive }?.content)

        api.close()
    }

    @Test
    fun testFailureThrowsIllegalStateException() = runTest {
        val api = createApi { _ ->
            respond(
                content = "Internal Server Error",
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "text/plain")
            )
        }

        val exception = assertFailsWith<IllegalStateException> {
            api.listCrons()
        }

        assertTrue(exception.message!!.contains("Cron API 500"))
        assertTrue(exception.message!!.contains("Internal Server Error"))

        api.close()
    }
}
