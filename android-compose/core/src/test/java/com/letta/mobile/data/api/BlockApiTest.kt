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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BlockApiTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun createApi(handler: suspend (io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.engine.mock.HttpResponseData)): BlockApi {
        val client = HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        val apiClient = object : LettaApiClient(null!!) {
            override fun getClient() = client
            override fun getBaseUrl() = "http://test"
        }
        return BlockApi(apiClient)
    }

    @Test
    fun `listBlocks sends GET with agentId`() = runTest {
        var url: String? = null
        val api = createApi { req -> url = req.url.toString(); respond("[]", HttpStatusCode.OK, jsonHeaders) }
        api.listBlocks("a1")
        assertTrue(url!!.contains("/v1/agents/a1/blocks"))
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
    fun `updateBlock sends PATCH`() = runTest {
        var method: HttpMethod? = null
        val api = createApi { req ->
            method = req.method
            respond("""{"id":"b1","label":"persona","value":"updated"}""", HttpStatusCode.OK, jsonHeaders)
        }
        api.updateBlock("a1", "persona", com.letta.mobile.data.model.BlockUpdateParams(value = "updated"))
        assertEquals(HttpMethod.Patch, method)
    }

    @Test(expected = ApiException::class)
    fun `listBlocks throws on error`() = runTest {
        val api = createApi { respond("error", HttpStatusCode.InternalServerError, jsonHeaders) }
        api.listBlocks("a1")
    }
}
