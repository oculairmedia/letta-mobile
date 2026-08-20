package com.letta.mobile.data.api

import com.letta.mobile.data.model.ArchiveCreateParams
import com.letta.mobile.data.model.ArchiveUpdateParams
import com.letta.mobile.data.model.EmbeddingConfig
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
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class ArchiveApiTest : com.letta.mobile.testutil.TrackedMockClientTestSupport() {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun createApi(handler: suspend (io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData)): ArchiveApi {
        val client = trackClient(HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) } })
        val apiClient = mockk<LettaApiClient> {
            coEvery { getClient() } returns client
            every { getBaseUrl() } returns "http://test"
            coEvery { session() } returns ApiSession(client, "http://test")
        }
        return ArchiveApi(apiClient)
    }

    @Test
    fun `listArchives sends GET`() = runTest {
        var url: String? = null
        val api = createApi { req -> url = req.url.toString(); respond("[]", HttpStatusCode.OK, jsonHeaders) }

        api.listArchives(limit = 10, name = "Docs")

        assertTrue(url!!.contains("/v1/archives/"))
        assertTrue(url!!.contains("limit=10"))
        assertTrue(url!!.contains("name=Docs"))
    }

    @Test
    fun `createArchive sends POST`() = runTest {
        var method: HttpMethod? = null
        val api = createApi { req ->
            method = req.method
            respond("""{"id":"archive-1","name":"Docs"}""", HttpStatusCode.OK, jsonHeaders)
        }

        api.createArchive(ArchiveCreateParams(name = "Docs", embeddingConfig = EmbeddingConfig(embeddingModel = "text-embedding-3-small")))

        assertEquals(HttpMethod.Post, method)
    }

    @Test
    fun `updateArchive sends PATCH`() = runTest {
        var method: HttpMethod? = null
        val api = createApi { req ->
            method = req.method
            respond("""{"id":"archive-1","name":"Docs","description":"Shared"}""", HttpStatusCode.OK, jsonHeaders)
        }

        api.updateArchive("archive-1", ArchiveUpdateParams(description = "Shared"))

        assertEquals(HttpMethod.Patch, method)
    }
}
