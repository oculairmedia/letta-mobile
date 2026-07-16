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
        turnInFlight: Boolean = false,
    ) = ChatStreamingPresencePolicy.derive(
        previousIsStreaming = previousIsStreaming,
        previousIsAgentTyping = previousIsAgentTyping,
        anyServerLocalPending = anyServerLocalPending,
        tailIsAssistant = tailIsAssistant,
        replyStreaming = replyStreaming,
        clientModeStreamInFlight = clientModeStreamInFlight,
        a2uiThinkingActive = a2uiThinkingActive,
        duplicateInitialMessageInFlight = duplicateInitialMessageInFlight,
        turnInFlight = turnInFlight,
    )

    @Test
    fun idleWhenNothingInFlight() {
        val p = derive()
        assertFalse(p.isStreaming)
        assertFalse(p.isAgentTyping)
    }

    @Test
    fun turnInFlightHoldsPresenceAcrossInterRoundGap() {
        // letta-mobile-c4igq.7: the inter-round gap of a multi-tool turn —
        // replyStreaming is briefly false, nothing is server-local-pending, and
        // the tail is the post-tool assistant text. Without turnInFlight this
        // derives idle (the flicker + "looks finished" bug). With turnInFlight
        // true, presence holds: streaming stays true; typing is suppressed only
        // because the tail is a settled assistant message (show the glow, not
        // the dots).
        val p = derive(
            turnInFlight = true,
            replyStreaming = false,
            anyServerLocalPending = false,
            tailIsAssistant = true,
        )
        assertTrue(p.isStreaming)
        assertFalse(p.isAgentTyping)
    }

    @Test
    fun turnInFlightShowsTypingWhenTailIsNotAssistant() {
        // Inter-round gap where the tail is not yet a settled assistant message
        // (e.g. right after a tool return, before the next assistant text):
        // presence holds as full working state including the typing indicator.
        val p = derive(
            turnInFlight = true,
            replyStreaming = false,
            anyServerLocalPending = false,
            tailIsAssistant = false,
        )
        assertTrue(p.isStreaming)
        assertTrue(p.isAgentTyping)
    }

    @Test
    fun turnInFlightFalseIsUnchangedFromLegacyBehavior() {
        // Regression guard: with turnInFlight=false the derivation is identical
        // to before this signal existed (falls through to anyServerLocalPending).
        val idle = derive(turnInFlight = false)
        assertFalse(idle.isStreaming)
        assertFalse(idle.isAgentTyping)
        val pending = derive(turnInFlight = false, anyServerLocalPending = true, tailIsAssistant = false)
        assertTrue(pending.isStreaming)
        assertTrue(pending.isAgentTyping)
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
