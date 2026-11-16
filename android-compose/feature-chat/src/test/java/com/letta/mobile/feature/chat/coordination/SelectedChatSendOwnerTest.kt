package com.letta.mobile.feature.chat.coordination

import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

class SelectedChatSendOwnerTest {
    @Test fun cancelledSuspendedCallbackEndsAsCancellation() = runTest {
        val entered = CompletableDeferred<Unit>()
        val failures = mutableListOf<Throwable>()
        val handler = kotlinx.coroutines.CoroutineExceptionHandler { _, failure -> failures += failure }
        val owner = SelectedChatSendOwner(mockk(), mockk(), mockk(), this) {}
        val send = owner.scope.launch(handler) {
            try {
                entered.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            } finally {
                // Cancellation resumes the send before its next captured-backend read.
                owner.requireCurrent()
            }
        }
        entered.await()
        owner.retire()
        assertTrue("Retirement must not escape as an uncaught failure: $failures", failures.isEmpty())
        assertTrue("The suspended send must end cancelled", send.isCancelled)
    }

    @Test fun retirementDuringPreparationCancelsWithoutPreparingIdentity() = runTest {
        val entered = CompletableDeferred<Unit>()
        val failures = mutableListOf<Throwable>()
        val handler = kotlinx.coroutines.CoroutineExceptionHandler { _, failure -> failures += failure }
        val owner = SelectedChatSendOwner(mockk(), mockk(), mockk(), this) {
            try {
                entered.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            } catch (_: CancellationException) {
                // A dependency may finish cleanup normally after cancellation.
            }
        }
        val send = owner.scope.launch(handler) { owner.ready("fresh") }
        entered.await()
        owner.retire()
        assertTrue("Readiness must propagate cancellation: $failures", failures.isEmpty())
        assertTrue(send.isCancelled)
    }

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
        } catch (_: CancellationException) {
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
        } catch (_: CancellationException) {
            // Late callbacks must not access the retired generation.
        }
    }
}
