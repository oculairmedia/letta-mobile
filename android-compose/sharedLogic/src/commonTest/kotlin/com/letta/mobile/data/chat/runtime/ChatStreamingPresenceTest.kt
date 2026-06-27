package com.letta.mobile.data.chat.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatStreamingPresenceTest {

    private fun derive(
        previousIsStreaming: Boolean = false,
        previousIsAgentTyping: Boolean = false,
        anyServerLocalPending: Boolean = false,
        tailIsAssistant: Boolean = false,
        replyStreaming: Boolean = false,
        clientModeStreamInFlight: Boolean = false,
        a2uiThinkingActive: Boolean = false,
        duplicateInitialMessageInFlight: Boolean = false,
    ) = ChatStreamingPresencePolicy.derive(
        previousIsStreaming = previousIsStreaming,
        previousIsAgentTyping = previousIsAgentTyping,
        anyServerLocalPending = anyServerLocalPending,
        tailIsAssistant = tailIsAssistant,
        replyStreaming = replyStreaming,
        clientModeStreamInFlight = clientModeStreamInFlight,
        a2uiThinkingActive = a2uiThinkingActive,
        duplicateInitialMessageInFlight = duplicateInitialMessageInFlight,
    )

    @Test
    fun idleWhenNothingInFlight() {
        val p = derive()
        assertFalse(p.isStreaming)
        assertFalse(p.isAgentTyping)
    }

    @Test
    fun clientModeStreamHoldsPreviousFlags() {
        // The runtime owns the flags during a client-mode turn — other signals
        // are ignored and the prior values are preserved.
        val p = derive(
            previousIsStreaming = true,
            previousIsAgentTyping = false,
            clientModeStreamInFlight = true,
            replyStreaming = true,
            anyServerLocalPending = true,
        )
        assertTrue(p.isStreaming)
        assertFalse(p.isAgentTyping)
    }

    @Test
    fun replyStreamingShowsStreamingAndTyping() {
        val p = derive(replyStreaming = true)
        assertTrue(p.isStreaming)
        assertTrue(p.isAgentTyping)
    }

    @Test
    fun a2uiThinkingShowsWorking() {
        val p = derive(a2uiThinkingActive = true)
        assertTrue(p.isStreaming)
        assertTrue(p.isAgentTyping)
    }

    @Test
    fun duplicateInitialFollowShowsWorking() {
        val p = derive(duplicateInitialMessageInFlight = true)
        assertTrue(p.isStreaming)
        assertTrue(p.isAgentTyping)
    }

    @Test
    fun localPendingStreamsAndTypesUntilAssistantTailLands() {
        // A still-sending local message: working. Typing while the tail is the
        // user's own prompt; once an assistant tail lands it's streaming, not
        // typing.
        val beforeReply = derive(anyServerLocalPending = true, tailIsAssistant = false)
        assertTrue(beforeReply.isStreaming)
        assertTrue(beforeReply.isAgentTyping)

        val afterReplyStarts = derive(anyServerLocalPending = true, tailIsAssistant = true)
        assertTrue(afterReplyStarts.isStreaming)
        assertFalse(afterReplyStarts.isAgentTyping)
    }
}
