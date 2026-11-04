package com.letta.mobile.data.lanes

import com.letta.mobile.data.chat.conversations.activeChatOf
import com.letta.mobile.data.chat.runtime.ChatSessionReducer
import com.letta.mobile.data.chat.runtime.ChatSessionState
import com.letta.mobile.data.chat.runtime.toChatConversationSummary
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LaneAnchorSelectionTest {
    private val agent = AgentId("main")
    private val records = listOf(
        Conversation(ConversationId("work"), agent, tags = listOf("lane:work")),
        Conversation(ConversationId("inbox"), agent, tags = listOf("lane:inbox")),
        Conversation(ConversationId("home"), agent, tags = listOf("lane:home")),
    )

    @Test
    fun automaticConversationLoadPrefersHomeWhileAnExplicitWorkViewStaysOpen() {
        val summaries = records.map { it.toChatConversationSummary() }
        val initial = ChatSessionReducer.conversationsLoaded(ChatSessionState(), summaries)
        assertEquals("home", initial.selectedConversationId)
        val explicit = ChatSessionReducer.conversationsLoaded(ChatSessionState(selectedConversationId = "work"), summaries)
        assertEquals("work", explicit.selectedConversationId)
    }

    @Test
    fun focusedTimelineDefaultsToHomeAndNeverTreatsWorkOrInboxAsHumanFallback() {
        assertEquals(ConversationId("home"), records.activeChatOf(agent))
        assertNull(records.take(2).activeChatOf(agent))
    }
}
