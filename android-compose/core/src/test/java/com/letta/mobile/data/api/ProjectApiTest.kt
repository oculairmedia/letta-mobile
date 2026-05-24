package com.letta.mobile.data.api

import com.letta.mobile.data.model.AgentId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
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
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class ProjectApiTest : com.letta.mobile.testutil.TrackedMockClientTestSupport() {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun createApi(client: HttpClient, baseUrl: String = "http://test"): ProjectApi {
        val apiClient = mockk<LettaApiClient> {
            coEvery { getClient() } returns client
            every { getBaseUrl() } returns baseUrl
            coEvery { session() } returns ApiSession(client, baseUrl)
        }
        return ProjectApi(apiClient)
    }

    @Test
    fun `listProjects sends GET to projects endpoint`() = runTest {
        var capturedMethod: HttpMethod? = null
        var capturedUrl: String? = null
        val client = trackClient(HttpClient(MockEngine { request ->
            capturedMethod = request.method
            capturedUrl = request.url.toString()
            respond(
                """{"projects":[{"id":"GRAPH","identifier":"GRAPH","name":"Graphiti","repo":{"remote_url":"https://github.com/example/graphiti.git","filesystem_path":"/opt/stacks/graphiti"},"tracker":{"summary":{"total_known":12,"ready":3}}}]}""",
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
        assertTrue(capturedUrl!!.endsWith("/api/projects"))
        assertEquals(1, result.projects.size)
        assertEquals("GRAPH", result.projects.single().identifier)
        assertEquals(3, result.projects.single().tracker?.summary?.ready)
    }

    @Test
    fun `getProject sends GET to project detail endpoint`() = runTest {
        var capturedUrl: String? = null
        val client = trackClient(HttpClient(MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(
                """{"project":{"id":"GRAPH","identifier":"GRAPH","name":"Graphiti","letta_agent_id":"agent-1"}}""",
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

        assertTrue(capturedUrl!!.endsWith("/api/projects/GRAPH"))
        assertEquals(AgentId("agent-1"), result.lettaAgentId)
    }

    @Test
    fun `createProject sends POST to registry endpoint`() = runTest {
        var capturedMethod: HttpMethod? = null
        var capturedUrl: String? = null
        val client = trackClient(HttpClient(MockEngine { request: HttpRequestData ->
            capturedMethod = request.method
            capturedUrl = request.url.toString()
            respond(
                """{"project":{"identifier":"GRAPHITI","name":"Graphiti","filesystem_path":"/opt/stacks/graphiti","git_url":"https://github.com/example/graphiti.git"}}""",
                HttpStatusCode.Created,
                jsonHeaders,
            )
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false })
            }
        })

        val api = createApi(client)
        val result = api.createProject(
            ProjectCreateRequest(
                name = "Graphiti",
                filesystemPath = "/opt/stacks/graphiti",
                gitUrl = "https://github.com/example/graphiti.git",
            )
        )

        assertEquals(HttpMethod.Post, capturedMethod)
        assertTrue(capturedUrl!!.endsWith("/api/registry/projects"))
        assertEquals("GRAPHITI", result.identifier)
        assertEquals("/opt/stacks/graphiti", result.filesystemPath)
    }

    @Test
    fun `updateProject sends PATCH to registry endpoint`() = runTest {
        var capturedMethod: HttpMethod? = null
        var capturedUrl: String? = null
        val client = trackClient(HttpClient(MockEngine { request: HttpRequestData ->
            capturedMethod = request.method
            capturedUrl = request.url.toString()
            respond(
                """{"project":{"identifier":"GRAPH","name":"Graphiti","filesystem_path":"/opt/stacks/graphiti","git_url":"https://github.com/example/graphiti.git"}}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false })
            }
        })

        val api = createApi(client)
        val result = api.updateProject(
            identifier = "GRAPH",
            request = ProjectUpdateRequest(
                filesystemPath = "/opt/stacks/graphiti",
                gitUrl = "https://github.com/example/graphiti.git",
            )
        )

        assertEquals(HttpMethod.Patch, capturedMethod)
        assertTrue(capturedUrl!!.endsWith("/api/registry/projects/GRAPH"))
        assertEquals("https://github.com/example/graphiti.git", result.gitUrl)
    }

    @Test
    fun `archiveProject sends PATCH with archived status`() = runTest {
        var capturedMethod: HttpMethod? = null
        var capturedUrl: String? = null
        val client = trackClient(HttpClient(MockEngine { request: HttpRequestData ->
            capturedMethod = request.method
            capturedUrl = request.url.toString()
            respond(
                """{"project":{"identifier":"GRAPH","name":"Graphiti","status":"archived","filesystem_path":"/opt/stacks/graphiti"}}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false })
            }
        })

        val api = createApi(client)
        val result = api.archiveProject("GRAPH")

        assertEquals(HttpMethod.Patch, capturedMethod)
        assertTrue(capturedUrl!!.endsWith("/api/registry/projects/GRAPH"))
        assertEquals("archived", result.status)
    }

    @Test
    fun `deleteProject sends DELETE to registry endpoint`() = runTest {
        var capturedMethod: HttpMethod? = null
        var capturedUrl: String? = null
        val client = trackClient(HttpClient(MockEngine { request: HttpRequestData ->
            capturedMethod = request.method
            capturedUrl = request.url.toString()
            respond(
                """{"message":"Project deleted","identifier":"GRAPH"}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false })
            }
        })

        val api = createApi(client)
        api.deleteProject("GRAPH")

        assertEquals(HttpMethod.Delete, capturedMethod)
        assertTrue(capturedUrl!!.endsWith("/api/registry/projects/GRAPH"))
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
