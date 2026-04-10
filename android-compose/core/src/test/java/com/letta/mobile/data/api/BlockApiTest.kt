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
class BlockApiTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun createApi(handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData): BlockApi {
        val client = HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        val apiClient = mockk<LettaApiClient> {
            coEvery { getClient() } returns client
            every { getBaseUrl() } returns "http://test"
        }
        return BlockApi(apiClient)
    }

    @Test
    fun `listBlocks sends GET with agentId`() = runTest {
        var url: String? = null
        val api = createApi { req -> url = req.url.toString(); respond("[]", HttpStatusCode.OK, jsonHeaders) }
        api.listBlocks("a1")
        assertTrue(url!!.contains("/v1/agents/a1/core-memory/blocks"))
    }

    @Test
    fun `createBlock sends POST`() = runTest {
        var method: HttpMethod? = null
        val api = createApi { req ->
            method = req.method
            respond("""{"id":"b1","label":"custom","value":"test"}""", HttpStatusCode.OK, jsonHeaders)
        }
        api.createBlock(com.letta.mobile.data.model.BlockCreateParams(label = "custom", value = "test"))
        assertEquals(HttpMethod.Post, method)
    }

    @Test
    fun `deleteBlock sends DELETE`() = runTest {
        var method: HttpMethod? = null
        val api = createApi { req -> method = req.method; respond("", HttpStatusCode.OK, jsonHeaders) }
        api.deleteBlock("b1")
        assertEquals(HttpMethod.Delete, method)
    }

    @Test
    fun `updateAgentBlock sends PATCH`() = runTest {
        var method: HttpMethod? = null
        var url: String? = null
        val api = createApi { req ->
            method = req.method
            url = req.url.toString()
            respond("""{"id":"b1","label":"persona","value":"updated"}""", HttpStatusCode.OK, jsonHeaders)
        }
        api.updateAgentBlock("a1", "persona", com.letta.mobile.data.model.BlockUpdateParams(value = "updated"))
        assertEquals(HttpMethod.Patch, method)
        assertTrue(url!!.contains("/v1/agents/a1/core-memory/blocks/persona"))
    }

    @Test
    fun `attachBlock sends PATCH to core memory attach endpoint`() = runTest {
        var method: HttpMethod? = null
        var url: String? = null
        val api = createApi { req ->
            method = req.method
            url = req.url.toString()
            respond("""{"id":"a1","name":"agent"}""", HttpStatusCode.OK, jsonHeaders)
        }

        api.attachBlock("a1", "b1")

        assertEquals(HttpMethod.Patch, method)
        assertTrue(url!!.contains("/v1/agents/a1/core-memory/blocks/attach/b1"))
    }

    @Test
    fun `detachBlock sends PATCH to core memory detach endpoint`() = runTest {
        var method: HttpMethod? = null
        var url: String? = null
        val api = createApi { req ->
            method = req.method
            url = req.url.toString()
            respond("""{"id":"a1","name":"agent"}""", HttpStatusCode.OK, jsonHeaders)
        }

        api.detachBlock("a1", "b1")

        assertEquals(HttpMethod.Patch, method)
        assertTrue(url!!.contains("/v1/agents/a1/core-memory/blocks/detach/b1"))
    }

    @Test
    fun `updateGlobalBlock sends PATCH to global block endpoint`() = runTest {
        var method: HttpMethod? = null
        var url: String? = null
        val api = createApi { req ->
            method = req.method
            url = req.url.toString()
            respond("""{"id":"b1","label":"persona","value":"updated"}""", HttpStatusCode.OK, jsonHeaders)
        }
        api.updateGlobalBlock("b1", com.letta.mobile.data.model.BlockUpdateParams(value = "updated"))
        assertEquals(HttpMethod.Patch, method)
        assertTrue(url!!.contains("/v1/blocks/b1"))
    }

    @Test(expected = ApiException::class)
    fun `listBlocks throws on error`() = runTest {
        val api = createApi { respond("error", HttpStatusCode.InternalServerError, jsonHeaders) }
        api.listBlocks("a1")
    }
}
