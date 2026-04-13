package com.letta.mobile.data.api

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
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectApiTest : com.letta.mobile.testutil.TrackedMockClientTestSupport() {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun createApi(client: HttpClient, baseUrl: String = "http://test"): ProjectApi {
        val apiClient = mockk<LettaApiClient> {
            coEvery { getClient() } returns client
            every { getBaseUrl() } returns baseUrl
        }
        return ProjectApi(apiClient)
    }

    @Test
    fun `listProjects sends GET to registry endpoint`() = runTest {
        var capturedMethod: HttpMethod? = null
        var capturedUrl: String? = null
        val client = trackClient(HttpClient(MockEngine { request ->
            capturedMethod = request.method
            capturedUrl = request.url.toString()
            respond(
                """{"total":1,"projects":[{"identifier":"GRAPH","name":"Graphiti"}]}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false })
            }
        })

        val api = createApi(client)
        val result = api.listProjects()

        assertEquals(HttpMethod.Get, capturedMethod)
        assertTrue(capturedUrl!!.endsWith("/api/registry/projects"))
        assertEquals(1, result.total)
        assertEquals("GRAPH", result.projects.single().identifier)
    }

    @Test
    fun `getProject sends GET to project detail endpoint`() = runTest {
        var capturedUrl: String? = null
        val client = trackClient(HttpClient(MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(
                """{"identifier":"GRAPH","name":"Graphiti","letta_agent_id":"agent-1"}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false })
            }
        })

        val api = createApi(client, baseUrl = "http://test/")
        val result = api.getProject("GRAPH")

        assertTrue(capturedUrl!!.endsWith("/api/registry/projects/GRAPH"))
        assertEquals("agent-1", result.lettaAgentId)
    }

    @Test(expected = ApiException::class)
    fun `listProjects throws on non success`() = runTest {
        val client = trackClient(HttpClient(MockEngine {
            respond("error", HttpStatusCode.InternalServerError, jsonHeaders)
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false })
            }
        })

        createApi(client).listProjects()
    }
}
