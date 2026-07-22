package com.letta.mobile.data.chat.send

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TurnIdentityLifecycleTest {
    @Test
    fun delayedStartForAcceptedSendPreservesGeneration() {
        val lifecycle = TurnIdentityLifecycle()
        val accepted = lifecycle.acceptedSend(CONVERSATION)

        val transition = assertIs<TurnIdentityTransition.SameTurn>(
            lifecycle.turnStarted(CONVERSATION, TURN, SYNTHETIC_RUN),
        )

        assertEquals(accepted.generation, transition.identity.generation)
        assertNull(transition.identity.durableRunId)
    }

    @Test
    fun realRunPromotionKeepsTurnAndExposesDurableRun() {
        val lifecycle = TurnIdentityLifecycle()
        lifecycle.acceptedSend(CONVERSATION)
        val started = assertIs<TurnIdentityTransition.SameTurn>(
            lifecycle.turnStarted(CONVERSATION, TURN, SYNTHETIC_RUN),
        )

        val promoted = assertIs<TurnIdentityTransition.SameTurn>(
            lifecycle.turnStarted(CONVERSATION, TURN, REAL_RUN),
        )

        assertEquals(started.identity.generation, promoted.identity.generation)
        assertEquals(true, promoted.runPromoted)
        assertEquals(REAL_RUN, promoted.identity.durableRunId)
    }

    @Test
    fun duplicateRealRunPromotionIsIdempotent() {
        val lifecycle = lifecycleWithPromotedTurn()
        val before = lifecycle.active

        val duplicate = assertIs<TurnIdentityTransition.SameTurn>(
            lifecycle.turnStarted(CONVERSATION, TURN, REAL_RUN),
        )

        assertEquals(false, duplicate.runPromoted)
        assertEquals(before, duplicate.identity)
    }

    @Test
    fun replacementTurnAdvancesGenerationAndReplacesIdentity() {
        val lifecycle = lifecycleWithPromotedTurn()
        val old = lifecycle.active!!

        val replacement = assertIs<TurnIdentityTransition.Replacement>(
            lifecycle.turnStarted(CONVERSATION, REPLACEMENT_TURN, REPLACEMENT_RUN),
        )

        assertEquals(old.generation + 1L, replacement.identity.generation)
        assertEquals(REPLACEMENT_TURN, replacement.identity.turnId)
        assertEquals(REPLACEMENT_RUN, replacement.identity.durableRunId)
        assertEquals(false, lifecycle.owns(old))
        assertEquals(true, lifecycle.owns(replacement.identity))
    }

    @Test
    fun acceptedSendAcceptsImmediateFailureButRejectsDelayedOldTerminal() {
        val lifecycle = lifecycleWithPromotedTurn()
        lifecycle.clear()
        lifecycle.acceptedSend(CONVERSATION)

        assertFalse(lifecycle.acceptsTerminal(TURN))
        assertTrue(lifecycle.acceptsTerminal(REPLACEMENT_TURN))
        assertTrue(lifecycle.acceptsTerminal(""))

        lifecycle.turnStarted(CONVERSATION, REPLACEMENT_TURN, REPLACEMENT_RUN)
        assertFalse(lifecycle.acceptsTerminal(TURN))
        assertFalse(lifecycle.acceptsTerminal(""))
        assertTrue(lifecycle.acceptsTerminal(REPLACEMENT_TURN))
    }

    @Test
    fun identifiedFailureBeforeAnyTurnStartedOwnsAcceptedSend() {
        val lifecycle = TurnIdentityLifecycle()
        lifecycle.acceptedSend(CONVERSATION)

        assertTrue(lifecycle.acceptsTerminal(TURN))
    }

    @Test
    fun blankFailureBeforeAnyTurnStartedOwnsAcceptedSend() {
        val lifecycle = TurnIdentityLifecycle()
        lifecycle.acceptedSend(CONVERSATION)

        assertTrue(lifecycle.acceptsTerminal(""))
    }

    @Test
    fun differentConversationIsAlwaysReplacement() {
        val lifecycle = lifecycleWithPromotedTurn()
        val oldGeneration = lifecycle.active!!.generation

        val replacement = assertIs<TurnIdentityTransition.Replacement>(
            lifecycle.turnStarted("conv-2", TURN, REAL_RUN),
        )

        assertEquals(oldGeneration + 1L, replacement.identity.generation)
    }

    private fun lifecycleWithPromotedTurn() = TurnIdentityLifecycle().apply {
        acceptedSend(CONVERSATION)
        turnStarted(CONVERSATION, TURN, SYNTHETIC_RUN)
        turnStarted(CONVERSATION, TURN, REAL_RUN)
    }

    private companion object {
        const val CONVERSATION = "conv-1"
        const val TURN = "turn-1"
        const val SYNTHETIC_RUN = "iroh-run-turn-1"
        const val REAL_RUN = "run-1"
        const val REPLACEMENT_TURN = "turn-2"
        const val REPLACEMENT_RUN = "run-2"
    }
}
