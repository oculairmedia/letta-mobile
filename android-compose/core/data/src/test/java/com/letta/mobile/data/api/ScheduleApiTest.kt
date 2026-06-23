package com.letta.mobile.data.api

import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduleDefinition
import com.letta.mobile.data.model.ScheduleMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class ScheduleApiTest : com.letta.mobile.testutil.TrackedMockClientTestSupport() {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun createApi(handler: suspend (io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData)): ScheduleApi {
        val client = trackClient(HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) } })
        val apiClient = mockk<LettaApiClient> {
            coEvery { getClient() } returns client
            every { getBaseUrl() } returns "http://test"
            coEvery { session() } returns ApiSession(client, "http://test")
        }
        return ScheduleApi(apiClient)
    }

    @Test
    fun `listSchedules sends GET with agentId`() = runTest {
        var url: String? = null
        val api = createApi { req ->
            url = req.url.toString()
            respond("{\"has_next_page\":false,\"scheduled_messages\":[]}", HttpStatusCode.OK, jsonHeaders)
        }

        api.listSchedules("a1")

        assertTrue(url!!.contains("/v1/agents/a1/schedule"))
    }

    @Test
    fun `createSchedule sends POST`() = runTest {
        var method: HttpMethod? = null
        val api = createApi { req ->
            method = req.method
            respond(
                """{"id":"s1","agent_id":"a1","message":{"messages":[{"content":"hello","role":"user"}]},"schedule":{"type":"one-time","scheduled_at":1700000000.0}}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }

        api.createSchedule(
            "a1",
            ScheduleCreateParams(
                messages = listOf(ScheduleMessage(content = "hello", role = "user")),
                schedule = ScheduleDefinition(type = "one-time", scheduledAt = 1_700_000_000.0),
            )
        )

        assertEquals(HttpMethod.Post, method)
    }

    @Test
    fun `deleteSchedule sends DELETE`() = runTest {
        var method: HttpMethod? = null
        val api = createApi { req -> method = req.method; respond("{}", HttpStatusCode.OK, jsonHeaders) }

        api.deleteSchedule("a1", "s1")

        assertEquals(HttpMethod.Delete, method)
    }

    @Test(expected = ApiException::class)
    fun `listSchedules throws on error`() = runTest {
        val api = createApi { respond("error", HttpStatusCode.InternalServerError, jsonHeaders) }
        api.listSchedules("a1")
    }
}
