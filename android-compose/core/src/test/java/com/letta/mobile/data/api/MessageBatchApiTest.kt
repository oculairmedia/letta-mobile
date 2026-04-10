package com.letta.mobile.data.api

import com.letta.mobile.data.model.BatchMessageRequest
import com.letta.mobile.data.model.CreateBatchMessagesRequest
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
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageBatchApiTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun createApi(handler: suspend (io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData)): MessageApi {
        val client = HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        val apiClient = mockk<LettaApiClient> {
            coEvery { getClient() } returns client
            every { getBaseUrl() } returns "http://test"
        }
        return MessageApi(apiClient)
    }

    @Test
    fun `createBatch sends POST`() = runTest {
        var method: HttpMethod? = null
        val api = createApi { req ->
            method = req.method
            respond("""{"id":"job-1","status":"running","job_type":"batch"}""", HttpStatusCode.OK, jsonHeaders)
        }

        api.createBatch(CreateBatchMessagesRequest(requests = listOf(BatchMessageRequest(agentId = "agent-1", messages = listOf(JsonPrimitive("hello"))))) )

        assertEquals(HttpMethod.Post, method)
    }

    @Test
    fun `listBatches sends GET`() = runTest {
        var url: String? = null
        val api = createApi { req -> url = req.url.toString(); respond("[]", HttpStatusCode.OK, jsonHeaders) }

        api.listBatches(limit = 20)

        assertTrue(url!!.contains("/v1/messages/batches"))
        assertTrue(url!!.contains("limit=20"))
    }

    @Test
    fun `listBatchMessages sends GET`() = runTest {
        var url: String? = null
        val api = createApi { req -> url = req.url.toString(); respond("""{"messages":[]}""", HttpStatusCode.OK, jsonHeaders) }

        api.listBatchMessages("job-1", agentId = "agent-1")

        assertTrue(url!!.contains("/v1/messages/batches/job-1/messages"))
        assertTrue(url!!.contains("agent_id=agent-1"))
    }
}
