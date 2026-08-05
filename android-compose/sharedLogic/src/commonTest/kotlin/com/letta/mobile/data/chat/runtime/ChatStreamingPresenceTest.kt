package com.letta.mobile.data.chat.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatStreamingPresenceTest {

    // Default ChatStreamInputs (all signals idle). Tests call `.derive(...)`
    // to copy with only the fields they exercise, keeping the test surface
    // ergonomic AND inside the CodeScene "max 4 args per function" budget
    // by avoiding a ten-parameter wrapper.
    private val idleInputs = ChatStreamInputs()
    private fun derive(overrides: ChatStreamInputs.() -> ChatStreamInputs) =
        ChatStreamingPresencePolicy.derive(inputs = idleInputs.overrides())

    @Test
    fun idleWhenNothingInFlight() {
        val p = derive { this }
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
        val p = derive {
            copy(turnInFlight = true, projectionRunActive = true, tailIsAssistant = true)
        }
        assertTrue(p.isStreaming)
        assertFalse(p.isAgentTyping)
    }

    @Test
    fun turnInFlightShowsTypingWhenTailIsNotAssistant() {
        // Inter-round gap where the tail is not yet a settled assistant message
        // (e.g. right after a tool return, before the next assistant text):
        // presence holds as full working state including the typing indicator.
        val p = derive {
            copy(turnInFlight = true, projectionRunActive = true, tailIsAssistant = false)
        }
        assertTrue(p.isStreaming)
        assertTrue(p.isAgentTyping)
    }

    @Test
    fun turnInFlightFalseIsUnchangedFromLegacyBehavior() {
        // Regression guard: with turnInFlight=false the derivation is identical
        // to before this signal existed (falls through to anyServerLocalPending).
        val idle = derive { this }
        assertFalse(idle.isStreaming)
        assertFalse(idle.isAgentTyping)
        val pending = derive {
            copy(anyServerLocalPending = true, tailIsAssistant = false)
        }
        assertTrue(pending.isStreaming)
        assertTrue(pending.isAgentTyping)
    }

    @Test
    fun staleTurnInFlightIsMaskedByProjectionRunActiveFalse() {
        // letta-mobile-dir4k: the projection says the run is settled, but the
        // transport's `turnInFlight` signal is still true for one observer
        // cycle (e.g. the engine's terminal `emitTurnFrame` has not run yet
        // to retire the `ActiveTurn` entry). Without the projection mask the
        // derive returns `isStreaming=true` and the composer keeps showing
        // "Thinking…" + the red cancel button while the run disclosure
        // correctly shows "Worked for 5.0s" — the exact stuck-state bug.
        val stuck = derive {
            // The projection's "any server run pending" gate. False means the
            // projection has folded the run — its run-activity fact is gone.
            // The tail is a settled assistant message (the run finished and
            // the assistant text landed). This is the state the user sees at
            // the end of a successful reply.
            copy(turnInFlight = true, projectionRunActive = false, tailIsAssistant = true)
        }
        assertFalse(stuck.isStreaming, "stale turnInFlight must not keep streaming presence alive")
        assertFalse(stuck.isAgentTyping, "stale turnInFlight must not keep typing presence alive")
    }

    @Test
    fun freshTurnInFlightWithProjectionRunActiveKeepsPresence() {
        // letta-mobile-dir4k: the positive control — when both signals agree
        // (projection says run is pending AND transport says turn is in
        // flight), presence is held just like the c4igq.7 inter-round gap
        // case. This guards against the mask from being too aggressive and
        // regressing the multi-tool-round flicker fix.
        val fresh = derive {
            copy(turnInFlight = true, projectionRunActive = true, tailIsAssistant = true)
        }
        assertTrue(fresh.isStreaming)
        assertFalse(fresh.isAgentTyping)
    }

    @Test
    fun clientModeStreamHoldsPreviousFlags() {
        // The runtime owns the flags during a client-mode turn — other signals
        // are ignored and the prior values are preserved.
        val p = derive {
            copy(
                previousIsStreaming = true,
                clientModeStreamInFlight = true,
            )
        }
        assertTrue(p.isStreaming)
        assertFalse(p.isAgentTyping)
    }

    @Test
    fun replyStreamingShowsStreamingAndTyping() {
        val p = derive { copy(replyStreaming = true) }
        assertTrue(p.isStreaming)
        assertTrue(p.isAgentTyping)
    }

    @Test
    fun a2uiThinkingShowsWorking() {
        val p = derive { copy(a2uiThinkingActive = true) }
        assertTrue(p.isStreaming)
        assertTrue(p.isAgentTyping)
    }

    @Test
    fun duplicateInitialFollowShowsWorking() {
        val p = derive { copy(duplicateInitialMessageInFlight = true) }
        assertTrue(p.isStreaming)
        assertTrue(p.isAgentTyping)
    }

    @Test
    fun localPendingStreamsAndTypesUntilAssistantTailLands() {
        // A still-sending local message: working. Typing while the tail is the
        // user's own prompt; once an assistant tail lands it's streaming, not
        // typing.
        val beforeReply = derive {
            copy(anyServerLocalPending = true, tailIsAssistant = false)
        }
        assertTrue(beforeReply.isStreaming)
        assertTrue(beforeReply.isAgentTyping)

        val afterReplyStarts = derive {
            copy(anyServerLocalPending = true, tailIsAssistant = true)
        }
        assertTrue(afterReplyStarts.isStreaming)
        assertFalse(afterReplyStarts.isAgentTyping)
    }
}
