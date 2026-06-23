package com.letta.mobile.data.api

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentUpdateParams
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
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class AgentApiTest : com.letta.mobile.testutil.TrackedMockClientTestSupport() {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    private val json = Json { ignoreUnknownKeys = true }

    private fun createApi(client: HttpClient): AgentApi {
        val apiClient = mockk<LettaApiClient> {
            coEvery { getClient() } returns client
            every { getBaseUrl() } returns "http://test"
            coEvery { session() } returns ApiSession(client, "http://test")
        }
        return AgentApi(apiClient)
    }

    @Test
    fun `listAgents sends GET to correct endpoint`() = runTest {
        var capturedMethod: HttpMethod? = null
        var capturedUrl: String? = null
        val client = trackClient(HttpClient(MockEngine { request ->
            capturedMethod = request.method
            capturedUrl = request.url.toString()
            respond("""[{"id":"1","name":"Agent1"}]""", HttpStatusCode.OK, jsonHeaders) }) { install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        } })
        val api = createApi(client)
        val agents = api.listAgents()
        assertEquals(HttpMethod.Get, capturedMethod)
        assertTrue(capturedUrl!!.contains("/v1/agents"))
        assertEquals(1, agents.size)
        assertEquals("Agent1", agents[0].name)
    }

    @Test
    fun `listAgentsSlim hits slim endpoint and parses summaries including null description`() = runTest {
        var capturedMethod: HttpMethod? = null
        var capturedUrl: String? = null
        val client = trackClient(HttpClient(MockEngine { request ->
            capturedMethod = request.method
            capturedUrl = request.url.toString()
            respond(
                """[{"id":"a1","name":"Agent One","description":"first"},{"id":"a2","name":"Agent Two","description":null}]""",
                HttpStatusCode.OK,
                jsonHeaders,
            ) }) { install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        } })
        val api = createApi(client)

        val summaries = api.listAgentsSlim()

        assertEquals(HttpMethod.Get, capturedMethod)
        assertTrue(capturedUrl!!.contains("/v1/agents"))
        assertTrue(capturedUrl!!.contains("slim=true"))
        assertEquals(2, summaries.size)
        assertEquals("a1", summaries[0].id.value)
        assertEquals("Agent One", summaries[0].name)
        assertEquals("first", summaries[0].description)
        assertEquals("a2", summaries[1].id.value)
        assertEquals(null, summaries[1].description)
    }

    @Test
    fun `listAgentsSlim passes pagination parameters`() = runTest {
        var capturedUrl: String? = null
        val client = trackClient(HttpClient(MockEngine { request ->
            capturedUrl = request.url.toString()
            respond("[]", HttpStatusCode.OK, jsonHeaders) }) { install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        } })
        val api = createApi(client)
        api.listAgentsSlim(limit = 50, offset = 100)
        assertTrue(capturedUrl!!.contains("slim=true"))
        assertTrue(capturedUrl!!.contains("limit=50"))
        assertTrue(capturedUrl!!.contains("offset=100"))
    }

    @Test(expected = ApiException::class)
    fun `listAgentsSlim throws ApiException on 500`() = runTest {
        val client = trackClient(HttpClient(MockEngine { respond("""{"error":"server error"}""", HttpStatusCode.InternalServerError, jsonHeaders) }) { install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        } })
        val api = createApi(client)
        api.listAgentsSlim()
    }

    @Test
    fun `getAgent sends GET with agent ID`() = runTest {
        var capturedUrl: String? = null
        val client = trackClient(HttpClient(MockEngine { request ->
            capturedUrl = request.url.toString()
            respond("""{"id":"a1","name":"MyAgent"}""", HttpStatusCode.OK, jsonHeaders) }) { install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        } })
        val api = createApi(client)
        val agent = api.getAgent("a1")
        assertTrue(capturedUrl!!.contains("/v1/agents/a1"))
        assertEquals("MyAgent", agent.name)
    }

    @Test
    fun `getContextWindow requests mobile-safe overview`() = runTest {
        var capturedUrl: String? = null
        val client = trackClient(HttpClient(MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(
                """{"context_window_size_max":4096,"context_window_size_current":256,"num_messages":3}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }) { install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        } })
        val api = createApi(client)

        val overview = api.getContextWindow("a1", "conversation-1")

        assertTrue(capturedUrl!!.contains("/v1/agents/a1/context"))
        assertTrue(capturedUrl!!.contains("conversation_id=conversation-1"))
        assertTrue(capturedUrl!!.contains("mobile_safe=true"))
        assertTrue(capturedUrl!!.contains("include_raw=false"))
        assertEquals(4096, overview.contextWindowSizeMax)
        assertEquals(256, overview.contextWindowSizeCurrent)
        assertEquals(3, overview.numMessages)
    }

    @Test
    fun `getContextWindow rejects oversized response before buffering body`() = runTest {
        val client = trackClient(HttpClient(MockEngine {
            respond(
                "{}",
                HttpStatusCode.OK,
                headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                    HttpHeaders.ContentLength to listOf((1024 * 1024).toString()),
                ),
            )
        }) { install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        } })
        val api = createApi(client)

        val exception = assertThrows(AgentApi.ResponseTooLargeException::class.java) {
            kotlinx.coroutines.runBlocking { api.getContextWindow("a1") }
        }
        assertEquals(HttpStatusCode.PayloadTooLarge.value, exception.code)
    }

    @Test
    fun `getContextWindow rejects incremental response larger than max bytes`() = runTest {
        val client = trackClient(HttpClient(MockEngine {
            // Emulate chunked transfer by omitting Content-Length and writing a large body
            // We'll write slightly more than MAX_CONTEXT_WINDOW_RESPONSE_BYTES
            val largeBody = "a".repeat(512 * 1024 + 10)
            respond(
                largeBody,
                HttpStatusCode.OK,
                headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString())
                ),
            )
        }) { install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        } })
        val api = createApi(client)

        val exception = assertThrows(AgentApi.ResponseTooLargeException::class.java) {
            kotlinx.coroutines.runBlocking { api.getContextWindow("a1") }
        }
        assertEquals(HttpStatusCode.PayloadTooLarge.value, exception.code)
    }

    @Test
    fun `getContextWindow succeeds for small response`() = runTest {
        val client = trackClient(HttpClient(MockEngine {
            respond(
                """{"context_window_size_max":8192,"context_window_size_current":1024,"num_messages":5}""",
                HttpStatusCode.OK,
                headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                    // Explicitly set content length
                    HttpHeaders.ContentLength to listOf("88"),
                ),
            )
        }) { install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        } })
        val api = createApi(client)

        val overview = api.getContextWindow("a1")
        assertEquals(8192, overview.contextWindowSizeMax)
        assertEquals(1024, overview.contextWindowSizeCurrent)
        assertEquals(5, overview.numMessages)
    }

    @Test
    fun `createAgent sends POST with body`() = runTest {
        var capturedMethod: HttpMethod? = null
        val client = trackClient(HttpClient(MockEngine { request ->
            capturedMethod = request.method
            respond("""{"id":"new-1","name":"NewAgent"}""", HttpStatusCode.OK, jsonHeaders) }) { install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        } })
        val api = createApi(client)
        val agent = api.createAgent(AgentCreateParams(name = "NewAgent"))
        assertEquals(HttpMethod.Post, capturedMethod)
        assertEquals("NewAgent", agent.name)
    }

    @Test
    fun `updateAgent sends PATCH`() = runTest {
        var capturedMethod: HttpMethod? = null
        val client = trackClient(HttpClient(MockEngine { request ->
            capturedMethod = request.method
            respond("""{"id":"a1","name":"Updated"}""", HttpStatusCode.OK, jsonHeaders) }) { install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        } })
        val api = createApi(client)
        val agent = api.updateAgent("a1", AgentUpdateParams(name = "Updated"))
        assertEquals(HttpMethod.Patch, capturedMethod)
        assertEquals("Updated", agent.name)
    }

    @Test
    fun `deleteAgent sends DELETE`() = runTest {
        var capturedMethod: HttpMethod? = null
        val client = trackClient(HttpClient(MockEngine { request ->
            capturedMethod = request.method
            respond("", HttpStatusCode.OK, jsonHeaders) }) { install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        } })
        val api = createApi(client)
        api.deleteAgent("a1")
        assertEquals(HttpMethod.Delete, capturedMethod)
    }

    @Test(expected = ApiException::class)
    fun `listAgents throws ApiException on 500`() = runTest {
        val client = trackClient(HttpClient(MockEngine { respond("""{"error":"server error"}""", HttpStatusCode.InternalServerError, jsonHeaders) }) { install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        } })
        val api = createApi(client)
        api.listAgents()
    }

    @Test(expected = ApiException::class)
    fun `getAgent throws ApiException on 404`() = runTest {
        val client = trackClient(HttpClient(MockEngine { respond("""{"error":"not found"}""", HttpStatusCode.NotFound, jsonHeaders) }) { install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        } })
        val api = createApi(client)
        api.getAgent("nonexistent")
    }

    @Test
    fun `listAgents passes limit and offset parameters`() = runTest {
        var capturedUrl: String? = null
        val client = trackClient(HttpClient(MockEngine { request ->
            capturedUrl = request.url.toString()
            respond("[]", HttpStatusCode.OK, jsonHeaders) }) { install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        } })
        val api = createApi(client)
        api.listAgents(limit = 10, offset = 20)
        assertTrue(capturedUrl!!.contains("limit=10"))
        assertTrue(capturedUrl!!.contains("offset=20"))
    }

    @Test
    fun `importAgent sends multipart form with safety toggles`() = runTest {
        var capturedUrl: String? = null
        var capturedMethod: HttpMethod? = null
        val client = trackClient(HttpClient(MockEngine { request ->
            capturedUrl = request.url.toString()
            capturedMethod = request.method
            respond("""{"agent_ids":["a2"]}""", HttpStatusCode.OK, jsonHeaders) }) { install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        } })
        val api = createApi(client)

        val response = api.importAgent(
            fileName = "agent.json",
            fileBytes = "{}".toByteArray(),
            overrideName = "Clone Name",
            overrideExistingTools = false,
            stripMessages = true,
        )

        assertEquals(HttpMethod.Post, capturedMethod)
        assertTrue(capturedUrl!!.contains("/v1/agents/import"))
        assertEquals(listOf("a2"), response.agentIds)
    }

    @Test
    fun `attachArchive sends PATCH to agent archive attach endpoint`() = runTest {
        var capturedMethod: HttpMethod? = null
        var capturedUrl: String? = null
        val client = trackClient(HttpClient(MockEngine { request ->
            capturedMethod = request.method
            capturedUrl = request.url.toString()
            respond("", HttpStatusCode.NoContent, jsonHeaders) }) { install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        } })
        val api = createApi(client)

        api.attachArchive("agent-1", "archive-1")

        assertEquals(HttpMethod.Patch, capturedMethod)
        assertTrue(capturedUrl!!.contains("/v1/agents/agent-1/archives/attach/archive-1"))
    }

    @Test
    fun `detachArchive sends PATCH to agent archive detach endpoint`() = runTest {
        var capturedMethod: HttpMethod? = null
        var capturedUrl: String? = null
        val client = trackClient(HttpClient(MockEngine { request ->
            capturedMethod = request.method
            capturedUrl = request.url.toString()
            respond("", HttpStatusCode.NoContent, jsonHeaders) }) { install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        } })
        val api = createApi(client)

        api.detachArchive("agent-1", "archive-1")

        assertEquals(HttpMethod.Patch, capturedMethod)
        assertTrue(capturedUrl!!.contains("/v1/agents/agent-1/archives/detach/archive-1"))
    }
}
