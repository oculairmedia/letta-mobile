package com.letta.mobile.data.api

import com.letta.mobile.data.model.GroupCreateParams
import com.letta.mobile.data.model.GroupUpdateParams
import com.letta.mobile.data.model.MessageCreateRequest
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
class GroupApiTest : com.letta.mobile.testutil.TrackedMockClientTestSupport() {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun createApi(handler: suspend (io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData)): GroupApi {
        val client = trackClient(HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) } })
        val apiClient = mockk<LettaApiClient> {
            coEvery { getClient() } returns client
            every { getBaseUrl() } returns "http://test"
            coEvery { session() } returns ApiSession(client, "http://test")
        }
        return GroupApi(apiClient)
    }

    @Test
    fun `listGroups sends GET with manager type`() = runTest {
        var url: String? = null
        val api = createApi { req -> url = req.url.toString(); respond("[]", HttpStatusCode.OK, jsonHeaders) }

        api.listGroups(managerType = "round_robin")

        assertTrue(url!!.contains("/v1/groups/"))
        assertTrue(url!!.contains("manager_type=round_robin"))
    }

    @Test
    fun `createGroup sends POST`() = runTest {
        var method: HttpMethod? = null
        val api = createApi { req ->
            method = req.method
            respond("""{"id":"group-1","manager_type":"round_robin","agent_ids":["agent-1"],"description":"Test"}""", HttpStatusCode.OK, jsonHeaders)
        }

        api.createGroup(GroupCreateParams(agentIds = listOf("agent-1"), description = "Test"))

        assertEquals(HttpMethod.Post, method)
    }

    @Test
    fun `sendGroupMessage sends POST`() = runTest {
        var method: HttpMethod? = null
        val api = createApi { req ->
            method = req.method
            respond("""{"messages":[],"stop_reason":{"stop_reason":"completed"},"usage":{}}""", HttpStatusCode.OK, jsonHeaders)
        }

        api.sendGroupMessage("group-1", MessageCreateRequest(input = "hello"))

        assertEquals(HttpMethod.Post, method)
    }
}
