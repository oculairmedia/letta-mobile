package com.letta.mobile.data.api

import com.letta.mobile.data.model.FolderCreateParams
import com.letta.mobile.data.model.FolderUpdateParams
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
class FolderApiTest : com.letta.mobile.testutil.TrackedMockClientTestSupport() {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun createApi(handler: suspend (io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData)): FolderApi {
        val client = trackClient(HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) } })
        val apiClient = mockk<LettaApiClient> {
            coEvery { getClient() } returns client
            every { getBaseUrl() } returns "http://test"
            coEvery { session() } returns ApiSession(client, "http://test")
        }
        return FolderApi(apiClient)
    }

    @Test
    fun `countFolders sends GET`() = runTest {
        var url: String? = null
        val api = createApi { req -> url = req.url.toString(); respond("1", HttpStatusCode.OK, jsonHeaders) }

        api.countFolders()

        assertTrue(url!!.contains("/v1/folders/count"))
    }

    @Test
    fun `createFolder sends POST`() = runTest {
        var method: HttpMethod? = null
        val api = createApi { req ->
            method = req.method
            respond("""{"id":"source-1","name":"Knowledge"}""", HttpStatusCode.OK, jsonHeaders)
        }

        api.createFolder(FolderCreateParams(name = "Knowledge"))

        assertEquals(HttpMethod.Post, method)
    }

    @Test
    fun `updateFolder sends PATCH`() = runTest {
        var method: HttpMethod? = null
        val api = createApi { req ->
            method = req.method
            respond("""{"id":"source-1","name":"Knowledge","description":"Docs"}""", HttpStatusCode.OK, jsonHeaders)
        }

        api.updateFolder("source-1", FolderUpdateParams(description = "Docs"))

        assertEquals(HttpMethod.Patch, method)
    }
}
