package com.letta.mobile.feature.chat.route

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRouteArgsTest {
    @Test
    fun `requires agent id`() {
        val result = runCatching { ChatRouteArgs(SavedStateHandle()).agentId }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Missing agentId") == true)
    }

    @Test
    fun `derives route identity and freshness from saved state`() {
        val args = ChatRouteArgs(
            SavedStateHandle(
                mapOf(
                    "agentId" to "agent-1",
                    "agentName" to "Agent One",
                    "initialMessage" to "hello",
                    "conversationId" to "",
                    "freshRouteKey" to 123L,
                    "scrollToMessageId" to "msg-1",
                )
            )
        )

        assertEquals("agent-1", args.agentId)
        assertEquals("Agent One", args.initialAgentName)
        assertEquals("hello", args.initialMessage)
        assertNull(args.explicitConversationId)
        assertTrue(args.isFreshRoute)
        assertTrue(args.explicitNewChat)
        assertEquals("msg-1", args.scrollToMessageId)
    }

    @Test
    fun `blank conversation id is fresh but not explicit new chat without fresh key`() {
        val args = ChatRouteArgs(
            SavedStateHandle(
                mapOf(
                    "agentId" to "agent-1",
                    "conversationId" to "",
                )
            )
        )

        assertTrue(args.isFreshRoute)
        assertFalse(args.explicitNewChat)
        assertNull(args.explicitConversationId)
    }

    @Test
    fun `restores project context with identifier fallback name`() {
        val args = ChatRouteArgs(
            SavedStateHandle(
                mapOf(
                    "agentId" to "agent-1",
                    "projectIdentifier" to "letta-mobile",
                    "projectFilesystemPath" to "/workspace/letta-mobile",
                    "projectGitUrl" to "https://github.com/oculairmedia/letta-mobile",
                    "projectLastSyncAt" to "2026-05-15T12:00:00Z",
                    "projectActiveCodingAgents" to "android",
                    "projectLettaFolderId" to "folder-1",
                )
            )
        )

        val project = args.projectContext
        assertEquals("letta-mobile", project?.identifier)
        assertEquals("letta-mobile", project?.name)
        assertEquals("/workspace/letta-mobile", project?.filesystemPath)
        assertEquals("https://github.com/oculairmedia/letta-mobile", project?.gitUrl)
        assertEquals("2026-05-15T12:00:00Z", project?.lastSyncAt)
        assertEquals("android", project?.activeCodingAgents)
        assertEquals("folder-1", project?.lettaFolderId)
    }

    @Test
    fun `persists route and client-mode conversation ids through typed mutators`() {
        val savedState = SavedStateHandle(mapOf("agentId" to "agent-1"))
        val args = ChatRouteArgs(savedState)

        args.setClientModeConversationId("client-conv")
        args.setRouteConversationId("route-conv")

        assertEquals("client-conv", args.currentClientModeConversationId())
        assertEquals("route-conv", savedState.get<String>("conversationId"))

        args.setClientModeConversationId("")
        assertNull(args.currentClientModeConversationId())
    }
}
