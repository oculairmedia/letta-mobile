package com.letta.mobile.data.repository.http

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.LettaConfig
import kotlinx.serialization.json.Json
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

import kotlin.time.Duration.Companion.milliseconds
class LettaHttpAdminRepositoriesTest {
    private val testJson = Json { ignoreUnknownKeys = true }

    @Test
    fun refreshesSharedAdminModelsFromDesktopHttpEndpoints() = runTest {
        val requestedPaths = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                requestedPaths += path
                when (path) {
                    "/v1/agents" -> jsonResponse(
                        """
                        [
                          {
                            "id": "agent-1",
                            "name": "Ada",
                            "context_window_limit": 8000,
                            "blocks": [
                              {
                                "id": "block-1",
                                "label": "persona",
                                "value": "Keeps a concise research voice."
                              }
                            ],
                            "tools": [
                              {
                                "id": "tool-1",
                                "name": "search_docs",
                                "description": "Search indexed docs",
                                "source_type": "python",
                                "tags": ["research"]
                              }
                            ]
                          }
                        ]
                        """.trimIndent(),
                    )
                    "/v1/tools" -> jsonResponse(
                        """
                        [
                          {
                            "id": "tool-1",
                            "name": "search_docs",
                            "description": "Search indexed docs",
                            "source_type": "python",
                            "tags": ["research"]
                          }
                        ]
                        """.trimIndent(),
                    )
                    "/v1/agents/agent-1/schedule" -> jsonResponse(
                        """
                        {
                          "scheduled_messages": [
                            {
                              "id": "schedule-1",
                              "agent_id": "agent-1",
                              "message": {
                                "messages": [
                                  {
                                    "role": "user",
                                    "content": "Summarize the latest project memory"
                                  }
                                ]
                              },
                              "next_scheduled_time": "2026-06-14T08:00:00Z",
                              "schedule": {
                                "type": "recurring",
                                "cron_expression": "0 8 * * *"
                              }
                            }
                          ]
                        }
                        """.trimIndent(),
                    )
                    "/v1/agents/agent-1/context" -> jsonResponse(
                        """
                        {
                          "context_window_size_current": 512,
                          "num_tokens_core_memory": 100,
                          "num_tokens_external_memory_summary": 25,
                          "num_tokens_memory_filesystem": 50,
                          "num_tokens_summary_memory": 10
                        }
                        """.trimIndent(),
                    )
                    else -> jsonResponse("""{"error":"unexpected path"}""", HttpStatusCode.NotFound)
                }
            },
        ) {
            install(ContentNegotiation) {
                json(testJson)
            }
        }
        val repositories = LettaHttpAdminRepositories(
            config = LettaConfig(
                id = "desktop-test",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "http://localhost:8283",
                accessToken = "token-1",
            ),
            httpClient = client,
            nowMillis = { 0L },
        )

        repositories.refreshAgents()
        repositories.refreshTools()
        repositories.refreshSchedules("agent-1")
        val context = repositories.getContextWindow(AgentId("agent-1"))

        assertEquals("Ada", repositories.agents.value.single().name)
        assertEquals("search_docs", repositories.getTools().value.single().name)
        assertEquals(
            "Summarize the latest project memory",
            repositories.getSchedules("agent-1").first().single().message.messages.single().content,
        )
        assertEquals(512, context.contextWindowSizeCurrent)
        assertTrue("/v1/agents" in requestedPaths)
        assertTrue("/v1/tools" in requestedPaths)
        assertTrue("/v1/agents/agent-1/schedule" in requestedPaths)
        assertTrue("/v1/agents/agent-1/context" in requestedPaths)
    }

    @Test
    fun refreshAgentsSurfacesHttpFailureViaRefreshError() = runTest {
        val client = HttpClient(
            MockEngine { _ ->
                jsonResponse("""{"error":"boom"}""", HttpStatusCode.InternalServerError)
            },
        ) {
            install(ContentNegotiation) { json(testJson) }
        }
        val repositories = repositories(client)

        val thrown = runCatching { repositories.refreshAgents() }.exceptionOrNull()

        assertTrue(thrown != null, "refreshAgents must rethrow on HTTP failure")
        assertTrue(
            repositories.refreshError.value != null,
            "refreshError must hold the failure",
        )
        assertEquals(false, repositories.isRefreshing.value)
        assertTrue(repositories.agents.value.isEmpty(), "agents cache stays empty on failure")
    }

    @Test
    fun refreshAgentsIfStaleSkipsFetchWhenCacheIsFresh() = runTest {
        var agentFetches = 0
        var now = 1_000L
        val client = HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath == "/v1/agents") {
                    agentFetches += 1
                    jsonResponse("""[{"id":"agent-1","name":"Ada"}]""")
                } else {
                    jsonResponse("[]")
                }
            },
        ) {
            install(ContentNegotiation) { json(testJson) }
        }
        val repositories = repositories(client, nowMillis = { now })

        // First call populates the cache and records the refresh time.
        assertTrue(repositories.refreshAgentsIfStale(maxAgeMs = 10_000L), "first stale-check must fetch")
        assertEquals(1, agentFetches)

        // Still within TTL → no second fetch.
        now += 5_000L
        assertEquals(false, repositories.refreshAgentsIfStale(maxAgeMs = 10_000L))
        assertEquals(1, agentFetches)

        // Still inside the short full-sweep cache TTL → no second fetch.
        now += 20_000L
        assertFalse(repositories.refreshAgentsIfStale(maxAgeMs = 10_000L))
        assertEquals(1, agentFetches)

        // Past the full-sweep cache TTL → refetch.
        now += 30_000L
        assertTrue(repositories.refreshAgentsIfStale(maxAgeMs = 10_000L), "expired cache must refetch")
        assertEquals(2, agentFetches)
    }

    @Test
    fun concurrentRefreshAgentsShareSingleFullPaginationSweep() = runTest {
        var agentFetches = 0
        val client = HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath == "/v1/agents") {
                    agentFetches += 1
                    delay(25.milliseconds)
                    jsonResponse("""[{"id":"agent-1","name":"Ada"}]""")
                } else {
                    jsonResponse("[]")
                }
            },
        ) {
            install(ContentNegotiation) { json(testJson) }
        }
        val repositories = repositories(client, nowMillis = { 1_000L })

        awaitAll(
            async { repositories.refreshAgents() },
            async { repositories.refreshAgents() },
        )

        assertEquals(1, agentFetches)
        assertEquals("Ada", repositories.agents.value.single().name)
    }

    private fun repositories(
        client: HttpClient,
        nowMillis: () -> Long = { 0L },
    ) = LettaHttpAdminRepositories(
        config = LettaConfig(
            id = "desktop-test",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = "http://localhost:8283",
            accessToken = "token-1",
        ),
        httpClient = client,
        nowMillis = nowMillis,
    )

    private fun MockRequestHandleScope.jsonResponse(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )
}
