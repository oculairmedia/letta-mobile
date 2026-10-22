package com.letta.mobile.ui.navigation

import com.letta.mobile.feature.chat.route.AgentChatRoute
import com.letta.mobile.feature.chat.route.ProjectChatStartAction
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class RouteSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── Data objects (parameterless routes) ──────────────────

    @Test
    fun `the workspace and Android's own screens serialize and deserialize`() {
        verifyIdempotent(ShellWorkspaceRoute)
        verifyIdempotent(SystemAccessRoute)
        verifyIdempotent(PairingScanRoute)
        verifyIdempotent(PairingInviteRoute)
        verifyIdempotent(CanvasDebugRoute)
        verifyRoundTrip(CanvasRoute(canvasId = "c-1", conversationId = "conv-1", agentId = "a-1", shareRecipient = "share-1"))
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
            projectStartAction = ProjectChatStartAction.BUG_REPORT,
        )
        verifyRoundTrip(route)
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
