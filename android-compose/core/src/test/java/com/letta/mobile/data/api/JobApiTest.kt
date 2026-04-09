package com.letta.mobile.data.api

import com.letta.mobile.data.model.JobListParams
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
class JobApiTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun createApi(handler: suspend (io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData)): JobApi {
        val client = HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        val apiClient = mockk<LettaApiClient> {
            coEvery { getClient() } returns client
            every { getBaseUrl() } returns "http://test"
        }
        return JobApi(apiClient)
    }

    @Test
    fun `listJobs sends GET with query params`() = runTest {
        var url: String? = null
        val api = createApi { req ->
            url = req.url.toString()
            respond("[]", HttpStatusCode.OK, jsonHeaders)
        }

        api.listJobs(JobListParams(limit = 10, active = true, order = "desc"))

        assertTrue(url!!.contains("/v1/jobs/"))
        assertTrue(url!!.contains("limit=10"))
        assertTrue(url!!.contains("active=true"))
    }

    @Test
    fun `retrieveJob sends GET`() = runTest {
        var method: HttpMethod? = null
        val api = createApi { req ->
            method = req.method
            respond("""{"id":"job-1","status":"running"}""", HttpStatusCode.OK, jsonHeaders)
        }

        api.retrieveJob("job-1")

        assertEquals(HttpMethod.Get, method)
    }

    @Test
    fun `cancelJob sends PATCH`() = runTest {
        var method: HttpMethod? = null
        val api = createApi { req ->
            method = req.method
            respond("""{"id":"job-1","status":"cancelled"}""", HttpStatusCode.OK, jsonHeaders)
        }

        api.cancelJob("job-1")

        assertEquals(HttpMethod.Patch, method)
    }

    @Test
    fun `deleteJob sends DELETE`() = runTest {
        var method: HttpMethod? = null
        val api = createApi { req ->
            method = req.method
            respond("""{"id":"job-1","status":"completed"}""", HttpStatusCode.OK, jsonHeaders)
        }

        api.deleteJob("job-1")

        assertEquals(HttpMethod.Delete, method)
    }

    @Test
    fun `deleteJob handles no content response`() = runTest {
        val api = createApi { respond("", HttpStatusCode.NoContent, jsonHeaders) }

        val deleted = api.deleteJob("job-1")

        assertEquals("job-1", deleted.id)
    }

    @Test(expected = ApiException::class)
    fun `listJobs throws on error`() = runTest {
        val api = createApi { respond("error", HttpStatusCode.InternalServerError, jsonHeaders) }
        api.listJobs()
    }
}
