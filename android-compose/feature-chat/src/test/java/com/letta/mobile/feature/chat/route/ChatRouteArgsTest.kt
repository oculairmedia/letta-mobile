package com.letta.mobile.feature.chat.route

import androidx.lifecycle.SavedStateHandle
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.IdFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceState
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
    fun `parses initialChatMode from saved state`() {
        // letta-mobile-aw0dv: subagent-chip nav passes initialChatMode=interactive
        // so the subagent's conversation opens with full tool/turn detail.
        val args = ChatRouteArgs(
            SavedStateHandle(
                mapOf(
                    "agentId" to "agent-local-sub",
                    "conversationId" to IdFormats.DEFAULT_CONVERSATION_ID,
                    "initialChatMode" to "interactive",
                )
            )
        )

        assertEquals("interactive", args.initialChatMode)
        // a subagent route pins its conversation, never fresh
        assertFalse(args.isFreshRoute)
        assertEquals(IdFormats.DEFAULT_CONVERSATION_ID, args.explicitConversationId)
    }

    @Test
    fun `initialChatMode is null when absent or blank`() {
        val absent = ChatRouteArgs(SavedStateHandle(mapOf("agentId" to "agent-1")))
        assertNull(absent.initialChatMode)

        val blank = ChatRouteArgs(
            SavedStateHandle(mapOf("agentId" to "agent-1", "initialChatMode" to ""))
        )
        assertNull(blank.initialChatMode)
    }

    @Test
    fun `owner rejects resolution that conflicts with an explicit default target`() {
        val args = ChatRouteArgs(
            SavedStateHandle(
                mapOf(
                    "agentId" to "agent-1",
                    "conversationId" to IdFormats.DEFAULT_CONVERSATION_ID,
                )
            )
        )

        assertEquals(IdFormats.DEFAULT_CONVERSATION_ID, args.explicitConversationId)

        val owner = ShellWorkspaceState(
            ShellDestination.Chat("agent-1", IdFormats.DEFAULT_CONVERSATION_ID),
        ).apply { bindSource("source", 1L) }
        args.bindWorkspace(owner)
        val result = runCatching { args.settle(ConversationId("conv-other")) }
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals("conv-default-agent-1", args.explicitConversationId)
        assertEquals(ConversationId(IdFormats.DEFAULT_CONVERSATION_ID), owner.currentFocus.conversationId)
    }

    @Test
    fun `fresh and blank routes have no explicit conversation target`() {
        val freshArgs = ChatRouteArgs(
            SavedStateHandle(
                mapOf(
                    "agentId" to "agent-1",
                    "conversationId" to IdFormats.DEFAULT_CONVERSATION_ID,
                    "freshRouteKey" to 123L,
                )
            )
        )
        assertNull(freshArgs.explicitConversationId)

        val blankArgs = ChatRouteArgs(
            SavedStateHandle(
                mapOf(
                    "agentId" to "agent-1",
                    "conversationId" to "",
                )
            )
        )
        assertNull(blankArgs.explicitConversationId)
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
    fun `freshness stays stable as its effect resolves`() {
        val args = ChatRouteArgs(
            SavedStateHandle(
                mapOf(
                    "agentId" to "agent-1",
                    "conversationId" to "",
                    "freshRouteKey" to 123L,
                )
            )
        )

        assertTrue(args.isFreshRoute)

        args.settle(ConversationId("conv-resolved"))

        assertEquals("conv-resolved", args.explicitConversationId)
        assertTrue(args.isFreshRoute)
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
    fun `resolution never persists a competing conversation selection in the route handle`() {
        val savedState = SavedStateHandle(mapOf("agentId" to "agent-1"))
        val args = ChatRouteArgs(savedState)

        args.settle(ConversationId("route-conv"))
        assertEquals("route-conv", args.explicitConversationId)
        assertNull(savedState.get<String>("conversationId"))

        savedState["conversationId"] = ""
        assertEquals("route-conv", args.explicitConversationId)
        assertEquals("", savedState.get<String>("conversationId"))
    }
    @Test
    fun `saved handle restoration cannot replace the explicit default target`() {
        val handle = SavedStateHandle(mapOf("agentId" to "agent-1", "conversationId" to IdFormats.DEFAULT_CONVERSATION_ID))
        val args = ChatRouteArgs(handle)
        handle["conversationId"] = "stale-last-conversation"
        assertEquals(IdFormats.DEFAULT_CONVERSATION_ID, args.explicitConversationId)
    }

    @Test
    fun `fresh target ignores a conversation restored into the handle`() {
        val handle = SavedStateHandle(mapOf("agentId" to "agent-1", "freshRouteKey" to 7L))
        val args = ChatRouteArgs(handle)
        handle["conversationId"] = "stale-last-conversation"
        assertNull(args.explicitConversationId)
        assertTrue(args.explicitNewChat)
    }

    @Test
    fun callbacksPauseUntilTheRetainedVmBindsTheActiveOwner() {
        val old = ShellWorkspaceState(ShellDestination.Chat("agent-a", "exact")).apply { bindSource("source", 1L) }
        val args = ChatRouteArgs(SavedStateHandle(mapOf("agentId" to "agent-a"))).apply { bindWorkspace(old) }
        val token = args.requestToken()
        args.isOwnerCurrent = { false }
        assertFalse(args.accepts(token))
        val restored = ShellWorkspaceState().apply { bindSource("source", 1L); assertTrue(restore(old.snapshot())) }
        args.bindWorkspace(restored)
        args.isOwnerCurrent = { true }
        assertTrue(args.accepts(token))
        assertTrue(args.complete(token, ConversationId("exact")))
    }

}
