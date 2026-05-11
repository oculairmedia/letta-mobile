package com.letta.mobile.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
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
class ProjectWorkApiTest : com.letta.mobile.testutil.TrackedMockClientTestSupport() {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun createApi(client: HttpClient, baseUrl: String = "http://test"): ProjectWorkApi {
        val apiClient = mockk<LettaApiClient> {
            coEvery { getClient() } returns client
            every { getBaseUrl() } returns baseUrl
        }
        return ProjectWorkApi(apiClient)
    }

    @Test
    fun `getReadyWork sends GET to project ready-work endpoint`() = runTest {
        var capturedUrl: String? = null
        val client = trackClient(HttpClient(MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(readyWorkJson, HttpStatusCode.OK, jsonHeaders)
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false })
            }
        })

        val result = createApi(client).getReadyWork("letta-mobile", limit = 10, cursor = "next")

        assertTrue(capturedUrl!!.startsWith("http://test/api/projects/letta-mobile/ready-work"))
        assertTrue(capturedUrl.contains("limit=10"))
        assertTrue(capturedUrl.contains("cursor=next"))
        assertEquals("letta-mobile-qmbg", result.readyWork.single().id)
    }

    @Test
    fun `getIssue parses issue detail wrapper`() = runTest {
        val client = trackClient(HttpClient(MockEngine {
            respond(issueDetailJson, HttpStatusCode.OK, jsonHeaders)
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false })
            }
        })

        val result = createApi(client).getIssue("letta-mobile-qmbg")

        assertEquals("Define Android Beads data contract", result.issue.title)
        assertEquals("Full description", result.issue.description)
        assertEquals(false, result.issue.metadata?.deletedFromHuly)
    }

    @Test
    fun `claimIssue sends concurrency and idempotency headers`() = runTest {
        var capturedMethod: HttpMethod? = null
        var capturedUrl: String? = null
        var capturedIfMatch: String? = null
        var capturedIdempotencyKey: String? = null
        val client = trackClient(HttpClient(MockEngine { request: HttpRequestData ->
            capturedMethod = request.method
            capturedUrl = request.url.toString()
            capturedIfMatch = request.headers[HttpHeaders.IfMatch]
            capturedIdempotencyKey = request.headers["Idempotency-Key"]
            respond(mutationJson, HttpStatusCode.OK, jsonHeaders)
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false })
            }
        })

        val result = createApi(client).claimIssue(
            issueId = "letta-mobile-qmbg",
            request = ProjectIssueClaimRequest("emmanuel"),
            headers = ProjectIssueMutationHeaders(
                ifMatch = "letta-mobile-qmbg:1",
                idempotencyKey = "android-queue-42",
            ),
        )

        assertEquals(HttpMethod.Post, capturedMethod)
        assertTrue(capturedUrl!!.endsWith("/api/issues/letta-mobile-qmbg/claim"))
        assertEquals("letta-mobile-qmbg:1", capturedIfMatch)
        assertEquals("android-queue-42", capturedIdempotencyKey)
        assertEquals(false, result.idempotentReplay)
    }

    @Test(expected = ProjectIssueConflictException::class)
    fun `mutation conflict throws structured conflict exception`() = runTest {
        val client = trackClient(HttpClient(MockEngine {
            respond(conflictJson, HttpStatusCode.Conflict, jsonHeaders)
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false })
            }
        })

        createApi(client).closeIssue(
            issueId = "letta-mobile-qmbg",
            request = ProjectIssueReasonRequest("done"),
            headers = ProjectIssueMutationHeaders("stale", "android-queue-43"),
        )
    }

    private val readyWorkJson = """
        {
          "project_id": "letta-mobile",
          "ready_work": [{
            "id": "letta-mobile-qmbg",
            "project_id": "letta-mobile",
            "provider": "beads",
            "title": "Define Android Beads data contract",
            "type": "task",
            "priority": "high",
            "status": "open",
            "ready": true,
            "updated_at": "2026-05-10T12:34:56.000Z",
            "etag": "letta-mobile-qmbg:1"
          }],
          "page": {"next_cursor": null, "has_more": false, "total_known": 1},
          "data_freshness": {"status": "available", "error": null, "is_stale": false}
        }
    """.trimIndent()

    private val issueDetailJson = """
        {
          "issue": {
            "id": "letta-mobile-qmbg",
            "project_id": "letta-mobile",
            "title": "Define Android Beads data contract",
            "status": "open",
            "description": "Full description",
            "metadata": {"deleted_from_huly": false, "deleted_from_vibe": false}
          },
          "timestamp": "2026-05-10T20:00:00.000Z"
        }
    """.trimIndent()

    private val mutationJson = """
        {
          "issue_id": "letta-mobile-qmbg",
          "action": "claim",
          "applied": true,
          "idempotent_replay": false,
          "issue": {"id": "letta-mobile-qmbg", "assignee": "emmanuel", "etag": "letta-mobile-qmbg:2"},
          "timestamp": "2026-05-10T20:00:00.000Z"
        }
    """.trimIndent()

    private val conflictJson = """
        {
          "error": "Issue conflict",
          "statusCode": 409,
          "conflict": {"reason": "etag_mismatch", "expected": "stale", "current": "letta-mobile-qmbg:2", "issueId": "letta-mobile-qmbg"},
          "timestamp": "2026-05-10T20:00:00.000Z"
        }
    """.trimIndent()
}
