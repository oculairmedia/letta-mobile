package com.letta.mobile.desktop

import com.letta.mobile.data.chat.runtime.NowActiveStatus
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
        avatarStyleByAgentId: Map<String, Int> = emptyMap(),
        fallbackOrbIndex: Int = 0,
        avatarCompanionActive: Boolean = false,
    ) = NowActiveBarHostState(
        thinkingConversationId = thinkingConversationId,
        isStreamingReplySelected = isStreamingReplySelected,
        avatarStyleByAgentId = avatarStyleByAgentId,
        fallbackOrbIndex = fallbackOrbIndex,
        avatarCompanionActive = avatarCompanionActive,
    )

    @Test
    fun noConversationAtAllReturnsNull() {
        val pin = deriveNowActiveBarPin(
            lastPromptedId = null,
            streamingId = null,
            cancellingId = null,
            chatState = surfaceState(conversations = emptyList(), selectedConversationId = null),
            host = host(),
        )
        assertNull(pin)
    }

    @Test
    fun fallsBackToSelectedConversationWhenNothingWasPrompted() {
        val convo = conversation("c1")
        val pin = deriveNowActiveBarPin(
            lastPromptedId = null,
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
    fun pinsToLastPromptedConversationEvenWhenBrowsingElsewhere() {
        val prompted = conversation("c1")
        val browsing = conversation("c2")
        val pin = deriveNowActiveBarPin(
            lastPromptedId = "c1",
            streamingId = null,
            cancellingId = null,
            chatState = surfaceState(conversations = listOf(prompted, browsing), selectedConversationId = "c2"),
            host = host(),
        )
        // Pinned to the prompted conversation (c1), not the one currently
        // being browsed (c2) — Spotify-style now-playing semantics.
        assertEquals("c1", pin?.conversationId)
        assertEquals("Conversation c1", pin?.state?.conversationTitle)
    }

    @Test
    fun mapsThinkingStatusForThePinnedConversation() {
        val convo = conversation("c1")
        val pin = deriveNowActiveBarPin(
            lastPromptedId = "c1",
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
            lastPromptedId = "c1",
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
            lastPromptedId = "c1",
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
            lastPromptedId = "c1",
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
            lastPromptedId = "c1",
            streamingId = null,
            cancellingId = null,
            chatState = surfaceState(conversations = listOf(convo), selectedConversationId = "c1"),
            host = host(thinkingConversationId = "c1"),
        )
        assertNull(pin?.state?.backgroundWorkAgentName)
    }

    @Test
    fun orbIndexPrefersTheAgentStyleOverrideOverTheFallback() {
        val convo = conversation("c1", agentId = "agent-1")
        val pin = deriveNowActiveBarPin(
            lastPromptedId = "c1",
            streamingId = null,
            cancellingId = null,
            chatState = surfaceState(conversations = listOf(convo), selectedConversationId = "c1"),
            host = host(avatarStyleByAgentId = mapOf("agent-1" to 4), fallbackOrbIndex = 9),
        )
        assertEquals(4, pin?.state?.orbIndex)
    }

    @Test
    fun orbIndexFallsBackWhenTheAgentHasNoStyleOverride() {
        val convo = conversation("c1", agentId = "agent-1")
        val pin = deriveNowActiveBarPin(
            lastPromptedId = "c1",
            streamingId = null,
            cancellingId = null,
            chatState = surfaceState(conversations = listOf(convo), selectedConversationId = "c1"),
            host = host(avatarStyleByAgentId = emptyMap(), fallbackOrbIndex = 9),
        )
        assertEquals(9, pin?.state?.orbIndex)
    }

    @Test
    fun avatarCompanionActiveFlowsThroughUnchanged() {
        val convo = conversation("c1")
        val pin = deriveNowActiveBarPin(
            lastPromptedId = "c1",
            streamingId = null,
            cancellingId = null,
            chatState = surfaceState(conversations = listOf(convo), selectedConversationId = "c1"),
            host = host(avatarCompanionActive = true),
        )
        assertEquals(true, pin?.state?.avatarCompanionActive)
    }
}
