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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RunApiTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun createApi(handler: suspend (io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.engine.mock.HttpResponseData)): RunApi {
        val client = HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        val apiClient = object : LettaApiClient(null!!) {
            override fun getClient() = client
            override fun getBaseUrl() = "http://test"
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
        val api = createApi { req ->
            method = req.method
            respond(
                """{"id":"r1","agent_id":"a1","status":"running"}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }

        api.retrieveRun("r1")

        assertEquals(HttpMethod.Get, method)
    }

    @Test(expected = ApiException::class)
    fun `listRuns throws on error`() = runTest {
        val api = createApi { respond("error", HttpStatusCode.InternalServerError, jsonHeaders) }
        api.listRuns()
    }
}
