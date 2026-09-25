package com.letta.mobile.data.chat.send

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

        assertTrue(rig.driver.cancel("otid-b"))
        assertFalse(rig.driver.cancel("otid-b"))
        repeat(3) { rig.finishTurn() }

        assertEquals(listOf("a", "c"), rig.turns.dispatched)
    }

    @Test
    fun sendNowAbortsTheRunningTurnAndRunsTheItemBeforeTheOnesItJumped() = runTest {
        val rig = rig(busy = true)
        listOf("a", "b", "c").forEach { rig.enqueue(it) }

        assertTrue(rig.driver.sendNow("otid-c"))
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

        rig.driver.sendNow("otid-b")
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

        rig.driver.sendNow("otid-a")
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
        rig.enqueue("b", conversationId = "conv-2")

        rig.driver.onDisconnected()

        assertTrue(rig.queue.isPaused(CONV))
        assertTrue(rig.queue.isPaused("conv-2"))
    }

    private class ScriptedTurns(var busy: Boolean) : QueuedSendTurns {
        private val lock = Mutex()
        var accept = true
        val dispatched = mutableListOf<String>()
        val aborted = mutableListOf<String>()

        override suspend fun <T> serialized(block: suspend () -> T): T = lock.withLock { block() }
        override fun hasActiveTurn(conversationId: String): Boolean = busy
        override fun abortTurn(conversationId: String): Boolean {
            aborted += conversationId
            return busy
        }
        override suspend fun dispatch(item: QueuedChatSend): Boolean {
            if (!accept) return false
            dispatched += item.text
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
        suspend fun enqueue(text: String, conversationId: String = CONV) {
            turns.serialized { driver.enqueueLocked(QueuedChatSend("otid-$text", conversationId, text)) }
        }

        /** The running turn ends: its terminal is seen and the transport retires it. */
        suspend fun finishTurn(conversationId: String = CONV) {
            turns.busy = false
            turnFinished(conversationId)
        }

        /** Only the terminal is seen; the transport may still report the turn. */
        suspend fun turnFinished(conversationId: String = CONV) {
            turns.serialized { driver.onTurnFinishedLocked(conversationId) }
            scope.runCurrent()
        }

        fun queuedTexts() = queue.queueFor(CONV).items.map { it.text }
    }

    private fun TestScope.rig(busy: Boolean): Rig {
        val queue = ChatSendQueue()
        val turns = ScriptedTurns(busy)
        val driverScope: CoroutineScope = backgroundScope
        return Rig(this, queue, turns, QueuedSendDriver(driverScope, queue, turns, retryDelayMs = RETRY_DELAY_MS))
    }

    private companion object {
        const val CONV = "conv-1"
        const val RETRY_DELAY_MS = 50L
    }
}
