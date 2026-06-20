package com.letta.mobile.data.chat.runtime

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChatSessionReducerTest {
    @Test
    fun configNeededAndOfflineStatesDoNotFallBackToDemoData() {
        val state = ChatSessionState(
            conversations = listOf(conversation("old")),
            selectedConversationId = "old",
            messagesByConversationId = mapOf("old" to listOf(message("old-message", "old"))),
        )

        val configNeeded = ChatSessionReducer.configNeeded(state)
        val offline = ChatSessionReducer.conversationLoadFailed(state, errorMessage = "backend offline")

        assertEquals(ChatConnectionState.ConfigNeeded, configNeeded.connectionState)
        assertFalse(configNeeded.isRemoteBacked)
        assertTrue(configNeeded.conversations.isEmpty())
        assertEquals(ChatConnectionState.Offline, offline.connectionState)
        assertFalse(offline.isRemoteBacked)
        assertTrue(offline.conversations.isEmpty())
    }

    @Test
    fun loadedConversationsSelectFirstConversationAndRepresentEmptyState() {
        val initial = ChatSessionState(selectionGeneration = 4)

        val live = ChatSessionReducer.conversationsLoaded(initial, listOf(conversation("a"), conversation("b")))
        val empty = ChatSessionReducer.conversationsLoaded(initial, emptyList())

        assertEquals("a", live.selectedConversationId)
        assertEquals(ChatConnectionState.Live, live.connectionState)
        assertFalse(live.isLoading)
        assertEquals(5, live.selectionGeneration)
        assertEquals(ChatConnectionState.NoConversations, empty.connectionState)
        assertEquals(null, empty.selectedConversationId)
        assertEquals(4, empty.selectionGeneration)
    }

    @Test
    fun groupsConversationsByAgentNameForNavigation() {
        val groups = groupConversationsByAgentName(
            listOf(
                conversation("newer-alpha", agentName = "Alpha", unreadCount = 1),
                conversation("beta", agentName = "Beta", unreadCount = 3),
                conversation("older-alpha", agentName = " alpha ", unreadCount = 2),
                conversation("unknown", agentName = " "),
            ),
        )

        assertEquals(listOf("Alpha", "Beta", "Unknown agent"), groups.map { it.agentName })
        assertEquals(listOf("newer-alpha", "older-alpha"), groups[0].conversations.map { it.id })
        assertEquals(3, groups[0].unreadCount)
        assertEquals(3, groups[1].unreadCount)
    }

    @Test
    fun mapsConversationSummaryWithResolvedAgentNameAndAgentId() {
        val summary = Conversation(
            id = ConversationId("conversation-abcdef"),
            agentId = AgentId("agent-1"),
            summary = "",
            createdAt = "2026-06-01T00:00:00Z",
            updatedAt = "2026-06-02T00:00:00Z",
            lastMessageAt = "2026-06-03T00:00:00Z",
        ).toChatConversationSummary(agentNamesById = mapOf("agent-1" to "Ada"))

        assertEquals("conversation-abcdef", summary.id)
        assertEquals("agent-1", summary.agentId)
        assertEquals("Ada", summary.agentName)
        assertEquals("Conversation abcdef", summary.title)
        assertEquals("2026-06-03T00:00:00Z", summary.updatedAtLabel)
    }

    @Test
    fun mapsConversationSummariesWithoutDefaultShimPlaceholders() {
        val summaries = listOf(
            Conversation(
                id = ConversationId("conv-default-agent-1"),
                agentId = AgentId("agent-1"),
            ),
            Conversation(
                id = ConversationId("conversation-abcdef"),
                agentId = AgentId("agent-1"),
                summary = "Real conversation",
            ),
        ).toChatConversationSummaries(agentNamesById = mapOf("agent-1" to "Ada"))

        assertEquals(listOf("conversation-abcdef"), summaries.map { it.id })
        assertEquals("Real conversation", summaries.single().title)
        assertEquals("Ada", summaries.single().agentName)
    }

    @Test
    fun selectingRemoteConversationClearsComposerAndStartsHydrateGeneration() {
        val state = ChatSessionState(
            conversations = listOf(conversation("a"), conversation("b", unreadCount = 3)),
            selectedConversationId = "a",
            composer = ChatComposerState(text = "draft"),
            selectionGeneration = 9,
            connectionState = ChatConnectionState.Live,
            isRemoteBacked = true,
        )

        val selected = ChatSessionReducer.selectConversation(state, "b")

        assertEquals("b", selected.selectedConversationId)
        assertEquals(0, selected.conversations.first { it.id == "b" }.unreadCount)
        assertEquals(ChatComposerState(), selected.composer)
        assertTrue(selected.isLoading)
        assertEquals(ChatConnectionState.Loading, selected.connectionState)
        assertEquals(10, selected.selectionGeneration)
    }

    @Test
    fun staleHydrateAndTimelineUpdatesCannotMutateState() {
        val state = ChatSessionState(
            conversations = listOf(conversation("a")),
            selectedConversationId = "a",
            selectionGeneration = 2,
            isLoading = true,
            connectionState = ChatConnectionState.Loading,
        )

        val staleHydrate = ChatSessionReducer.hydrateCompleted(state, generation = 1)
        val staleMessages = ChatSessionReducer.timelineMessagesUpdated(
            state = state,
            generation = 1,
            conversationId = "a",
            messages = listOf(message("new", "new text")),
        )

        assertSame(state, staleHydrate)
        assertSame(state, staleMessages)
    }

    @Test
    fun currentHydrateAndTimelineUpdatesApplyToSelectedConversation() {
        val state = ChatSessionState(
            conversations = listOf(conversation("a")),
            selectedConversationId = "a",
            selectionGeneration = 2,
            isLoading = true,
            connectionState = ChatConnectionState.Loading,
        )

        val hydrated = ChatSessionReducer.hydrateCompleted(state, generation = 2)
        val withMessages = ChatSessionReducer.timelineMessagesUpdated(
            state = hydrated,
            generation = 2,
            conversationId = "a",
            messages = listOf(message("new", "new text")),
        )

        assertFalse(hydrated.isLoading)
        assertEquals(ChatConnectionState.Live, hydrated.connectionState)
        assertEquals("new text", withMessages.selectedMessages.single().content)
        assertEquals("new text", withMessages.conversations.single().lastMessagePreview)
    }

    @Test
    fun sendTransitionsClearAndRestoreComposerForRetry() {
        val image = MessageContentPart.Image(base64 = "AAAA", mediaType = "image/png")
        val draft = ChatComposerSendDraft(
            text = "send me",
            attachments = listOf(image),
            nextState = ChatComposerState(),
        )
        val sending = ChatSessionReducer.beginSend(
            ChatSessionState(
                selectedConversationId = "a",
                composer = ChatComposerState(text = "send me", pendingImageAttachments = listOf(image)),
                connectionState = ChatConnectionState.Live,
            ),
            draft,
        )
        val failed = ChatSessionReducer.sendFailed(sending, draft.text, draft.attachments, "network failed")

        assertEquals(ChatConnectionState.Sending, sending.connectionState)
        assertTrue(sending.isSending)
        assertEquals(ChatComposerState(), sending.composer)
        assertEquals(ChatConnectionState.SendFailed, failed.connectionState)
        assertFalse(failed.isSending)
        assertEquals("send me", failed.composer.text)
        assertEquals(listOf(image), failed.composer.pendingImageAttachments)
    }

    @Test
    fun retryResetsStateAndInvalidatesPendingSelectionWork() {
        val current = ChatSessionState(
            selectedConversationId = "a",
            selectionGeneration = 12,
            connectionState = ChatConnectionState.Offline,
            errorMessage = "offline",
        )
        val initial = ChatSessionState(
            connectionState = ChatConnectionState.Loading,
            isLoading = true,
            statusMessage = "Connecting",
        )

        val retried = ChatSessionReducer.retryConnection(current, initial)

        assertEquals(ChatConnectionState.Loading, retried.connectionState)
        assertTrue(retried.isLoading)
        assertEquals(null, retried.errorMessage)
        assertEquals(13, retried.selectionGeneration)
    }

    @Test
    fun sendPolicyAndStatePanelDecisionsAreShared() {
        val live = ChatSessionState(
            selectedConversationId = "a",
            isRemoteBacked = true,
            connectionState = ChatConnectionState.Live,
        )
        val loading = live.copy(isLoading = true)
        val offline = live.copy(
            selectedConversationId = null,
            connectionState = ChatConnectionState.Offline,
            isRemoteBacked = false,
        )
        val newChat = live.copy(selectedConversationId = null)
        val noConversations = live.copy(
            selectedConversationId = null,
            connectionState = ChatConnectionState.NoConversations,
        )

        assertTrue(ChatSessionReducer.canSend(live))
        assertFalse(ChatSessionReducer.canSend(loading))
        assertTrue(ChatSessionReducer.shouldShowStatePanel(offline))
        assertTrue(ChatSessionReducer.canSend(newChat))
        assertTrue(ChatSessionReducer.canSend(noConversations))
    }

    private fun conversation(
        id: String,
        unreadCount: Int = 0,
        agentName: String = "agent-$id",
    ): ChatConversationSummary =
        ChatConversationSummary(
            id = id,
            title = "Conversation $id",
            agentName = agentName,
            updatedAtLabel = "now",
            lastMessagePreview = "preview",
            unreadCount = unreadCount,
        )

    private fun message(
        id: String,
        content: String,
    ): UiMessage =
        UiMessage(
            id = id,
            role = "assistant",
            content = content,
            timestamp = "2026-06-07T00:00:00Z",
        )
}
