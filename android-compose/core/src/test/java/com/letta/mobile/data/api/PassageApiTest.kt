package com.letta.mobile.data.api

import com.letta.mobile.data.model.PassageCreateParams
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
class PassageApiTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun createApi(handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData): PassageApi {
        val client = HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        val apiClient = mockk<LettaApiClient> {
            coEvery { getClient() } returns client
            every { getBaseUrl() } returns "http://test"
        }
        return PassageApi(apiClient)
    }

    @Test
    fun `listPassages sends GET to archival memory endpoint`() = runTest {
        var url: String? = null
        val api = createApi { req ->
            url = req.url.toString()
            respond("[]", HttpStatusCode.OK, jsonHeaders)
        }

        api.listPassages("a1", limit = 25, after = "p0")

        assertTrue(url!!.contains("/v1/agents/a1/archival-memory"))
        assertTrue(url!!.contains("limit=25"))
        assertTrue(url!!.contains("after=p0"))
    }

    @Test
    fun `createPassage sends POST to archival memory endpoint and returns the single created passage`() = runTest {
        var method: HttpMethod? = null
        var url: String? = null
        val api = createApi { req ->
            method = req.method
            url = req.url.toString()
            respond("""[{"id":"p1","text":"hello","agent_id":"a1"}]""", HttpStatusCode.OK, jsonHeaders)
        }

        val passage = api.createPassage("a1", PassageCreateParams(text = "hello"))

        assertEquals(HttpMethod.Post, method)
        assertTrue(url!!.contains("/v1/agents/a1/archival-memory"))
        assertEquals("p1", passage.id)
    }

    @Test(expected = ApiException::class)
    fun `createPassage throws when archival memory create returns empty list`() = runTest {
        val api = createApi { respond("[]", HttpStatusCode.OK, jsonHeaders) }

        api.createPassage("a1", PassageCreateParams(text = "hello"))
    }

    @Test(expected = ApiException::class)
    fun `createPassage throws when archival memory create returns multiple passages`() = runTest {
        val api = createApi {
            respond(
                """[{"id":"p1","text":"hello"},{"id":"p2","text":"hello again"}]""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }

        api.createPassage("a1", PassageCreateParams(text = "hello"))
    }

    @Test
    fun `deletePassage sends DELETE to archival memory endpoint`() = runTest {
        var method: HttpMethod? = null
        var url: String? = null
        val api = createApi { req ->
            method = req.method
            url = req.url.toString()
            respond("{}", HttpStatusCode.OK, jsonHeaders)
        }

        api.deletePassage("a1", "p1")

        assertEquals(HttpMethod.Delete, method)
        assertTrue(url!!.contains("/v1/agents/a1/archival-memory/p1"))
    }

    @Test
    fun `searchArchival reuses archival memory list query parameter`() = runTest {
        var url: String? = null
        val api = createApi { req ->
            url = req.url.toString()
            respond("[]", HttpStatusCode.OK, jsonHeaders)
        }

        api.searchArchival("a1", query = "memory", limit = 10)

        assertTrue(url!!.contains("/v1/agents/a1/archival-memory"))
        assertTrue(url!!.contains("search=memory"))
        assertTrue(url!!.contains("limit=10"))
    }

    @Test(expected = ApiException::class)
    fun `listPassages throws on error`() = runTest {
        val api = createApi { respond("error", HttpStatusCode.InternalServerError, jsonHeaders) }

        api.listPassages("a1")
    }
}
