package com.letta.mobile.feature.chat.coordination

import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

class SelectedChatSendOwnerTest {
    @Test fun freshConversationReadinessIsOncePerIdentity() = runTest {
        val prepared = mutableListOf<String>()
        val owner = SelectedChatSendOwner(mockk(), mockk(), mockk(), this) { prepared += it }
        owner.ready("fresh")
        owner.ready("fresh")
        owner.ready("second")
        assertEquals(listOf("fresh", "second"), prepared)
        owner.retire()
        try {
            owner.ready("late")
            fail("Retired owner accepted readiness")
        } catch (_: IllegalStateException) {
            // Retirement rejects new conversation preparation.
        }
    }

    @Test fun retirementJoinsAcceptedCollectorAndRejectsLateCallbacks() = runTest {
        val entered = CompletableDeferred<Unit>()
        var cleaned = false
        val owner = SelectedChatSendOwner(mockk(), mockk(), mockk(), this) {}
        owner.scope.launch {
            try { entered.complete(Unit); kotlinx.coroutines.awaitCancellation() }
            finally { cleaned = true }
        }
        entered.await()
        owner.retire()
        assertTrue(cleaned)
        try {
            owner.requireCurrent()
            fail("Retired owner accepted a callback")
        } catch (_: IllegalStateException) {
            // Late callbacks must not access the retired generation.
        }
    }
}
