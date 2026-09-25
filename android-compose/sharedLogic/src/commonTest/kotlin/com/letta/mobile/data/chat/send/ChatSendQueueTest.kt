package com.letta.mobile.data.chat.send

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-1n5py: the send queue's shape, independent of any transport. */
class ChatSendQueueTest {

    @Test
    fun enqueueKeepsOrderAndReportsPositions() {
        val queue = ChatSendQueue()
        val positions = (1..5).map { queue.enqueue(send("m$it")) }

        assertEquals(listOf(1, 2, 3, 4, 5), positions)
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5"), queue.texts(CONV))
        assertEquals(3, queue.queueFor(CONV).positionOf("otid-m3"))
    }

    @Test
    fun queueHasNoCapacityLimit() {
        val queue = ChatSendQueue()
        repeat(500) { queue.enqueue(send("m$it")) }

        assertEquals(500, queue.queueFor(CONV).items.size)
    }

    @Test
    fun cancellingTheMiddleItemKeepsTheRestInOrder() {
        val queue = queueOf("a", "b", "c")

        assertEquals("b", queue.remove("otid-b")?.text)
        assertEquals(listOf("a", "c"), queue.texts(CONV))
        assertNull(queue.remove("otid-b"), "a second cancel of the same item is a no-op")
    }

    @Test
    fun promoteMovesTheItemFirstAndKeepsTheJumpedItemsInOrder() {
        val queue = queueOf("a", "b", "c", "d")

        assertTrue(queue.promote("otid-c"))
        assertEquals(listOf("c", "a", "b", "d"), queue.texts(CONV))
        assertFalse(queue.promote("otid-missing"))
    }

    @Test
    fun pausedQueueYieldsNothingUntilResumed() {
        val queue = queueOf("a", "b")

        assertTrue(queue.pause(CONV))
        assertNull(queue.takeNext(CONV))
        assertTrue(queue.isPaused(CONV))

        queue.resume(CONV)
        assertEquals("a", queue.takeNext(CONV)?.text)
        assertEquals(listOf("b"), queue.texts(CONV))
    }

    @Test
    fun pausingAnEmptyQueueHoldsNothing() {
        val queue = ChatSendQueue()

        assertFalse(queue.pause(CONV))
        queue.enqueue(send("a"))
        assertFalse(queue.isPaused(CONV), "a pause must not latch onto a conversation with nothing queued")
    }

    @Test
    fun emptiedQueueForgetsItsPause() {
        val queue = queueOf("a")
        queue.pause(CONV)

        queue.remove("otid-a")
        queue.enqueue(send("b"))

        assertFalse(queue.isPaused(CONV))
    }

    @Test
    fun putBackRestoresTheHeadOnce() {
        val queue = queueOf("a", "b")
        val head = queue.takeNext(CONV)!!

        queue.putBack(head)
        queue.putBack(head)

        assertEquals(listOf("a", "b"), queue.texts(CONV))
    }

    @Test
    fun conversationsAreIndependent() {
        val queue = ChatSendQueue()
        queue.enqueue(send("a", conversationId = "conv-a"))
        queue.enqueue(send("b", conversationId = "conv-b"))

        queue.pause("conv-a")

        assertNull(queue.takeNext("conv-a"))
        assertEquals("b", queue.takeNext("conv-b")?.text)
        assertEquals(listOf("conv-a"), queue.pauseAll(), "only a conversation with items has anything to hold")
    }

    private fun queueOf(vararg texts: String) = ChatSendQueue().apply { texts.forEach { enqueue(send(it)) } }

    private fun ChatSendQueue.texts(conversationId: String) = queueFor(conversationId).items.map { it.text }

    private companion object {
        const val CONV = "conv-1"

        fun send(text: String, conversationId: String = CONV) =
            QueuedChatSend(otid = "otid-$text", conversationId = conversationId, text = text)
    }
}
