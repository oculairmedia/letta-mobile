package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.runtime.ChatScreenStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopChatStatePanelTest {

    @Test
    fun failuresLeadWithTheProblemNotTheWordmark() {
        assertEquals(
            "Can't reach the backend",
            failureHeadline(ChatScreenStatus.BackendOffline(errorMessage = null), errorMessage = null),
        )
        assertEquals(
            "Message wasn't sent",
            failureHeadline(ChatScreenStatus.SendFailed(errorMessage = null), errorMessage = null),
        )
    }

    @Test
    fun aCarriedErrorIsAFailureEvenWhenTheStatusReadsOrdinary() {
        assertEquals(
            "Something went wrong",
            failureHeadline(ChatScreenStatus.Ready(selectedConversationId = "c1", isSending = false), errorMessage = "boom"),
        )
    }

    @Test
    fun idleAndLoadingStatesKeepTheWordmark() {
        // Null headline == show the brand lockup; these are a welcome, not a fault.
        assertNull(failureHeadline(ChatScreenStatus.ConfigNeeded(errorMessage = null), errorMessage = null))
        assertNull(failureHeadline(ChatScreenStatus.NoConversations, errorMessage = null))
        assertNull(failureHeadline(ChatScreenStatus.Loading, errorMessage = null))
        assertNull(failureHeadline(ChatScreenStatus.Ready(selectedConversationId = "c1", isSending = true), errorMessage = null))
    }
}
