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
class StepApiTest : com.letta.mobile.testutil.TrackedMockClientTestSupport() {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun createApi(handler: suspend (io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData)): StepApi {
        val client = trackClient(HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) } })
        val apiClient = mockk<LettaApiClient> {
            coEvery { getClient() } returns client
            every { getBaseUrl() } returns "http://test"
            coEvery { session() } returns ApiSession(client, "http://test")
        }
        return StepApi(apiClient)
    }

    @Test
    fun `listSteps sends GET with filters`() = runTest {
        var url: String? = null
        val api = createApi { req ->
            url = req.url.toString()
            respond("[]", HttpStatusCode.OK, jsonHeaders)
        }

        api.listSteps(com.letta.mobile.data.model.StepListParams(agentId = "agent-1", hasFeedback = true, traceIds = listOf("trace-1"), limit = 25))

        assertTrue(url!!.contains("/v1/steps/"))
        assertTrue(url!!.contains("agent_id=agent-1"))
        assertTrue(url!!.contains("has_feedback=true"))
        assertTrue(url!!.contains("trace_ids=trace-1"))
        assertTrue(url!!.contains("limit=25"))
    }

    @Test
    fun `retrieveStep sends GET`() = runTest {
        var url: String? = null
        val api = createApi { req ->
            url = req.url.toString()
            respond("""{"id":"step-1","agent_id":"agent-1","status":"completed"}""", HttpStatusCode.OK, jsonHeaders)
        }

        val result = api.retrieveStep("step-1")

        assertTrue(url!!.contains("/v1/steps/step-1"))
        assertEquals("agent-1", result.agentId)
    }

    @Test
    fun `retrieveStepMetrics sends GET`() = runTest {
        var url: String? = null
        val api = createApi { req ->
            url = req.url.toString()
            respond("""{"id":"step-1","step_start_ns":100,"llm_request_ns":250,"step_ns":500}""", HttpStatusCode.OK, jsonHeaders)
        }

        val result = api.retrieveStepMetrics("step-1")

        assertTrue(url!!.contains("/v1/steps/step-1/metrics"))
        assertEquals(250L, result.llmRequestNs)
    }

    @Test
    fun `retrieveStepTrace sends GET`() = runTest {
        var url: String? = null
        val api = createApi { req ->
            url = req.url.toString()
            respond("""{"id":"provider_trace-1","step_id":"step-1","request_json":{"model":"gpt-4"},"response_json":{"finish_reason":"stop"},"created_at":"2026-04-10T12:00:00Z"}""", HttpStatusCode.OK, jsonHeaders)
        }

        val result = api.retrieveStepTrace("step-1")

        assertTrue(url!!.contains("/v1/steps/step-1/trace"))
        assertEquals("step-1", result?.stepId)
    }

    @Test
    fun `listStepMessages sends GET`() = runTest {
        var url: String? = null
        val api = createApi { req ->
            url = req.url.toString()
            respond("[]", HttpStatusCode.OK, jsonHeaders)
        }

        api.listStepMessages("step-1", limit = 10, order = "asc")

        assertTrue(url!!.contains("/v1/steps/step-1/messages"))
        assertTrue(url!!.contains("limit=10"))
        assertTrue(url!!.contains("order=asc"))
        assertTrue(url!!.contains("order_by=created_at"))
    }

    @Test
    fun `updateStepFeedback sends PATCH`() = runTest {
        var method: HttpMethod? = null
        var url: String? = null
        val api = createApi { req ->
            method = req.method
            url = req.url.toString()
            respond("""{"id":"step-1","feedback":"positive","tags":["verified"]}""", HttpStatusCode.OK, jsonHeaders)
        }

        val result = api.updateStepFeedback("step-1", com.letta.mobile.data.model.StepFeedbackUpdateParams(feedback = "positive", tags = listOf("verified")))

        assertEquals(HttpMethod.Patch, method)
        assertTrue(url!!.contains("/v1/steps/step-1/feedback"))
        assertEquals("positive", result.feedback)
    }
}
