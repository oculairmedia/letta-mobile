package com.letta.mobile.desktop

import com.letta.mobile.data.chat.runtime.NowActiveStatus
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.desktop.chat.DesktopChatSurfaceState
import com.letta.mobile.desktop.chat.DesktopConversationSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure-function coverage for the header identity block's state mapping
 * (letta-mobile-3arhe.1 AC #8). [deriveNowActiveBarPin] is deliberately
 * Compose-free so these run as plain JVM tests without a UI test harness.
 */
class DesktopNowActiveBarTest {

    private fun conversation(
        id: String,
        title: String = "Conversation $id",
        agentName: String = "Agent $id",
        agentId: String? = "agent-$id",
    ) = DesktopConversationSummary(
        id = id,
        title = title,
        agentName = agentName,
        updatedAtLabel = "Just now",
        lastMessagePreview = "preview",
        agentId = agentId,
    )

    private fun surfaceState(
        conversations: List<DesktopConversationSummary>,
        selectedConversationId: String?,
        errorMessage: String? = null,
    ) = DesktopChatSurfaceState(
        conversations = conversations,
        selectedConversationId = selectedConversationId,
        messagesByConversationId = emptyMap(),
        composerText = "",
        isSending = false,
        errorMessage = errorMessage,
        backendLabel = "test-backend",
        sessionGraphId = 0L,
    )

    private fun host(
        thinkingConversationId: String? = null,
        isStreamingReplySelected: Boolean = false,
    ) = NowActiveBarHostState(
        thinkingConversationId = thinkingConversationId,
        isStreamingReplySelected = isStreamingReplySelected,
    )

    @Test
    fun noConversationAtAllReturnsNull() {
        val pin = deriveNowActiveBarPin(
            ownerConversationId = null,
            streamingId = null,
            cancellingId = null,
            chatState = surfaceState(conversations = emptyList(), selectedConversationId = null),
            host = host(),
        )
        assertNull(pin)
        val staleController = deriveNowActiveBarPin(
            ownerConversationId = null,
            streamingId = null,
            cancellingId = null,
            chatState = surfaceState(conversations = listOf(conversation("c1")), selectedConversationId = "c1"),
            host = host(),
        )
        assertNull(staleController)
    }

    @Test
    fun showsTheOwnerSelectedConversation() {
        val convo = conversation("c1")
        val pin = deriveNowActiveBarPin(
            ownerConversationId = ConversationId("c1"),
            streamingId = null,
            cancellingId = null,
            chatState = surfaceState(conversations = listOf(convo), selectedConversationId = "c1"),
            host = host(),
        )
        assertEquals("c1", pin?.conversationId)
        assertEquals("Conversation c1", pin?.state?.conversationTitle)
        assertEquals("Agent c1", pin?.state?.agentName)
        assertEquals(NowActiveStatus.Idle, pin?.state?.status)
    }

    @Test
    fun headerTracksTheSelectedChatWhileAnotherChatWorks() {
        val prompted = conversation("c1", agentName = "Background agent")
        val selected = conversation("c2")
        val pin = deriveNowActiveBarPin(
            ownerConversationId = ConversationId("c2"),
            streamingId = null,
            cancellingId = null,
            chatState = surfaceState(conversations = listOf(prompted, selected), selectedConversationId = "c2"),
            host = host(thinkingConversationId = "c1"),
        )
        assertEquals("c2", pin?.conversationId)
        assertEquals("Conversation c2", pin?.state?.conversationTitle)
        assertEquals("Agent c2", pin?.state?.agentName)
        assertEquals("Background agent", pin?.state?.backgroundWorkAgentName)
    }

    @Test
    fun showsTheSelectedConversationEvenWhenAnotherWasPrompted() {
        val prompted = conversation("c1")
        val browsing = conversation("c2")
        val pin = deriveNowActiveBarPin(
            ownerConversationId = ConversationId("c2"),
            streamingId = null,
            cancellingId = null,
            chatState = surfaceState(conversations = listOf(prompted, browsing), selectedConversationId = "c2"),
            host = host(),
        )
        assertEquals("c2", pin?.conversationId)
        assertEquals("Conversation c2", pin?.state?.conversationTitle)
    }

    @Test
    fun headerFollowsOwnerWhileControllerStillShowsAnOlderChat() {
        val old = conversation("c1")
        val target = conversation("c2")
        val pin = deriveNowActiveBarPin(
            ownerConversationId = ConversationId("c2"),
            streamingId = "c1",
            cancellingId = null,
            chatState = surfaceState(conversations = listOf(old, target), selectedConversationId = "c1"),
            host = host(isStreamingReplySelected = true),
        )
        assertEquals("c2", pin?.conversationId)
        assertEquals("Conversation c2", pin?.state?.conversationTitle)
        assertEquals(NowActiveStatus.Idle, pin?.state?.status)
        assertEquals("Agent c1", pin?.state?.backgroundWorkAgentName)
    }

    @Test
    fun mapsThinkingStatusForThePinnedConversation() {
        val convo = conversation("c1")
        val pin = deriveNowActiveBarPin(
            ownerConversationId = ConversationId("c1"),
            streamingId = null,
            cancellingId = null,
            chatState = surfaceState(conversations = listOf(convo), selectedConversationId = "c1"),
            host = host(thinkingConversationId = "c1"),
        )
        assertEquals(NowActiveStatus.Thinking, pin?.state?.status)
    }

    @Test
    fun mapsErrorStatusOnlyWhenPinnedConversationIsSelected() {
        val convo = conversation("c1")
        val pin = deriveNowActiveBarPin(
            ownerConversationId = ConversationId("c1"),
            streamingId = null,
            cancellingId = null,
            chatState = surfaceState(
                conversations = listOf(convo),
                selectedConversationId = "c1",
                errorMessage = "boom",
            ),
            host = host(),
        )
        assertEquals(NowActiveStatus.Error, pin?.state?.status)
    }

    @Test
    fun mapsStoppingStatusFromCancellingId() {
        val convo = conversation("c1")
        val pin = deriveNowActiveBarPin(
            ownerConversationId = ConversationId("c1"),
            streamingId = null,
            cancellingId = "c1",
            chatState = surfaceState(conversations = listOf(convo), selectedConversationId = "c1"),
            host = host(),
        )
        assertEquals(NowActiveStatus.Stopping, pin?.state?.status)
    }

    @Test
    fun surfacesBackgroundWorkFromADifferentConversation() {
        val pinned = conversation("c1")
        val working = conversation("c2", agentName = "Background agent")
        val pin = deriveNowActiveBarPin(
            ownerConversationId = ConversationId("c1"),
            streamingId = null,
            cancellingId = null,
            chatState = surfaceState(conversations = listOf(pinned, working), selectedConversationId = "c1"),
            host = host(thinkingConversationId = "c2"),
        )
        assertEquals("Background agent", pin?.state?.backgroundWorkAgentName)
    }

    @Test
    fun noBackgroundWorkChipWhenTheThinkingConversationIsTheOneAlreadyPinned() {
        val convo = conversation("c1")
        val pin = deriveNowActiveBarPin(
            ownerConversationId = ConversationId("c1"),
            streamingId = null,
            cancellingId = null,
            chatState = surfaceState(conversations = listOf(convo), selectedConversationId = "c1"),
            host = host(thinkingConversationId = "c1"),
        )
        assertNull(pin?.state?.backgroundWorkAgentName)
    }

    @Test
    fun carriesTheAgentIdSoTheSharedAvatarCanPictureIt() {
        val convo = conversation("c1", agentId = "agent-1")
        val pin = deriveNowActiveBarPin(
            ownerConversationId = ConversationId("c1"),
            streamingId = null,
            cancellingId = null,
            chatState = surfaceState(conversations = listOf(convo), selectedConversationId = "c1"),
            host = host(),
        )
        assertEquals("agent-1", pin?.state?.agentId)
    }
}
