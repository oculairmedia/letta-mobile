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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolApiTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun createApi(handler: suspend (io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.engine.mock.HttpResponseData)): ToolApi {
        val client = HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        val apiClient = object : LettaApiClient(null!!) {
            override fun getClient() = client
            override fun getBaseUrl() = "http://test"
        }
        return ToolApi(apiClient)
    }

    @Test
    fun `listTools sends GET`() = runTest {
        var method: HttpMethod? = null
        val api = createApi { req -> method = req.method; respond("[]", HttpStatusCode.OK, jsonHeaders) }
        api.listTools()
        assertEquals(HttpMethod.Get, method)
    }

    @Test
    fun `attachTool sends POST`() = runTest {
        var method: HttpMethod? = null
        val api = createApi { req -> method = req.method; respond("", HttpStatusCode.OK, jsonHeaders) }
        api.attachTool("a1", "t1")
        assertEquals(HttpMethod.Post, method)
    }

    @Test
    fun `detachTool sends POST`() = runTest {
        var method: HttpMethod? = null
        val api = createApi { req -> method = req.method; respond("", HttpStatusCode.OK, jsonHeaders) }
        api.detachTool("a1", "t1")
        assertEquals(HttpMethod.Post, method)
    }

    @Test(expected = ApiException::class)
    fun `listTools throws on error`() = runTest {
        val api = createApi { respond("error", HttpStatusCode.InternalServerError, jsonHeaders) }
        api.listTools()
    }
}
