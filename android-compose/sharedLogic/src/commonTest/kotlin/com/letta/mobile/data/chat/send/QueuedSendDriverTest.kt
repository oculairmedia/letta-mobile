package com.letta.mobile.data.chat.send

import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecorder
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * letta-mobile-1n5py: the queue state machine against a scripted transport, in virtual time:
 * enqueue many, cancel one, push one through, pause on Stop and resume.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueuedSendDriverTest {

    @Test
    fun busySendsWaitAndRunOneAtATimeInOrder() = runTest {
        val rig = rig(busy = true)
        listOf("a", "b", "c").forEach { rig.enqueue(it) }
        advanceUntilIdle()
        assertTrue(rig.turns.dispatched.isEmpty(), "nothing runs while the turn ahead is live")

        rig.finishTurn()
        assertEquals(listOf("a"), rig.turns.dispatched)
        assertEquals(listOf("b", "c"), rig.queuedTexts())

        rig.finishTurn()
        rig.finishTurn()
        assertEquals(listOf("a", "b", "c"), rig.turns.dispatched)
        assertTrue(rig.queue.queueFor(CONV).isEmpty)
    }

    @Test
    fun cancellingTheMiddleItemSkipsItOnly() = runTest {
        val rig = rig(busy = true)
        listOf("a", "b", "c").forEach { rig.enqueue(it) }

        assertTrue(rig.driver.cancel(QueuedSendId("otid-b")))
        assertFalse(rig.driver.cancel(QueuedSendId("otid-b")))
        repeat(3) { rig.finishTurn() }

        assertEquals(listOf("a", "c"), rig.turns.dispatched)
    }

    @Test
    fun sendNowAbortsTheRunningTurnAndRunsTheItemBeforeTheOnesItJumped() = runTest {
        val rig = rig(busy = true)
        listOf("a", "b", "c").forEach { rig.enqueue(it) }

        assertTrue(rig.driver.sendNow(QueuedSendId("otid-c")))
        assertEquals(listOf(CONV), rig.turns.aborted)
        assertEquals(listOf("c", "a", "b"), rig.queuedTexts())

        // The aborted turn's terminal releases the queue.
        rig.finishTurn()
        assertEquals(listOf("c"), rig.turns.dispatched)
        rig.finishTurn()
        rig.finishTurn()
        assertEquals(listOf("c", "a", "b"), rig.turns.dispatched)
    }

    @Test
    fun sendNowWithNothingRunningDispatchesAtOnce() = runTest {
        val rig = rig(busy = true)
        rig.enqueue("a")
        rig.enqueue("b")
        rig.turns.busy = false

        rig.driver.sendNow(QueuedSendId("otid-b"))
        advanceUntilIdle()

        assertTrue(rig.turns.aborted.isEmpty())
        assertEquals(listOf("b"), rig.turns.dispatched)
    }

    @Test
    fun stopPausesTheQueueUntilResume() = runTest {
        val rig = rig(busy = true)
        rig.enqueue("a")

        rig.driver.onStopped(CONV)
        rig.finishTurn()
        assertTrue(rig.turns.dispatched.isEmpty(), "a stopped queue holds its items")
        assertTrue(rig.queue.isPaused(CONV))

        rig.driver.resume(CONV)
        advanceUntilIdle()
        assertEquals(listOf("a"), rig.turns.dispatched)
    }

    @Test
    fun aNewSendReleasesAPausedQueue() = runTest {
        val rig = rig(busy = true)
        rig.enqueue("a")
        rig.driver.onStopped(CONV)

        rig.enqueue("b")
        assertFalse(rig.queue.isPaused(CONV), "the next input resumes the queue, as on the App Server")
        rig.finishTurn()

        assertEquals(listOf("a"), rig.turns.dispatched)
    }

    @Test
    fun sendNowOnAPausedQueueResumesIt() = runTest {
        val rig = rig(busy = false)
        rig.turns.busy = true
        rig.enqueue("a")
        rig.driver.onStopped(CONV)
        rig.turns.busy = false

        rig.driver.sendNow(QueuedSendId("otid-a"))
        advanceUntilIdle()

        assertEquals(listOf("a"), rig.turns.dispatched)
    }

    @Test
    fun drainWaitsOutATurnThatRetiresAfterItsTerminal() = runTest {
        val rig = rig(busy = true)
        rig.enqueue("a")

        // The terminal arrived but the transport still reports the turn for a moment.
        rig.turnFinished()
        advanceTimeBy(RETRY_DELAY_MS * 3)
        runCurrent()
        assertTrue(rig.turns.dispatched.isEmpty())

        rig.turns.busy = false
        advanceTimeBy(RETRY_DELAY_MS + 1)
        runCurrent()
        assertEquals(listOf("a"), rig.turns.dispatched)
    }

    @Test
    fun aDeclinedDispatchKeepsTheItemAtTheHead() = runTest {
        val rig = rig(busy = false)
        rig.turns.accept = false
        rig.enqueue("a")
        rig.enqueue("b")
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), rig.queuedTexts(), "the declined head is put back, in front")
        rig.turns.accept = true
        rig.turnFinished()
        assertEquals(listOf("a"), rig.turns.dispatched.takeLast(1))
    }

    @Test
    fun disconnectPausesEveryQueue() = runTest {
        val rig = rig(busy = true)
        rig.enqueue("a")
        rig.enqueue("b", conversationId = QueueConversationId("conv-2"))

        rig.driver.onDisconnected()

        assertTrue(rig.queue.isPaused(CONV))
        assertTrue(rig.queue.isPaused(QueueConversationId("conv-2")))
    }

    @Test
    fun aSendHeldForAnotherClientWaitsForTheirTerminal() = runTest {
        val rig = rig(busy = false)
        rig.holdForOtherClient("a")
        advanceTimeBy(HOLD_INITIAL_MS - 1)
        runCurrent()
        assertTrue(rig.turns.dispatched.isEmpty(), "a held send waits; it is never failed")
        assertTrue(rig.queue.queueFor(CONV).heldByOtherClient)

        rig.foreignTerminal()
        assertEquals(listOf("a"), rig.turns.dispatched)
        assertFalse(rig.queue.queueFor(CONV).heldByOtherClient)
    }

    @Test
    fun aHeldSendRetriesOnTheBackstopWithBackoff() = runTest {
        val rig = rig(busy = false)
        rig.holdForOtherClient("a")
        advanceTimeBy(HOLD_INITIAL_MS + 1)
        runCurrent()
        assertEquals(listOf("a"), rig.turns.dispatched, "no terminal seen: the backstop retries")

        // Bounced again: the next retry waits twice as long.
        rig.turns.busy = false
        rig.holdForOtherClient("a")
        advanceTimeBy(HOLD_INITIAL_MS + 1)
        runCurrent()
        assertEquals(1, rig.turns.dispatched.size)
        advanceTimeBy(HOLD_INITIAL_MS)
        runCurrent()
        assertEquals(listOf("a", "a"), rig.turns.dispatched)
    }

    @Test
    fun newSendsQueueBehindAHeldOneAndKeepTheirOrder() = runTest {
        val rig = rig(busy = false)
        rig.holdForOtherClient("a")
        rig.enqueue("b")
        runCurrent()
        assertTrue(rig.turns.dispatched.isEmpty(), "nothing jumps a send held for another client")
        assertEquals(listOf("a", "b"), rig.queuedTexts())

        rig.foreignTerminal()
        rig.finishTurn()
        assertEquals(listOf("a", "b"), rig.turns.dispatched)
    }

    @Test
    fun sendNowOnAHeldSendTriesAtOnce() = runTest {
        val rig = rig(busy = false)
        rig.holdForOtherClient("a")

        rig.driver.sendNow(QueuedSendId("otid-a"))
        runCurrent()

        assertEquals(listOf("a"), rig.turns.dispatched)
    }

    @Test
    fun immediateSubmissionAndDeferredHeadKeepDistinctComposerSemantics() = runTest {
        val rig = rig(busy = false)
        val first = QueuedChatSend(QueuedSendId("first"), CONV, "first")
        val second = QueuedChatSend(QueuedSendId("second"), CONV, "second")
        rig.turns.serialized { assertEquals(null, rig.driver.submitLocked(first)) }
        rig.turns.accept = false
        rig.turns.serialized { assertEquals(1, rig.driver.submitLocked(second)) }
        assertEquals(listOf("first"), rig.turns.dispatched)
        rig.turns.accept = true
        rig.finishTurn()
        assertEquals(listOf("first", "second"), rig.turns.dispatched)
        assertEquals(listOf(true, false), rig.turns.composerClears)
    }

    @Test
    fun newSubmissionCannotPassADeclinedHead() = runTest {
        val rig = rig(busy = false)
        rig.turns.accept = false
        rig.enqueue("head")
        rig.turns.accept = true
        val next = QueuedChatSend(QueuedSendId("next"), CONV, "next")
        rig.turns.serialized { assertEquals(2, rig.driver.submitLocked(next)) }
        assertEquals(listOf("head"), rig.turns.dispatched)
        assertEquals(listOf("next"), rig.queuedTexts())
        rig.finishTurn()
        assertEquals(listOf("head", "next"), rig.turns.dispatched)
    }

    @Test
    fun stopPausesOnlyWhenTheTransportAcceptsIt() = runTest {
        val rig = rig(busy = true)
        rig.enqueue("head")
        rig.turns.busy = false
        assertFalse(rig.driver.stop(CONV))
        assertFalse(rig.queue.isPaused(CONV))
        rig.turns.busy = true
        assertTrue(rig.driver.stop(CONV))
        assertTrue(rig.queue.isPaused(CONV))
        rig.finishTurn()
        assertTrue(rig.turns.dispatched.isEmpty())
    }

    @Test
    fun cancelledScopeLeavesDeclinedHeadEligibleWithoutRetrying() = runTest {
        val rig = rig(busy = false)
        rig.turns.accept = false
        rig.enqueue("head")
        backgroundScope.cancel()
        rig.turns.accept = true
        advanceTimeBy(RETRY_DELAY_MS * 3)
        runCurrent()
        assertTrue(rig.turns.dispatched.isEmpty())
        assertEquals(listOf("head"), rig.queuedTexts())
    }

    @Test
    fun throwingRetryRemainsVisibleAndKeepsDrainingUntilPublished() = runTest {
        val rig = rig(busy = true)
        val retry = QueuedChatSend(QueuedSendId("retry-prompt"), CONV, "retry input", attempt = 1)
        rig.turns.serialized { rig.driver.enqueueLocked(retry) }
        rig.turns.dispatchFailure = IllegalStateException("retry transport rejected")
        val firstDrain = runCatching { rig.finishTurn() }
        assertTrue(firstDrain.isSuccess, "a transient throwing retry must not terminate the drain")
        advanceTimeBy(RETRY_DELAY_MS * 2 + 1)
        runCurrent()
        assertTrue(rig.turns.attempts.size >= 3, "throwing dispatch must keep retrying without Send now")
        assertEquals(listOf(retry), rig.queue.queueFor(CONV).items)
        assertTrue(rig.turns.dispatched.isEmpty())
        assertTrue(rig.turns.visibleCounts.all { it == 1 }, "queue plus transcript must represent the retry once")
        rig.turns.dispatchFailure = null
        advanceTimeBy(RETRY_DELAY_MS + 1)
        runCurrent()
        assertTrue(rig.queue.queueFor(CONV).isEmpty)
        assertEquals(listOf("retry input"), rig.turns.dispatched)
    }

    @Test
    fun cancellationFromRetryDispatchPropagatesAndRetainsHead() = runTest {
        val rig = rig(busy = true)
        val retry = QueuedChatSend(QueuedSendId("cancelled-retry"), CONV, "retry input", attempt = 1)
        rig.turns.serialized { rig.driver.enqueueLocked(retry) }
        rig.turns.dispatchFailure = kotlinx.coroutines.CancellationException("cancelled dispatch")
        val failure = runCatching { rig.finishTurn() }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertEquals(listOf(retry), rig.queue.queueFor(CONV).items)
        advanceTimeBy(RETRY_DELAY_MS * 3)
        assertEquals(1, rig.turns.attempts.size, "cancellation must not schedule another attempt")
    }

    @Test
    fun declinedHeadSurvivesTenSecondOutage() = runTest {
        val rig = rig(busy = false)
        rig.turns.accept = false
        rig.enqueue("outage")
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf("outage"), rig.queuedTexts())
        rig.turns.accept = true
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(listOf("outage"), rig.turns.dispatched)
        assertTrue(rig.turns.visibleCounts.all { it == 1 })
        assertTrue(rig.queue.queueFor(CONV).isEmpty)
    }

    @Test
    fun throwingHeadSurvivesTenSecondOutage() = runTest {
        val rig = rig(busy = true)
        rig.enqueue("outage")
        rig.turns.dispatchFailure = IllegalStateException("transport unavailable")
        rig.finishTurn()
        advanceTimeBy(10_000)
        runCurrent()
        rig.turns.dispatchFailure = null
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(listOf("outage"), rig.turns.dispatched)
        assertTrue(rig.turns.visibleCounts.all { it == 1 })
    }

    @Test
    fun foreignTerminalWakesHeadDuringBackoff() = runTest {
        val rig = rig(busy = false)
        rig.turns.accept = false
        rig.enqueue("outage")
        advanceTimeBy(10_000)
        runCurrent()
        rig.turns.accept = true
        rig.foreignTerminal()
        assertEquals(listOf("outage"), rig.turns.dispatched)
    }

    @Test
    fun pauseDuringBackoffStopsRetriesUntilResume() = runTest {
        val rig = rig(busy = false)
        rig.turns.accept = false
        rig.enqueue("outage")
        advanceTimeBy(10_000)
        runCurrent()
        rig.driver.onStopped(CONV)
        val attempts = rig.turns.attempts.size
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(attempts, rig.turns.attempts.size)
        rig.turns.accept = true
        rig.driver.resume(CONV)
        assertEquals(listOf("outage"), rig.turns.dispatched)
    }

    @Test
    fun scopeCancellationDuringBackoffSchedulesNothingMore() = runTest {
        val rig = rig(busy = false)
        rig.turns.accept = false
        rig.enqueue("outage")
        advanceTimeBy(10_000)
        runCurrent()
        val attempts = rig.turns.attempts.size
        backgroundScope.cancel()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(attempts, rig.turns.attempts.size)
        assertEquals(listOf("outage"), rig.queuedTexts())
    }

    @Test
    fun dispatchCancellationDuringBackoffStopsTheDrain() = runTest {
        val rig = rig(busy = false)
        rig.turns.accept = false
        rig.enqueue("outage")
        advanceTimeBy(10_000)
        runCurrent()
        rig.turns.dispatchFailure = kotlinx.coroutines.CancellationException("cancelled backoff")
        val attempts = rig.turns.attempts.size
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(attempts + 1, rig.turns.attempts.size)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(attempts + 1, rig.turns.attempts.size)
        assertEquals(listOf("outage"), rig.queuedTexts())
    }

    @Test
    fun cancellingHeadDuringBackoffStopsTheDrain() = runTest {
        val rig = rig(busy = false)
        rig.turns.accept = false
        rig.enqueue("outage")
        advanceTimeBy(10_000)
        runCurrent()
        val attempts = rig.turns.attempts.size
        assertTrue(rig.driver.cancel(rig.queue.queueFor(CONV).items.single().id))
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(attempts, rig.turns.attempts.size)
        assertTrue(rig.queue.queueFor(CONV).isEmpty)
    }

    @Test
    fun retryDelayGrowsThenStaysAtThirtySeconds() = runTest {
        val rig = rig(busy = false)
        rig.turns.accept = false
        rig.enqueue("outage")
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        val fastAttempts = rig.turns.attempts.size
        advanceTimeBy(498)
        runCurrent()
        assertEquals(fastAttempts, rig.turns.attempts.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(fastAttempts + 1, rig.turns.attempts.size)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(fastAttempts + 2, rig.turns.attempts.size)
        advanceTimeBy(60_000)
        runCurrent()
        val cappedAttempts = rig.turns.attempts.size
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(cappedAttempts + 1, rig.turns.attempts.size)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(cappedAttempts + 2, rig.turns.attempts.size)
    }

    @Test
    fun recoveryOnLastFastRetryDoesNotReportBackoff() = runTest {
        val recording = Telemetry.installRecorder(TelemetryRecorder())
        try {
            val rig = rig(busy = false)
            rig.turns.accept = false
            rig.enqueue("outage")
            runCurrent()
            advanceTimeBy(2_000)
            rig.turns.accept = true
            runCurrent()
            assertEquals(listOf("outage"), rig.turns.dispatched)
            assertTrue(Telemetry.events.value.none { it.name.startsWith("queue.drain") })
        } finally {
            recording.close()
        }
    }

    @Test
    fun backoffReportsOneFallbackAndOneImmediateRecoveryWithRetryCounts() = runTest {
        val recording = Telemetry.installRecorder(TelemetryRecorder())
        try {
            val rig = rig(busy = false)
            rig.turns.accept = false
            rig.enqueue("outage")
            runCurrent()
            advanceTimeBy(2_000)
            runCurrent()
            rig.turns.accept = true
            rig.driver.resume(CONV)
            advanceTimeBy(60_000)
            runCurrent()
            val events = Telemetry.events.value.asReversed().filter { it.name.startsWith("queue.drain") }
            assertEquals(listOf("queue.drainDeferred", "queue.drainRecovered"), events.map { it.name })
            assertEquals(listOf("40", "40"), events.map { it.attrs["attempts"].toString() })
        } finally {
            recording.close()
        }
    }

    private class ScriptedTurns(var busy: Boolean, private val queue: ChatSendQueue) : QueuedSendTurns {
        private val lock = Mutex()
        var accept = true
        var dispatchFailure: Throwable? = null
        val attempts = mutableListOf<QueuedChatSend>()
        val visibleCounts = mutableListOf<Int>()
        val dispatched = mutableListOf<String>()
        val composerClears = mutableListOf<Boolean>()
        val aborted = mutableListOf<QueueConversationId>()

        override suspend fun <T> serialized(block: suspend () -> T): T = lock.withLock { block() }
        override fun hasActiveTurn(conversationId: QueueConversationId): Boolean = busy
        override fun abortTurn(conversationId: QueueConversationId): Boolean {
            aborted += conversationId
            return busy
        }
        override suspend fun dispatch(item: QueuedChatSend, clearComposer: Boolean): Boolean {
            attempts += item
            val queued = queue.queueFor(item.conversationId).items
            visibleCounts += queued.size + dispatched.count { text -> queued.none { it.text == text } }
            dispatchFailure?.let { throw it }
            if (!accept) return false
            dispatched += item.text
            composerClears += clearComposer
            busy = true
            return true
        }
    }

    private class Rig(
        val scope: TestScope,
        val queue: ChatSendQueue,
        val turns: ScriptedTurns,
        val driver: QueuedSendDriver,
    ) {
        suspend fun enqueue(text: String, conversationId: QueueConversationId = CONV) {
            turns.serialized { driver.enqueueLocked(QueuedChatSend(QueuedSendId("otid-$text"), conversationId, text)) }
        }

        /** The running turn ends: its terminal is seen and the transport retires it. */
        suspend fun finishTurn(conversationId: QueueConversationId = CONV) {
            turns.busy = false
            turnFinished(conversationId)
        }

        /** Only the terminal is seen; the transport may still report the turn. */
        suspend fun turnFinished(conversationId: QueueConversationId = CONV) {
            turns.serialized { driver.onTurnFinishedLocked(conversationId) }
            scope.runCurrent()
        }

        fun queuedTexts() = queue.queueFor(CONV).items.map { it.text }

        /** The wrapper bounced [text]'s send off another client's turn. */
        suspend fun holdForOtherClient(text: String) {
            turns.serialized { driver.holdForOtherClientLocked(QueuedChatSend(QueuedSendId("otid-$text"), CONV, text)) }
        }

        /** A terminal this device does not own reached the coordinator. */
        suspend fun foreignTerminal() {
            turns.serialized { driver.onForeignTerminalLocked() }
            scope.runCurrent()
        }
    }

    private fun TestScope.rig(busy: Boolean): Rig {
        val queue = ChatSendQueue()
        val turns = ScriptedTurns(busy, queue)
        val driverScope: CoroutineScope = backgroundScope
        return Rig(this, queue, turns, QueuedSendDriver(
            driverScope, queue, turns,
            QueuedSendDriver.DrainRetry(delayMs = RETRY_DELAY_MS),
            OtherClientHold.Backoff(initialMs = HOLD_INITIAL_MS, maxMs = HOLD_INITIAL_MS * 4),
        ))
    }

    private companion object {
        val CONV = QueueConversationId("conv-1")
        const val RETRY_DELAY_MS = 50L
        const val HOLD_INITIAL_MS = 1_000L
    }
}
