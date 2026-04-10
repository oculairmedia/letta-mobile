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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentApiTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    private val json = Json { ignoreUnknownKeys = true }

    private fun createApi(client: HttpClient): AgentApi {
        val apiClient = mockk<LettaApiClient> {
            coEvery { getClient() } returns client
            every { getBaseUrl() } returns "http://test"
        }
        return AgentApi(apiClient)
    }

    @Test
    fun `listAgents sends GET to correct endpoint`() = runTest {
        var capturedMethod: HttpMethod? = null
        var capturedUrl: String? = null
        val client = HttpClient(MockEngine { request ->
            capturedMethod = request.method
            capturedUrl = request.url.toString()
            respond("""[{"id":"1","name":"Agent1"}]""", HttpStatusCode.OK, jsonHeaders)
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
        val api = createApi(client)
        val agents = api.listAgents()
        assertEquals(HttpMethod.Get, capturedMethod)
        assertTrue(capturedUrl!!.contains("/v1/agents"))
        assertEquals(1, agents.size)
        assertEquals("Agent1", agents[0].name)
    }

    @Test
    fun `getAgent sends GET with agent ID`() = runTest {
        var capturedUrl: String? = null
        val client = HttpClient(MockEngine { request ->
            capturedUrl = request.url.toString()
            respond("""{"id":"a1","name":"MyAgent"}""", HttpStatusCode.OK, jsonHeaders)
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
        val api = createApi(client)
        val agent = api.getAgent("a1")
        assertTrue(capturedUrl!!.contains("/v1/agents/a1"))
        assertEquals("MyAgent", agent.name)
    }

    @Test
    fun `createAgent sends POST with body`() = runTest {
        var capturedMethod: HttpMethod? = null
        val client = HttpClient(MockEngine { request ->
            capturedMethod = request.method
            respond("""{"id":"new-1","name":"NewAgent"}""", HttpStatusCode.OK, jsonHeaders)
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
        val api = createApi(client)
        val agent = api.createAgent(AgentCreateParams(name = "NewAgent"))
        assertEquals(HttpMethod.Post, capturedMethod)
        assertEquals("NewAgent", agent.name)
    }

    @Test
    fun `updateAgent sends PATCH`() = runTest {
        var capturedMethod: HttpMethod? = null
        val client = HttpClient(MockEngine { request ->
            capturedMethod = request.method
            respond("""{"id":"a1","name":"Updated"}""", HttpStatusCode.OK, jsonHeaders)
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
        val api = createApi(client)
        val agent = api.updateAgent("a1", AgentUpdateParams(name = "Updated"))
        assertEquals(HttpMethod.Patch, capturedMethod)
        assertEquals("Updated", agent.name)
    }

    @Test
    fun `deleteAgent sends DELETE`() = runTest {
        var capturedMethod: HttpMethod? = null
        val client = HttpClient(MockEngine { request ->
            capturedMethod = request.method
            respond("", HttpStatusCode.OK, jsonHeaders)
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
        val api = createApi(client)
        api.deleteAgent("a1")
        assertEquals(HttpMethod.Delete, capturedMethod)
    }

    @Test(expected = ApiException::class)
    fun `listAgents throws ApiException on 500`() = runTest {
        val client = HttpClient(MockEngine {
            respond("""{"error":"server error"}""", HttpStatusCode.InternalServerError, jsonHeaders)
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
        val api = createApi(client)
        api.listAgents()
    }

    @Test(expected = ApiException::class)
    fun `getAgent throws ApiException on 404`() = runTest {
        val client = HttpClient(MockEngine {
            respond("""{"error":"not found"}""", HttpStatusCode.NotFound, jsonHeaders)
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
        val api = createApi(client)
        api.getAgent("nonexistent")
    }

    @Test
    fun `listAgents passes limit and offset parameters`() = runTest {
        var capturedUrl: String? = null
        val client = HttpClient(MockEngine { request ->
            capturedUrl = request.url.toString()
            respond("[]", HttpStatusCode.OK, jsonHeaders)
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
        val api = createApi(client)
        api.listAgents(limit = 10, offset = 20)
        assertTrue(capturedUrl!!.contains("limit=10"))
        assertTrue(capturedUrl!!.contains("offset=20"))
    }

    @Test
    fun `importAgent sends multipart form with safety toggles`() = runTest {
        var capturedUrl: String? = null
        var capturedMethod: HttpMethod? = null
        val client = HttpClient(MockEngine { request ->
            capturedUrl = request.url.toString()
            capturedMethod = request.method
            respond("""{"agent_ids":["a2"]}""", HttpStatusCode.OK, jsonHeaders)
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
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
        val client = HttpClient(MockEngine { request ->
            capturedMethod = request.method
            capturedUrl = request.url.toString()
            respond("", HttpStatusCode.NoContent, jsonHeaders)
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
        val api = createApi(client)

        api.attachArchive("agent-1", "archive-1")

        assertEquals(HttpMethod.Patch, capturedMethod)
        assertTrue(capturedUrl!!.contains("/v1/agents/agent-1/archives/attach/archive-1"))
    }

    @Test
    fun `detachArchive sends PATCH to agent archive detach endpoint`() = runTest {
        var capturedMethod: HttpMethod? = null
        var capturedUrl: String? = null
        val client = HttpClient(MockEngine { request ->
            capturedMethod = request.method
            capturedUrl = request.url.toString()
            respond("", HttpStatusCode.NoContent, jsonHeaders)
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
        val api = createApi(client)

        api.detachArchive("agent-1", "archive-1")

        assertEquals(HttpMethod.Patch, capturedMethod)
        assertTrue(capturedUrl!!.contains("/v1/agents/agent-1/archives/detach/archive-1"))
    }
}
