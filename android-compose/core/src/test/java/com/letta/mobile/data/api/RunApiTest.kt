package com.letta.mobile.data.api

import com.letta.mobile.data.model.RunListParams
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

@OptIn(ExperimentalCoroutinesApi::class)
class RunApiTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun createApi(handler: suspend (io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData)): RunApi {
        val client = HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        val apiClient = mockk<LettaApiClient> {
            coEvery { getClient() } returns client
            every { getBaseUrl() } returns "http://test"
        }
        return RunApi(apiClient)
    }

    @Test
    fun `listRuns sends GET with query params`() = runTest {
        var url: String? = null
        val api = createApi { req ->
            url = req.url.toString()
            respond("[]", HttpStatusCode.OK, jsonHeaders)
        }

        api.listRuns(RunListParams(agentId = "a1", limit = 10, active = true))

        assertTrue(url!!.contains("/v1/runs/"))
        assertTrue(url!!.contains("agent_id=a1"))
        assertTrue(url!!.contains("limit=10"))
    }

    @Test
    fun `retrieveRun sends GET`() = runTest {
        var method: HttpMethod? = null
        var url: String? = null
        val api = createApi { req ->
            method = req.method
            url = req.url.toString()
            respond(
                """{"id":"r1","agent_id":"a1","status":"running"}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }

        api.retrieveRun("r1")

        assertEquals(HttpMethod.Get, method)
        assertTrue(url!!.contains("/v1/runs/r1"))
    }

    @Test
    fun `listRunMessages sends GET to run messages endpoint`() = runTest {
        var url: String? = null
        val api = createApi { req ->
            url = req.url.toString()
            respond("[]", HttpStatusCode.OK, jsonHeaders)
        }

        api.listRunMessages("r1", limit = 25, order = "asc")

        assertTrue(url!!.contains("/v1/runs/r1/messages"))
        assertTrue(url!!.contains("limit=25"))
        assertTrue(url!!.contains("order=asc"))
    }

    @Test
    fun `retrieveRunUsage sends GET to usage endpoint`() = runTest {
        var url: String? = null
        val api = createApi { req ->
            url = req.url.toString()
            respond("""{"prompt_tokens":12,"completion_tokens":5,"total_tokens":17}""", HttpStatusCode.OK, jsonHeaders)
        }

        api.retrieveRunUsage("r1")

        assertTrue(url!!.contains("/v1/runs/r1/usage"))
    }

    @Test
    fun `retrieveRunMetrics sends GET to metrics endpoint`() = runTest {
        var url: String? = null
        val api = createApi { req ->
            url = req.url.toString()
            respond("""{"id":"r1","num_steps":2,"run_ns":1000}""", HttpStatusCode.OK, jsonHeaders)
        }

        api.retrieveRunMetrics("r1")

        assertTrue(url!!.contains("/v1/runs/r1/metrics"))
    }

    @Test
    fun `listRunSteps sends GET to steps endpoint`() = runTest {
        var url: String? = null
        val api = createApi { req ->
            url = req.url.toString()
            respond("[]", HttpStatusCode.OK, jsonHeaders)
        }

        api.listRunSteps("r1", limit = 10, order = "desc")

        assertTrue(url!!.contains("/v1/runs/r1/steps"))
        assertTrue(url!!.contains("limit=10"))
        assertTrue(url!!.contains("order=desc"))
    }

    @Test
    fun `cancelRun sends POST to agent cancel route`() = runTest {
        var method: HttpMethod? = null
        var url: String? = null
        val api = createApi { req ->
            method = req.method
            url = req.url.toString()
            respond("""{"r1":"cancelled"}""", HttpStatusCode.OK, jsonHeaders)
        }

        api.cancelRun("a1", "r1")

        assertEquals(HttpMethod.Post, method)
        assertTrue(url!!.contains("/v1/agents/a1/messages/cancel"))
    }

    @Test
    fun `deleteRun sends DELETE`() = runTest {
        var method: HttpMethod? = null
        var url: String? = null
        val api = createApi { req ->
            method = req.method
            url = req.url.toString()
            respond("", HttpStatusCode.NoContent, jsonHeaders)
        }

        api.deleteRun("r1")

        assertEquals(HttpMethod.Delete, method)
        assertTrue(url!!.contains("/v1/runs/r1"))
    }

    @Test(expected = ApiException::class)
    fun `listRuns throws on error`() = runTest {
        val api = createApi { respond("error", HttpStatusCode.InternalServerError, jsonHeaders) }
        api.listRuns()
    }
}
