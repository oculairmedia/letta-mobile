package com.letta.mobile.data.api

import com.letta.mobile.data.model.ProjectIssueAnalyticsParams
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
            coEvery { session() } returns ApiSession(client, baseUrl)
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
    fun `getIssueAnalytics sends analytics query and parses flexible priority`() = runTest {
        var capturedUrl: String? = null
        val client = trackClient(HttpClient(MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(issueAnalyticsJson, HttpStatusCode.OK, jsonHeaders)
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false })
            }
        })

        val result = createApi(client).getIssueAnalytics(
            projectId = "letta-mobile",
            params = ProjectIssueAnalyticsParams(
                rangeStart = "2026-05-01T00:00:00-04:00",
                rangeEnd = "2026-05-12T00:00:00-04:00",
                granularity = "day",
                timezone = "America/Toronto",
                statusFilter = "closed",
                typeFilter = "feature",
                priorityFilter = "high",
                assigneeFilter = "emmanuel",
                labelFilter = "android",
                timelineLimit = 20,
            ),
        )

        assertTrue(capturedUrl!!.startsWith("http://test/api/projects/letta-mobile/issue-analytics"))
        assertTrue(capturedUrl.contains("rangeStart=2026-05-01T00%3A00%3A00-04%3A00"))
        assertTrue(capturedUrl.contains("rangeEnd=2026-05-12T00%3A00%3A00-04%3A00"))
        assertTrue(capturedUrl.contains("granularity=day"))
        assertTrue(capturedUrl.contains("timezone=America%2FToronto"))
        assertTrue(capturedUrl.contains("statusFilter=closed"))
        assertTrue(capturedUrl.contains("typeFilter=feature"))
        assertTrue(capturedUrl.contains("priorityFilter=high"))
        assertTrue(capturedUrl.contains("assigneeFilter=emmanuel"))
        assertTrue(capturedUrl.contains("labelFilter=android"))
        assertTrue(capturedUrl.contains("timelineLimit=20"))
        assertEquals(3, result.createdBuckets.single().createdCount)
        assertEquals("2", result.completedTimeline.single().priority)
        assertEquals("issue_close_metadata", result.completionSource)
        assertEquals(true, result.isPartial)
    }

    @Test
    fun `getIssueAnalytics parses snake case analytics payload`() = runTest {
        val client = trackClient(HttpClient(MockEngine {
            respond(issueAnalyticsSnakeCaseJson, HttpStatusCode.OK, jsonHeaders)
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false })
            }
        })

        val result = createApi(client).getIssueAnalytics(
            projectId = "letta-mobile",
            params = ProjectIssueAnalyticsParams(
                rangeStart = "2026-05-01T00:00:00-04:00",
                rangeEnd = "2026-05-12T00:00:00-04:00",
                granularity = "day",
                timezone = "America/Toronto",
                timelineLimit = 20,
            ),
        )

        assertEquals("letta-mobile", result.projectId)
        assertEquals(3, result.createdBuckets.single().createdCount)
        assertEquals(1, result.completedBuckets.single().completedCount)
        assertEquals("letta-mobile-123", result.completedTimeline.single().issueId)
        assertEquals("2026-05-10T18:24:11Z", result.completedTimeline.single().completedAt)
        assertEquals("2", result.completedTimeline.single().priority)
        assertEquals(3, result.summary.totalCreatedInRange)
        assertEquals("issue_close_metadata", result.completionSource)
        assertEquals(true, result.isPartial)
        assertEquals(false, result.timelinePage.hasMore)
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

    private val issueAnalyticsJson = """
        {
          "schema_version": 1,
          "projectId": "letta-mobile",
          "rangeStart": "2026-05-01T00:00:00-04:00",
          "rangeEnd": "2026-05-12T00:00:00-04:00",
          "granularity": "day",
          "timezone": "America/Toronto",
          "createdBuckets": [{
            "bucketStart": "2026-05-01T00:00:00-04:00",
            "bucketEnd": "2026-05-02T00:00:00-04:00",
            "label": "May 1",
            "createdCount": 3
          }],
          "completedBuckets": [{
            "bucketStart": "2026-05-01T00:00:00-04:00",
            "bucketEnd": "2026-05-02T00:00:00-04:00",
            "label": "May 1",
            "completedCount": 1
          }],
          "completedTimeline": [{
            "issueId": "letta-mobile-123",
            "title": "Ship analytics",
            "status": "closed",
            "statusLabel": "Closed",
            "completedAt": "2026-05-10T18:24:11Z",
            "createdAt": "2026-05-08T12:04:00Z",
            "updatedAt": "2026-05-10T18:24:11Z",
            "priority": 2,
            "type": "feature",
            "assignee": "emmanuel",
            "labels": ["android"],
            "completedBy": "emmanuel",
            "completionReason": "done"
          }],
          "summary": {
            "openCount": 4,
            "inProgressCount": 1,
            "completedCount": 5,
            "blockedCount": 2,
            "readyCount": 3,
            "totalCreatedInRange": 3,
            "totalCompletedInRange": 1
          },
          "nextTimelineCursor": null,
          "timelinePage": {"limit": 20, "has_more": false, "total_known": 1},
          "completionSource": "issue_close_metadata",
          "isPartial": true,
          "etag": "analytics:1",
          "data_freshness": {"status": "available", "source": "beads", "is_stale": false, "stale_threshold_ms": 60000},
          "generatedAt": "2026-05-11T15:42:00Z"
        }
    """.trimIndent()

    private val issueAnalyticsSnakeCaseJson = """
        {
          "schema_version": 1,
          "project_id": "letta-mobile",
          "range_start": "2026-05-01T00:00:00-04:00",
          "range_end": "2026-05-12T00:00:00-04:00",
          "granularity": "day",
          "timezone": "America/Toronto",
          "created_buckets": [{
            "bucket_start": "2026-05-01T00:00:00-04:00",
            "bucket_end": "2026-05-02T00:00:00-04:00",
            "label": "May 1",
            "created_count": 3
          }],
          "completed_buckets": [{
            "bucket_start": "2026-05-01T00:00:00-04:00",
            "bucket_end": "2026-05-02T00:00:00-04:00",
            "label": "May 1",
            "completed_count": 1
          }],
          "completed_timeline": [{
            "issue_id": "letta-mobile-123",
            "title": "Ship analytics",
            "status": "closed",
            "status_label": "Closed",
            "completed_at": "2026-05-10T18:24:11Z",
            "created_at": "2026-05-08T12:04:00Z",
            "updated_at": "2026-05-10T18:24:11Z",
            "priority": 2,
            "type": "feature",
            "assignee": "emmanuel",
            "labels": ["android"],
            "completed_by": "emmanuel",
            "completion_reason": "done"
          }],
          "summary": {
            "open_count": 4,
            "in_progress_count": 1,
            "completed_count": 5,
            "blocked_count": 2,
            "ready_count": 3,
            "total_created_in_range": 3,
            "total_completed_in_range": 1
          },
          "next_timeline_cursor": null,
          "timeline_page": {"limit": 20, "has_more": false, "total_known": 1},
          "completion_source": "issue_close_metadata",
          "is_partial": true,
          "etag": "analytics:1",
          "data_freshness": {"status": "available", "source": "beads", "is_stale": false, "stale_threshold_ms": 60000},
          "generated_at": "2026-05-11T15:42:00Z"
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
