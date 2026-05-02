package com.letta.mobile.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationSwitchActionTest {

    @Test
    fun `new conversation action routes with null conversation id`() {
        assertNull(ConversationSwitchAction.NewConversation.conversationId)
    }

    @Test
    fun `existing conversation action preserves selected conversation id`() {
        val action = ConversationSwitchAction.ExistingConversation("conv-123")

        assertEquals("conv-123", action.conversationId)
    }
}
