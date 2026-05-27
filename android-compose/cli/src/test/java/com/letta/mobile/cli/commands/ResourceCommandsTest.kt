package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.core.UsageError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResourceCommandsTest {
    @Test
    fun `path templates encode each placeholder as a path segment`() {
        val path = buildResourcePathTemplate(
            "/v1/agents/{agent_id}/tools/attach/{tool_id}",
            mapOf("agent_id" to "agent/one", "tool_id" to "tool one"),
        )

        assertEquals("/v1/agents/agent%2Fone/tools/attach/tool%20one", path)
    }

    @Test
    fun `body templates render placeholder values as json strings`() {
        val body = buildResourceBodyTemplate(
            """{"projectId":{project_id}}""",
            mapOf("project_id" to """proj"one"""),
        )

        assertEquals("""{"projectId":"proj\"one"}""", body)
    }

    @Test
    fun `missing template values fail before an http request is made`() {
        assertThrows(UsageError::class.java) {
            buildResourcePathTemplate("/v1/agents/{agent_id}", emptyMap())
        }
    }

    @Test
    fun `resource registry includes app admin surfaces`() {
        val names = resourceCommandNames()

        assertTrue(
            names.containsAll(
                setOf(
                    "agents",
                    "conversations",
                    "tools",
                    "blocks",
                    "archives",
                    "folders",
                    "groups",
                    "identities",
                    "schedules",
                    "mcp",
                    "runs",
                    "jobs",
                    "steps",
                    "models",
                    "providers",
                    "projects",
                    "project-work",
                )
            )
        )
    }

    @Test
    fun `resource route registry covers app api routes`() {
        val routes = resourceCommandRouteKeys()

        val expectedRoutes = setOf(
            "GET /v1/agents",
            "POST /v1/agents",
            "GET /v1/agents/{agent_id}",
            "PATCH /v1/agents/{agent_id}",
            "DELETE /v1/agents/{agent_id}",
            "POST /v1/agents/import",
            "GET /v1/conversations",
            "POST /v1/conversations/{conversation_id}/stream",
            "POST /v1/conversations/{conversation_id}/messages",
            "GET /v1/tools",
            "PUT /v1/tools",
            "POST /v1/tools/generate-schema",
            "GET /v1/blocks",
            "PATCH /v1/blocks/{block_id}/identities/attach/{identity_id}",
            "GET /v1/archives/",
            "DELETE /v1/archives/{archive_id}/passages/{passage_id}",
            "POST /v1/folders/{folder_id}/upload",
            "GET /v1/groups/",
            "POST /v1/groups/{group_id}/messages/stream",
            "GET /v1/identities/",
            "PUT /v1/identities/{identity_id}/properties",
            "GET /v1/mcp-servers",
            "POST /v1/mcp-servers/{server_id}/tools/{tool_id}/run",
            "GET /v1/runs/",
            "GET /v1/jobs/",
            "GET /v1/steps/",
            "PATCH /v1/steps/{step_id}/feedback",
            "GET /v1/models",
            "GET /v1/models/embedding",
            "GET /v1/agents/{agent_id}/archival-memory",
            "GET /v1/providers/",
            "POST /v1/providers/check",
            "GET /v1/agents/{agent_id}/schedule",
            "POST /v1/messages/search",
            "GET /v1/messages/batches",
            "POST /v1/messages/batches",
            "GET /api/projects",
            "POST /api/registry/projects",
            "PATCH /api/registry/projects/{project_id}",
            "POST /api/sync/trigger",
            "GET /api/agents/lookup",
            "GET /api/projects/{project_id}/ready-work",
            "PATCH /api/issues/{issue_id}/status",
            "POST /api/issues/{issue_id}/notes",
            "GET /health",
            "GET /api/stats",
            "POST /api/admin/agents-md/refresh",
        )

        assertTrue(
            routes.containsAll(expectedRoutes),
            "Missing CLI route coverage for: ${expectedRoutes - routes}",
        )
    }
}
