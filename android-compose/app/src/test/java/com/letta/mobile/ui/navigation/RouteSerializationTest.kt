package com.letta.mobile.ui.navigation

import com.letta.mobile.feature.chat.AgentChatRoute
import com.letta.mobile.feature.chat.ProjectChatStartAction
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class RouteSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── Data objects (parameterless routes) ──────────────────

    @Test
    fun `HomeRoute serializes and deserializes`() =
        verifyIdempotent(HomeRoute)

    @Test
    fun `AdminRoute serializes and deserializes`() =
        verifyIdempotent(AdminRoute)

    @Test
    fun `ConversationsRoute serializes and deserializes`() =
        verifyIdempotent(ConversationsRoute)

    @Test
    fun `AgentListRoute serializes and deserializes`() =
        verifyIdempotent(AgentListRoute)

    @Test
    fun `ConfigRoute serializes and deserializes`() {
        // letta-mobile-cdlk: ConfigRoute changed from `data object` to
        // `data class ConfigRoute(val createNew: Boolean = false)`.
        // verifyIdempotent only handles singletons; use verifyRoundTrip
        // for instances and cover both default (createNew = false) and
        // the new create-mode (createNew = true) explicitly.
        verifyRoundTrip(ConfigRoute())
        verifyRoundTrip(ConfigRoute(createNew = true))
    }

    @Test
    fun `BlocksRoute serializes and deserializes`() =
        verifyIdempotent(BlocksRoute)

    @Test
    fun `McpRoute serializes and deserializes`() =
        verifyIdempotent(McpRoute)

    @Test
    fun `ProjectsRoute serializes and deserializes`() =
        verifyIdempotent(ProjectsRoute)

    @Test
    fun `ProjectIssuesRoute round-trips`() {
        verifyRoundTrip(ProjectIssuesRoute(projectId = "letta-mobile", projectName = "Letta Mobile"))
    }

    @Test
    fun `ProjectIssueDetailRoute round-trips`() {
        verifyRoundTrip(
            ProjectIssueDetailRoute(
                projectId = "letta-mobile",
                issueId = "letta-mobile-x8pm",
                projectName = "Letta Mobile",
            )
        )
    }

    // ── Data classes (parameterized routes) ──────────────────

    @Test
    fun `AgentChatRoute with required fields round-trips`() {
        val route = AgentChatRoute(agentId = "agent-123")
        verifyRoundTrip(route)
    }

    @Test
    fun `AgentChatRoute with all optional fields round-trips`() {
        val route = AgentChatRoute(
            agentId = "agent-abc",
            agentName = "Test Agent",
            conversationId = "conv-456",
            freshRouteKey = 42L,
            initialMessage = "hello",
            scrollToMessageId = "msg-789",
            projectIdentifier = "proj-x",
            projectName = "Test Project",
            projectLettaFolderId = "folder-1",
            projectFilesystemPath = "/home/projects/test",
            projectGitUrl = "https://github.com/test/repo",
            projectLastSyncAt = "2024-01-01T00:00:00Z",
            projectActiveCodingAgents = "agent-a,agent-b",
            projectStartAction = ProjectChatStartAction.BugReport,
        )
        verifyRoundTrip(route)
    }

    @Test
    fun `McpServerToolsRoute round-trips`() {
        verifyRoundTrip(McpServerToolsRoute(serverId = "server-1"))
    }

    @Test
    fun `EditAgentRoute round-trips`() {
        verifyRoundTrip(EditAgentRoute(agentId = "agent-edit"))
    }

    @Test
    fun `ToolDetailRoute round-trips`() {
        verifyRoundTrip(ToolDetailRoute(toolId = "tool-detail"))
    }

    @Test
    fun `ArchivalRoute round-trips`() {
        verifyRoundTrip(ArchivalRoute(agentId = "agent-arch"))
    }

    @Test
    fun `BotConfigEditRoute with null configId round-trips`() {
        verifyRoundTrip(BotConfigEditRoute(configId = null))
    }

    @Test
    fun `BotConfigEditRoute with configId round-trips`() {
        verifyRoundTrip(BotConfigEditRoute(configId = "bot-config-1"))
    }

    @Test
    fun `ShareToAgentRoute round-trips`() {
        verifyRoundTrip(ShareToAgentRoute(sharedText = "check this out: https://example.com"))
    }

    // ── Edge cases ───────────────────────────────────────────

    @Test
    fun `AgentChatRoute with special characters in initialMessage`() {
        val route = AgentChatRoute(
            agentId = "agent-1",
            initialMessage = "Hello\nWorld\t\"quoted\" <b>bold</b> & entity;",
        )
        verifyRoundTrip(route)
    }

    @Test
    fun `AgentChatRoute agentId is preserved after round-trip`() {
        val route = AgentChatRoute(agentId = "specific-agent-id-42")
        val decoded = decode<AgentChatRoute>(encode(route))
        assertEquals("specific-agent-id-42", decoded.agentId)
    }

    @Test
    fun `EditAgentRoute agentId is preserved after round-trip`() {
        val route = EditAgentRoute(agentId = "edit-me-99")
        val decoded = decode<EditAgentRoute>(encode(route))
        assertEquals("edit-me-99", decoded.agentId)
    }

    @Test
    fun `BotConfigEditRoute null configId encodes correctly`() {
        val encoded = encode(BotConfigEditRoute(configId = null))
        assertNotNull(encoded)
    }

    // ── Helpers ──────────────────────────────────────────────

    private inline fun <reified T> verifyIdempotent(route: T) {
        val encoded = encode(route)
        val decoded = decode<T>(encoded)
        assertEquals(route, decoded)
    }

    private inline fun <reified T> verifyRoundTrip(route: T) {
        val encoded = encode(route)
        val decoded = decode<T>(encoded)
        assertEquals("Round-trip failed for $route", route, decoded)
    }

    private inline fun <reified T> encode(route: T): String =
        json.encodeToString(route)

    private inline fun <reified T> decode(encoded: String): T =
        json.decodeFromString(encoded)
}
