package com.letta.mobile.data.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-by4vm.7: a turn ends through one [TurnCompletion] instead of a thrown singleton.
 * The first outcome wins, and only that first completion stops the frame loop.
 */
class TurnCompletionTest {

    @Test
    fun firstOutcomeWinsAndStopsCollectionOnce() {
        var stops = 0
        val completion = TurnCompletion(stopCollecting = { stops += 1 })
        assertNull(completion.outcome)
        assertFalse(completion.isSettled)

        completion.complete(TurnOutcome.IdleTimedOut)
        completion.complete(TurnOutcome.Completed)
        completion.complete(TurnOutcome.StreamEnded)

        assertEquals(TurnOutcome.IdleTimedOut, completion.outcome)
        assertTrue(completion.isSettled)
        assertEquals(1, stops, "a later outcome must not stop the loop again")
    }

    @Test
    fun separateTurnsShareNoCompletionState() {
        val first = TurnCompletion(stopCollecting = {})
        val second = TurnCompletion(stopCollecting = {})

        first.complete(TurnOutcome.Completed)

        assertEquals(TurnOutcome.Completed, first.outcome)
        assertNull(second.outcome, "one turn's completion is not visible to the next")
    }
}
