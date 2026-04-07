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
class ConversationApiTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun createApi(handler: suspend (io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.engine.mock.HttpResponseData)): ConversationApi {
        val client = HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        val apiClient = object : LettaApiClient(null!!) {
            override fun getClient() = client
            override fun getBaseUrl() = "http://test"
        }
        return ConversationApi(apiClient)
    }

    @Test
    fun `listConversations sends GET`() = runTest {
        var method: HttpMethod? = null
        val api = createApi { req -> method = req.method; respond("[]", HttpStatusCode.OK, jsonHeaders) }
        api.listConversations()
        assertEquals(HttpMethod.Get, method)
    }

    @Test
    fun `listConversations passes agentId parameter`() = runTest {
        var url: String? = null
        val api = createApi { req -> url = req.url.toString(); respond("[]", HttpStatusCode.OK, jsonHeaders) }
        api.listConversations(agentId = "a1")
        assertTrue(url!!.contains("agent_id=a1"))
    }

    @Test
    fun `createConversation sends POST`() = runTest {
        var method: HttpMethod? = null
        val api = createApi { req ->
            method = req.method
            respond("""{"id":"c1","agent_id":"a1"}""", HttpStatusCode.OK, jsonHeaders)
        }
        api.createConversation(com.letta.mobile.data.model.ConversationCreateParams(agentId = "a1"))
        assertEquals(HttpMethod.Post, method)
    }

    @Test
    fun `deleteConversation sends DELETE`() = runTest {
        var method: HttpMethod? = null
        val api = createApi { req -> method = req.method; respond("", HttpStatusCode.OK, jsonHeaders) }
        api.deleteConversation("c1")
        assertEquals(HttpMethod.Delete, method)
    }

    @Test(expected = ApiException::class)
    fun `listConversations throws on error`() = runTest {
        val api = createApi { respond("error", HttpStatusCode.InternalServerError, jsonHeaders) }
        api.listConversations()
    }
}
