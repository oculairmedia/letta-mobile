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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentApiTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    private val json = Json { ignoreUnknownKeys = true }

    private fun createMockClient(handler: suspend (io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.engine.mock.HttpResponseData)): HttpClient {
        return HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
    }

    private fun createApi(client: HttpClient): AgentApi {
        val apiClient = object : LettaApiClient(null!!) {
            override fun getClient() = client
            override fun getBaseUrl() = "http://test"
        }
        return AgentApi(apiClient)
    }

    @Test
    fun `listAgents sends GET to correct endpoint`() = runTest {
        var capturedMethod: HttpMethod? = null
        var capturedUrl: String? = null
        val client = createMockClient { request ->
            capturedMethod = request.method
            capturedUrl = request.url.toString()
            respond("""[{"id":"1","name":"Agent1"}]""", HttpStatusCode.OK, jsonHeaders)
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
        val client = createMockClient { request ->
            capturedUrl = request.url.toString()
            respond("""{"id":"a1","name":"MyAgent"}""", HttpStatusCode.OK, jsonHeaders)
        }
        val api = createApi(client)
        val agent = api.getAgent("a1")
        assertTrue(capturedUrl!!.contains("/v1/agents/a1"))
        assertEquals("MyAgent", agent.name)
    }

    @Test
    fun `createAgent sends POST with body`() = runTest {
        var capturedMethod: HttpMethod? = null
        val client = createMockClient { request ->
            capturedMethod = request.method
            respond("""{"id":"new-1","name":"NewAgent"}""", HttpStatusCode.OK, jsonHeaders)
        }
        val api = createApi(client)
        val agent = api.createAgent(AgentCreateParams(name = "NewAgent"))
        assertEquals(HttpMethod.Post, capturedMethod)
        assertEquals("NewAgent", agent.name)
    }

    @Test
    fun `updateAgent sends PATCH`() = runTest {
        var capturedMethod: HttpMethod? = null
        val client = createMockClient { request ->
            capturedMethod = request.method
            respond("""{"id":"a1","name":"Updated"}""", HttpStatusCode.OK, jsonHeaders)
        }
        val api = createApi(client)
        val agent = api.updateAgent("a1", AgentUpdateParams(name = "Updated"))
        assertEquals(HttpMethod.Patch, capturedMethod)
        assertEquals("Updated", agent.name)
    }

    @Test
    fun `deleteAgent sends DELETE`() = runTest {
        var capturedMethod: HttpMethod? = null
        val client = createMockClient { request ->
            capturedMethod = request.method
            respond("", HttpStatusCode.OK, jsonHeaders)
        }
        val api = createApi(client)
        api.deleteAgent("a1")
        assertEquals(HttpMethod.Delete, capturedMethod)
    }

    @Test(expected = ApiException::class)
    fun `listAgents throws ApiException on 500`() = runTest {
        val client = createMockClient {
            respond("""{"error":"server error"}""", HttpStatusCode.InternalServerError, jsonHeaders)
        }
        val api = createApi(client)
        api.listAgents()
    }

    @Test(expected = ApiException::class)
    fun `getAgent throws ApiException on 404`() = runTest {
        val client = createMockClient {
            respond("""{"error":"not found"}""", HttpStatusCode.NotFound, jsonHeaders)
        }
        val api = createApi(client)
        api.getAgent("nonexistent")
    }

    @Test
    fun `listAgents passes limit and offset parameters`() = runTest {
        var capturedUrl: String? = null
        val client = createMockClient { request ->
            capturedUrl = request.url.toString()
            respond("[]", HttpStatusCode.OK, jsonHeaders)
        }
        val api = createApi(client)
        api.listAgents(limit = 10, offset = 20)
        assertTrue(capturedUrl!!.contains("limit=10"))
        assertTrue(capturedUrl!!.contains("offset=20"))
    }
}
